package com.alex.mfppro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alex.mfppro.data.AssetClasses
import com.alex.mfppro.data.Candle
import com.alex.mfppro.data.Market
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.riskOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

private val POS_C = Color(0xFFF0A030)
private val NEG_C = Color(0xFF20D0D0)

/** Lookback windows: candle interval, number of candles, candles per day. */
private enum class Window(val label: String, val interval: String, val bars: Int, val perDay: Double) {
    H1("1h · 7d", "1h", 168, 24.0), H4("4h · 30d", "4h", 180, 6.0), D1("1d · 90d", "1d", 90, 1.0)
}

private enum class Universe(val label: String) { POS("Positions"), TOP10("Top 10"), TOP20("Top 20") }

/** Log returns keyed by candle open time. */
private fun returns(c: List<Candle>): Map<Long, Double> {
    val out = HashMap<Long, Double>()
    for (i in 1 until c.size) if (c[i - 1].c > 0 && c[i].c > 0) out[c[i].openTime] = ln(c[i].c / c[i - 1].c)
    return out
}

/** Covariance and correlation over the times both series share. */
private class Pair2(val cov: Double, val corr: Double, val n: Int)

private fun pair(a: Map<Long, Double>, b: Map<Long, Double>): Pair2? {
    val keys = a.keys.filter { it in b }
    if (keys.size < 10) return null
    val xa = keys.map { a[it]!! }; val xb = keys.map { b[it]!! }
    val ma = xa.average(); val mb = xb.average()
    var cov = 0.0; var va = 0.0; var vb = 0.0
    for (i in keys.indices) { val da = xa[i] - ma; val db = xb[i] - mb; cov += da * db; va += da * da; vb += db * db }
    val n = keys.size - 1
    val corr = if (va > 0 && vb > 0) cov / sqrt(va * vb) else 0.0
    return Pair2(cov / n, corr, keys.size)
}

private class Matrix(val markets: List<Market>, val ret: List<Map<Long, Double>>, val cells: Array<Array<Pair2?>>)

/**
 * Analysis: correlation matrix of the chosen markets, what that means for the
 * open positions (VaR against drawdown room, stacked or hedged pairs),
 * volatility and beta to BTC, a returns heatmap and funding/OI extremes.
 * Everything is computed on the phone from MFP candles and stats.
 */
@Composable
fun AnalysisScreen(repo: Repo, onOpenMarket: (String) -> Unit) {
    val markets by repo.markets.collectAsStateWithLifecycle()
    val venues by repo.venues.collectAsStateWithLifecycle()
    val stats by repo.market.stats.collectThrottled(1000)
    val marks by repo.market.marks.collectThrottled(1000)
    val extras by repo.extras.collectAsStateWithLifecycle()
    val classes by repo.classes.collectAsStateWithLifecycle()
    val acc = rememberTradeAccount(repo)
    var universe by remember { mutableStateOf(Universe.entries.firstOrNull { it.name == repo.prefs.corrSet } ?: Universe.POS) }
    var window by remember { mutableStateOf(Window.entries.firstOrNull { it.name == repo.prefs.corrWindow } ?: Window.H1) }

    val all = markets + venues.filter { v -> markets.none { it.marketId == v.marketId } }
    val byVol = markets.sortedByDescending { stats[it.feedKey]?.dayNtlVlm ?: 0.0 }
    val btc = markets.firstOrNull { it.symbol == "BTC" }
    val held = acc?.positions?.values?.mapNotNull { p -> all.firstOrNull { it.marketId == p.marketId } ?: markets.firstOrNull { it.symbol == p.symbol } }.orEmpty()
    // BTC always in, for beta; positions first.
    val set = when (universe) {
        Universe.POS -> (held + listOfNotNull(btc) + markets.filter { it.symbol == "ETH" } + byVol).distinctBy { it.marketId }.take(maxOf(6, held.size + 2))
        Universe.TOP10 -> (listOfNotNull(btc) + byVol).distinctBy { it.marketId }.take(10)
        Universe.TOP20 -> (listOfNotNull(btc) + byVol).distinctBy { it.marketId }.take(20)
    }
    // Freeze the set per choice: live volume ranks swap constantly and would restart the load each time.
    val heldKey = held.map { it.marketId }.sorted().joinToString()
    var frozen by remember(universe, heldKey) { mutableStateOf<List<Market>?>(null) }
    if (frozen == null || (frozen!!.size < set.size) || (frozen!!.isNotEmpty() && stats.isNotEmpty() && frozen!!.none { stats[it.feedKey]?.dayNtlVlm != null } && set.any { stats[it.feedKey]?.dayNtlVlm != null })) frozen = set
    val setKey = frozen.orEmpty().map { it.marketId }.sorted().joinToString(",")

    var matrix by remember { mutableStateOf<Matrix?>(null) }
    var loading by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(setKey, window) {
        val set = frozen.orEmpty()
        if (set.size < 2) return@LaunchedEffect
        loading = true; picked = null
        val rets = coroutineScope {
            set.map { m -> async { returns(runCatching { repo.market.candleHistory(m.provider, m.coin, window.interval, window.bars) }.getOrDefault(emptyList())) } }.awaitAll()
        }
        val cells = Array(set.size) { i -> Array(set.size) { j -> if (i <= j) pair(rets[i], rets[j]) else null } }
        for (i in set.indices) for (j in 0 until i) cells[i][j] = cells[j][i]
        matrix = Matrix(set, rets, cells)
        loading = false
    }

    LazyColumn(Modifier.fillMaxSize().background(C.bg), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Universe.entries.forEach { u -> Seg(u.label, universe == u, C.accent) { universe = u; repo.prefs.corrSet = u.name } }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Window.entries.forEach { w -> Seg(w.label, window == w, C.accent) { window = w; repo.prefs.corrWindow = w.name } }
            }
        }
        val mx = matrix
        item { Title("Correlation", if (loading) "loading…" else window.label) }
        if (mx == null) item { Text(if (loading) "Loading candles…" else "Not enough markets", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
        else {
            item { Heatmap(mx, picked) { picked = it } }
            picked?.let { (i, j) ->
                item {
                    val c = mx.cells[i][j]
                    Card {
                        Text("${mx.markets[i].symbol} × ${mx.markets[j].symbol}", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(c?.let { String.format(Locale.US, "Correlation %.2f over %d bars", it.corr, it.n) } ?: "Not enough overlap",
                            style = num.copy(fontSize = 12.sp, color = C.muted))
                    }
                }
            }
            item { PositionRisk(repo, mx, window) }
            item { Title("Volatility & beta", "annualised, vs BTC") }
            item { VolBeta(mx, window, onOpenMarket) }
        }
        item { Title("Movers", "4h · 24h · 7d") }
        item { Movers(repo, markets, stats, extras, classes, onOpenMarket) }
        item { Title("Funding & OI", "extremes now") }
        item { FundingOi(markets, stats, extras, onOpenMarket) }
    }
}

@Composable
private fun Title(text: String, sub: String) {
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(sub, color = C.muted, fontSize = 11.sp)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.panel).padding(12.dp)) { content() }
}

/** Correlation colour: amber for moving together, cyan for opposite, dark near zero. */
private fun corrColor(c: Double?) = when {
    c == null -> C.panel2
    c >= 0 -> lerp(C.panel2, POS_C, c.toFloat().coerceIn(0f, 1f))
    else -> lerp(C.panel2, NEG_C, (-c).toFloat().coerceIn(0f, 1f))
}

@Composable
private fun Heatmap(mx: Matrix, picked: Pair<Int, Int>?, onPick: (Pair<Int, Int>) -> Unit) {
    val n = mx.markets.size
    Card {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val label = 44.dp
            val cell = (maxWidth - label) / n
            val showNum = cell >= 26.dp
            Column {
                // Column headers.
                Row {
                    Spacer(Modifier.width(label))
                    mx.markets.forEach { m ->
                        Text(m.symbol.take(4), color = C.muted, fontSize = 8.sp, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(cell))
                    }
                }
                for (i in 0 until n) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(mx.markets[i].symbol.take(6), color = C.text, fontSize = 9.sp, maxLines = 1, modifier = Modifier.width(label))
                    for (j in 0 until n) {
                        val c = mx.cells[i][j]?.corr
                        val on = picked == (i to j) || picked == (j to i)
                        Box(
                            Modifier.width(cell).aspectRatio(1f).padding(0.5.dp).clip(RoundedCornerShape(3.dp)).background(corrColor(c))
                                .then(if (on) Modifier.border(1.5.dp, C.text, RoundedCornerShape(3.dp)) else Modifier)
                                .clickable { onPick(i to j) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (showNum && c != null && i != j) Text(String.format(Locale.US, "%.2f", c).replace("0.", "."),
                                style = num.copy(fontSize = 8.sp, color = if (abs(c) > 0.5) Color.Black else C.text))
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("-1", style = num.copy(fontSize = 9.sp, color = C.muted))
            Box(Modifier.padding(horizontal = 6.dp).weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(NEG_C, C.panel2, POS_C))))
            Text("+1", style = num.copy(fontSize = 9.sp, color = C.muted))
        }
    }
}

/**
 * What the correlations mean for the open book: one-day 95% VaR from the
 * covariance of the held markets (signed by side) against the drawdown room,
 * and pairs that stack risk (same direction, high correlation) or hedge.
 */
@Composable
private fun PositionRisk(repo: Repo, mx: Matrix, window: Window) {
    val marks by repo.market.marks.collectThrottled(1000)
    val acc = rememberTradeAccount(repo) ?: return
    val pos = acc.positions.values.toList()
    if (pos.isEmpty()) return
    val idx = pos.map { p -> mx.markets.indexOfFirst { it.marketId == p.marketId || it.symbol == p.symbol } }
    // Signed USD exposure.
    val w = pos.map { p -> p.size * (marks[p.feedKey] ?: p.entryPrice) * (if (p.isLong) 1 else -1) }
    var variance = 0.0
    var known = true
    for (a in pos.indices) for (b in pos.indices) {
        val ia = idx[a]; val ib = idx[b]
        if (ia < 0 || ib < 0) { known = false; continue }
        val c = mx.cells[ia][ib] ?: run { known = false; null } ?: continue
        variance += w[a] * w[b] * c.cov
    }
    val varDay = 1.645 * sqrt(variance.coerceAtLeast(0.0) * window.perDay)
    val gross = w.sumOf { abs(it) }
    val r = riskOf(acc, marks)
    val room = r.room
    Title("Your open risk", accountName(acc))
    Card {
        Row {
            Column(Modifier.weight(1f)) {
                Text("1-day VaR 95%", color = C.muted, fontSize = 11.sp)
                Text(fmtUsd(varDay), style = num.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.text))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("of drawdown room", color = C.muted, fontSize = 11.sp)
                val share = room?.takeIf { it > 0 }?.let { varDay / it * 100 }
                Text(share?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—",
                    style = num.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = when { share == null -> C.muted; share > 60 -> C.down; share > 30 -> C.accent; else -> C.up }))
            }
        }
        Text("Gross ${fmtUsd(gross)} · net ${fmtUsd(w.sum(), true)}" + if (!known) " · some markets missing" else "",
            style = num.copy(fontSize = 11.sp, color = C.muted), modifier = Modifier.padding(top = 4.dp))
        // Pairs: same direction + correlated = stacked; opposite + correlated = hedged.
        val pairs = buildList {
            for (a in pos.indices) for (b in a + 1 until pos.size) {
                val ia = idx[a]; val ib = idx[b]
                if (ia < 0 || ib < 0) continue
                val c = mx.cells[ia][ib]?.corr ?: continue
                val same = pos[a].isLong == pos[b].isLong
                val eff = if (same) c else -c
                if (abs(c) >= 0.5) add(Triple("${pos[a].symbol} / ${pos[b].symbol}", c, eff))
            }
        }.sortedByDescending { abs(it.third) }
        pairs.take(6).forEach { (name, c, eff) ->
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = C.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(String.format(Locale.US, "%.2f", c), style = num.copy(fontSize = 12.sp, color = C.muted))
                Spacer(Modifier.width(8.dp))
                Tag(if (eff > 0) "Stacked" else "Hedged", if (eff > 0) C.down else C.up)
            }
        }
    }
}

@Composable
private fun VolBeta(mx: Matrix, window: Window, onOpenMarket: (String) -> Unit) {
    val bi = mx.markets.indexOfFirst { it.symbol == "BTC" }
    val bVar = if (bi >= 0) mx.cells[bi][bi]?.cov else null
    val perYear = window.perDay * 365
    Card {
        Row(Modifier.padding(bottom = 4.dp)) {
            Text("Market", color = C.muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("Vol", color = C.muted, fontSize = 10.sp, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
            Text("β BTC", color = C.muted, fontSize = 10.sp, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
            Text("ρ BTC", color = C.muted, fontSize = 10.sp, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
        }
        mx.markets.indices.sortedByDescending { mx.cells[it][it]?.cov ?: 0.0 }.forEach { i ->
            val m = mx.markets[i]
            val vol = mx.cells[i][i]?.cov?.let { sqrt(it * perYear) * 100 }
            val c = if (bi >= 0) mx.cells[i][bi] else null
            val beta = if (c != null && bVar != null && bVar > 0) c.cov / bVar else null
            Row(Modifier.fillMaxWidth().clickable { onOpenMarket(m.marketId) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.symbol, color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(vol?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—", style = num.copy(fontSize = 12.sp), textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
                Text(beta?.let { String.format(Locale.US, "%.2f", it) } ?: "—", style = num.copy(fontSize = 12.sp), textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
                Text(c?.corr?.let { String.format(Locale.US, "%.2f", it) } ?: "—", style = num.copy(fontSize = 12.sp, color = C.muted), textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
            }
        }
    }
}

/** Tiles for the 30 busiest markets, coloured by change over the chosen period. */
@Composable
private fun Movers(
    repo: Repo, markets: List<Market>, stats: Map<String, com.alex.mfppro.data.Stats>,
    extras: Map<String, com.alex.mfppro.data.MarketExtra>, classes: Map<String, String>, onOpenMarket: (String) -> Unit,
) {
    var period by remember { mutableStateOf("24h") }
    val chg4h by repo.chg4h.collectAsStateWithLifecycle()
    LaunchedEffect(period) { if (period == "4h") repo.load4h() }
    fun chg(m: Market): Double? = when (period) {
        "4h" -> chg4h[m.marketId]
        "7d" -> extras[m.marketId]?.week
        else -> {
            val px = stats[m.feedKey]?.markPx; val open = extras[m.marketId]?.dayOpen
            if (px != null && open != null && open > 0) (px / open - 1) * 100 else stats[m.feedKey]?.change24hPct
        }
    }
    val top = markets.sortedByDescending { stats[it.feedKey]?.dayNtlVlm ?: 0.0 }.take(30).sortedByDescending { chg(it) ?: -999.0 }
    val maxAbs = top.mapNotNull { chg(it) }.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1.0
    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            for (p in listOf("4h", "24h", "7d")) Seg(p, period == p, C.accent) { period = p }
        }
        top.chunked(5).forEach { row ->
            Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { m ->
                    val c = chg(m)
                    val f = c?.let { (abs(it) / maxAbs).toFloat() } ?: 0f
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .background(if (c == null) C.panel2 else pnlColor(c).copy(alpha = 0.15f + 0.55f * f))
                            .clickable { onOpenMarket(m.marketId) }.padding(vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(m.symbol.take(6), color = C.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(fmtPct(c), style = num.copy(fontSize = 10.sp, color = C.text), maxLines = 1)
                    }
                }
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun FundingOi(markets: List<Market>, stats: Map<String, com.alex.mfppro.data.Stats>, extras: Map<String, com.alex.mfppro.data.MarketExtra>, onOpenMarket: (String) -> Unit) {
    // Annualised funding: rate per interval × intervals per year.
    fun apr(m: Market) = stats[m.feedKey]?.fundingRate?.let { r -> r * (8760.0 / (stats[m.feedKey]?.fundingIntervalHours ?: 8.0)) * 100 }
    val rated = markets.filter { apr(it) != null }
    val hot = rated.sortedByDescending { apr(it)!! }.take(4)
    val cold = rated.sortedBy { apr(it)!! }.take(4)
    val oiUp = markets.filter { extras[it.marketId]?.oi24h != null }.sortedByDescending { abs(extras[it.marketId]!!.oi24h!!) }.take(5)
    Card {
        Text("Funding APR", color = C.muted, fontSize = 11.sp)
        (hot + cold).distinctBy { it.marketId }.forEach { m ->
            val a = apr(m)
            Row(Modifier.fillMaxWidth().clickable { onOpenMarket(m.marketId) }.padding(vertical = 3.dp)) {
                Text(m.symbol, color = C.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(a?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "—", style = num.copy(fontSize = 12.sp, color = pnlColor(a)))
            }
        }
        Text("OI change 24h", color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        oiUp.forEach { m ->
            val o = extras[m.marketId]?.oi24h
            Row(Modifier.fillMaxWidth().clickable { onOpenMarket(m.marketId) }.padding(vertical = 3.dp)) {
                Text(m.symbol, color = C.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("OI $" + fmtCompact(stats[m.feedKey]?.openInterestUsd), style = num.copy(fontSize = 11.sp, color = C.muted))
                Spacer(Modifier.width(10.dp))
                Text(fmtPct(o), style = num.copy(fontSize = 12.sp, color = pnlColor(o)))
            }
        }
    }
}
