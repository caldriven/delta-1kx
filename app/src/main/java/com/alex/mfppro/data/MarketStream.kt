package com.alex.mfppro.data

import android.util.Log
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
import kotlin.random.Random

private const val TAG = "MarketStream"
private const val URL = "wss://api-stream.myfundedperpetuals.com/v1/market-data"
// Publish at ~20 fps: chart and PnL feel live without flooding recomposition.
private const val FLUSH_MS = 50L
private const val MAX_TRADES = 80
private const val MAX_CANDLES = 1500
private const val MAX_OI = 5000

data class Sub(val channel: String, val provider: String, val coin: String, val interval: String? = null) {
    val feed get() = feedKey(provider, coin)
}

fun candleKey(provider: String, coin: String, interval: String) = "$provider|$coin|$interval"

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

    private val refs = HashMap<Sub, Int>()
    private val activeIds = HashMap<Sub, Int>()
    private val idToSub = HashMap<Int, Sub>()
    private val historyReqs = HashMap<Int, String>()
    private var nextId = 1

    private var ws: WebSocket? = null
    private var gen = 0
    private var paused = false
    private var attempt = 0
    private var reconnectJob: Job? = null

    private var lastFrameAt = 0L
    private var lastPingAt = 0L
    private var probeAt = 0L

    init {
        scope.launch(serial) {
            while (isActive) {
                delay(FLUSH_MS)
                flush()
                watchdog()
            }
        }
    }

    // Same liveness policy as the account stream: ping every 3s, dead after 8s silence.
    private fun watchdog() {
        val socket = ws ?: return
        val now = System.currentTimeMillis()
        val probeFailed = probeAt > 0 && lastFrameAt < probeAt && now - probeAt > 2_500
        if (probeFailed || now - lastFrameAt > 8_000) {
            Log.w(TAG, "socket silent, reconnecting")
            probeAt = 0
            _live.value = false
            connect()
        } else if (_live.value && now - lastPingAt >= 3_000) {
            lastPingAt = now
            socket.send("""{"op":"req","id":${nextId++},"method":"ping"}""")
        }
    }

    fun probe() = scope.launch(serial) {
        val socket = ws ?: return@launch
        probeAt = System.currentTimeMillis(); lastPingAt = probeAt
        socket.send("""{"op":"req","id":${nextId++},"method":"ping"}""")
    }

    private val oneShots = HashMap<Int, CompletableDeferred<JsonElement?>>()
    // The server allows 4 concurrent history requests; keep one free for the chart.
    private val historySlots = Semaphore(3)

    /** One-off candle history (for overview stats), or empty if unavailable. */
    suspend fun candleHistory(provider: String, coin: String, interval: String, limit: Int): List<Candle> = historySlots.withPermit {
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
        if (!sent) return emptyList()
        val res = withTimeoutOrNull(10_000) { d.await() } as? JsonArray ?: return emptyList()
        res.mapNotNull { e ->
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
        activeIds.remove(sub)?.let { id ->
            idToSub.remove(id)
            ws?.send("""{"op":"unsub","id":$id}""")
        }
    }

    fun pause() = scope.launch(serial) {
        paused = true
        reconnectJob?.cancel()
        gen++
        ws?.close(1000, "background"); ws = null
        _live.value = false
    }

    fun resume() = scope.launch(serial) {
        paused = false
        if (ws == null && refs.isNotEmpty()) { attempt = 0; connect() } else if (ws != null) probe()
    }

    private fun connect() {
        reconnectJob?.cancel()
        gen++
        ws?.cancel()
        val myGen = gen
        lastFrameAt = System.currentTimeMillis()
        activeIds.clear(); idToSub.clear(); historyReqs.clear()
        oneShots.values.forEach { it.complete(null) }; oneShots.clear()
        ws = http.newWebSocket(Request.Builder().url(URL).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch(serial) {
                    if (myGen != gen) return@launch
                    attempt = 0
                    _live.value = true
                    refs.keys.forEach { send(it) }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch(serial) { if (myGen == gen) handle(text) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch(serial) { if (myGen == gen) onDrop(null) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "failure ${response?.code}: ${t.message}")
                scope.launch(serial) { if (myGen == gen) onDrop(response) }
            }
        })
    }

    private fun onDrop(upgrade: Response?) {
        ws = null
        _live.value = false
        if (paused || refs.isEmpty()) return
        val retryAfter = upgrade?.header("Retry-After")?.toLongOrNull()?.times(1000)
        val backoff = retryAfter ?: (minOf(30_000L, 500L shl minOf(attempt, 6)) + Random.nextLong(0, 500))
        attempt++
        reconnectJob = scope.launch(serial) { delay(backoff); connect() }
    }

    private fun send(sub: Sub) {
        val socket = ws ?: return
        val id = nextId++
        activeIds[sub] = id
        idToSub[id] = sub
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
        if (sub.channel == "candles" && sub.interval != null) {
            val hid = nextId++
            historyReqs[hid] = candleKey(sub.provider, sub.coin, sub.interval)
            socket.send(buildJsonObject {
                put("op", "req"); put("id", hid); put("method", "candles.history")
                put("payload", buildJsonObject {
                    put("provider", sub.provider); put("symbol", sub.coin)
                    put("interval", sub.interval); put("limit", 500)
                })
            }.toString())
        }
    }

    private fun handle(text: String) {
        lastFrameAt = System.currentTimeMillis()
        val f =runCatching { restJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (f.str("op")) {
            "events" -> f.arr("events").forEach { (it as? JsonObject)?.let(::onEvent) }
            "res" -> {
                val id = f.long("id")?.toInt() ?: return
                oneShots.remove(id)?.let { it.complete(f["result"]); return }
                val key = historyReqs.remove(id) ?: return
                f.arr("result").forEach { (it as? JsonObject)?.let { c -> putCandle(key, c) } }
            }
            "end" -> {
                val sub = idToSub.remove(f.long("id")?.toInt() ?: return) ?: return
                activeIds.remove(sub)
                if (refs.containsKey(sub)) send(sub)
            }
            "draining" -> connect()
            "sub_err", "err" -> {
                f.long("id")?.toInt()?.let { oneShots.remove(it)?.complete(null) }
                Log.w(TAG, "server error: $text")
            }
        }
    }

    private fun onEvent(e: JsonObject) {
        val provider = e.str("provider") ?: return
        val coin = e.str("symbol") ?: return
        val key = feedKey(provider, coin)
        when (e.str("type")) {
            "tick" -> if (e.str("kind") == "mark" && e.dbl("price")?.also { venueBuf[key] = it; dirty += "vmarks" } != null &&
                realtime && System.currentTimeMillis() - (bookMidAt[key] ?: 0) < 3_000) {
                // The book is fresher; ignore mark ticks while it keeps flowing.
            } else if (e.str("kind") == "mark") {
                marksBuf[key] = e.dbl("price") ?: return; dirty += "marks"
            } else if (key !in marksBuf && e.str("kind") == "mid") {
                marksBuf[key] = e.dbl("price") ?: return; dirty += "marks"
            }
            "book" -> {
                val b = Book(levels(e, "bids"), levels(e, "asks"), e.long("time") ?: 0)
                booksBuf[key] = b
                dirty += "books"
                val bid = b.bids.firstOrNull()?.px
                val ask = b.asks.firstOrNull()?.px
                if (realtime && bid != null && ask != null) {
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
            "candle" -> putCandle(candleKey(provider, coin, e.str("interval") ?: return), e)
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
                        dirty += "oi"
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
        dirty += "candles"
    }

    private fun levels(e: JsonObject, side: String) = e.arr(side).mapNotNull {
        val o = it as? JsonObject ?: return@mapNotNull null
        Level(o.dbl("px") ?: return@mapNotNull null, o.dbl("sz") ?: 0.0)
    }

    private fun flush() {
        if (dirty.isEmpty()) return
        if ("marks" in dirty) _marks.value = HashMap(marksBuf)
        if ("vmarks" in dirty) _venueMarks.value = HashMap(venueBuf)
        if ("stats" in dirty) _stats.value = HashMap(statsBuf)
        if ("books" in dirty) _books.value = HashMap(booksBuf)
        if ("trades" in dirty) _trades.value = tradesBuf.mapValues { it.value.toList() }
        if ("candles" in dirty) _candles.value = candlesBuf.mapValues { it.value.values.toList() }
        if ("oi" in dirty) _oi.value = oiBuf.mapValues { it.value.toList() }
        dirty.clear()
    }
}
