package com.alex.mfppro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.alex.mfppro.data.AssetClasses
import com.alex.mfppro.data.Logos
import com.alex.mfppro.data.Market
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.Sub

private enum class SortKey(val label: String) { VOL("Volume"), CHG("24h"), WEEK("7d"), OI("OI"), OIC("OI 24h"), FUND("Funding"), NAME("Name") }

/** Sortable market overview: every MFP market with 24h (from the UTC open) and 7d change, OI, OI change and funding. */
@Composable
fun OverviewScreen(repo: Repo, analysis: Boolean = false, onOpenMarket: (String) -> Unit) {
    val markets by repo.markets.collectAsStateWithLifecycle()
    val stats by repo.market.stats.collectThrottled(500)
    val classes by repo.classes.collectAsStateWithLifecycle()
    val extras by repo.extras.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var cls by remember { mutableStateOf(repo.prefs.overviewClass) }
    var sort by remember {
        val (k, d) = repo.prefs.overviewSort.split(":").let { it[0] to (it.getOrNull(1) != "asc") }
        mutableStateOf<Pair<SortKey, Boolean>>((SortKey.entries.firstOrNull { it.name.equals(k, true) } ?: SortKey.VOL) to d)
    }

    // Live 24h stats for every market while this page is open.
    DisposableEffect(markets) {
        val subs = markets.map { Sub("marketStats", it.provider, it.coin) }
        subs.forEach { repo.market.acquire(it) }
        onDispose { subs.forEach { repo.market.release(it) } }
    }
    LaunchedEffect(markets) { if (markets.isNotEmpty()) repo.loadOverviewExtras() }

    val classOf = { m: Market -> AssetClasses.classOf(m, classes) }
    // Change since today's 00:00 UTC open; the feed's rolling 24h until the daily candles load.
    val dayChg = { m: Market ->
        val px = stats[m.feedKey]?.markPx
        val open = extras[m.marketId]?.dayOpen
        if (px != null && open != null && open > 0) (px / open - 1) * 100 else stats[m.feedKey]?.change24hPct
    }
    val classList = listOf("All") + markets.map(classOf).distinct().sortedBy {
        listOf("Crypto", "Stocks", "Indices", "Commodities", "FX", "Forex").indexOf(it).let { i -> if (i < 0) 99 else i }
    }
    // A saved class that no longer exists would leave the list empty.
    val activeCls = if (cls in classList) cls else "All"
    val q = query.trim()
    val live = markets
        .filter { activeCls == "All" || classOf(it) == activeCls }
        .filter { q.isBlank() || it.symbol.contains(q, true) }
        .let { list ->
            val (key, desc) = sort
            val sel: (Market) -> Double? = { m ->
                val s = stats[m.feedKey]
                when (key) {
                    SortKey.NAME -> null
                    SortKey.CHG -> dayChg(m)
                    SortKey.WEEK -> extras[m.marketId]?.week
                    SortKey.VOL -> s?.dayNtlVlm
                    SortKey.OI -> s?.openInterestUsd
                    SortKey.OIC -> extras[m.marketId]?.oi24h
                    SortKey.FUND -> s?.fundingRate
                }
            }
            if (key == SortKey.NAME) (if (desc) list.sortedByDescending { it.symbol } else list.sortedBy { it.symbol })
            else {
                // Markets without a value sink to the bottom either way.
                val (has, none) = list.partition { sel(it) != null }
                (if (desc) has.sortedByDescending { sel(it)!! } else has.sortedBy { sel(it)!! }) + none
            }
        }
    // Freeze the order between refreshes: re-sorting on every 50 ms tick made rows jump while scrolling.
    // A new sort/filter takes effect at once and starts at the top.
    val latest by androidx.compose.runtime.rememberUpdatedState(live)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var order by remember(sort, activeCls, q) { mutableStateOf(live.map { it.marketId }) }
    LaunchedEffect(sort, activeCls, q) {
        listState.scrollToItem(0)
        // Re-sort every second while stats are still arriving, then every 5 s.
        var n = 0
        while (true) {
            kotlinx.coroutines.delay(if (n++ < 8) 1_000 else 5_000)
            // Keyed rows keep the old first row in view after a reorder; stay pinned to the top if we were there.
            val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            order = latest.map { it.marketId }
            if (atTop) listState.scrollToItem(0)
        }
    }
    val byId = live.associateBy { it.marketId }
    val rows = order.mapNotNull { byId[it] }.let { r -> if (r.size == live.size) r else live }
    val setSort = { k: SortKey ->
        sort = if (sort.first == k) k to !sort.second else k to (k != SortKey.NAME)
        repo.prefs.overviewSort = sort.first.name + ":" + if (sort.second) "desc" else "asc"
    }
    val fundingCol = sort.first == SortKey.FUND

    if (analysis) {
        var view by remember { mutableStateOf(repo.prefs.analysisView) }
        Column(Modifier.fillMaxSize().background(C.bg)) {
            Row(Modifier.fillMaxWidth().padding(14.dp, 8.dp, 10.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Analysis", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = C.text)
                Spacer(Modifier.weight(1f))
                Seg("Correlation", view != "SPECIAL", C.accent) { view = "CORR"; repo.prefs.analysisView = view }
                Spacer(Modifier.width(4.dp))
                Seg("Special trades", view == "SPECIAL", C.accent) { view = "SPECIAL"; repo.prefs.analysisView = view }
            }
            if (view == "SPECIAL") SpecialTrades(repo) else AnalysisScreen(repo, onOpenMarket)
        }
        return
    }
    Column(Modifier.fillMaxSize().background(C.bg)) {
        var searching by remember { mutableStateOf(query.isNotEmpty()) }
        Row(Modifier.fillMaxWidth().padding(14.dp, 6.dp, 10.dp, 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Markets", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = C.text)
            Spacer(Modifier.width(6.dp))
            Text("${rows.size}", style = num.copy(fontSize = 12.sp, color = C.muted))
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.Icon(
                if (searching) Icons.Filled.Close else Icons.Filled.Search, "Search",
                tint = if (searching) C.accent else C.muted,
                modifier = Modifier.clip(CircleShape).clickable { searching = !searching; if (!searching) query = "" }.padding(6.dp).size(20.dp),
            )
        }
        androidx.compose.animation.AnimatedVisibility(searching) {
            AppInput("", query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 2.dp), placeholder = "Search ticker")
        }
        // Asset classes as underlined text tabs.
        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.padding(top = 4.dp)) {
            items(classList) { c ->
                val on = c == activeCls
                val n = if (c == "All") markets.size else markets.count { classOf(it) == c }
                Column(
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable { cls = c; repo.prefs.overviewClass = c }.padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c, color = if (on) C.text else C.muted, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                        Text(" $n", style = num.copy(fontSize = 10.sp, color = C.muted))
                    }
                    Box(Modifier.padding(top = 4.dp).width(22.dp).height(2.dp).clip(CircleShape).background(if (on) C.accent else Color.Transparent))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
        // Sort: plain text tokens; tap the active one again to flip direction.
        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            item { Text("Sort", color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp)) }
            items(SortKey.entries) { k ->
                val on = sort.first == k
                Text(
                    k.label + if (on) (if (sort.second) " ↓" else " ↑") else "",
                    fontSize = 12.sp, color = if (on) C.accent else C.text.copy(alpha = 0.75f), fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) C.accent.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { setSort(k) }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        // Column headers double as sort shortcuts.
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 4.dp)) {
            HeaderCell("Market", Modifier.weight(1f), TextAlign.Start) { setSort(SortKey.NAME) }
            HeaderCell("Price · 24h", Modifier.width(88.dp)) { setSort(SortKey.CHG) }
            HeaderCell("7d", Modifier.width(54.dp)) { setSort(SortKey.WEEK) }
            HeaderCell(if (fundingCol) "Funding" else "OI 24h", Modifier.width(58.dp)) { setSort(if (fundingCol) SortKey.FUND else SortKey.OIC) }
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 12.dp)) {
            // Not keyed: after a re-sort the list stays at the same index (the top), not on the old first row.
            items(rows) { m ->
                val s = stats[m.feedKey]
                val x = extras[m.marketId]
                val chg = dayChg(m)
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenMarket(m.marketId) }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MarketLogo(m, classOf(m), 30.dp)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.symbol, color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                            Spacer(Modifier.width(5.dp))
                            VenueTag(m.provider)
                        }
                        Text(
                            "Vol " + fmtCompact(s?.dayNtlVlm) + (s?.openInterestUsd?.let { " · OI " + fmtCompact(it) } ?: ""),
                            style = num.copy(fontSize = 10.sp, color = C.muted), maxLines = 1,
                        )
                    }
                    Column(Modifier.width(88.dp), horizontalAlignment = Alignment.End) {
                        Text(fmtPrice(s?.markPx), style = num.copy(fontSize = 13.sp), maxLines = 1)
                        ChangePill(chg)
                    }
                    Text(fmtPct(x?.week), style = num.copy(fontSize = 11.sp, color = pnlColor(x?.week)), textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(54.dp))
                    if (fundingCol) Text(
                        s?.fundingRate?.let { String.format(java.util.Locale.US, "%+.4f%%", it * 100) } ?: "—",
                        style = num.copy(fontSize = 10.sp, color = pnlColor(s?.fundingRate)), textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(58.dp),
                    ) else Text(fmtPct(x?.oi24h), style = num.copy(fontSize = 11.sp, color = pnlColor(x?.oi24h)), textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(58.dp))
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(label: String, modifier: Modifier, align: TextAlign = TextAlign.End, onClick: () -> Unit) {
    Text(label, color = C.muted, fontSize = 10.sp, textAlign = align, maxLines = 1, modifier = modifier.clickable(onClick = onClick))
}

@Composable
private fun ChangePill(pct: Double?) {
    val col = pnlColor(pct)
    Text(
        fmtPct(pct), style = num.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (pct == null || pct == 0.0) C.muted else Color.White),
        modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(4.dp))
            .background(if (pct == null || pct == 0.0) C.panel2 else col.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Ticker logo: image where one exists, a drawn tile for commodities/indices, else a letter. */
@Composable
fun MarketLogo(m: Market, cls: String, size: Dp = 34.dp) {
    val tile = Logos.tile(m.symbol)
    if (tile != null) {
        Box(Modifier.size(size).clip(CircleShape).background(Color(tile.bg)), contentAlignment = Alignment.Center) {
            Text(tile.text, color = Color(tile.fg), fontWeight = FontWeight.Bold, fontSize = (size.value * if (tile.text.length > 2) 0.3f else 0.4f).sp, maxLines = 1)
        }
        return
    }
    RoundImage(Logos.ticker(m.symbol, cls), m.symbol, size)
}

/** Ticker logo from a coin alone, for rows (positions) that have no Market at hand. */
@Composable
fun CoinLogo(coin: String, provider: String, size: Dp = 18.dp) {
    val tile = Logos.tile(coin)
    if (tile != null) {
        Box(Modifier.size(size).clip(CircleShape).background(Color(tile.bg)), contentAlignment = Alignment.Center) {
            Text(tile.text, color = Color(tile.fg), fontWeight = FontWeight.Bold, fontSize = (size.value * if (tile.text.length > 2) 0.3f else 0.4f).sp, maxLines = 1)
        }
        return
    }
    // Ostium lists stocks and FX; everything else here is crypto.
    RoundImage(Logos.ticker(coin, if (provider == "ostium") "Stocks" else "Crypto"), coin, size)
}

@Composable
private fun RoundImage(url: String?, name: String, size: Dp) {
    val fallback = @Composable {
        Box(Modifier.size(size).clip(CircleShape).background(letterColor(name)), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.45f).sp)
        }
    }
    if (url == null) { fallback(); return }
    SubcomposeAsyncImage(
        model = url, contentDescription = name,
        modifier = Modifier.size(size).clip(CircleShape),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        loading = { fallback() }, error = { fallback() },
    )
}

private val palette = listOf(0xFF3B82F6, 0xFF8B5CF6, 0xFFEC4899, 0xFF14B8A6, 0xFFF59E0B, 0xFF6366F1, 0xFF10B981, 0xFFEF4444)
private fun letterColor(s: String) = Color(palette[(s.hashCode() and 0x7fffffff) % palette.size])
