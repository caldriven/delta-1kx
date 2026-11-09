package com.alex.mfppro.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.random.Random

private const val TAG = "AccountStream"
private const val LIVE_STREAM = "wss://account-stream.myfundedperpetuals.com/v1/stream"
private const val SANDBOX_STREAM = "wss://account-stream.myfundedperpetuals.com/v1/sandbox/stream"
private const val PING_MS = 3_000L
private const val SILENCE_MS = 8_000L
private const val PROBE_MS = 2_500L

data class AccountState(
    val account: Account,
    val positions: Map<String, Position> = emptyMap(),
    val orders: Map<String, Order> = emptyMap(),
    val lockedOut: Boolean = false,
)

enum class Conn { Off, Connecting, Live, Reconnecting, AuthFailed }

/**
 * One authenticated socket for every account the key reaches. Keeps the
 * stream id, last seq and last time so a reconnect resumes without a gap.
 * All state changes run on a single-threaded dispatcher.
 */
class AccountStream(private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val serial = Dispatchers.IO.limitedParallelism(1)

    private val _accounts = MutableStateFlow<Map<String, AccountState>>(emptyMap())
    val accounts = _accounts.asStateFlow()
    private val _conn = MutableStateFlow(Conn.Off)
    val conn = _conn.asStateFlow()
    private val _fills = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    val fills = _fills.asSharedFlow()

    private var key: String? = null
    private var ws: WebSocket? = null
    private var gen = 0
    private var streamId: String? = null
    private var lastSeq = 0L
    private var lastTime = 0L
    private var lastFrameAt = 0L
    private var lastPingAt = 0L
    private var pingSentAt = 0L
    private var probeAt = 0L
    private var pingId = 1
    private var attempt = 0
    private var reconnectJob: Job? = null
    private var building: MutableMap<String, AccountState>? = null

    private val _rtt = MutableStateFlow<Long?>(null)
    val rtt = _rtt.asStateFlow()

    /** False while MFP's heartbeat says it lost its own data source: events may be delayed. */
    private val _upstream = MutableStateFlow(true)
    val upstream = _upstream.asStateFlow()

    // Fill ids already passed on: a reconnect replays recent fills, which must not alert twice.
    private val seenFills = object : LinkedHashMap<String, Unit>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 500
    }

    // Ping every 3s; no frame for 8s (or 2.5s after a probe) means the socket is dead.
    init {
        scope.launch(serial) {
            while (isActive) {
                delay(500)
                val socket = ws ?: continue
                val now = System.currentTimeMillis()
                val probeFailed = probeAt > 0 && lastFrameAt < probeAt && now - probeAt > PROBE_MS
                if (probeFailed || now - lastFrameAt > SILENCE_MS) {
                    Log.w(TAG, "socket silent, reconnecting")
                    probeAt = 0
                    reconnectNow()
                } else if (_conn.value == Conn.Live && now - lastPingAt >= PING_MS) {
                    lastPingAt = now; pingSentAt = now
                    socket.send("""{"op":"ping","id":${pingId++}}""")
                }
            }
        }
    }

    /** Check right now whether the socket still answers (foreground, network change). */
    fun probe() = scope.launch(serial) {
        val socket = ws ?: return@launch
        val now = System.currentTimeMillis()
        probeAt = now; lastPingAt = now; pingSentAt = now
        socket.send("""{"op":"ping","id":${pingId++}}""")
    }

    /** Drop the socket and reconnect with the resume cursor (network switched). */
    fun reconnect() = scope.launch(serial) { if (key != null && _conn.value != Conn.AuthFailed) { attempt = 0; reconnectNow() } }

    /**
     * Drop the resume cursor and take a fresh snapshot of every account (manual refresh,
     * or back from a long stay in the background). Also retries after an auth failure.
     */
    fun resync() = scope.launch(serial) {
        if (key == null) return@launch
        streamId = null; lastSeq = 0; lastTime = 0
        building = null
        attempt = 0
        reconnectNow()
    }

    fun start(apiKey: String) = scope.launch(serial) {
        if (key != apiKey) {
            key = apiKey
            streamId = null; lastSeq = 0; lastTime = 0
            _accounts.value = emptyMap()
        }
        reconnectNow()
    }

    fun stop() = scope.launch(serial) {
        key = null
        reconnectJob?.cancel()
        gen++
        ws?.close(1000, null); ws = null
        _conn.value = Conn.Off
        _accounts.value = emptyMap()
    }

    /** Close the socket but keep the resume cursor (app went to background). */
    fun pause() = scope.launch(serial) {
        reconnectJob?.cancel()
        gen++
        ws?.close(1000, "background"); ws = null
        if (key != null) _conn.value = Conn.Reconnecting
    }

    /** Called when the app returns to the foreground. */
    fun resume() = scope.launch(serial) {
        if (key == null || _conn.value == Conn.AuthFailed) return@launch
        if (ws == null || System.currentTimeMillis() - lastFrameAt > SILENCE_MS) {
            attempt = 0
            reconnectNow()
        } else probe()
    }

    private fun reconnectNow() {
        val k = key ?: return
        reconnectJob?.cancel()
        gen++
        ws?.cancel(); ws = null
        val myGen = gen
        val base = if (k.startsWith("fp_test_")) SANDBOX_STREAM else LIVE_STREAM
        val url = streamId?.let { "$base?resume=$it&last_seq=$lastSeq&since=$lastTime" } ?: base
        _conn.value = if (_accounts.value.isEmpty()) Conn.Connecting else Conn.Reconnecting
        lastFrameAt = System.currentTimeMillis()
        val req = Request.Builder().url(url).header("Authorization", "Bearer $k").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch(serial) { if (myGen == gen) handle(text) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch(serial) { if (myGen == gen) onDrop(code, null) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "failure ${response?.code}: ${t.message}")
                scope.launch(serial) { if (myGen == gen) onDrop(null, response) }
            }
        })
    }

    private fun onDrop(closeCode: Int?, upgrade: Response?) {
        ws = null
        val status = upgrade?.code
        if (closeCode == 4001 || status == 401 || status == 403) {
            _conn.value = Conn.AuthFailed
            return
        }
        if (closeCode == 4008) streamId = null // too slow: take a fresh snapshot
        // Dropped mid-snapshot: a resume would never finish it, so start over with a fresh one.
        if (building != null) { building = null; streamId = null }
        val retryAfter = upgrade?.header("Retry-After")?.toLongOrNull()?.times(1000)
        val backoff = retryAfter ?: (minOf(30_000L, 500L shl minOf(attempt, 6)) + Random.nextLong(0, 500))
        attempt++
        _conn.value = Conn.Reconnecting
        reconnectJob = scope.launch(serial) {
            delay(if (closeCode == 1012) Random.nextLong(0, 1000) else backoff)
            reconnectNow()
        }
    }

    private fun handle(text: String) {
        lastFrameAt = System.currentTimeMillis()
        val f = runCatching { restJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        f.long("seq")?.let { lastSeq = it }
        f.long("time")?.let { lastTime = it }
        when (f.str("type")) {
            "hello" -> {
                val resumed = f["resumed"]?.jsonPrimitive?.booleanOrNull == true
                streamId = f.str("stream_id") ?: streamId
                attempt = 0
                if (!resumed) building = mutableMapOf()
                _conn.value = Conn.Live
            }
            "snapshot" -> applySnapshot(f)
            "snapshot_end" -> building?.let { _accounts.value = it.toMap(); building = null }
            "pong" -> _rtt.value = System.currentTimeMillis() - pingSentAt
            "heartbeat" -> _upstream.value = f["upstream_connected"]?.jsonPrimitive?.booleanOrNull ?: true
            "reconnect" -> reconnectNow()
            "error" -> Log.w(TAG, "error frame: $text")
            "order" -> mutate(f) { s ->
                val o = f.obj("order")?.let { decode<Order>(it) } ?: return@mutate s
                when (f.str("event")) {
                    "filled", "canceled", "rejected", "expired" -> s.copy(orders = s.orders - o.id)
                    else -> s.copy(orders = s.orders + (o.id to o))
                }
            }
            "position" -> mutate(f) { s ->
                val p = f.obj("position")?.let { decode<Position>(it) } ?: return@mutate s
                if (f.str("event") == "closed") s.copy(positions = s.positions - p.id)
                else s.copy(positions = s.positions + (p.id to p))
            }
            "account" -> {
                val id = f.str("account_id") ?: return
                if (f.str("event") == "removed") {
                    target().remove(id); publish()
                } else mutate(f) { s ->
                    val a = f.obj("account")?.let { decode<Account>(it) } ?: return@mutate s
                    s.copy(
                        account = a.copy(risk = keepFloors(a.risk, s.account.risk)),
                        lockedOut = f.obj("account")?.get("locked_out")?.jsonPrimitive?.booleanOrNull ?: s.lockedOut,
                    )
                }
            }
            "fill" -> f.obj("fill")?.let { fill ->
                val id = fill.str("id")
                if (id == null || seenFills.put(id, Unit) == null) _fills.tryEmit(fill)
            }
        }
    }

    private fun applySnapshot(frame: JsonObject) {
        // Tolerate the payload sitting at the top level or under "data"/"snapshot".
        val f = frame.obj("data") ?: frame.obj("snapshot") ?: frame
        val aJson = f.obj("account") ?: frame.obj("account") ?: return
        val acc = decode<Account>(aJson) ?: return
        fun list(vararg keys: String) = keys.firstNotNullOfOrNull { k -> (f[k] ?: frame[k]) as? JsonArray } ?: emptyList()
        val positions = list("positions", "open_positions").mapNotNull { decode<Position>(it.jsonObject) }.associateBy { it.id }
        val orders = list("orders", "working_orders").mapNotNull { decode<Order>(it.jsonObject) }.associateBy { it.id }
        target()[acc.id] = AccountState(
            // Snapshots don't carry the REST floors; keep the last ones we had.
            account = acc.copy(risk = keepFloors(acc.risk, _accounts.value[acc.id]?.account?.risk)),
            positions = positions,
            orders = orders,
            lockedOut = aJson["locked_out"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
        publish()
    }

    /** Snapshots after a non-resumed hello build a fresh map that replaces the old one at snapshot_end. */
    private fun target(): MutableMap<String, AccountState> = building ?: _accounts.value.toMutableMap().also { pending = it }
    private var pending: MutableMap<String, AccountState>? = null
    private fun publish() {
        if (building != null) return
        pending?.let { _accounts.value = it.toMap(); pending = null }
    }

    private inline fun mutate(f: JsonObject, block: (AccountState) -> AccountState) {
        val id = f.str("account_id") ?: return
        val map = target()
        val cur = map[id] ?: return
        map[id] = block(cur)
        publish()
    }

    /**
     * REST cross-check: if the open positions REST reports differ from what the
     * stream gave us, trust REST. Guards against a missed or unparsed event.
     */
    fun reconcilePositions(accountId: String, rest: List<Position>) = scope.launch(serial) {
        if (building != null) return@launch
        val map = target()
        val cur = map[accountId] ?: return@launch
        val fresh = rest.associateBy { it.id }
        if (fresh.keys != cur.positions.keys) {
            Log.w(TAG, "positions out of sync for $accountId, using REST")
            map[accountId] = cur.copy(positions = fresh)
        }
        publish()
    }

    /**
     * REST cross-check for working orders: if MFP's set, prices or sizes differ from what the
     * stream gave us (an edit that was accepted but not applied, a missed event), trust REST.
     */
    fun reconcileOrders(accountId: String, rest: List<Order>) = scope.launch(serial) {
        if (building != null) return@launch
        val map = target()
        val cur = map[accountId] ?: return@launch
        val fresh = rest.associateBy { it.id }
        fun sig(o: Order) = Triple(o.limitPrice, o.triggerPrice, o.size)
        val differs = fresh.keys != cur.orders.keys || fresh.any { (id, o) -> cur.orders[id]?.let(::sig) != sig(o) }
        if (differs) {
            Log.w(TAG, "orders out of sync for $accountId, using REST")
            map[accountId] = cur.copy(orders = fresh)
        }
        publish()
    }

    /** REST risk snapshots carry the floors the stream does not send. */
    fun mergeRisk(acc: Account) = scope.launch(serial) {
        val map = target()
        map[acc.id]?.let { map[acc.id] = it.copy(account = it.account.copy(risk = acc.risk)) }
        publish()
    }

    private inline fun <reified T> decode(o: JsonObject): T? =
        runCatching { restJson.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), o) }
            .onFailure { Log.w(TAG, "decode ${T::class.simpleName}: ${it.message}") }
            .getOrNull()
}

internal fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
internal fun JsonObject.long(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
    ?: (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()
internal fun JsonObject.dbl(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
internal fun JsonObject.obj(k: String) = this[k] as? JsonObject
internal fun JsonObject.arr(k: String): List<JsonElement> = (this[k] as? JsonArray) ?: emptyList()

/** Newer risk block, with floors and rules filled from the older one where the newer has none. */
private fun keepFloors(new: AccountRisk?, old: AccountRisk?): AccountRisk? {
    if (new == null || old == null) return new ?: old
    return new.copy(
        dailyLossFloor = new.dailyLossFloor ?: old.dailyLossFloor,
        maxDrawdownFloor = new.maxDrawdownFloor ?: old.maxDrawdownFloor,
        // Rules don't change mid-account, and only REST fills them in.
        requirements = old.requirements ?: new.requirements,
    )
}
