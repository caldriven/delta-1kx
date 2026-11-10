package com.alex.mfppro.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alex.mfppro.data.AssetClasses
import com.alex.mfppro.data.Fill
import com.alex.mfppro.data.Market
import com.alex.mfppro.data.Order
import com.alex.mfppro.data.Position
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.Sub
import com.alex.mfppro.data.feedKey
import com.alex.mfppro.ui.fmtCompact
import com.alex.mfppro.ui.fmtPct
import com.alex.mfppro.ui.fmtPrice
import com.alex.mfppro.ui.fmtSize
import com.alex.mfppro.ui.fmtUsd
import com.alex.mfppro.ui.ticker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Holds a market-data subscription while the composable is on screen. */
@Composable
fun Subscribe(repo: Repo, sub: Sub) {
    DisposableEffect(sub) {
        repo.market.acquire(sub)
        onDispose { repo.market.release(sub) }
    }
}

// ---- watchlist ------------------------------------------------------------

/** Columns of the market list; each header sorts by it (again: flips the direction). */
private enum class Col(val head: String, val w: Float) { SYMBOL("Symbol", 1.25f), PRICE("Price", 1f), H1("1h", 0.62f), H4("4h", 0.62f), D1("24h", 0.68f), VOL("Vol 24h", 0.7f) }

@Composable
fun Watchlist(repo: Repo, selectedId: String?, onSelect: (String) -> Unit, modifier: Modifier, store: JsonFileStore? = null) {
    val markets by repo.markets.collectAsState()
    val marks by repo.market.marks.collectAsState()
    val stats by repo.market.stats.collectAsState()
    val chg1 by repo.chg1h.collectAsState()
    val chg4 by repo.chg4h.collectAsState()
    val accounts by repo.account.accounts.collectAsState()
    val classMap by repo.classes.collectAsState()
    var query by remember { mutableStateOf("") }
    var cls by remember { mutableStateOf(store?.getString("wlClass", "All") ?: "All") }
    var sort by remember { mutableStateOf(runCatching { Col.valueOf(store?.getString("wlSort", "VOL") ?: "VOL") }.getOrDefault(Col.VOL)) }
    var desc by remember { mutableStateOf(store?.getBoolean("wlDesc", true) ?: true) }
    var heldFirst by remember { mutableStateOf(store?.getBoolean("wlHeld", true) ?: true) }
    fun save() = store?.edit()?.putString("wlClass", cls)?.putString("wlSort", sort.name)?.putBoolean("wlDesc", desc)?.putBoolean("wlHeld", heldFirst)?.apply()

    // 24h change and volume for every market (not only the rows on screen), so sorting sees them all.
    DisposableEffect(markets) {
        val subs = markets.map { Sub("marketStats", it.provider, it.coin) }
        subs.forEach { repo.market.acquire(it) }
        onDispose { subs.forEach { repo.market.release(it) } }
    }
    // 1h and 4h change come from candles; Repo throttles these to every 2 and 5 minutes.
    LaunchedEffect(markets.size) {
        if (markets.isEmpty()) return@LaunchedEffect
        repo.loadOverviewExtras()
        // Not while the window is minimised: nobody is looking, and it's a hundred-odd requests a round.
        while (true) { if (repo.visible.value) { repo.load1h(); repo.load4h() }; delay(60_000) }
    }
    // Re-sort every few seconds, not on every tick, so rows don't jump about under the mouse.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3_000); tick++ } }

    val held = accounts.values.flatMap { it.positions.values }.map { it.marketId }.toSet()
    fun classOf(m: Market) = AssetClasses.classOf(m, classMap)
    val classes = listOf("All") + markets.map(::classOf).distinct().sortedBy {
        listOf("Crypto", "Stocks", "Indices", "Commodities", "FX", "Forex").indexOf(it).let { i -> if (i < 0) 99 else i }
    }
    fun value(m: Market, c: Col): Double? = when (c) {
        Col.H1 -> chg1[m.marketId]
        Col.H4 -> chg4[m.marketId]
        Col.D1 -> stats[m.feedKey]?.change24hPct
        Col.VOL -> stats[m.feedKey]?.dayNtlVlm
        Col.PRICE -> marks[m.feedKey] ?: stats[m.feedKey]?.markPx
        Col.SYMBOL -> null
    }
    val filtered = markets.filter { m ->
        (cls == "All" || classOf(m) == cls) && (query.isBlank() || m.symbol.contains(query.trim(), true) || m.coin.contains(query.trim(), true))
    }
    val order = remember(filtered, sort, desc, heldFirst, held, tick) {
        val byValue: Comparator<Market> = if (sort == Col.SYMBOL) compareBy<Market> { it.symbol }.let { if (desc) it.reversed() else it }
            else Comparator { a, b ->
                // Markets without the number yet go last whichever way the list runs.
                val x = value(a, sort); val y = value(b, sort)
                when {
                    x == null && y == null -> a.symbol.compareTo(b.symbol)
                    x == null -> 1
                    y == null -> -1
                    else -> if (desc) y.compareTo(x) else x.compareTo(y)
                }
            }
        filtered.sortedWith(if (heldFirst) compareBy<Market> { it.marketId !in held }.then(byValue) else byValue).map { it.marketId }
    }
    val byId = filtered.associateBy { it.marketId }

    Panel("Markets · ${filtered.size}", modifier, actions = {
        if (held.isNotEmpty()) Pill("● first", heldFirst) { heldFirst = !heldFirst; save() }
    }) {
        Field(query, { query = it }, Modifier.fillMaxWidth().padding(8.dp), placeholder = "Search")
        Row(Modifier.padding(horizontal = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            classes.forEach { c -> Pill(c, c == cls) { cls = c; save() } }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Narrow list: the less used columns give way first.
            val cols = Col.entries.filter {
                when (it) { Col.VOL -> maxWidth > 360.dp; Col.H4 -> maxWidth > 300.dp; Col.H1 -> maxWidth > 250.dp; else -> true }
            }
            Column {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
                    for (c in cols) {
                        val on = c == sort
                        Text(
                            c.head + if (on) (if (desc) " ▼" else " ▲") else "",
                            Modifier.weight(c.w).clickable {
                                if (on) desc = !desc else { sort = c; desc = c != Col.SYMBOL }
                                save()
                            }.pointerHoverIcon(PointerIcon.Hand),
                            style = label.copy(fontSize = 10.sp, color = if (on) D.accent else D.muted),
                            textAlign = if (c == Col.SYMBOL) TextAlign.Start else TextAlign.End, maxLines = 1,
                        )
                    }
                }
                // Rows by position, not by key: keyed rows made the list follow the old top row as it re-sorted,
                // scrolling the leaders out of sight above. A new sort, filter or search starts at the top.
                val listState = rememberLazyListState()
                LaunchedEffect(sort, desc, cls, query, heldFirst) { listState.scrollToItem(0) }
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(order) { id ->
                        val m = byId[id] ?: return@items
                        // Only rows on screen hold a tick feed; scrolling moves the subscriptions along.
                        Subscribe(repo, Sub("ticks", m.provider, m.coin))
                        val on = m.marketId == selectedId
                        Row(
                            Modifier.fillMaxWidth().background(if (on) D.panel2 else D.panel)
                                .clickable { onSelect(m.marketId) }.pointerHoverIcon(PointerIcon.Hand)
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            for (c in cols) when (c) {
                                Col.SYMBOL -> Row(Modifier.weight(c.w), verticalAlignment = Alignment.CenterVertically) {
                                    Text(ticker(m.symbol, m.provider), fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                                    if (m.marketId in held) Text("  ●", color = D.accent, fontSize = 9.sp)
                                }
                                Col.PRICE -> Cell(fmtPrice(value(m, c), m), c.w)
                                Col.VOL -> Cell(value(m, c)?.let { "$" + fmtCompact(it) } ?: "", c.w, D.muted, style = num.copy(fontSize = 11.sp))
                                else -> { val v = value(m, c); Cell(v?.let { fmtPct(it) } ?: "", c.w, pnlColor(v), style = num.copy(fontSize = 11.sp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- order book and tape --------------------------------------------------

@Composable
fun BookPanel(repo: Repo, m: Market, modifier: Modifier) {
    Subscribe(repo, Sub("books", m.provider, m.coin))
    val books by repo.market.books.collectAsState()
    val book = books[m.feedKey]
    Panel("Order book", modifier) {
        if (book == null) { Empty("Waiting for the book…"); return@Panel }
        val depth = 14
        val asks = book.asks.take(depth).reversed()
        val bids = book.bids.take(depth)
        val maxSz = (asks + bids).maxOfOrNull { it.sz * it.px }?.takeIf { it > 0 } ?: 1.0
        Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp)) {
            HeadCell("Price", 1f, TextAlign.Start); HeadCell("Size", 1f); HeadCell("USD", 1f)
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
            asks.forEach { BookRow(it.px, it.sz, m, maxSz, false) }
            val bid = bids.firstOrNull()?.px; val ask = book.asks.firstOrNull()?.px
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
                Text(fmtPrice(if (bid != null && ask != null) (bid + ask) / 2 else bid ?: ask, m), Modifier.weight(1f), style = num.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold))
                if (bid != null && ask != null) Text("spread " + fmtPrice(ask - bid, m), style = label)
            }
            bids.forEach { BookRow(it.px, it.sz, m, maxSz, true) }
        }
    }
}

@Composable
private fun BookRow(px: Double, sz: Double, m: Market, maxUsd: Double, bid: Boolean) {
    val usd = px * sz
    Box(Modifier.fillMaxWidth().height(17.dp)) {
        Box(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth((usd / maxUsd).toFloat().coerceIn(0f, 1f))
                .background(if (bid) D.upDim else D.downDim),
        )
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Cell(fmtPrice(px, m), 1f, if (bid) D.up else D.down, TextAlign.Start, num.copy(fontSize = 11.sp))
            Cell(fmtSize(sz), 1f, style = num.copy(fontSize = 11.sp))
            Cell(fmtCompact(usd), 1f, D.muted, style = num.copy(fontSize = 11.sp))
        }
    }
}

private val hms = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

@Composable
fun TapePanel(repo: Repo, m: Market, modifier: Modifier) {
    Subscribe(repo, Sub("trades", m.provider, m.coin))
    val trades by repo.market.trades.collectAsState()
    val list = trades[m.feedKey].orEmpty()
    Panel("Trades", modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp)) {
            HeadCell("Price", 1f, TextAlign.Start); HeadCell("USD", 1f); HeadCell("Time", 1f)
        }
        if (list.isEmpty()) Empty("Waiting for trades…")
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            items(list, key = { it.id }) { t ->
                val usd = t.price * t.size
                Row(Modifier.fillMaxWidth().height(17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cell(fmtPrice(t.price, m), 1f, if (t.buy) D.up else D.down, TextAlign.Start, num.copy(fontSize = 11.sp))
                    Cell(fmtCompact(usd), 1f, if (usd >= 100_000) D.accent else D.text, style = num.copy(fontSize = 11.sp, fontWeight = if (usd >= 100_000) FontWeight.Bold else FontWeight.Normal))
                    Cell(hms.format(Instant.ofEpochMilli(t.time)), 1f, D.muted, style = num.copy(fontSize = 11.sp))
                }
            }
        }
    }
}

// ---- blotter: positions, orders, fills -------------------------------------

@Composable
fun Blotter(repo: Repo, onSelect: (String) -> Unit, modifier: Modifier) {
    val accounts by repo.account.accounts.collectAsState()
    val selected by repo.selected.collectAsState()
    val shown = accounts.values.filter { selected.isEmpty() || it.account.id in selected }
    val positions = shown.flatMap { it.positions.values }.sortedBy { it.symbol }
    val orders = shown.flatMap { it.orders.values }.filter { it.exitGroup == null }.sortedBy { it.symbol }
    var tab by remember { mutableStateOf(0) }
    Panel(
        "Blotter", modifier,
        actions = {
            Pill("Positions ${positions.size}", tab == 0) { tab = 0 }
            Pill("Orders ${orders.size}", tab == 1) { tab = 1 }
            Pill("Fills", tab == 2) { tab = 2 }
        },
    ) {
        when (tab) {
            0 -> PositionsTable(repo, positions, onSelect)
            1 -> OrdersTable(repo, orders)
            else -> FillsTable(repo)
        }
    }
}

@Composable
private fun PositionsTable(repo: Repo, list: List<Position>, onSelect: (String) -> Unit) {
    val marks by repo.market.marks.collectAsState()
    val accounts by repo.account.accounts.collectAsState()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<Position?>(null) }
    Row(Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
        HeadCell("Account", 1.3f, TextAlign.Start); HeadCell("Symbol", 1f, TextAlign.Start); HeadCell("Size", 1f); HeadCell("Value", 1f)
        HeadCell("Entry", 1f); HeadCell("Mark", 1f); HeadCell("Liq.", 1f); HeadCell("uPnL", 1f); HeadCell("ROE", 0.7f); HeadCell("TP / SL", 1.3f)
        HeadCell("", 0.8f)
    }
    if (list.isEmpty()) { Empty("No open positions"); return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { p ->
            val m = repo.market(p.marketId)
            val mark = marks[p.feedKey]
            val upnl = mark?.let(p::pnlAt)
            val exits = repo.exitsOf(p)
            val tp = exits.firstOrNull { it.exitGroup == "tp" }?.exitPrice
            val sl = exits.firstOrNull { it.exitGroup == "sl" }?.exitPrice
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(p.marketId) }.padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cell(accounts[p.accountId]?.let(::accountName) ?: p.accountId, 1.3f, D.muted, TextAlign.Start, label.copy(fontSize = 12.sp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(ticker(p.symbol, p.provider), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(" ${if (p.isLong) "L" else "S"} ${p.leverage.toInt()}x", color = if (p.isLong) D.up else D.down, fontSize = 11.sp)
                }
                Cell(fmtSize(p.size), 1f)
                Cell(fmtUsd(mark?.let { it * p.size }), 1f)
                Cell(fmtPrice(p.entryPrice, m), 1f)
                Cell(fmtPrice(mark, m), 1f)
                Cell(fmtPrice(p.liquidationPrice, m), 1f, D.muted)
                Cell(fmtUsd(upnl, signed = true), 1f, pnlColor(upnl))
                Cell(upnl?.let { fmtPct(it / p.marginAt().coerceAtLeast(1e-9) * 100) } ?: "—", 0.7f, pnlColor(upnl))
                Cell((tp?.let { fmtPrice(it, m) } ?: "—") + " / " + (sl?.let { fmtPrice(it, m) } ?: "—"), 1.3f, D.muted)
                Box(Modifier.weight(0.8f), contentAlignment = Alignment.CenterEnd) {
                    Btn("Close", color = D.downDim, textColor = D.down) { confirm = p }
                }
            }
        }
    }
    confirm?.let { p ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Close ${ticker(p.symbol, p.provider)}?") },
            text = { Text("Market close of the whole ${if (p.isLong) "long" else "short"} (${fmtSize(p.size)}) on ${accounts[p.accountId]?.let(::accountName) ?: p.accountId}.") },
            confirmButton = { TextButton({ confirm = null; scope.launch { repo.closePosition(p) } }) { Text("Close position", color = D.down) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OrdersTable(repo: Repo, list: List<Order>) {
    val accounts by repo.account.accounts.collectAsState()
    val scope = rememberCoroutineScope()
    Row(Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
        HeadCell("Account", 1.3f, TextAlign.Start); HeadCell("Symbol", 1f, TextAlign.Start); HeadCell("Type", 1f, TextAlign.Start)
        HeadCell("Side", 0.6f, TextAlign.Start); HeadCell("Size", 1f); HeadCell("Price", 1f); HeadCell("Status", 1f); HeadCell("", 0.8f)
    }
    if (list.isEmpty()) { Empty("No open orders"); return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { o ->
            val m = repo.market(o.marketId)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Cell(accounts[o.accountId]?.let(::accountName) ?: o.accountId, 1.3f, D.muted, TextAlign.Start, label.copy(fontSize = 12.sp))
                Cell(o.symbol, 1f, align = TextAlign.Start)
                Cell(o.type.replace('_', ' ') + if (o.reduceOnly) " · RO" else "", 1f, D.muted, TextAlign.Start)
                Cell(o.side.uppercase(), 0.6f, if (o.side == "buy") D.up else D.down, TextAlign.Start)
                Cell(fmtSize(o.size), 1f)
                Cell(fmtPrice(o.limitPrice ?: o.triggerPrice, m), 1f)
                Cell(o.status.replace('_', ' '), 1f, D.muted)
                Box(Modifier.weight(0.8f), contentAlignment = Alignment.CenterEnd) {
                    Btn("Cancel") { scope.launch { repo.cancelOrder(o) } }
                }
            }
        }
    }
}

private val dayTime = DateTimeFormatter.ofPattern("d MMM HH:mm:ss", java.util.Locale.US).withZone(ZoneId.systemDefault())

@Composable
private fun FillsTable(repo: Repo) {
    val trade by repo.tradeAccount.collectAsState()
    val accounts by repo.account.accounts.collectAsState()
    var fills by remember { mutableStateOf<List<Fill>?>(null) }
    var bump by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { repo.tradeEvents.collect { if (it.ok == true) bump++ } }
    LaunchedEffect(trade, bump) {
        val id = trade ?: return@LaunchedEffect
        fills = runCatching { repo.fills(id, maxPages = 1) }.getOrElse { emptyList() }
    }
    Text(
        "Last 100 fills on " + (accounts[trade]?.let(::accountName) ?: "the trading account"),
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = label,
    )
    Row(Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
        HeadCell("Time", 1.2f, TextAlign.Start); HeadCell("Symbol", 1f, TextAlign.Start); HeadCell("Side", 0.6f, TextAlign.Start)
        HeadCell("Size", 1f); HeadCell("Price", 1f); HeadCell("Notional", 1f); HeadCell("Fee", 0.8f); HeadCell("Realized", 1f)
    }
    val list = fills
    if (list == null) { Empty("Loading…"); return }
    if (list.isEmpty()) { Empty("No fills"); return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { f ->
            val m = repo.market(f.marketId)
            val t = f.settledAt ?: f.requestedAt
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Cell(dayTime.format(Instant.ofEpochMilli(t)), 1.2f, D.muted, TextAlign.Start)
                Cell(f.symbol, 1f, align = TextAlign.Start)
                Cell(f.side.uppercase(), 0.6f, if (f.side == "buy") D.up else D.down, TextAlign.Start)
                Cell(fmtSize(f.size), 1f)
                Cell(fmtPrice(f.fillPrice, m), 1f)
                Cell(fmtUsd(f.notional), 1f)
                Cell(fmtUsd(f.fee), 0.8f, D.muted)
                Cell(if (f.realizedPnl != 0.0) fmtUsd(f.realizedPnl, signed = true) else "", 1f, pnlColor(f.realizedPnl))
            }
        }
    }
}
