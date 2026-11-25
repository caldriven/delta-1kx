package com.alex.mfppro.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.TreeMap
import kotlin.math.abs
import kotlin.random.Random

private const val TAG = "MarketStream"
private const val URL = "wss://api-stream.myfundedperpetuals.com/v1/market-data"
// Publish at ~20 fps: chart and PnL feel live without flooding recomposition.
private const val FLUSH_MS = 100L
private const val MAX_TRADES = 80
private const val MAX_CANDLES = 1500
private const val MAX_OI = 5000

data class Sub(val channel: String, val provider: String, val coin: String, val interval: String? = null) {
    val feed get() = feedKey(provider, coin)
}

fun candleKey(provider: String, coin: String, interval: String) = "$provider|$coin|$interval"

/** Length of one candle, or null for calendar intervals ("1M") whose length varies. */
fun intervalMs(iv: String): Long? {
    if (iv.length < 2 || iv.endsWith("M")) return null
    val n = iv.dropLast(1).toLongOrNull() ?: return null
    return n * when (iv.last()) { 's' -> 1_000L; 'm' -> 60_000L; 'h' -> 3_600_000L; 'd' -> 86_400_000L; 'w' -> 7 * 86_400_000L; else -> return null }
}

/**
 * Public market socket. Subscriptions are reference counted so screens can
 * acquire/release freely; everything is re-subscribed after a reconnect.
 * Incoming data is buffered and published at most every [FLUSH_MS].
 */
class MarketStream(private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val serial = Dispatchers.IO.limitedParallelism(1)

    private val _marks = MutableStateFlow<Map<String, Double>>(emptyMap())
    val marks = _marks.asStateFlow()
    /** The venue's own mark price, kept apart from [marks] (which follows the live book when real-time is on). */
    private val _venueMarks = MutableStateFlow<Map<String, Double>>(emptyMap())
    val venueMarks = _venueMarks.asStateFlow()
    private val venueBuf = HashMap<String, Double>()
    private val _stats = MutableStateFlow<Map<String, Stats>>(emptyMap())
    val stats = _stats.asStateFlow()
    private val _books = MutableStateFlow<Map<String, Book>>(emptyMap())
    val books = _books.asStateFlow()
    private val _trades = MutableStateFlow<Map<String, List<Trade>>>(emptyMap())
    val trades = _trades.asStateFlow()
    private val _candles = MutableStateFlow<Map<String, List<Candle>>>(emptyMap())
    val candles = _candles.asStateFlow()
    private val _oi = MutableStateFlow<Map<String, List<OiPoint>>>(emptyMap())
    val oi = _oi.asStateFlow()
    private val _live = MutableStateFlow(false)
    val live = _live.asStateFlow()

    /**
     * Real-time valuation: price positions off the order book mid (pushed every
     * ≤150 ms) instead of the venue's mark ticks, which arrive less often.
     */
    @Volatile var realtime = false
    private val bookMidAt = HashMap<String, Long>()

    private val marksBuf = HashMap<String, Double>()
    private val statsBuf = HashMap<String, Stats>()
    private val booksBuf = HashMap<String, Book>()
    private val tradesBuf = HashMap<String, ArrayDeque<Trade>>()
    private val candlesBuf = HashMap<String, TreeMap<Long, Candle>>()
    private val oiBuf = HashMap<String, ArrayList<OiPoint>>()
    private val dirty = HashSet<String>()
    // Candle and OI series are long: only the keys that changed are copied on a flush.
    private val dirtyCandles = HashSet<String>()
    private val dirtyOi = HashSet<String>()
    // Data-flow watchdog state (see checkStale).
    private var staleCheckAt = 0L
    private var lastAnyEventAt = 0L
    private val feedEventAt = HashMap<String, Long>()
    private val subSentAt = HashMap<Sub, Long>()
    private var connectedAt = 0L
    private var staleLoggedAt = 0L
    private var staleReconnectAt = 0L
    /** Resubscribes in a row that brought no data, per feed (see checkStale). */
    private val quietResubs = HashMap<Sub, Int>()

    private val refs = HashMap<Sub, Int>()
    private val activeIds = HashMap<Sub, Int>()
    private val idToSub = HashMap<Int, Sub>()
    private var nextId = 1

    // Chart history per candle key: the running load, when the key may next be checked, and since
    // when its forming candle has disagreed with the live price.
    private val historyJobs = HashMap<String, Job>()
    private val auditAt = HashMap<String, Long>()
    private val mismatchSince = HashMap<String, Long>()
    private var lastAudit = 0L

    private var ws: WebSocket? = null
    private var gen = 0
    private var paused = false
    private var attempt = 0
    private var reconnectJob: Job? = null

    // ---- developer-feed rules: API key, compression, 5 sockets per key, 30 admissions a minute ----
    /** Live API key sent as Bearer on the upgrade; MFP refuses the feed without one once enforced. */
    private var key: String? = null
    /**
     * Why the feed is refused until something changes (bad or missing key, no compression).
     * No reconnect loop while set; a new key clears it.
     */
    private val _blocked = MutableStateFlow<String?>(null)
    val blocked = _blocked.asStateFlow()
    /** X-Developer-Feed-Auth from the last upgrade: "authenticated" when this client meets every rule. */
    private val _feedAuth = MutableStateFlow<String?>(null)
    val feedAuth = _feedAuth.asStateFlow()
    /** Subscriptions the server turned down (bad filter, over a limit): not re-sent until released or reconnected. */
    private val rejected = HashMap<Sub, String>()
    /** The socket the server is draining: still read for prices until its replacement has its snapshot. */
    private var draining: WebSocket? = null
    private var drainJob: Job? = null
    /** Upgrade times in the last minute, to stay under MFP's 30 a minute (burst 10). */
    private val admissions = ArrayDeque<Long>()
    /** Last ping reply's upstreamReadAgeMs: negative = MFP's own feed is down, null = unknown. */
    private var upstreamAgeMs: Long? = null

    /** Set or clear the API key; reconnects with it when the feed is in use. */
    fun setKey(k: String?) = scope.launch(serial) {
        if (k == key) return@launch
        key = k
        _blocked.value = null
        if (!paused && refs.isNotEmpty()) { attempt = 0; connect() }
    }

    private var lastFrameAt = 0L
    private var lastPingAt = 0L
    private var probeAt = 0L

    init {
        scope.launch(serial) {
            while (isActive) {
                // Paused (app in the background, no monitor): no socket, nothing to publish, so tick
                // once a second, not ten times, and let the CPU sleep.
                delay(if (paused) 1_000 else FLUSH_MS)
                if (paused) continue
                flush()
                watchdog()
                val now = System.currentTimeMillis()
                if (_live.value && now - lastAudit >= 5_000) { lastAudit = now; audit(now) }
            }
        }
    }

    // Same liveness policy as the account stream: ping every 3s, dead after 8s silence.
    private fun watchdog() {
        val socket = ws ?: return
        val now = System.currentTimeMillis()
        if (_live.value && now - staleCheckAt >= 1_000) { staleCheckAt = now; if (checkStale(now)) return }
        val probeFailed = probeAt > 0 && lastFrameAt < probeAt && now - probeAt > 2_500
        if (probeFailed || now - lastFrameAt > 8_000) {
            Log.w(TAG, "socket silent, reconnecting")
            probeAt = 0
            _live.value = false
            connect()
        } else if (_live.value && now - lastPingAt >= 3_000) {
            lastPingAt = now
            val id = nextId++
            pingSent[id] = now
            if (pingSent.size > 20) pingSent.keys.minOrNull()?.let { pingSent.remove(it) }
            socket.send("""{"op":"req","id":$id,"method":"ping"}""")
        }
    }

    /**
     * Pongs prove the socket is up, not that data still flows. A price feed
     * (ticks/books) that has gone quiet is re-subscribed; if every feed is quiet
     * the socket is replaced. Returns true when it reconnected.
     */
    private fun checkStale(now: Long): Boolean {
        val price = activeIds.keys.filter { it.channel == "ticks" || it.channel == "books" }
        if (price.isEmpty()) return false
        // A feed that stays quiet after a resubscribe is likely a closed market (stocks at the weekend):
        // wait twice as long each time, up to 5 minutes, instead of churning it every 20 s.
        fun quietAfter(s: Sub): Long {
            if ((feedEventAt[s.feed] ?: 0) > (subSentAt[s] ?: 0)) quietResubs.remove(s)
            return minOf(300_000L, 20_000L shl minOf(quietResubs[s] ?: 0, 4))
        }
        val quiet = price.filter { now - maxOf(feedEventAt[it.feed] ?: 0, subSentAt[it] ?: now) > quietAfter(it) }
        // Replacing the whole socket at most every 2 minutes: when only closed markets are open, a new
        // socket gets the same silence.
        if (quiet.size == price.size && now - maxOf(lastAnyEventAt, connectedAt) > 30_000 && now - staleReconnectAt > 120_000) {
            staleReconnectAt = now
            // MFP's own upstream is down: a new socket would get the same silence, and burn admissions.
            val up = upstreamAgeMs
            if (up != null && (up < 0 || up > 30_000)) {
                if (now - lastAnyEventAt > 30_000 && now - staleLoggedAt > 60_000) { staleLoggedAt = now; Log.w(TAG, "no market data: MFP upstream unavailable (${up}ms)") }
                return false
            }
            Log.w(TAG, "no market data for 30s, reconnecting")
            _live.value = false
            connect()
            return true
        }
        for (sub in quiet) {
            quietResubs[sub] = (quietResubs[sub] ?: 0) + 1
            Log.w(TAG, "feed ${sub.feed} ${sub.channel} quiet, resubscribing")
            activeIds.remove(sub)?.let { id -> idToSub.remove(id); ws?.send("""{"op":"unsub","id":$id}""") }
            send(sub)
        }
        return false
    }
    fun probe() = scope.launch(serial) {
        val socket = ws ?: return@launch
        probeAt = System.currentTimeMillis(); lastPingAt = probeAt
        socket.send("""{"op":"req","id":${nextId++},"method":"ping"}""")
    }

    private val oneShots = HashMap<Int, CompletableDeferred<JsonElement?>>()
    private val pingSent = HashMap<Int, Long>()
    // The server answers at most 4 history requests at once and turns the rest down. Every request
    // takes one of [allSlots]; one-offs (overview stats, scanners) also need one of [bulkSlots], so the
    // charts on screen always have a slot even while the overview loads a hundred markets.
    private val allSlots = Semaphore(4)
    private val bulkSlots = Semaphore(3)

    /** One-off candle history (for overview stats), or empty if unavailable. */
    suspend fun candleHistory(provider: String, coin: String, interval: String, limit: Int): List<Candle> =
        bulkSlots.withPermit { allSlots.withPermit { requestHistory(provider, coin, interval, limit) } }.orEmpty()

    /** One history request over the socket; null when it failed or timed out (empty = no candles). */
    private suspend fun requestHistory(provider: String, coin: String, interval: String, limit: Int): List<Candle>? {
        val d = CompletableDeferred<JsonElement?>()
        var sent = false
        // Send once; retry only while the socket is down.
        for (attempt in 0 until 20) {
            sent = withContext(serial) {
                val socket = ws?.takeIf { _live.value } ?: return@withContext false
                val id = nextId++
                oneShots[id] = d
                socket.send(buildJsonObject {
                    put("op", "req"); put("id", id); put("method", "candles.history")
                    put("payload", buildJsonObject { put("provider", provider); put("symbol", coin); put("interval", interval); put("limit", limit) })
                }.toString())
                true
            }
            if (sent) break
            delay(500)
        }
        if (!sent) return null
        val res = withTimeoutOrNull(10_000) { d.await() } as? JsonArray ?: return null
        return res.mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            Candle(c.long("openTime") ?: return@mapNotNull null, c.dbl("open") ?: 0.0, c.dbl("high") ?: 0.0, c.dbl("low") ?: 0.0, c.dbl("close") ?: 0.0, c.dbl("volume") ?: 0.0)
        }.sortedBy { it.openTime }
    }

    fun reconnect() = scope.launch(serial) { if (!paused && refs.isNotEmpty()) { attempt = 0; connect() } }

    fun acquire(sub: Sub) = scope.launch(serial) {
        val n = (refs[sub] ?: 0) + 1
        refs[sub] = n
        if (n == 1) {
            // While connecting, onOpen subscribes everything in refs; sending now would double up.
            if (ws == null) { if (!paused) connect() } else if (_live.value) send(sub)
        }
    }

    fun release(sub: Sub) = scope.launch(serial) {
        val n = (refs[sub] ?: return@launch) - 1
        if (n > 0) { refs[sub] = n; return@launch }
        refs.remove(sub)
        rejected.remove(sub)
        activeIds.remove(sub)?.let { id ->
            idToSub.remove(id)
            ws?.send("""{"op":"unsub","id":$id}""")
        }
        // Without a feed the bars go stale at once; drop them so a later chart starts from fresh history.
        if (sub.channel == "candles" && sub.interval != null) {
            val key = candleKey(sub.provider, sub.coin, sub.interval)
            historyJobs.remove(key)?.cancel()
            auditAt.remove(key); mismatchSince.remove(key)
            if (candlesBuf.remove(key) != null) dirtyCandles += key
        }
    }

    /** [still] is checked on the stream thread: a pause that lost a race with resume does nothing. */
    fun pause(still: () -> Boolean = { true }) = scope.launch(serial) {
        if (!still()) return@launch
        paused = true
        reconnectJob?.cancel()
        gen++
        ws?.close(1000, "background"); ws = null
        closeDraining()
        _live.value = false
    }

    /** Back in the foreground. [fresh] replaces the socket: after a while away its data is stale or backed up. */
    fun resume(fresh: Boolean = false) = scope.launch(serial) {
        paused = false
        // A refusal may have been passing (a revoked-then-restored key, a CDN hiccup): try once more on return.
        _blocked.value = null
        // A socket that went quiet while the app was frozen is reconnected now rather than
        // after the probe times out, so the screen isn't showing old prices for seconds.
        val stale = System.currentTimeMillis() - lastFrameAt > 8_000
        if (refs.isNotEmpty() && (ws == null || stale || fresh)) { attempt = 0; connect() } else if (ws != null) probe()
    }

    /**
     * Manual refresh. MFP asks clients not to reconnect just to get a fresh snapshot, so a
     * healthy socket stays open and only the chart history is backfilled; a dead or quiet one
     * is replaced.
     */
    fun refresh() = scope.launch(serial) {
        paused = false
        _blocked.value = null
        if (refs.isEmpty()) return@launch
        val socket = ws
        if (socket == null || !_live.value || System.currentTimeMillis() - lastFrameAt > 4_000) { attempt = 0; connect(); return@launch }
        // Through the shared limiter, with retries, replacing any bars from before a gap.
        for (sub in refs.keys.toList()) if (sub.channel == "candles" && sub.interval != null) loadChartHistory(sub)
    }

    /**
     * Open a socket and subscribe everything in [refs]. [keepOld]: the server is draining the
     * current one, so it stays open (and is read for prices) until the replacement has its snapshot.
     */
    private fun connect(keepOld: Boolean = false) {
        reconnectJob?.cancel()
        if (_blocked.value != null) { ws?.cancel(); ws = null; _live.value = false; return }
        // Every caller reconnects for a reason, but a flapping feed must not burn MFP's
        // 30 admissions a minute (burst 10): past 8 in a minute, wait for the oldest to age out.
        val now = System.currentTimeMillis()
        while (admissions.isNotEmpty() && now - admissions.first() > 60_000) admissions.removeFirst()
        // A server drain is MFP's own request and always gets its replacement.
        if (!keepOld && admissions.size >= 8) {
            val wait = admissions.first() + 60_000 - now + Random.nextLong(0, 1_000)
            Log.w(TAG, "reconnecting too often, next try in ${wait / 1000}s")
            // A socket that still works keeps running meanwhile; only a dead one is dropped.
            if (ws == null || !_live.value) { gen++; ws?.cancel(); ws = null; _live.value = false }
            reconnectJob = scope.launch(serial) { delay(wait); connect() }
            return
        }
        admissions.addLast(now)
        gen++
        if (keepOld) draining = ws
        else { ws?.cancel(); draining?.cancel(); draining = null; drainJob?.cancel() }
        val myGen = gen
        lastFrameAt = now
        // IDs and sub mappings belong to one connection. On a drain the old socket still answers the
        // requests it was sent (ids keep counting up, so they never clash with the new socket's).
        activeIds.clear(); idToSub.clear(); rejected.clear()
        if (!keepOld) { pingSent.clear(); oneShots.values.forEach { it.complete(null) }; oneShots.clear() }
        upstreamAgeMs = null
        // OkHttp offers permessage-deflate on every upgrade by itself, which MFP requires.
        val req = Request.Builder().url(URL).apply { key?.let { header("Authorization", "Bearer $it") } }.build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val auth = response.header("X-Developer-Feed-Auth")
                val enforceAt = response.header("X-Developer-Feed-Enforce-At")
                val ext = response.header("Sec-WebSocket-Extensions")
                scope.launch(serial) {
                    if (myGen != gen) return@launch
                    attempt = 0
                    connectedAt = System.currentTimeMillis()
                    _feedAuth.value = auth
                    // Anything but "authenticated" is the error this socket will get once MFP enforces the rules.
                    if (auth != null && auth != "authenticated") Log.w(TAG, "feed auth: $auth (enforced from ${enforceAt ?: "a later date"}), extensions: $ext")
                    else Log.i(TAG, "connected, feed auth: ${auth ?: "n/a"}, extensions: ${ext ?: "none"}")
                    _live.value = true
                    refs.keys.forEach { send(it) }
                    // The replacement's snapshots are arriving now; close the draining socket shortly after.
                    if (draining != null) drainJob = scope.launch(serial) { delay(5_000); closeDraining() }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch(serial) {
                    if (myGen == gen) handle(text)
                    else if (webSocket === draining) handleDraining(text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Complete the close handshake; onClosed follows with the same code.
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch(serial) {
                    if (webSocket === draining) draining = null
                    else if (myGen == gen) onDrop(null, code, reason)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // A refused upgrade carries {"error":{"code","message"}} like the REST API.
                val err = response?.let { r -> runCatching { r.body?.string() }.getOrNull() }
                    ?.let { runCatching { restJson.parseToJsonElement(it).jsonObject.obj("error")?.str("code") }.getOrNull() }
                Log.w(TAG, "failure ${response?.code ?: ""} ${err ?: ""}: ${t.message}")
                scope.launch(serial) {
                    if (webSocket === draining) draining = null
                    else if (myGen == gen) onDrop(response, null, err)
                }
            }
        })
    }

    private fun closeDraining() {
        drainJob?.cancel()
        draining?.close(1000, "replaced")
        draining = null
    }

    /**
     * Frames from a socket being drained: market events to bridge the gap until the replacement is
     * live, and answers to history requests it was already sent.
     */
    private fun handleDraining(text: String) {
        val f = runCatching { restJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (f.str("op")) {
            "events" -> f.arr("events").forEach { (it as? JsonObject)?.let(::onEvent) }
            "res" -> f.long("id")?.toInt()?.let { id -> pingSent.remove(id); oneShots.remove(id)?.complete(f["result"]) }
            "err" -> f.long("id")?.toInt()?.let { oneShots.remove(it)?.complete(null) }
        }
    }

    /**
     * The socket went away. [upgrade] is set when the upgrade itself was refused, [closeCode] when an
     * open socket was closed; [reason] is MFP's error code for either. Key, compression and bad-request
     * errors are permanent until the key changes, so they stop here instead of looping.
     */
    private fun onDrop(upgrade: Response?, closeCode: Int?, reason: String?) {
        ws = null
        _live.value = false
        val status = upgrade?.code
        val permanent = when {
            status == 400 || closeCode == 4003 -> "MFP refused the feed: ${reason ?: "compression_required"} (permessage-deflate)"
            status == 401 || status == 403 || closeCode == 4001 || closeCode == 4002 ->
                if (key == null || reason == "api_key_required") "Market data needs an API key: add a live key (fp_live_…) in Settings"
                else "Market data refused the API key (${reason ?: "invalid_api_key"}): use a live read-only or trading key"
            else -> null
        }
        if (permanent != null) {
            Log.e(TAG, permanent)
            _blocked.value = permanent
            reconnectJob?.cancel()
            return
        }
        if (paused || refs.isEmpty()) return
        val retryAfter = upgrade?.header("Retry-After")?.toLongOrNull()?.times(1000)
        // 5 sockets per key: another device or window holds them. Wait a while rather than hammer.
        val tooMany = closeCode == 4029 || reason == "too_many_connections"
        val base = minOf(30_000L, 500L shl minOf(attempt, 6))
        val backoff = maxOf(retryAfter ?: 0L, if (tooMany) 60_000L else base) + Random.nextLong(0, 1_000)
        if (tooMany) Log.w(TAG, "too many market sockets on this key (max 5), retrying in ${backoff / 1000}s")
        attempt++
        reconnectJob = scope.launch(serial) { delay(backoff); connect() }
    }

    private fun send(sub: Sub) {
        val socket = ws ?: return
        if (sub in rejected) return
        val id = nextId++
        activeIds[sub] = id
        idToSub[id] = sub
        subSentAt[sub] = System.currentTimeMillis()
        val payload = buildJsonObject {
            put("symbols", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(sub.coin)) })
            put("providers", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(sub.provider)) })
            if (sub.interval != null) {
                put("intervals", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(sub.interval)) })
                put("historyLimit", 0)
            }
        }
        socket.send(buildJsonObject {
            put("op", "sub"); put("id", id); put("channel", sub.channel); put("payload", payload)
        }.toString())
        if (sub.channel == "candles" && sub.interval != null) loadChartHistory(sub)
    }

    /**
     * Fetch 500 bars for a chart's candle feed and lay them under the live bars. Retries until it
     * lands: a turned-down request used to leave the chart on old bars until the app restarted.
     */
    private fun loadChartHistory(sub: Sub) {
        val iv = sub.interval ?: return
        val key = candleKey(sub.provider, sub.coin, iv)
        // Bars from before a pause would draw a wrong chart until the history lands: show none instead.
        val ms = intervalMs(iv)
        val last = candlesBuf[key]?.lastEntry()?.value
        if (last != null && ms != null && System.currentTimeMillis() - last.openTime > 2 * ms) {
            candlesBuf.remove(key); dirtyCandles += key
        }
        historyJobs[key]?.cancel()
        historyJobs[key] = scope.launch {
            for (attempt in 0 until 6) {
                val list = allSlots.withPermit { requestHistory(sub.provider, sub.coin, iv, 500) }
                if (list != null) {
                    withContext(serial) { if (sub in refs) mergeHistory(key, list) }
                    break
                }
                delay(1_000L * (attempt + 1))
            }
            withContext(serial) {
                val now = System.currentTimeMillis()
                // Still behind after a fresh load: the market is quiet or shut, so look again much later.
                val l = candlesBuf[key]?.lastEntry()?.value
                auditAt[key] = now + if (ms != null && (l == null || behind(l, ms, now))) 300_000 else 60_000
            }
        }
    }

    /** History is the truth for its span: bars older than it are from before a gap and go. */
    private fun mergeHistory(key: String, list: List<Candle>) {
        if (list.isEmpty()) return
        val map = candlesBuf.getOrPut(key) { TreeMap() }
        map.headMap(list.first().openTime).clear()
        val lastT = list.last().openTime
        // The forming bar may already have newer live updates than the snapshot; keep those.
        for (c in list) if (c.openTime < lastT || c.openTime !in map) map[c.openTime] = c
        dirtyCandles += key
    }

    /** The last bar ended over 30 s ago, so at least one candle is missing. */
    private fun behind(last: Candle, ms: Long, now: Long) = now - (last.openTime + ms) > 30_000

    /**
     * Every 5 s: reload a chart's history when its bars fall behind the clock (a stalled feed or a gap
     * after a reconnect), or when the forming candle sits over 1.5% from the venue's mark for 20 s.
     * The venue's own mark, not the book mid that real-time mode puts in [marks]: on mid-priced
     * venues the mid can sit apart from the traded candle without anything being wrong.
     */
    private fun audit(now: Long) {
        for (sub in activeIds.keys.toList()) {
            val iv = sub.interval ?: continue
            if (sub.channel != "candles") continue
            val ms = intervalMs(iv) ?: continue
            val key = candleKey(sub.provider, sub.coin, iv)
            if (historyJobs[key]?.isActive == true || now < (auditAt[key] ?: 0)) continue
            val last = candlesBuf[key]?.lastEntry()?.value
            val mark = venueBuf[sub.feed] ?: marksBuf[sub.feed]
            val drift = last != null && mark != null && mark > 0 && last.openTime + ms > now && abs(last.c / mark - 1) > 0.005
            if (drift) mismatchSince.getOrPut(key) { now } else mismatchSince.remove(key)
            val stale = last == null || behind(last, ms, now)
            if (stale || (drift && now - (mismatchSince[key] ?: now) >= 10_000)) {
                Log.i(TAG, "reloading candles $key: " + if (stale) "behind" else "off the live price")
                mismatchSince.remove(key)
                auditAt[key] = now + 60_000
                loadChartHistory(sub)
            }
        }
    }

    private fun handle(text: String) {
        lastFrameAt = System.currentTimeMillis()
        val f =runCatching { restJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (f.str("op")) {
            "events" -> f.arr("events").forEach { (it as? JsonObject)?.let(::onEvent) }
            "res" -> {
                val id = f.long("id")?.toInt() ?: return
                // Ping replies carry the server's time: keep the clock offset for UTC day boundaries.
                pingSent.remove(id)?.let { sent ->
                    // How long since MFP's own upstream heard anything; absent = unknown.
                    upstreamAgeMs = f.long("upstreamReadAgeMs")
                    val server = f.long("result") ?: return
                    val now = System.currentTimeMillis()
                    ServerClock.offset = server - (sent + now) / 2
                    return
                }
                oneShots.remove(id)?.complete(f["result"])
            }
            "end" -> {
                val sub = idToSub.remove(f.long("id")?.toInt() ?: return) ?: return
                activeIds.remove(sub)
                if (refs.containsKey(sub)) send(sub)
            }
            // Server is replacing this socket: open the replacement first, then let this one go.
            "draining" -> if (draining == null) { Log.i(TAG, "server draining, opening a replacement"); connect(keepOld = true) }
            "sub_err" -> {
                // A rejected filter or a full connection (256 subs, 64 leases): don't re-send it on every
                // stale check. It is tried again after a release or on the next connection.
                val sub = f.long("id")?.toInt()?.let { idToSub.remove(it) }
                val err = f.obj("error")
                val why = err?.str("reason") ?: err?.str("code") ?: err?.str("message") ?: "rejected"
                if (sub != null) { activeIds.remove(sub); rejected[sub] = why }
                Log.w(TAG, "subscription refused ${sub?.channel ?: ""} ${sub?.feed ?: ""}: $why")
            }
            "err" -> {
                f.long("id")?.toInt()?.let { oneShots.remove(it)?.complete(null) }
                Log.w(TAG, "request failed: $text")
            }
        }
    }

    private fun onEvent(e: JsonObject) {
        val provider = e.str("provider") ?: return
        val coin = e.str("symbol") ?: return
        val key = feedKey(provider, coin)
        val now = System.currentTimeMillis()
        lastAnyEventAt = now
        if (e.str("type") == "tick" || e.str("type") == "book") feedEventAt[key] = now
        when (e.str("type")) {
            "tick" -> if (e.str("kind") == "mark" && e.dbl("price")?.also { venueBuf[key] = it; dirty += "vmarks" } != null &&
                realtime && System.currentTimeMillis() - (bookMidAt[key] ?: 0) < 3_000) {
                // The book is fresher; ignore mark ticks while it keeps flowing.
            } else if (e.str("kind") == "mark") {
                // A zero is "no price yet" (closed or idle market), never a price to mark a position at.
                marksBuf[key] = e.dbl("price")?.takeIf { it > 0 } ?: return; dirty += "marks"
            } else if (key !in marksBuf && e.str("kind") == "mid") {
                marksBuf[key] = e.dbl("price")?.takeIf { it > 0 } ?: return; dirty += "marks"
            }
            "book" -> {
                val b = Book(levels(e, "bids"), levels(e, "asks"), e.long("time") ?: 0)
                booksBuf[key] = b
                dirty += "books"
                val bid = b.bids.firstOrNull()?.px
                val ask = b.asks.firstOrNull()?.px
                if (realtime && bid != null && ask != null && bid > 0 && ask > 0) {
                    marksBuf[key] = (bid + ask) / 2; dirty += "marks"
                    bookMidAt[key] = System.currentTimeMillis()
                }
            }
            "trade" -> {
                val q = tradesBuf.getOrPut(key) { ArrayDeque() }
                val id = e.str("tradeId") ?: return
                if (q.any { it.id == id }) return
                q.addFirst(Trade(id, e.str("side") == "buy", e.dbl("price") ?: 0.0, e.dbl("size") ?: 0.0, e.long("time") ?: 0))
                while (q.size > MAX_TRADES) q.removeLast()
                dirty += "trades"
            }
            "candle" -> {
                val iv = e.str("interval") ?: return
                // Events still in flight after a release would bring back a few stale bars that nothing clears.
                if (Sub("candles", provider, coin, iv) !in refs) return
                putCandle(candleKey(provider, coin, iv), e)
            }
            "marketStats" -> {
                val old = statsBuf[key] ?: Stats()
                fun d(k: String, prev: Double?) = if (!e.containsKey(k)) prev else if (e[k] is JsonNull) null else e.dbl(k)
                val s = Stats(
                    markPx = d("markPx", old.markPx),
                    change24hPct = d("change24hPct", old.change24hPct),
                    fundingRate = d("fundingRate", old.fundingRate),
                    fundingIntervalHours = d("fundingIntervalHours", old.fundingIntervalHours),
                    nextFundingTime = d("nextFundingTime", old.nextFundingTime?.toDouble())?.toLong(),
                    openInterestUsd = d("openInterestUsd", old.openInterestUsd),
                    dayNtlVlm = d("dayNtlVlm", old.dayNtlVlm),
                )
                statsBuf[key] = s
                dirty += "stats"
                val oi = s.openInterestUsd
                if (oi != null && e.containsKey("openInterestUsd")) {
                    val list = oiBuf.getOrPut(key) { ArrayList() }
                    val t = e.long("time") ?: System.currentTimeMillis()
                    if (list.isEmpty() || list.last().time < t) {
                        list += OiPoint(t, oi)
                        if (list.size > MAX_OI) list.subList(0, list.size - MAX_OI).clear()
                        dirtyOi += key
                    }
                }
            }
        }
    }

    private fun putCandle(key: String, c: JsonObject) {
        val t = c.long("openTime") ?: return
        val map = candlesBuf.getOrPut(key) { TreeMap() }
        map[t] = Candle(t, c.dbl("open") ?: 0.0, c.dbl("high") ?: 0.0, c.dbl("low") ?: 0.0, c.dbl("close") ?: 0.0, c.dbl("volume") ?: 0.0)
        while (map.size > MAX_CANDLES) map.pollFirstEntry()
        dirtyCandles += key
    }

    private fun levels(e: JsonObject, side: String) = e.arr(side).mapNotNull {
        val o = it as? JsonObject ?: return@mapNotNull null
        Level(o.dbl("px") ?: return@mapNotNull null, o.dbl("sz") ?: 0.0)
    }

    private fun flush() {
        if (dirtyCandles.isNotEmpty()) {
            // Copying every chart's 1,500 bars ten times a second was most of the app's garbage.
            _candles.value = HashMap(_candles.value).apply {
                for (k in dirtyCandles) { val b = candlesBuf[k]; if (b != null) put(k, b.values.toList()) else remove(k) }
            }
            dirtyCandles.clear()
        }
        if (dirtyOi.isNotEmpty()) {
            _oi.value = HashMap(_oi.value).apply { for (k in dirtyOi) { val b = oiBuf[k]; if (b != null) put(k, b.toList()) } }
            dirtyOi.clear()
        }
        if (dirty.isEmpty()) return
        if ("marks" in dirty) _marks.value = HashMap(marksBuf)
        if ("vmarks" in dirty) _venueMarks.value = HashMap(venueBuf)
        if ("stats" in dirty) _stats.value = HashMap(statsBuf)
        if ("books" in dirty) _books.value = HashMap(booksBuf)
        if ("trades" in dirty) _trades.value = tradesBuf.mapValues { it.value.toList() }
        dirty.clear()
    }
}
