package com.alex.mfppro.data

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeUnit

private const val TAG = "Repo"

/** Round a base-asset size down to the market's precision. */
fun roundSize(size: Double, m: Market?): Double =
    BigDecimal.valueOf(size).setScale(m?.sizeDecimals ?: 6, RoundingMode.DOWN).toDouble()
private const val BACKGROUND_GRACE_MS = 20_000L
private const val RISK_REFRESH_MS = 15_000L

class Repo(ctx: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = Prefs(ctx)
    private val http = OkHttpClient.Builder()
        .pingInterval(5, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    val rest = RestApi(http)
    val trade = TradeApi(http)
    val oiHistory = OiHistoryApi(http)
    val account = AccountStream(http, scope)
    val market = MarketStream(http, scope)

    /** Public trade prints straight from the venues, for the cross-exchange tape. */
    val venueTrades by lazy { VenueTrades(http) }

    /** Macro factors (VIX, 10Y, DXY) for analysis only. */
    val factors by lazy { FactorFeed(http) }

    /** Candle history for a market or a macro factor. */
    suspend fun history(m: Market, interval: String, bars: Int): List<Candle> =
        if (m.provider == FactorFeed.PROVIDER) factors.candles(m, interval, bars) else market.candleHistory(m.provider, m.coin, interval, bars)

    /** One-line results of trading actions, shown as snackbars. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages = _messages.asSharedFlow()
    fun postMessage(text: String) { _messages.tryEmit(text) }

    /** What happened to a trading action, for the result banner. [ok] null = still in progress (queued). */
    data class TradeEvent(val what: String, val ok: Boolean?, val status: String, val detail: String, val time: Long = System.currentTimeMillis())
    private val _tradeEvents = MutableSharedFlow<TradeEvent>(extraBufferCapacity = 16)
    val tradeEvents = _tradeEvents.asSharedFlow()

    private val _markets = MutableStateFlow<List<Market>>(emptyList())
    val markets = _markets.asStateFlow()
    private val _selected = MutableStateFlow(prefs.selectedAccounts)
    val selected = _selected.asStateFlow()
    private val _apiKey = MutableStateFlow(prefs.apiKey)
    val apiKey = _apiKey.asStateFlow()
    // Declared before init: trackPositionMarks() reads it on startup.
    private val _realtime = MutableStateFlow(prefs.realtimePnl)
    val realtime = _realtime.asStateFlow()

    private var pauseJob: Job? = null
    private var riskJob: Job? = null
    /** App on screen. In the background the monitor keeps mark ticks only and checks less often. */
    private val _visible = MutableStateFlow(true)
    val visible = _visible.asStateFlow()

    init {
        loadMarkets()
        _apiKey.value?.let { account.start(it) }
        trackPositionMarks()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { _visible.value = true; onForeground() }
            override fun onStop(owner: LifecycleOwner) { _visible.value = false; onBackground() }
        })
        // A new default network (Wi-Fi <-> mobile) leaves old sockets dead: reconnect at once.
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        var first = true
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (first) { first = false; return }
                account.reconnect(); market.reconnect()
            }
        })
    }

    // ---- trading -------------------------------------------------------

    /** Closed positions (whole trades), newest first, up to [maxPages] × 100. */
    suspend fun closedPositions(accountId: String, maxPages: Int = 20): List<ClosedPosition> {
        val key = _apiKey.value ?: error("Add an API key first")
        val out = ArrayList<ClosedPosition>()
        var cursor: String? = null
        repeat(maxPages) {
            val page = rest.closedPositions(key, accountId, cursor)
            out += page.data
            cursor = page.nextCursor
            if (!page.hasMore || cursor == null) return out
        }
        return out
    }

    /** Fill history for the journal, newest first (up to 2,000 fills). */
    suspend fun fills(accountId: String, maxPages: Int = 20): List<Fill> {
        val key = _apiKey.value ?: error("Add an API key first")
        val out = ArrayList<Fill>()
        var cursor: String? = null
        repeat(maxPages) {
            val page = rest.fills(key, accountId, cursor)
            out += page.data
            cursor = page.nextCursor
            if (!page.hasMore || cursor == null) return out
        }
        return out
    }

    private fun requireKey(): String? = _apiKey.value.also { if (it == null) _messages.tryEmit("Add an API key first") }

    /** Every trading result goes to the result banner: green tick, red cross, or amber while queued. */
    private fun report(what: String, r: TradeResult): TradeResult {
        _tradeEvents.tryEmit(
            when (r) {
                is TradeResult.Ok -> {
                    val st = r.order?.status?.lowercase()
                    when {
                        // Accepted by the API but the engine turned it down.
                        st == "rejected" || st == "canceled" || st == "cancelled" || st == "expired" ->
                            TradeEvent(what, false, st.replaceFirstChar { it.uppercase() }, "The order was not executed")
                        r.queued -> TradeEvent(what, null, "Queued", "Waiting for the venue")
                        else -> TradeEvent(what, true, when (st) {
                            "filled" -> "Filled"; "open", "new", "resting" -> "Placed"; "partially_filled" -> "Partly filled"
                            "triggered", "pending" -> "Pending"; null -> "Done"; else -> st.replace('_', ' ').replaceFirstChar { it.uppercase() }
                        }, r.order?.let { o -> listOfNotNull(o.side.uppercase().ifBlank { null }, o.type.replace('_', ' ').ifBlank { null }, (o.limitPrice ?: o.triggerPrice)?.let { "@ " + com.alex.mfppro.ui.fmtPrice(it) }).joinToString(" ") } ?: "")
                    }
                }
                // Plain words for the rejections a trader can act on.
                is TradeResult.Rejected -> TradeEvent(what, false, "Rejected", when (r.code) {
                    "exposure_cap" -> "Market exposure cap reached: size down or close some first"
                    "venue_conflict" -> "You already hold this on another venue: close that first or switch venue"
                    "network" -> "No answer from MFP. Check open orders before retrying. (${r.message})"
                    else -> r.message
                } + if (r.code !in setOf("exposure_cap", "venue_conflict", "network")) "  [${r.code}]" else "")
            }
        )
        refreshRiskSoon()
        return r
    }

    suspend fun placeScaled(label: String, body: JsonObject): TradeResult {
        val key = requireKey() ?: return TradeResult.Rejected("no_key", "No API key")
        return report(label, trade.placeScaled(key, body))
    }

    suspend fun placeOrder(label: String, body: JsonObject): TradeResult {
        val key = requireKey() ?: return TradeResult.Rejected("no_key", "No API key")
        return report(label, trade.placeOrder(key, body))
    }

    /**
     * Market close of [fraction] of the position, or a limit close at [limitPrice].
     * With [postOnly] the limit must rest: MFP rejects it instead of filling it at market
     * when it would cross the book.
     */
    suspend fun closePosition(p: Position, fraction: Double = 1.0, limitPrice: Double? = null, postOnly: Boolean = false): TradeResult {
        val key = requireKey() ?: return TradeResult.Rejected("no_key", "No API key")
        val m = market(p.marketId)
        val body = buildJsonObject {
            if (fraction < 1.0) put("size", roundSize(p.size * fraction, m))
            if (limitPrice != null) {
                put("type", "limit"); put("limit_price", m?.let { com.alex.mfppro.ui.snapTick(limitPrice, it) } ?: limitPrice)
                if (!postOnly) put("marketable_limit_acknowledged", true)
            } else {
                put("type", "market")
                market.marks.value[p.feedKey]?.let { put("expected_price", it) }
            }
        }
        val what = if (fraction < 1.0) "Close ${kotlin.math.round(fraction * 100).toInt()}% ${p.symbol}" else "Close ${p.symbol}"
        return report(what, trade.closePosition(key, p.id, body))
    }

    /**
     * Reduce-only chase close: rests at the best ask (closing a long) or bid (closing a short),
     * [offset] further out in price, and follows the touch on MFP's server until filled.
     * [cap] is the worst price it may follow to.
     */
    suspend fun chaseClose(p: Position, fraction: Double, offset: Double, cap: Double?): TradeResult {
        val m = market(p.marketId)
        val what = if (fraction < 1.0) "Chase close ${kotlin.math.round(fraction * 100).toInt()}% ${p.symbol}" else "Chase close ${p.symbol}"
        return placeOrder(what, buildJsonObject {
            put("type", "chase"); put("account_id", p.accountId); put("market_id", p.marketId)
            put("side", if (p.isLong) "sell" else "buy")
            put("size", roundSize(p.size * fraction, m))
            put("leverage", p.leverage); put("margin_mode", p.marginMode)
            put("reduce_only", true)
            put("chase_offset", offset)
            cap?.let { put("chase_cap_price", it) }
        })
    }

    /** Flip a position: one market order for twice its size on the other side. */
    suspend fun reverse(p: Position): TradeResult = placeOrder("Reverse ${p.symbol}", buildJsonObject {
        put("type", "market"); put("account_id", p.accountId); put("market_id", p.marketId)
        put("side", if (p.isLong) "sell" else "buy")
        put("size", roundSize(p.size * 2, market(p.marketId)))
        put("leverage", p.leverage); put("margin_mode", p.marginMode)
        put("target_position_id", p.id)
        put("slippage_tolerance_bps", prefs.slippageBps)
        market.marks.value[p.feedKey]?.let { put("expected_price", it) }
    })

    fun exitsOf(p: Position): List<Order> =
        account.accounts.value[p.accountId]?.orders?.values.orEmpty()
            .filter { it.marketId == p.marketId && it.exitGroup != null }

    /**
     * Set (or with null remove) the position's TP and SL in one atomic batch.
     * [tpExec] / [slExec] pick "market" (trigger, then market close) or "limit" (a resting
     * reduce-only limit at the price); null keeps what an existing exit uses, or the saved default.
     */
    suspend fun setTpSl(
        p: Position, tp: Double?, sl: Double?, label: String = "TP/SL ${p.symbol}",
        tpExec: String? = null, slExec: String? = null,
    ): TradeResult {
        val key = requireKey() ?: return TradeResult.Rejected("no_key", "No API key")
        val current = exitsOf(p)
        val ops = mutableListOf<JsonObject>()
        val placed = mutableMapOf<String, Int>()
        // Every exit price lands on the market's tick (breakeven at an average entry, guard stops, drags).
        val m = market(p.marketId)
        fun snap(v: Double?) = v?.let { x -> m?.let { com.alex.mfppro.ui.snapTick(x, it) } ?: x }
        for ((group, price) in listOf("tp" to snap(tp), "sl" to snap(sl))) {
            val existing = current.filter { it.exitGroup == group }
            val asked = if (group == "tp") tpExec else slExec
            val exec = asked ?: existing.firstOrNull()?.exitExecution ?: if (group == "tp") prefs.tpExecution else prefs.slExecution
            // Modify can't change how an exit executes; switching means cancel and place again.
            val keep = existing.firstOrNull()?.takeIf { it.exitExecution == exec }
            if (price == null) {
                existing.forEach { ops += buildJsonObject { put("kind", "cancel"); put("order_id", it.id) } }
            } else if (keep != null) {
                ops += buildJsonObject { put("kind", "modify"); put("order_id", keep.id); put("price", price); put("size", p.size) }
                existing.filter { it !== keep }.forEach { ops += buildJsonObject { put("kind", "cancel"); put("order_id", it.id) } }
            } else {
                existing.forEach { ops += buildJsonObject { put("kind", "cancel"); put("order_id", it.id) } }
                placed[group] = ops.size
                ops += buildJsonObject { put("kind", "place"); put("group", group); put("execution_type", exec); put("price", price); put("size", p.size) }
            }
        }
        // Pair a freshly placed TP and SL as OCO so one filling cancels the other.
        val t = placed["tp"]; val s = placed["sl"]
        if (t != null && s != null) {
            ops[t] = JsonObject(ops[t] + ("oco_pair_with_operation_index" to JsonPrimitive(s)))
            ops[s] = JsonObject(ops[s] + ("oco_pair_with_operation_index" to JsonPrimitive(t)))
        }
        if (ops.isEmpty()) return TradeResult.Ok(null)
        return report(label, trade.replaceExits(key, p, current, ops))
    }

    /** Move the stop loss to the entry price, keeping any take profit. */
    suspend fun breakeven(p: Position): TradeResult {
        val tp = exitsOf(p).firstOrNull { it.exitGroup == "tp" }?.exitPrice
        return setTpSl(p, tp, p.entryPrice, "Breakeven ${p.symbol}")
    }

    suspend fun cancelOrder(o: Order): TradeResult {
        val key = requireKey() ?: return TradeResult.Rejected("no_key", "No API key")
        return report("Cancel ${o.symbol}", trade.cancelOrder(key, o.id))
    }

    suspend fun closeAll(accountIds: Collection<String>) {
        val key = requireKey() ?: return
        for (id in accountIds) {
            val name = account.accounts.value[id]?.account?.name ?: id
            report("Close all ($name)", trade.closeAll(key, id))
        }
    }

    fun market(marketId: String) = _markets.value.firstOrNull { it.marketId == marketId }

    private fun refreshRiskSoon() = scope.launch {
        delay(1_500)
        val key = _apiKey.value ?: return@launch
        for (id in account.accounts.value.keys) refreshAccount(key, id)
    }

    fun setApiKey(key: String?) {
        prefs.apiKey = key
        _apiKey.value = key
        if (key == null) account.stop() else account.start(key)
        startRiskRefresh()
    }

    /** The account the Markets screen and order ticket trade on. */
    private val _tradeAccount = MutableStateFlow(prefs.tradeAccountId)
    val tradeAccount = _tradeAccount.asStateFlow()
    fun setTradeAccount(id: String) { prefs.tradeAccountId = id; _tradeAccount.value = id }

    fun setSelected(ids: Set<String>) {
        prefs.selectedAccounts = ids
        _selected.value = ids
    }

    private fun onForeground() {
        pauseJob?.cancel()
        account.resume()
        market.resume()
        startRiskRefresh()
        if (_markets.value.isEmpty()) loadMarkets()
    }

    /** While the monitor service runs, the sockets stay open in the background. */
    @Volatile private var monitoring = false
    fun setBackgroundMonitoring(on: Boolean) { monitoring = on; if (on) onForeground() }

    private fun onBackground() {
        if (monitoring) return
        riskJob?.cancel()
        pauseJob = scope.launch {
            delay(BACKGROUND_GRACE_MS)
            account.pause()
            market.pause()
        }
    }

    /** Every venue per instrument (default first), for switching venue on the trade screen. */
    private val _venues = MutableStateFlow<List<Market>>(emptyList())
    val venues = _venues.asStateFlow()
    private fun loadVenues() = scope.launch {
        runCatching { rest.allVenues() }.onSuccess { list -> _venues.value = list.filter { it.tradingEnabled } }
    }

    /** 4-hour change per market, from hourly candles; loaded on demand, refreshed every 5 min at most. */
    private val _chg4h = MutableStateFlow<Map<String, Double>>(emptyMap())
    val chg4h = _chg4h.asStateFlow()
    private var chg4hAt = 0L
    private var chg4hJob: Job? = null
    fun load4h() {
        if (chg4hJob?.isActive == true || System.currentTimeMillis() - chg4hAt < 5 * 60_000) return
        chg4hJob = scope.launch {
            chg4hAt = System.currentTimeMillis()
            for (m in _markets.value) launch {
                val c = runCatching { market.candleHistory(m.provider, m.coin, "1h", 5) }.getOrNull().orEmpty()
                if (c.size >= 4) {
                    val open = c[c.size - 4].o
                    if (open > 0) _chg4h.update { it + (m.marketId to (c.last().c / open - 1) * 100) }
                }
            }
        }
    }

    /** Rolling 1-hour change per market, from twelve 5-minute candles; refreshed every 2 min at most. */
    private val _chg1h = MutableStateFlow<Map<String, Double>>(emptyMap())
    val chg1h = _chg1h.asStateFlow()
    private var chg1hAt = 0L
    private var chg1hJob: Job? = null
    fun load1h(only: List<Market> = _markets.value) {
        if (chg1hJob?.isActive == true || System.currentTimeMillis() - chg1hAt < 2 * 60_000) return
        chg1hJob = scope.launch {
            chg1hAt = System.currentTimeMillis()
            for (m in only) launch {
                val c = runCatching { market.candleHistory(m.provider, m.coin, "5m", 13) }.getOrNull().orEmpty()
                if (c.size >= 12) {
                    val open = c[c.size - 12].o
                    if (open > 0) _chg1h.update { it + (m.marketId to (c.last().c / open - 1) * 100) }
                }
            }
        }
    }

    private fun loadMarkets() = scope.launch {
        loadVenues()
        var wait = 1_000L
        while (isActive && _markets.value.isEmpty()) {
            runCatching { rest.markets() }
                .onSuccess { list -> _markets.value = list.filter { it.tradingEnabled }.sortedBy { it.symbol } }
                .onFailure { Log.w(TAG, "markets: ${it.message}"); delay(wait); wait = minOf(wait * 2, 30_000) }
        }
    }

    /** The stream carries realized balance only; floors and rule room come from REST. */
    private fun startRiskRefresh() {
        riskJob?.cancel()
        val key = _apiKey.value ?: return
        riskJob = scope.launch {
            var tries = 0
            while (isActive) {
                // Active accounts carry live rules; failed/passed ones never change, so skip them.
                val known = account.accounts.value.values
                // Give the stream a moment to list the accounts so the active ones go first.
                if (known.isEmpty() && tries++ < 10) { delay(1_000); continue }
                val ids = if (known.isEmpty()) runCatching { rest.accounts(key).map { it.id } }.getOrDefault(emptyList())
                    else known.filter { it.account.status == "active" || it.account.risk == null }
                        .sortedBy { if (it.account.status == "active") 0 else 1 }.map { it.account.id }
                for (id in ids) refreshAccount(key, id)
                delay(RISK_REFRESH_MS)
            }
        }
    }

    /** Keep a mark-price subscription for every market with an open position. */
    /** Value positions off the live order book instead of mark ticks (display and alerts). */
    fun setRealtime(on: Boolean) {
        prefs.realtimePnl = on
        market.realtime = on
        _realtime.value = on
    }

    // Price feeds for every held market: mark ticks, plus the book when valuing in real time.
    private fun trackPositionMarks() = scope.launch {
        market.realtime = prefs.realtimePnl
        var held = emptySet<Sub>()
        // Order books only while the app is on screen: in the background mark ticks are enough for alerts,
        // and every held market's full book is the heaviest feed there is.
        kotlinx.coroutines.flow.combine(account.accounts, _realtime, _visible) { m, rt, vis ->
            m.values.flatMap { it.positions.values }.flatMap {
                listOfNotNull(Sub("ticks", it.provider, it.coin), if (rt && vis) Sub("books", it.provider, it.coin) else null)
            }.toSet()
        }
            .distinctUntilChanged()
            .collect { want ->
                (want - held).forEach { market.acquire(it) }
                (held - want).forEach { market.release(it) }
                held = want
            }
    }

    /** Rule floors from REST, plus a position cross-check for active accounts. */
    /** Exposure caps per account; they rarely change, so fetched at most every 10 minutes. */
    private val _policies = MutableStateFlow<Map<String, TradingPolicy>>(emptyMap())
    val policies = _policies.asStateFlow()
    private val policyAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private suspend fun refreshAccount(key: String, id: String) {
        val acc = runCatching { rest.account(key, id) }.onFailure { Log.w(TAG, "account $id: ${it.message}") }.getOrNull() ?: return
        account.mergeRisk(acc)
        if (acc.status == "active" && System.currentTimeMillis() - (policyAt[id] ?: 0) > 600_000) {
            runCatching { rest.tradingPolicy(key, id) }.onSuccess { pol ->
                policyAt[id] = System.currentTimeMillis(); _policies.update { it + (id to pol) }
                Competitions.ids = if (pol.competition != null) Competitions.ids + id else Competitions.ids - id
            }
        }
        if (acc.status == "active") runCatching { rest.positions(key, id) }.onSuccess { account.reconcilePositions(id, it) }
    }

    // ---- market overview ----------------------------------------------

    private val _classes = MutableStateFlow(AssetClasses.decode(prefs.assetClasses))
    val classes = _classes.asStateFlow()
    private val _extras = MutableStateFlow<Map<String, MarketExtra>>(emptyMap())
    val extras = _extras.asStateFlow()
    private var extrasLoadedAt = 0L
    private var extrasJob: Job? = null

    /** Asset classes (cached), then 7-day change and 24h OI change for every market, every 15 min at most. */
    fun loadOverviewExtras() {
        if (extrasJob?.isActive == true || System.currentTimeMillis() - extrasLoadedAt < 15 * 60_000 &&
            extrasLoadedAt >= Opens.dayStart()) return
        extrasJob = scope.launch {
            runCatching { AssetClasses.load(http) }.onSuccess {
                if (it.isNotEmpty()) { _classes.value = it; prefs.assetClasses = AssetClasses.encode(it) }
            }
            val list = _markets.value
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            kotlinx.coroutines.coroutineScope {
                for (m in list) launch {
                    gate.acquire()
                    try {
                        val oi = runCatching { oiHistory.change24h(m.provider, m.coin) }.getOrNull()
                        val days = market.candleHistory(m.provider, m.coin, "1d", 8)
                        val week = if (days.size >= 2) {
                            val ref = if (days.size >= 8) days[days.size - 8].c else days.first().o
                            if (ref > 0) (days.last().c / ref - 1) * 100 else null
                        } else null
                        _extras.update { it + (m.marketId to MarketExtra(week, oi, Opens.dayOpen(days))) }
                    } finally { gate.release() }
                }
            }
            extrasLoadedAt = System.currentTimeMillis()
        }
    }
}
