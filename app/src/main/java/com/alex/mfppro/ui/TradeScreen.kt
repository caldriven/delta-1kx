package com.alex.mfppro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import com.alex.mfppro.data.AssetClasses
import com.alex.mfppro.data.Candle
import com.alex.mfppro.data.Opens
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CandlestickChart
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.StackedLineChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alex.mfppro.data.Book
import com.alex.mfppro.data.Market
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.Sub
import com.alex.mfppro.data.Trade
import com.alex.mfppro.data.candleKey
import com.alex.mfppro.data.breachPrice
import com.alex.mfppro.data.riskOf
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.TextButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val INTERVALS = listOf("1m", "5m", "15m", "1h", "4h", "1d", "1w", "1M")

/** One colour per unit, so minutes, hours, days and weeks (and 1m against 1M) tell apart at a glance. */
private fun intervalColor(iv: String) = when (iv.last()) {
    'm' -> Color(0xFF38BDF8)
    'h' -> Color(0xFFF59E0B)
    'd' -> Color(0xFFA78BFA)
    else -> Color(0xFFF472B6)
}

/**
 * Chart intervals as roomy chips, the picked one filled in its unit's colour. When they don't all
 * fit, the row scrolls and a fading arrow on the hidden side says so (tap it to jump there).
 */
@Composable
private fun IntervalRow(interval: String, fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier, onPick: (String) -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    Box(modifier, contentAlignment = Alignment.CenterEnd) {
        Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            INTERVALS.forEach { iv ->
                val on = iv == interval
                val col = intervalColor(iv)
                Text(
                    iv, style = num.copy(fontSize = fontSize, color = if (on) col else col.copy(alpha = 0.7f), fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) col.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { onPick(iv) }.padding(horizontal = 7.dp, vertical = 5.dp),
                )
            }
        }
        @Composable
        fun Hint(left: Boolean) = Box(
            Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd).width(26.dp).height(30.dp)
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(
                    if (left) listOf(C.bg, C.bg.copy(alpha = 0f)) else listOf(C.bg.copy(alpha = 0f), C.bg)))
                .clickable { scope.launch { scroll.animateScrollTo(if (left) 0 else scroll.maxValue) } },
            contentAlignment = if (left) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            Icon(if (left) Icons.Filled.ChevronLeft else Icons.Filled.ChevronRight, if (left) "More intervals left" else "More intervals right",
                tint = C.text, modifier = Modifier.size(18.dp))
        }
        if (scroll.canScrollBackward) Hint(true)
        if (scroll.canScrollForward) Hint(false)
    }
}

@Composable
/**
 * [compact] = position-management layout: chart at about a third of the screen
 * with quick trade and positions under it. Otherwise the chart gets most of it.
 */
fun TradeScreen(repo: Repo, marketId: String?, compact: Boolean, onPickMarket: (String) -> Unit, focus: Boolean = false, onOpenMarkets: (() -> Unit)? = null) {
    val markets by repo.markets.collectAsStateWithLifecycle()
    val venues by repo.venues.collectAsStateWithLifecycle()
    // Alternate venues aren't in the main list; look there too.
    val market = (markets.firstOrNull { it.marketId == marketId } ?: venues.firstOrNull { it.marketId == marketId })
        ?: markets.firstOrNull { it.symbol == "BTC" } ?: markets.firstOrNull()
    if (market == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading markets…", color = C.muted) }
        return
    }
    var interval by rememberSaveable { mutableStateOf(repo.prefs.lastInterval) }
    var picker by remember { mutableStateOf(false) }

    // Subscriptions live exactly as long as this market/interval is on screen.
    DisposableEffect(market.marketId, interval) {
        val subs = listOf("ticks", "marketStats", "books", "trades").map { Sub(it, market.provider, market.coin) } +
            Sub("candles", market.provider, market.coin, interval)
        subs.forEach { repo.market.acquire(it) }
        onDispose { subs.forEach { repo.market.release(it) } }
    }
    // Daily candles for the UTC daily/weekly/monthly opens.
    var days by remember(market.marketId) { mutableStateOf<List<Candle>>(emptyList()) }
    LaunchedEffect(market.marketId) {
        while (true) {
            val d = repo.market.candleHistory(market.provider, market.coin, "1d", 40)
            if (d.isNotEmpty()) days = d
            delay(if (d.isEmpty()) 5_000 else 600_000)
        }
    }

    val key = market.feedKey
    // Only this market's stats, book, candles and OI recompose the screen. Marks stay live (every
    // flush) because positions and risk across accounts need them. Collecting the whole maps rebuilt
    // the entire screen on every book, candle and stats update from any market.
    val s by repo.market.stats.collectKey(key, 1000)
    val marks by repo.market.marks.collectAsStateWithLifecycle()
    val venue by repo.market.venueMarks.collectThrottled(500)
    val chartCandles by repo.market.candles.collectKey(candleKey(market.provider, market.coin, interval), 250)
    val oiHere by repo.market.oi.collectKey(key, 1000)
    val accounts by repo.account.accounts.collectAsStateWithLifecycle()
    val connected by repo.market.live.collectAsStateWithLifecycle()
    val classes by repo.classes.collectAsStateWithLifecycle()
    val mark = marks[key] ?: s?.markPx
    // The book updates every 150 ms and leads the candle feed; use its mid as the live price.
    val book by repo.market.books.collectKey(key, 200)
    val bestBid = book?.bids?.firstOrNull()?.px
    val bestAsk = book?.asks?.firstOrNull()?.px
    val live = if (bestBid != null && bestAsk != null) (bestBid + bestAsk) / 2 else mark

    val extras by repo.extras.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { repo.loadOverviewExtras() }
    val oiChg = extras[market.marketId]?.oi24h
    val dOpen = Opens.dayOpen(days)
    val wOpen = Opens.openAt(days, Opens.weekStart())
    val mOpen = Opens.openAt(days, Opens.monthStart())
    // "Today" is measured from the UTC daily open, like most venues' daily candles.
    val today = if (live != null && dOpen != null && dOpen > 0) (live / dOpen - 1) * 100 else s?.change24hPct

    // Only the trading account unless "All accounts" is on.
    var showAll by remember { mutableStateOf(repo.prefs.tradeShowAll) }
    val tradeAcc = rememberTradeAccount(repo)
    val scoped = if (showAll) activeAccounts(accounts) else listOfNotNull(tradeAcc)
    val multi = showAll && scoped.size > 1
    val myPositions = scoped.flatMap { a -> a.positions.values.filter { it.inMarket(market) } }

    // Section under the chart; -1 = none open, so the chart fills the screen and nothing scrolls.
    var tab by rememberSaveable { mutableStateOf(-1) }
    var view by rememberSaveable { mutableStateOf(0) }
    var exitAsk by remember { mutableStateOf<ExitAsk?>(null) }
    var confirmAsk by remember { mutableStateOf<ChartAsk?>(null) }
    val tradeScope = rememberCoroutineScope()
    // Chart-box actions confirm when the settings ask for it, otherwise go straight away.
    // One at a time: a double tap on the box must not flip or close twice.
    var chartBusy by remember { mutableStateOf(false) }
    fun runChart(a: ChartAsk) {
        if (chartBusy) return
        chartBusy = true
        tradeScope.launch { try { a.run() } finally { chartBusy = false } }
    }
    fun chartAct(a: ChartAsk) {
        if (repo.prefs.confirmOrders || repo.prefs.confirmCloseAll) confirmAsk = a else runChart(a)
    }
    // A TP or SL dropped from the chart: snapped to the tick, and only on its own side of the price,
    // since a stop through the price would close the position at once.
    fun askExit(p: com.alex.mfppro.data.Position, tp: Boolean, raw: Double, tp0: Double?, sl0: Double?) {
        val px = repo.market(p.marketId)?.let { snapTick(raw, it) } ?: raw
        val now = mark ?: p.entryPrice
        val wrong = if (tp == p.isLong) px <= now else px >= now
        if (wrong) repo.postMessage(if (tp) "TP must be ${if (p.isLong) "above" else "below"} the price" else "SL must be ${if (p.isLong) "below" else "above"} the price")
        else exitAsk = ExitAsk(p, tp, px, tp0, sl0)
    }
    var showOpens by remember { mutableStateOf(repo.prefs.showOpens) }
    var showPivots by remember { mutableStateOf(repo.prefs.showPivots) }
    var showVp by remember { mutableStateOf(repo.prefs.showVp) }
    var showPoc by remember { mutableStateOf(repo.prefs.showPoc) }
    var showSess by remember { mutableStateOf(repo.prefs.showSessions) }
    var landscape by rememberSaveable { mutableStateOf(false) }
    val sessOpts = if (showSess) SessionOpts(repo.prefs.sessionEu, repo.prefs.killzones) else null

    // Dragging the position line sets TP (profit side) or SL (loss side); TP/SL lines drag to move.
    fun exitPreview(p: com.alex.mfppro.data.Position, px: Double, kind: String?): Pair<String, Color> {
        val pnl = p.pnlAt(px)
        val k = kind ?: if (pnl >= 0) "TP" else "SL"
        val pct = (px / p.entryPrice - 1) * 100 * (if (p.isLong) 1 else -1)
        return String.format(Locale.US, "%s %+.2f%% / %s", k, pct, fmtUsd(pnl, true)) to (if (k == "TP") C.up else C.down)
    }
    // Hidden only while its chip is offered; turning the chip off in Settings shows the lines again.
    var showPosPick by remember { mutableStateOf(repo.prefs.showPosLines) }
    val showPos = showPosPick || !repo.prefs.chipPos
    val draft = remember(market.marketId) { LimitDraft().apply { decimals = repo.prefs.priceDecimals(market.marketId) } }
    // Chart layers switched off in the Layers menu.
    var hidden by remember { mutableStateOf(repo.prefs.chartHidden) }
    fun shows(layer: String) = layer !in hidden
    // Your fills in this market, as candle markers; reloaded when a new fill arrives.
    var fillMarks by remember(market.marketId, tradeAcc?.account?.id) { mutableStateOf<List<FillMark>>(emptyList()) }
    LaunchedEffect(market.marketId, tradeAcc?.account?.id, shows("fills")) {
        val id = tradeAcc?.account?.id ?: return@LaunchedEffect
        if (!shows("fills")) { fillMarks = emptyList(); return@LaunchedEffect }
        suspend fun load() = runCatching { repo.fills(id, maxPages = 3) }.getOrNull()?.let { list ->
            fillMarks = list.filter { it.marketId == market.marketId || it.symbol.equals(market.symbol, true) }
                .map { FillMark(it.settledAt ?: it.requestedAt, it.side == "buy", it.fillPrice) }
        }
        load()
        repo.account.fills.collect { delay(1500); load() }
    }
    fun pctAway(px: Double) = mark?.let { fmtPct((px / it - 1) * 100) } ?: ""
    // Resting entry orders in this market (not TP/SL, which ride on the position), plus the draft limit.
    val orderLines = scoped.flatMap { a -> a.orders.values }.filter { o -> shows("orders") && o.exitGroup == null && (o.marketId == market.marketId || o.symbol.equals(market.symbol, true)) }
        .mapNotNull { o ->
            val px = o.limitPrice ?: o.triggerPrice ?: return@mapNotNull null
            val buy = o.side == "buy"
            val kind = (if (o.type.startsWith("stop")) "Stop " else "Limit ") + (if (buy) "buy" else "sell") + (if (o.reduceOnly) " · reduce" else "")
            PriceLine(px, if (buy) C.up else C.down, kind, parts = listOf(kind, fmtUsd(o.size * px), pctAway(px)))
        } + listOfNotNull(draft.price?.takeIf { compact && draft.on }?.let { px ->
            val col = if (draft.buy) C.up else C.down
            PriceLine(px, col, "Limit", parts = listOf(if (draft.buy) "Limit buy · draft" else "Limit sell · draft", pctAway(px)),
                drag = LineDrag({ p -> "Limit ${fmtPrice(draft.snap(p, market))} · ${pctAway(p)}" to col }) { p -> draft.price = draft.snap(p, market) })
        })
    // Daily pivots on intraday candles; weekly once a candle is 4h or longer, where daily levels crowd together.
    val weeklyPivots = interval in listOf("4h", "1d", "1w", "1M")
    val pivotLines = if (showPivots) Opens.pivots(days, weeklyPivots)?.let { pv ->
        val tag = if (weeklyPivots) "W" else "D"
        listOf(
            PriceLine(pv.r3, PIVOT_R, " R3", ref = true), PriceLine(pv.r2, PIVOT_R, " R2", ref = true), PriceLine(pv.r1, PIVOT_R, " R1", ref = true),
            PriceLine(pv.p, PIVOT_P, " P", ref = true),
            PriceLine(pv.s1, PIVOT_S, " S1", ref = true), PriceLine(pv.s2, PIVOT_S, " S2", ref = true), PriceLine(pv.s3, PIVOT_S, " S3", ref = true),
        )
    }.orEmpty() else emptyList()
    val chartLines = orderLines + (if (showPos) myPositions else emptyList()).flatMap { p ->
        val acc = accounts[p.accountId] ?: return@flatMap emptyList()
        val r = riskOf(acc, marks)
        val tag = if (multi) accountName(acc).take(14) else ""
        val pnl = net(mark?.let(p::pnlAt))
        val exits = repo.exitsOf(p)
        val tp = exits.firstOrNull { it.exitGroup == "tp" }?.exitPrice
        val sl = exits.firstOrNull { it.exitGroup == "sl" }?.exitPrice
        fun away(px: Double) = mark?.let { fmtPct((px / it - 1) * 100) } ?: ""
        listOfNotNull(
            // With the PnL box off, the entry stays as a plain line.
            if (!shows("pnl")) PriceLine(p.entryPrice, if ((pnl ?: 0.0) >= 0) C.up else C.down, "Entry", solid = true, parts = listOf("Entry"))
            else PriceLine(p.entryPrice, if ((pnl ?: 0.0) >= 0) C.up else C.down, "", solid = true,
                box = PosBox(
                    fmtUsd(pnl, true),
                    tp = LineDrag({ exitPreview(p, it, "TP") }) { px -> askExit(p, true, px, tp, sl) },
                    sl = LineDrag({ exitPreview(p, it, "SL") }) { px -> askExit(p, false, px, tp, sl) },
                    onReverse = {
                        if (repo.prefs.locked()) repo.postMessage("Night lock: no reversing until ${repo.prefs.lockEnd}:00")
                        else chartAct(ChartAsk("Reverse", "Flip to ${if (p.isLong) "short" else "long"}", C.accent,
                            listOf(ConfirmRow("Market", ticker(p.symbol, p.provider)), ConfirmRow("Order value", fmtUsd(p.size * 2 * (mark ?: p.entryPrice)))), "Reverse") { repo.reverse(p) })
                    },
                    onClose = {
                        chartAct(ChartAsk("Market close", "Close " + ticker(p.symbol, p.provider), C.down,
                            listOf(ConfirmRow("Value", fmtUsd(p.size * (mark ?: p.entryPrice))), ConfirmRow("PnL", fmtUsd(mark?.let(p::pnlAt), true), pnlColor(mark?.let(p::pnlAt)))), "Close") { repo.closePosition(p) })
                    },
                )),
            tp?.takeIf { shows("exits") }?.let { PriceLine(it, C.up, "TP", parts = listOfNotNull("TP", away(it), fmtUsd(net(p.pnlAt(it)), true), tag.ifBlank { null }),
                drag = LineDrag({ px -> exitPreview(p, px, "TP") }) { px -> askExit(p, true, px, tp, sl) }) },
            sl?.takeIf { shows("exits") }?.let { PriceLine(it, C.down, "SL", parts = listOfNotNull("SL", away(it), fmtUsd(p.pnlAt(it), true), tag.ifBlank { null }),
                drag = LineDrag({ px -> exitPreview(p, px, "SL") }) { px -> askExit(p, false, px, tp, sl) }) },
            // Where this position takes the account onto each floor, with % away from the last price.
            mark?.takeIf { shows("floors") }?.let { m -> r.dailyRoom?.let { room -> breachPrice(p, m, room)?.let { PriceLine(it, C.accent, "DLL", parts = listOfNotNull("DLL", away(it), "-" + fmtUsd(room), tag.ifBlank { null })) } } },
            mark?.takeIf { shows("floors") }?.let { m -> r.ddRoom?.let { room -> breachPrice(p, m, room)?.let { PriceLine(it, C.down, "Max DD", parts = listOfNotNull("Max DD", away(it), "-" + fmtUsd(room), tag.ifBlank { null })) } } },
            // Where this position alone would bring equity to the pass target.
            mark?.takeIf { shows("target") }?.let { m -> r.toTarget?.takeIf { it > 0 && p.size > 0 }?.let { need ->
                val px = if (p.isLong) m + need / p.size else m - need / p.size
                px.takeIf { it > 0 }?.let { PriceLine(it, PASS, "Pass target", parts = listOfNotNull("Pass target", away(it), "+" + fmtUsd(need), tag.ifBlank { null })) }
            } },
        )
    } + (if (showOpens) listOfNotNull(
        mOpen?.let { PriceLine(it, OPEN_M, "M open", ref = true) },
        wOpen?.let { PriceLine(it, OPEN_W, "W open", ref = true) },
        dOpen?.let { PriceLine(it, OPEN_D, "D open", ref = true) },
    ) else emptyList()) + pivotLines

    Column(Modifier.fillMaxSize().background(C.bg)) {
    // Chart view scrolls only while a section is open (or on the data view, which is a long list).
    // Trade view is one long page: chart, quick panel and positions scroll together.
    val scrolls = compact || tab >= 0 || view == 1
    val pageScroll = rememberScrollState()
    // Switching Chart/Trade starts at the top, positions below the fold.
    LaunchedEffect(compact) { pageScroll.scrollTo(0) }
    // Where the section tabs sit in the page, so opening one can bring the list into view.
    var tabsY by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(tab) {
        if (tab >= 0 && !compact) {
            delay(60)
            // Keep a strip of chart above the tabs; the rest of the screen is the list.
            pageScroll.animateScrollTo((tabsY - with(density) { 160.dp.toPx() }.toInt()).coerceAtLeast(0))
        }
    }
    Column(Modifier.weight(1f).then(if (scrolls) Modifier.verticalScroll(pageScroll) else Modifier)) {
        // Header: market + live price on one line, stats strip below.
        Row(Modifier.fillMaxWidth().padding(10.dp, 4.dp, 12.dp, 0.dp), verticalAlignment = Alignment.CenterVertically) {
            // The symbol glides over to the market list (or opens the picker when there's no list to go to).
            Row(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { onOpenMarkets?.invoke() ?: run { picker = true } }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                MarketLogo(market, AssetClasses.classOf(market, classes), 22.dp)
                Spacer(Modifier.width(8.dp))
                Text(market.symbol, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.text)
                Icon(Icons.Filled.ArrowDropDown, null, tint = C.muted, modifier = Modifier.size(18.dp))
                val pols by repo.policies.collectAsStateWithLifecycle()
                Text("${com.alex.mfppro.data.maxLeverage(market, tradeAcc?.account, tradeAcc?.account?.id?.let { pols[it] })}x", color = C.muted, fontSize = 10.sp)
                if (!connected) Text("  ●", color = C.accent, fontSize = 10.sp)
            }
            VenueSwap(market, venues.ifEmpty { markets }, onPickMarket)
            Text(fmtPrice(live), style = num.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = pnlColor(today)))
            Text(" " + fmtPct(today), style = num.copy(fontSize = 11.sp, color = pnlColor(today)))
        }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
        if (!compact && !focus) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HeadStat("OI", "$" + fmtCompact(s?.openInterestUsd))
            HeadStat("OI 24h", fmtPct(oiChg), pnlColor(oiChg))
            HeadStat("Funding", (s?.fundingRate?.let { String.format(Locale.US, "%+.4f%%", it * 100) } ?: "—") +
                (s?.nextFundingTime?.let { " · " + fmtCountdown(it - now) } ?: ""), pnlColor(s?.fundingRate))
            HeadStat("Vol 24h", "$" + fmtCompact(s?.dayNtlVlm))
            HeadStat("D open", fmtPrice(dOpen))
        }

        // The account lives on the Trade view; Chart keeps just the asset and its stats on top.
        androidx.compose.animation.AnimatedVisibility(!focus && compact) { RiskPanel(repo, Modifier.padding(top = 2.dp), showExposure = true) }

        var barOpen by remember { mutableStateOf(repo.prefs.indBarOpen) }
        var crossKey by remember { mutableStateOf(0) }
        // Toolbar: view switch and intervals. The bottom bar's Trade button switches the layout.
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            // Tapping Chart again folds the indicator row up or down.
            ViewChip("Chart", Icons.Filled.CandlestickChart, view == 0, trailing = if (view == 0) (if (barOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown) else null) {
                if (view == 0) { barOpen = !barOpen; repo.prefs.indBarOpen = barOpen } else view = 0
            }
            ViewChip("Depth", Icons.Filled.StackedLineChart, view == 2) { view = 2 }
            ViewChip("Data", Icons.Filled.Insights, view == 1) { view = 1 }
            // Order-flow bubbles: still an alpha.
            ViewChip("Flow α", Icons.Filled.BubbleChart, view == 3) { view = 3 }
            // The intervals take all the width left after the view chips (a spacer used to claim half of it).
            if (view == 0) IntervalRow(interval, 12.sp, Modifier.weight(1f).padding(start = 4.dp)) { interval = it; repo.prefs.lastInterval = it }
            else Spacer(Modifier.weight(1f))
        }
        // Indicator toggles sit above the chart so the plot stays clean. The tools button folds the bar away;
        // which toggles appear is set in Settings.
        androidx.compose.animation.AnimatedVisibility(view == 0 && barOpen,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            run {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (repo.prefs.chipPos) OverlayChip("Position", showPos, Icons.Filled.Layers) { showPosPick = !showPos; repo.prefs.showPosLines = showPosPick }
                    if (repo.prefs.chipOpens) OverlayChip("D/W/M", showOpens, Icons.Filled.HorizontalRule) { showOpens = !showOpens; repo.prefs.showOpens = showOpens }
                    if (repo.prefs.chipPivots) OverlayChip("Pivots", showPivots, Icons.Filled.Reorder) { showPivots = !showPivots; repo.prefs.showPivots = showPivots }
                    if (repo.prefs.chipVp) OverlayChip("VP", showVp, Icons.AutoMirrored.Filled.Notes) { showVp = !showVp; repo.prefs.showVp = showVp }
                    if (repo.prefs.chipPoc) OverlayChip("POC", showPoc, Icons.Filled.Adjust) { showPoc = !showPoc; repo.prefs.showPoc = showPoc }
                    if (repo.prefs.chipSess) OverlayChip("Sess", showSess, Icons.Filled.Schedule) { showSess = !showSess; repo.prefs.showSessions = showSess }
                }
            }
            Spacer(Modifier.weight(1f))
            // Layers: what the chart draws for your trades.
            Box {
                var layersOpen by remember { mutableStateOf(false) }
                Icon(Icons.Filled.Layers, "Layers", tint = if (hidden.isEmpty()) C.text else C.accent,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { layersOpen = true }.padding(3.dp).size(20.dp))
                androidx.compose.material3.DropdownMenu(layersOpen, onDismissRequest = { layersOpen = false }, containerColor = C.panel) {
                    Text("On the chart", color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                    for ((key, label) in listOf("pnl" to "Net PnL box", "exits" to "TP / SL lines", "floors" to "DLL / Max DD floors",
                        "target" to "Pass target", "orders" to "Open orders", "fills" to "Fill markers ▲▼")) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { AppCheck(label, key !in hidden) { on -> hidden = if (on) hidden - key else hidden + key; repo.prefs.chartHidden = hidden } },
                            onClick = { hidden = if (key in hidden) hidden - key else hidden + key; repo.prefs.chartHidden = hidden },
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.CenterFocusWeak, "Crosshair", tint = C.text, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { crossKey++ }.padding(3.dp).size(20.dp))
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.Fullscreen, "Landscape", tint = C.text, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { landscape = true }.padding(3.dp).size(20.dp))
        }
        }
        val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
        val chartH = (screenH * if (compact) 0.47f else 0.54f).dp
        // Nothing open below: the chart stretches down to the section tabs.
        val sized = if (!compact && !scrolls) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(chartH)
        run {
            when (view) {
                1 -> TradingData(repo, market, s?.openInterestUsd, mark, oiHere.orEmpty())
                3 -> FlowChart(repo, key, sized.padding(start = 2.dp, top = 2.dp))
                2 -> DepthChart(book, sized.padding(horizontal = 8.dp, vertical = 4.dp))
                else -> androidx.compose.runtime.key(market.marketId, interval) { CandleChart(
                    chartCandles.orEmpty().withLive(live), chartLines,
                    sized.padding(start = 2.dp, top = 2.dp),
                    showVp = showVp,
                    showPoc = showPoc,
                    vpSince = if (interval in listOf("1m", "5m", "15m", "1h")) Opens.dayStart() else null,
                    sessions = sessOpts,
                    crossKey = crossKey,
                    fills = fillMarks,
                ) }
            }
            if ((view == 2 || view == 3) && !compact) TopOfBook(book)
        }
        // Position management: one-tap open, reduce and close, then every open
        // position on the trading account whatever market the chart shows.
        if (compact) {
            QuickTrade(repo, market, draft)
            val open = tradeAcc?.positions?.values?.byFocus(market.marketId, marks).orEmpty()
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                if (open.isEmpty()) Text("Flat", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp, 10.dp))
                for (p in open) androidx.compose.runtime.key(p.id) {
                    val pm = marks[p.feedKey]
                    val br = pm?.let { mk -> tradeAcc?.let { riskOf(it, marks).room }?.let { breachPrice(p, mk, it) } }
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(C.panel)) {
                        PositionRow(p, pm, "", showAccount = false, breach = br, venueMark = venue[p.feedKey], tp = repo.exitPrice(p, "tp"), sl = repo.exitPrice(p, "sl"), market = repo.market(p.marketId)) { onPickMarket(p.marketId) }
                        PositionActions(repo, p, pm)
                    }
                }
            }
        }

        val allPositions = scoped.flatMap { it.positions.values }.byFocus(market.marketId, marks)
        val allOrders = scoped.flatMap { a -> a.orders.values.map { a to it } }
        var ordersAll by remember { mutableStateOf(repo.prefs.ordersAllMarkets) }
        val shownOrders = allOrders.filter { (_, o) -> ordersAll || o.marketId == market.marketId || o.symbol.equals(market.symbol, true) }
            .sortedWith(compareByDescending<Pair<com.alex.mfppro.data.AccountState, com.alex.mfppro.data.Order>> { it.second.marketId == market.marketId }.thenBy { it.second.symbol })
        if (!compact) Box(Modifier.onGloballyPositioned { tabsY = it.positionInParent().y.toInt() }) {
            PillTabs(listOf("Positions" to allPositions.size, "Orders" to shownOrders.size, "Fills" to 0, "Book" to 0, "Trades" to 0), tab) { tab = if (tab == it) -1 else it }
        }
        if (!compact && tab >= 0) when (tab) {
            0 -> Column {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (showAll) "All active accounts" else tradeAcc?.let(::accountName) ?: "", color = C.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("All accounts", color = C.muted, fontSize = 11.sp)
                    Spacer(Modifier.width(8.dp))
                    AppSwitch(showAll, { showAll = it; repo.prefs.tradeShowAll = it })
                }
                if (allPositions.isEmpty()) Text("Flat", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp, 10.dp))
                allPositions.forEach { p -> androidx.compose.runtime.key(p.id) {
                    val pm = marks[p.feedKey]
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(C.panel)) {
                        val br = accounts[p.accountId]?.let { a -> pm?.let { mk -> riskOf(a, marks).room?.let { breachPrice(p, mk, it) } } }
                        PositionRow(p, pm, accounts[p.accountId]?.let(::accountName) ?: "", showAccount = multi, breach = br, venueMark = venue[p.feedKey], tp = repo.exitPrice(p, "tp"), sl = repo.exitPrice(p, "sl"), market = repo.market(p.marketId)) { repo.setTradeAccount(p.accountId); onPickMarket(p.marketId) }
                        PositionActions(repo, p, pm)
                    }
                } }
                Spacer(Modifier.height(8.dp))
            }
            1 -> Column {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (showAll) "All active accounts" else tradeAcc?.let(::accountName) ?: "", color = C.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    // This market or every market.
                    MiniSwitch(market.symbol, "All", !ordersAll) { ordersAll = !it; repo.prefs.ordersAllMarkets = ordersAll }
                }
                if (shownOrders.isEmpty()) Text(if (ordersAll) "No open orders" else "No open ${market.symbol} orders", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp, 10.dp))
                shownOrders.forEach { (a, o) -> androidx.compose.runtime.key(o.id) { OrderRow(repo, o, if (multi) accountName(a) else "") } }
                Spacer(Modifier.height(8.dp))
            }
            2 -> FillsTab(repo, tradeAcc, market, onPickMarket)
            3 -> OrderBook(repo, key) { px, bid -> OrderPanel.open = market.marketId to TicketRequest(buy = bid, limitPrice = px) }
            else -> TradesTape(repo, market)
        }
    }
        // No sticky position bar: the positions list is right below the chart.
    }


    // Landscape chart is drawn by MainActivity over the whole app (bottom bar included).
    if (landscape) {
        val lc = chartCandles.orEmpty().withLive(live)
        val lines = chartLines
        val since = if (interval in listOf("1m", "5m", "15m", "1h")) Opens.dayStart() else null
        androidx.compose.runtime.SideEffect {
            Overlay.content = {
                LandscapeChart(market.symbol, interval, { interval = it; repo.prefs.lastInterval = it }, lc, lines, showVp, showPoc, since, sessOpts) { landscape = false }
            }
        }
        DisposableEffect(Unit) { onDispose { Overlay.content = null } }
    }

    confirmAsk?.let { a ->
        ConfirmDialog(a.title, a.headline, a.color, a.rows, confirm = a.confirm, onDismiss = { confirmAsk = null }) {
            confirmAsk = null; runChart(a)
        }
    }

    exitAsk?.let { e ->
        val p = e.p
        val em = repo.market(p.marketId)
        // How this exit executes: what it already uses, else the last choice.
        var exec by remember(e) { mutableStateOf(repo.exitsOf(p).firstOrNull { it.exitGroup == (if (e.tp) "tp" else "sl") }?.exitExecution
            ?: if (e.tp) repo.prefs.tpExecution else repo.prefs.slExecution) }
        val pnl = p.pnlAt(e.price)
        val pct = (e.price / p.entryPrice - 1) * 100 * (if (p.isLong) 1 else -1)
        ConfirmDialog(
            if (e.tp) "Set take profit" else "Set stop loss", (if (e.tp) "TP " else "SL ") + fmtPrice(e.price, em), if (e.tp) C.up else C.down,
            listOf(
                ConfirmRow("Position", "${if (p.isLong) "Long" else "Short"} ${fmtUsd(p.size * p.entryPrice)} ${p.symbol}"),
                ConfirmRow("Entry", fmtPrice(p.entryPrice, em)),
                ConfirmRow("Est. PnL", String.format(Locale.US, "%+.2f%%  %s", pct, fmtUsd(pnl, true)), pnlColor(pnl)),
            ),
            confirm = if (e.tp) "Set TP" else "Set SL", onDismiss = { exitAsk = null },
            extra = { ExitExecPicker(e.tp, exec, p.provider) { exec = it } },
        ) {
            exitAsk = null
            if (e.tp) repo.prefs.tpExecution = exec else repo.prefs.slExecution = exec
            tradeScope.launch { repo.setTpSl(p, if (e.tp) e.price else e.tp0, if (e.tp) e.sl0 else e.price, tpExec = exec.takeIf { e.tp }, slExec = exec.takeIf { !e.tp }) }
        }
    }

    if (picker) MarketPicker(repo, markets, classes, onDismiss = { picker = false }) { picker = false; onPickMarket(it.marketId) }
}

/** A chart-box action waiting for confirmation. */
private class ChartAsk(val title: String, val headline: String, val color: Color, val rows: List<ConfirmRow>, val confirm: String, val run: suspend () -> Unit)

private class ExitAsk(val p: com.alex.mfppro.data.Position, val tp: Boolean, val price: Double, val tp0: Double?, val sl0: Double?)

private val PASS = Color(0xFF22D3EE)
private val OPEN_D get() = C.openD
private val OPEN_W = Color(0xFF38BDF8)
private val OPEN_M = Color(0xFFF472B6)
// Pivots: resistance red-ish, support green-ish, P amber; muted so they sit behind your own lines.
private val PIVOT_R = Color(0xFFF87171)
private val PIVOT_S = Color(0xFF4ADE80)
private val PIVOT_P = Color(0xFFFBBF24)

@Composable
private fun HeadStat(label: String, value: String, color: Color = C.text) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.muted, fontSize = 10.sp)
        Spacer(Modifier.width(5.dp))
        Text(value, style = num.copy(fontSize = 11.sp, color = color), maxLines = 1)
    }
}

@Composable
private fun ViewChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, trailing: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.padding(end = 2.dp).clip(RoundedCornerShape(7.dp)).background(if (on) C.panel2 else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon only until selected, so four views and the intervals fit on one line.
        Icon(icon, label, tint = if (on) C.accent else C.muted, modifier = Modifier.size(16.dp))
        if (on) {
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 12.sp, color = C.text, fontWeight = FontWeight.SemiBold)
            trailing?.let {
                Spacer(Modifier.width(2.dp))
                Icon(Icons.Filled.Build, null, tint = C.muted, modifier = Modifier.size(11.dp))
                Icon(it, null, tint = C.muted, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun OverlayChip(label: String, on: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) C.panel2.copy(alpha = 0.95f) else C.bg.copy(alpha = 0.7f))
            .border(1.dp, if (on) C.accent.copy(alpha = 0.6f) else C.line, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (on) C.accent else C.muted, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(3.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = if (on) C.text else C.muted)
    }
}

@Composable
private fun OrderBook(repo: Repo, key: String, onPrice: (Double, Boolean) -> Unit) {
    val books by repo.market.books.collectThrottled(200)
    val b: Book = books[key] ?: run { Text("Waiting for book…", color = C.muted, modifier = Modifier.padding(16.dp)); return }
    val depth = 12
    val asks = b.asks.take(depth)
    val bids = b.bids.take(depth)
    val maxSz = (asks + bids).maxOfOrNull { it.sz }?.coerceAtLeast(1e-12) ?: 1.0
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row { Text("Price", color = C.muted, fontSize = 11.sp, modifier = Modifier.weight(1f)); Text("Size", color = C.muted, fontSize = 11.sp) }
        // Tapping a level opens a limit ticket there: sell from the ask side, buy from the bid side.
        asks.asReversed().forEach { BookRow(it.px, it.sz, maxSz, C.down) { onPrice(it.px, false) } }
        val spread = if (asks.isNotEmpty() && bids.isNotEmpty()) asks.first().px - bids.first().px else null
        Text(
            "Spread ${spread?.let { String.format(Locale.US, "%,.${priceDecimals(bids.first().px)}f", it) } ?: "—"}" + (spread?.let { "  (" + String.format(Locale.US, "%.3f%%", it / bids.first().px * 100) + ")" } ?: ""),
            style = num.copy(fontSize = 11.sp, color = C.muted), modifier = Modifier.padding(vertical = 4.dp),
        )
        bids.forEach { BookRow(it.px, it.sz, maxSz, C.up) { onPrice(it.px, true) } }
    }
}

@Composable
private fun BookRow(px: Double, sz: Double, maxSz: Double, color: Color, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(22.dp).clickable(onClick = onClick)) {
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth((sz / maxSz).toFloat().coerceIn(0f, 1f)).background(color.copy(alpha = 0.12f)))
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Text(fmtPrice(px), style = num.copy(fontSize = 12.sp, color = color), modifier = Modifier.weight(1f))
            Text("$" + fmtCompact(sz * px), style = num.copy(fontSize = 12.sp))
        }
    }
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun TradesTape(repo: Repo, market: Market) {
    val markets by repo.markets.collectAsStateWithLifecycle()
    // Same coin on every venue MFP carries, like aggr.trade.
    val venues = markets.filter { it.symbol.equals(market.symbol, ignoreCase = true) }.ifEmpty { listOf(market) }
    DisposableEffect(venues.map { it.feedKey }) {
        val subs = venues.map { Sub("trades", it.provider, it.coin) }
        subs.forEach { repo.market.acquire(it) }
        onDispose { subs.forEach { repo.market.release(it) } }
    }
    // Keep our own longer history: the shared buffer only holds the latest few per venue.
    val tape = remember(market.symbol) { ArrayDeque<Pair<String, Trade>>() }
    val seen = remember(market.symbol) { HashSet<String>() }
    var tick by remember { mutableLongStateOf(0L) }
    var dirty by remember { mutableStateOf(false) }
    fun add(v: String, t: Trade) {
        if (!seen.add(v + t.id)) return
        tape.addFirst(v to t)
        while (tape.size > 3000) { val (pv, old) = tape.removeLast(); seen.remove(pv + old.id) }
        dirty = true
    }
    val classes by repo.classes.collectAsStateWithLifecycle()
    // Crypto comes straight from Binance, Bybit, OKX and Hyperliquid; anything else from MFP's own feed.
    val direct = com.alex.mfppro.data.AssetClasses.classOf(market, classes) == "Crypto"
    if (direct) LaunchedEffect(market.symbol) { repo.venueTrades.stream(market.symbol).collect { (v, t) -> add(v, t) } }
    else LaunchedEffect(market.symbol, venues.size) {
        repo.market.trades.collect { m -> for (v in venues) for (t in m[v.feedKey].orEmpty().asReversed()) add(v.provider, t) }
    }
    // Redraw at most four times a second however fast prints arrive.
    LaunchedEffect(Unit) { while (true) { delay(250); if (dirty) { dirty = false; tick++ } } }
    var largeOnly by remember { mutableStateOf(repo.prefs.largeOnly) }
    var minUsd by remember { mutableStateOf(repo.prefs.largeTradeMin) }
    val rows = remember(tick, largeOnly, minUsd) {
        // One market order fills many levels: merge same venue + side within 100 ms into one print, like aggr.
        val merged = ArrayList<Pair<String, Trade>>()
        for ((v, t) in tape.sortedByDescending { it.second.time }) {
            val last = merged.lastOrNull()
            if (last != null && last.first == v && last.second.buy == t.buy && kotlin.math.abs(last.second.time - t.time) <= 100) {
                val a = last.second
                val size = a.size + t.size
                val px = if (t.buy) maxOf(a.price, t.price) else minOf(a.price, t.price)
                merged[merged.size - 1] = v to a.copy(size = size, price = px)
            } else merged += v to t
            if (merged.size > 400) break
        }
        merged.asSequence().filter { !largeOnly || it.second.price * it.second.size >= minUsd }.take(60).toList()
    }
    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Large only", color = C.text, fontSize = 12.sp)
            AppSwitch(largeOnly, { largeOnly = it; repo.prefs.largeOnly = it })
            Spacer(Modifier.weight(1f))
            for ((v, txt) in listOf(50_000 to "50K", 100_000 to "100K", 250_000 to "250K", 1_000_000 to "1M")) {
                Seg(txt, minUsd == v, C.accent) { minUsd = v; repo.prefs.largeTradeMin = v }
            }
        }
        Row(Modifier.padding(top = 6.dp, bottom = 2.dp)) {
            Text("Venue", color = C.muted, fontSize = 10.sp, modifier = Modifier.width(44.dp))
            Text("Price", color = C.muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("Value", color = C.muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("Time", color = C.muted, fontSize = 10.sp)
        }
        if (rows.isEmpty()) Text(if (largeOnly) "No trades over $${fmtCompact(minUsd.toDouble())} yet" else "Waiting for trades…", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        rows.forEach { (venue, t) ->
            val usd = t.price * t.size
            val col = if (t.buy) C.up else C.down
            // Shade by size relative to the large-trade line, like aggr.
            val heat = (usd / minUsd).coerceIn(0.0, 1.0).toFloat()
            val big = usd >= minUsd
            Row(
                Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(3.dp)).background(col.copy(alpha = 0.04f + 0.36f * heat)).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(40.dp)) { VenueTag(venue, 14.dp) }
                Text(fmtPrice(t.price), style = num.copy(fontSize = 12.sp, color = col), modifier = Modifier.weight(1f))
                Text("$" + fmtCompact(usd), style = num.copy(fontSize = 12.sp, color = C.text, fontWeight = if (big) FontWeight.Bold else FontWeight.Normal), modifier = Modifier.weight(1f))
                Text(timeFmt.format(Date(t.time)), style = num.copy(fontSize = 11.sp, color = C.muted))
            }
            Spacer(Modifier.height(1.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarketPicker(repo: Repo, markets: List<Market>, classes: Map<String, String>, onDismiss: () -> Unit, onPick: (Market) -> Unit) {
    var q by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf(repo.prefs.pickerSort) }
    var venue by remember { mutableStateOf<String?>(null) }
    val venues by repo.venues.collectAsStateWithLifecycle()
    val stats by repo.market.stats.collectThrottled(500)
    val extras by repo.extras.collectAsStateWithLifecycle()
    val chg4h by repo.chg4h.collectAsStateWithLifecycle()
    val all = venues.ifEmpty { markets }
    DisposableEffect(Unit) {
        val subs = markets.map { Sub("marketStats", it.provider, it.coin) }
        subs.forEach { repo.market.acquire(it) }
        onDispose { subs.forEach { repo.market.release(it) } }
    }
    LaunchedEffect(Unit) { repo.loadOverviewExtras() }
    LaunchedEffect(sortBy) { if (sortBy == "4h") repo.load4h() }
    // 24h = change since today's 00:00 UTC open.
    fun d24(m: Market): Double? {
        val px = stats[m.feedKey]?.markPx ?: return null
        val open = extras[m.marketId]?.dayOpen ?: return stats[m.feedKey]?.change24hPct
        return if (open > 0) (px / open - 1) * 100 else null
    }
    fun value(m: Market): Double? = when (sortBy) { "24h" -> d24(m); "4h" -> chg4h[m.marketId]; "vol" -> stats[m.feedKey]?.dayNtlVlm; else -> null }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.panel) {
        AppInput("", q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), placeholder = "Search")
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((k, label) in listOf("24h" to "24h", "4h" to "4h", "vol" to "Vol", "name" to "A–Z")) {
                Seg(label, sortBy == k, C.accent) { sortBy = k; repo.prefs.pickerSort = k }
            }
            Spacer(Modifier.weight(1f))
            // Venue filter: tap a logo to show only that venue, again to clear.
            for (v in all.map { it.provider }.distinct().sorted()) {
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp)).background(if (venue == v) C.accent.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable { venue = if (venue == v) null else v }.padding(5.dp),
                ) { VenueTag(v, 16.dp) }
            }
        }
        val base = (if (venue != null) all.filter { it.provider == venue } else markets)
            .filter { q.isBlank() || it.symbol.contains(q, true) || it.coin.contains(q, true) }
        val filtered = if (sortBy == "name") base.sortedBy { it.symbol }
            else base.partition { value(it) != null }.let { (has, none) -> has.sortedByDescending { value(it)!! } + none.sortedBy { it.symbol } }
        LazyColumn(Modifier.fillMaxWidth().height(480.dp)) {
            items(filtered, key = { it.marketId }) { m ->
                val cls = AssetClasses.classOf(m, classes)
                val chg = if (sortBy == "4h") chg4h[m.marketId] else d24(m)
                Row(Modifier.fillMaxWidth().clickable { onPick(m) }.padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MarketLogo(m, cls, 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.symbol, color = C.text, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(5.dp))
                            VenueTag(m.provider, 12.dp)
                        }
                        Text("$cls · ${m.maxLeverage.toInt()}x", color = C.muted, fontSize = 10.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmtPrice(stats[m.feedKey]?.markPx), style = num.copy(fontSize = 12.sp))
                        Text((if (sortBy == "4h") "4h " else "24h ") + fmtPct(chg), style = num.copy(fontSize = 11.sp, color = pnlColor(chg)))
                    }
                }
            }
        }
    }
}

/**
 * Open positions on the accounts in view (the trading account, or all of them),
 * always visible on Trade: tap to jump the chart to it, ✕ to market-close it.
 */
@Composable
private fun OpenPositionsStrip(repo: Repo, scoped: List<com.alex.mfppro.data.AccountState>, multi: Boolean, markets: List<Market>, current: Market, onPickMarket: (String) -> Unit) {
    val marks by repo.market.marks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val positions = scoped.flatMap { a -> a.positions.values.map { a to it } }
    if (positions.isEmpty()) return
    var ask by remember { mutableStateOf<com.alex.mfppro.data.Position?>(null) }
    LazyRow(contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(positions, key = { it.second.id }) { (acc, p) ->
            val pnl = marks[p.feedKey]?.let(p::pnlAt)
            val here = p.inMarket(current)
            val side = if (p.isLong) C.up else C.down
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(C.panel)
                    .border(1.dp, if (here) side.copy(alpha = 0.55f) else C.line.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .clickable { markets.firstOrNull { p.inMarket(it) }?.let { onPickMarket(it.marketId) } }
                    .padding(start = 10.dp, end = 2.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(3.dp).height(26.dp).clip(RoundedCornerShape(2.dp)).background(side))
                Spacer(Modifier.width(7.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.symbol, color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text(" ${p.leverage.toInt()}x", color = side, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        if (multi) Text("  " + accountName(acc).take(12), color = C.muted, fontSize = 10.sp)
                    }
                    Text(fmtUsd(net(pnl), true), style = num.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pnlColor(pnl)))
                }
                Text("✕", color = C.muted, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                    if (repo.prefs.confirmOrders) ask = p else scope.launch { repo.closePosition(p) }
                }.padding(horizontal = 9.dp, vertical = 6.dp))
            }
        }
    }
    ask?.let { p ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { ask = null }, containerColor = C.panel,
            title = { Text("Close ${p.symbol}", color = C.down) },
            text = { Text("Market close all ${fmtUsd(p.size * p.entryPrice)} of ${p.symbol}.", color = C.text) },
            confirmButton = { TextButton(onClick = { ask = null; scope.launch { repo.closePosition(p) } }) { Text("Close", color = C.down) } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("Cancel", color = C.muted) } },
        )
    }
}

/** Fold the live book price into the forming candle so the chart never trails the market. */
fun List<com.alex.mfppro.data.Candle>.withLive(p: Double?): List<com.alex.mfppro.data.Candle> {
    if (p == null || isEmpty()) return this
    val l = last()
    return dropLast(1) + l.copy(c = p, h = maxOf(l.h, p), l = minOf(l.l, p))
}

/** Best bid/ask with sizes, spread, and how the top 20 levels split between bids and asks. */
@Composable
private fun TopOfBook(b: Book?) {
    if (b == null) return
    val bid = b.bids.firstOrNull(); val ask = b.asks.firstOrNull()
    val bidNtl = b.bids.take(20).sumOf { it.px * it.sz }
    val askNtl = b.asks.take(20).sumOf { it.px * it.sz }
    val bidShare = if (bidNtl + askNtl > 0) bidNtl / (bidNtl + askNtl) else 0.5
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bid", color = C.muted, fontSize = 10.sp)
                Text(fmtPrice(bid?.px) + "  " + (bid?.let { "$" + fmtCompact(it.sz * it.px) } ?: ""), style = num.copy(fontSize = 12.sp, color = C.up))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Spread", color = C.muted, fontSize = 10.sp)
                val sp = if (bid != null && ask != null) ask.px - bid.px else null
                Text(sp?.let { String.format(Locale.US, "%,.${priceDecimals(bid!!.px)}f", it) } ?: "—", style = num.copy(fontSize = 12.sp))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("Ask", color = C.muted, fontSize = 10.sp)
                Text((ask?.let { "$" + fmtCompact(it.sz * it.px) } ?: "") + "  " + fmtPrice(ask?.px), style = num.copy(fontSize = 12.sp, color = C.down))
            }
        }
        Row(Modifier.padding(top = 4.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))) {
            Box(Modifier.weight(bidShare.toFloat().coerceIn(0.02f, 0.98f)).fillMaxHeight().background(C.up))
            Box(Modifier.weight((1 - bidShare).toFloat().coerceIn(0.02f, 0.98f)).fillMaxHeight().background(C.down))
        }
        Row {
            Text(String.format(Locale.US, "Bid depth %.1f%%", bidShare * 100), style = num.copy(fontSize = 10.sp, color = C.up))
            Spacer(Modifier.weight(1f))
            Text(String.format(Locale.US, "%.1f%% Ask depth", (1 - bidShare) * 100), style = num.copy(fontSize = 10.sp, color = C.down))
        }
    }
}

/** Full-screen chart that turns the phone sideways while open. */
@Composable
private fun LandscapeChart(
    symbol: String, interval: String, onInterval: (String) -> Unit,
    candles: List<com.alex.mfppro.data.Candle>, lines: List<PriceLine>,
    showVp: Boolean, showPoc: Boolean, vpSince: Long?, sessions: SessionOpts?, onClose: () -> Unit,
) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    DisposableEffect(Unit) {
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }
    androidx.activity.compose.BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(C.bg).systemBarsPadding().padding(horizontal = 8.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(symbol, color = C.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            IntervalRow(interval, 12.sp, Modifier.weight(1f), onInterval)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.FullscreenExit, "Exit", tint = C.text, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClose).padding(6.dp).size(22.dp))
        }
        CandleChart(candles, lines, Modifier.fillMaxSize(), showVp = showVp, showPoc = showPoc, vpSince = vpSince, sessions = sessions)
    }
}

/** Something drawn over the whole app, above the bottom bar (the landscape chart). */
object Overlay {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
}

/** Venue logo for the market on screen; when the coin trades on several venues, tap to switch. */
@Composable
private fun VenueSwap(market: Market, markets: List<Market>, onPick: (String) -> Unit) {
    val alts = markets.filter { it.symbol.equals(market.symbol, ignoreCase = true) }
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(end = 8.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).background(C.panel2).clickable { open = true }
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VenueTag(market.provider, 14.dp)
            Spacer(Modifier.width(3.dp))
            Icon(Icons.Filled.ArrowDropDown, "Venues", tint = C.muted, modifier = Modifier.size(14.dp))
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }, containerColor = C.panel) {
            alts.forEach { m ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            VenueTag(m.provider, 16.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(m.provider.replaceFirstChar { it.uppercase() }, color = if (m.marketId == market.marketId) C.accent else C.text, fontSize = 14.sp)
                        }
                    },
                    onClick = { open = false; onPick(m.marketId) },
                )
            }
        }
    }
}

/** Small two-way switch: [left] when [leftOn], else [right]. */
@Composable
fun MiniSwitch(left: String, right: String, leftOn: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.height(26.dp).clip(RoundedCornerShape(13.dp)).background(C.panel2).padding(2.dp)) {
        for ((label, on) in listOf(left to leftOn, right to !leftOn)) {
            Box(
                Modifier.fillMaxHeight().clip(RoundedCornerShape(11.dp)).background(if (on) C.accent.copy(alpha = 0.25f) else Color.Transparent)
                    .clickable { onChange(label == left) }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) C.text else C.muted, fontSize = 11.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
}

/**
 * Section switcher as a segmented control: one track, each section its own
 * cell, the selected cell raised with an accent underline and count badge.
 */
@Composable
private fun PillTabs(labels: List<Pair<String, Int>>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).height(38.dp)
            .clip(RoundedCornerShape(10.dp)).background(C.panel2).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        labels.forEachIndexed { i, (name, n) ->
            val on = i == selected
            val count = n.takeIf { it > 0 }?.toString()
            Row(
                Modifier.weight(if (name.length > 6) 1.3f else 1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                    .background(if (on) C.panel else Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, C.line, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable { onSelect(i) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, color = if (on) C.text else C.muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                count?.let {
                    Spacer(Modifier.width(4.dp))
                    Text(it, style = num.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (on) onColor(C.accent) else C.muted),
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) C.accent else C.line).padding(horizontal = 5.dp))
                }
            }
        }
    }
}
