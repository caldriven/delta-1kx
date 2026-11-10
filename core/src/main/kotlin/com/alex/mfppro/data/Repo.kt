package com.alex.mfppro.data

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
private const val STALE_AFTER_MS = 60_000L

class Repo(val prefs: Prefs) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
    }

    /** The app came on screen or left it (Android process lifecycle; desktop window shown or minimised). */
    fun setVisible(on: Boolean) {
        _visible.value = on
        if (on) onForeground() else onBackground()
    }

    /** A new default network (Wi-Fi <-> mobile, VPN, dock) leaves old sockets dead: reconnect at once. */
    fun networkChanged() {
        account.reconnect(); market.reconnect()
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
                    "stale_snapshot" -> "The TP/SL changed on MFP since the app last saw it. Wait a moment and try again."
                    "account_trader_lockout_active" -> "Trader lockout is on: MFP refuses new orders, edits and cancels until it ends. Existing TP/SL still work."
                    "competition_finalization_pending" -> "Competition is closing: retry in a few minutes."
                    "rate_limited" -> "Too many requests from this network: wait a minute and retry."
                    else -> r.message
                } + if (r.code !in setOf("exposure_cap", "venue_conflict", "network")) "  [${r.code}]" else "")
            }
        )
        // MFP: refresh the trading policy after a rejection, not on a timer alone.
        if (r is TradeResult.Rejected) policyAt.clear()
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
        var r = trade.closePosition(key, p.id, body)
        val exits = exitsOf(p)
        if (r is TradeResult.Rejected && r.code == "rule_violation" && exits.isNotEmpty()) {
            // A triggered TP/SL limit resting for the full size blocks the close. A full market close
            // drops the exits anyway, so cancel them and try once more; a partial close says why.
            r = if (fraction >= 1.0 && limitPrice == null) {
                exits.forEach { trade.cancelOrder(key, it.id) }
                trade.closePosition(key, p.id, body)
            } else r.copy(message = r.message + " · A triggered TP/SL may be holding the size: cancel it under Orders first")
        }
        return report(what, r)
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
        // MFP rejects target_position_id on market orders; the size alone flips the position.
        put("slippage_tolerance_bps", prefs.slippageBps)
        market.marks.value[p.feedKey]?.let { put("expected_price", it) }
    })

    fun exitsOf(p: Position): List<Order> =
        account.accounts.value[p.accountId]?.orders?.values.orEmpty()
            .filter { it.marketId == p.marketId }
            .mapNotNull { o ->
                when {
                    o.exitGroup != null -> o
                    // A resting reduce-only limit on the closing side for the full size is the TP; a partial
                    // one is a scale-out the user placed and stays an ordinary order.
                    o.isRestingExit && o.side == (if (p.isLong) "sell" else "buy") && o.size >= p.size * 0.999 -> o.copy(restingTp = true)
                    else -> null
                }
            }

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
        val all = exitsOf(p)
        // Resting limits aren't MFP exit orders: they are cancelled and placed one by one.
        val current = all.filter { !it.isRestingExit }
        val restingCancels = mutableListOf<Order>()
        var restingPlace: Double? = null
        var restingModify: Pair<Order, Double>? = null
        val ops = mutableListOf<JsonObject>()
        val placed = mutableMapOf<String, Int>()
        // Every exit price lands on the market's tick (breakeven at an average entry, guard stops, drags).
        val m = market(p.marketId)
        fun snap(v: Double?) = v?.let { x -> m?.let { com.alex.mfppro.ui.snapTick(x, it) } ?: x }
        for ((group, price) in listOf("tp" to snap(tp), "sl" to snap(sl))) {
            val asked = if (group == "tp") tpExec else slExec
            val resting = all.filter { it.exitGroup == group && it.isRestingExit }
            var exec = asked ?: all.firstOrNull { it.exitGroup == group }?.exitExecution ?: if (group == "tp") prefs.tpExecution else prefs.slExecution
            if (group == "sl" && exec == "rest") exec = "limit" // a stop can not rest on the book
            val existing = current.filter { it.exitGroup == group }
            if (exec == "rest") {
                // Cancel any trigger TP; keep a resting one already at this price and size.
                existing.forEach { ops += buildJsonObject { put("kind", "cancel"); put("order_id", it.id) } }
                val keepRest = if (price == null) null else resting.firstOrNull()
                restingCancels += resting.filter { it !== keepRest }
                if (keepRest != null && price != null) {
                    if (keepRest.limitPrice != price || keepRest.size != p.size) restingModify = keepRest to price
                } else if (price != null) restingPlace = price
                continue
            }
            restingCancels += resting
            // Modify can't change how an exit executes; switching means cancel and place again.
            val keep = existing.firstOrNull()?.takeIf { it.exitExecution == exec }
            if (price == null) {
                existing.forEach { ops += buildJsonObject { put("kind", "cancel"); put("order_id", it.id) } }
            } else if (keep != null) {
                // Unchanged (e.g. the other leg is being removed): leave it out of the batch.
                if (keep.exitPrice != price || keep.size != p.size)
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
        if (ops.isEmpty() && restingCancels.isEmpty() && restingPlace == null && restingModify == null) return TradeResult.Ok(null)
        var r: TradeResult = TradeResult.Ok(null)
        if (ops.isNotEmpty()) {
            r = trade.replaceExits(key, p, current, ops)
            // Older limit exits may be known to MFP by their trigger rather than their limit: try that once.
            if (r is TradeResult.Rejected && r.code == "stale_snapshot" && current.any { it.triggerPrice != null && it.limitPrice != null })
                r = trade.replaceExits(key, p, current, ops, armPrice = true)
            if (r is TradeResult.Rejected) return report(label, r)
        }
        // Old resting TPs go before the new one is placed, so reduce-only size never overlaps.
        for (o in restingCancels) {
            val c = trade.cancelOrder(key, o.id)
            if (c is TradeResult.Rejected) return report(label, c)
        }
        restingModify?.let { (o, px) ->
            r = trade.modifyOrder(key, o.id, px, p.size.takeIf { it != o.size })
            // Not modifiable (or gone): fall back to a fresh resting limit.
            if (r is TradeResult.Rejected && (r as TradeResult.Rejected).code != "network") {
                val c = trade.cancelOrder(key, o.id)
                if (c !is TradeResult.Rejected) restingPlace = px
            }
        }
        restingPlace?.let { px ->
            // No marketable acknowledgement: MFP refuses it rather than filling at market if it would cross.
            r = trade.placeOrder(key, buildJsonObject {
                put("type", "limit"); put("account_id", p.accountId); put("market_id", p.marketId)
                put("side", if (p.isLong) "sell" else "buy")
                put("size", p.size); put("limit_price", px)
                put("leverage", p.leverage); put("margin_mode", p.marginMode)
                put("reduce_only", true)
            })
        }
        return report(label, r)
    }

    /** Move the stop loss to the entry price, keeping any take profit. */
    suspend fun breakeven(p: Position): TradeResult {
        val tp = exitsOf(p).firstOrNull { it.exitGroup == "tp" }?.exitPrice
        return setTpSl(p, tp, p.entryPrice, "Breakeven ${p.symbol}")
    }

    /**
     * Breakeven for a losing position: a reduce-only limit at the entry price for the full size, a sell
     * closing a long or a buy closing a short. It rests on the book and closes the position if price
     * comes back to entry. Sent post-only (no marketable acknowledgement), so if price has already
     * crossed entry MFP turns it down instead of filling it at market.
     */
    suspend fun breakevenLimit(p: Position): TradeResult {
        val m = market(p.marketId)
        return placeOrder("Breakeven limit ${p.symbol}", buildJsonObject {
            put("type", "limit"); put("account_id", p.accountId); put("market_id", p.marketId)
            put("side", if (p.isLong) "sell" else "buy")
            put("size", roundSize(p.size, m))
            put("leverage", p.leverage); put("margin_mode", p.marginMode)
            put("reduce_only", true)
            put("limit_price", m?.let { com.alex.mfppro.ui.snapTick(p.entryPrice, it) } ?: p.entryPrice)
        })
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
        // Only accounts in play (holding something, or the one being traded): with dozens of accounts a
        // refresh of every one after each order would be a burst of 100+ requests.
        for (st in account.accounts.value.values) {
            val busy = st.positions.isNotEmpty() || st.orders.isNotEmpty()
            if (st.account.status == "active" && (busy || st.account.id == _tradeAccount.value))
                refreshAccount(key, st.account.id, crossCheck = true)
        }
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

    /** Bumped on every refresh; screens that load REST data once key their loads on it. */
    private val _refreshTick = MutableStateFlow(0)
    val refreshTick = _refreshTick.asStateFlow()
    @Volatile private var backgroundAt = 0L

    /**
     * Pull everything fresh without restarting the app: a new account snapshot (no resume),
     * a new market socket with chart history, markets, risk floors, policies and the
     * overview stats, and a tick for screens that loaded REST data.
     */
    @Volatile private var lastRefreshAt = 0L

    fun refreshAll() {
        // Repeated pulls or badge taps would reopen the account socket each time (MFP caps sockets per key).
        val now = System.currentTimeMillis()
        if (now - lastRefreshAt < 3_000) return
        lastRefreshAt = now
        pauseJob?.cancel()
        account.resync()
        market.refresh()
        loadMarkets(force = true)
        extrasLoadedAt = 0; chg1hAtById.clear(); chg4hAt = 0
        policyAt.clear()
        startRiskRefresh()
        _refreshTick.update { it + 1 }
    }

    /** [refreshAll], then wait (up to 8 s) until the account stream is live again. */
    suspend fun refreshAndWait() {
        refreshAll()
        // conn still reads Live until the serial reconnect runs: first see it leave Live, then come back.
        kotlinx.coroutines.withTimeoutOrNull(1_500) { account.conn.first { it != Conn.Live } }
        kotlinx.coroutines.withTimeoutOrNull(8_000) {
            account.conn.first { it == Conn.Live || it == Conn.AuthFailed || it == Conn.Off }
        }
    }

    private fun onForeground() {
        pauseJob?.cancel()
        val away = if (backgroundAt > 0) System.currentTimeMillis() - backgroundAt else 0
        backgroundAt = 0
        // After a minute or more away the resume cursor and REST data are likely stale: start clean.
        if (away > STALE_AFTER_MS) { refreshAll(); return }
        account.resume()
        market.resume()
        startRiskRefresh()
        if (_markets.value.isEmpty()) loadMarkets()
    }

    /** While the monitor service runs, the sockets stay open in the background. */
    @Volatile private var monitoring = false
    fun setBackgroundMonitoring(on: Boolean) { monitoring = on; if (on) onForeground() }

    private fun onBackground() {
        backgroundAt = System.currentTimeMillis()
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
    // Per market, so the scanner's short list doesn't hold back the full market list.
    private val chg1hAtById = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun load1h(only: List<Market> = _markets.value) {
        val now = System.currentTimeMillis()
        val due = only.filter { now - (chg1hAtById[it.marketId] ?: 0L) >= 2 * 60_000 }
        if (due.isEmpty()) return
        due.forEach { chg1hAtById[it.marketId] = now }
        scope.launch {
            for (m in due) launch {
                val c = runCatching { market.candleHistory(m.provider, m.coin, "5m", 13) }.getOrNull().orEmpty()
                if (c.size >= 12) {
                    val open = c[c.size - 12].o
                    if (open > 0) _chg1h.update { it + (m.marketId to (c.last().c / open - 1) * 100) }
                }
            }
        }
    }

    private fun loadMarkets(force: Boolean = false) = scope.launch {
        loadVenues()
        if (force) runCatching { rest.markets() }.onSuccess { list -> _markets.value = list.filter { it.tradingEnabled }.sortedBy { it.symbol } }
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
            var pass = 0
            while (isActive) {
                // With many accounts (one key can reach dozens), only the ones that can move right now are polled
                // fast: those holding positions or orders, and the account being traded. Their drawdown floor can
                // trail equity. Other active accounts only change through fills, which the stream reports and
                // which trigger a refresh anyway, so every 2 min is enough. Breached or finished accounts: never,
                // they can't trade and the app doesn't show them.
                val known = account.accounts.value.values
                // Give the stream a moment to list the accounts.
                if (known.isEmpty() && tries++ < 10) { delay(1_000); continue }
                val vis = _visible.value
                val ids = if (known.isEmpty()) runCatching { rest.accounts(key).filter { it.status == "active" }.map { it.id } }.getOrDefault(emptyList())
                    else known.filter { s ->
                        val id = s.account.id
                        when {
                            s.account.status != "active" -> false
                            s.positions.isNotEmpty() || s.orders.isNotEmpty() || id == _tradeAccount.value -> true
                            else -> pass % 8 == 0
                        }
                    }.map { it.account.id }
                // Position/order cross-check: once a minute, only where there is something to check, never in the background.
                for (id in ids) {
                    val st = account.accounts.value[id]
                    val busy = st == null || st.positions.isNotEmpty() || st.orders.isNotEmpty()
                    refreshAccount(key, id, crossCheck = vis && pass % 4 == 0 && busy)
                }
                pass++
                delay(if (vis) RISK_REFRESH_MS else 60_000L)
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

    private suspend fun refreshAccount(key: String, id: String, crossCheck: Boolean = true) {
        val acc = runCatching { rest.account(key, id) }.onFailure { Log.w(TAG, "account $id: ${it.message}") }.getOrNull() ?: return
        account.mergeRisk(acc)
        if (acc.status == "active" && System.currentTimeMillis() - (policyAt[id] ?: 0) > 600_000) {
            runCatching { rest.tradingPolicy(key, id) }.onSuccess { pol ->
                policyAt[id] = System.currentTimeMillis(); _policies.update { it + (id to pol) }
                Competitions.ids = if (pol.competition != null) Competitions.ids + id else Competitions.ids - id
            }
        }
        if (acc.status == "active" && crossCheck) {
            val asked = System.currentTimeMillis()
            runCatching { rest.positions(key, id) }.onSuccess { account.reconcilePositions(id, it, asked) }
            // A full page may be cut off (MFP pages at 100): never drop orders on the strength of it.
            runCatching { rest.workingOrders(key, id) }.onSuccess { if (it.size < 100) account.reconcileOrders(id, it, asked) }
                .onFailure { Log.w(TAG, "orders $id: ${it.message}") }
        }
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
        if (extrasJob?.isActive == true || ServerClock.now() - extrasLoadedAt < 15 * 60_000 &&
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
            extrasLoadedAt = ServerClock.now()
        }
    }
}
