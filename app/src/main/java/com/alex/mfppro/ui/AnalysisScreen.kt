package com.alex.mfppro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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

/** Lookback windows: how far back, measured on which candles (number of candles, candles per day). */
internal enum class Window(val label: String, val desc: String, val interval: String, val bars: Int, val perDay: Double) {
    H1("7D", "last 7 days · 1h candles", "1h", 168, 24.0),
    H4("30D", "last 30 days · 4h candles", "4h", 180, 6.0),
    D1("90D", "last 90 days · daily candles", "1d", 90, 1.0),
}

/** Correlation presets: your book, the big coins, top alts, or crypto against macro. */
private enum class Universe(val label: String) { POS("Positions"), MACRO("Cross-asset"), MAJORS("Crypto majors"), ALTS("Top alts"), TOP20("Top 20") }

private val MAJORS = listOf("BTC", "ETH", "SOL", "BNB", "XRP", "DOGE", "HYPE")
/** Crypto against stocks, metals, oil, FX; the macro factors (VIX, 10Y, DXY) are added on top. */
private val MACRO = listOf("BTC", "ETH", "SOL", "SP500", "XYZ100", "NAS100", "XAU", "XAG", "COPPER", "CL", "EUR", "JPY")

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
    fun bySymbols(list: List<String>) = list.mapNotNull { sym -> markets.firstOrNull { it.symbol.equals(sym, true) } }
    val crypto = byVol.filter { AssetClasses.classOf(it, classes) == "Crypto" }
    var extraIds by remember { mutableStateOf(repo.prefs.corrExtra) }
    val added = extraIds.mapNotNull { id -> all.firstOrNull { it.marketId == id } }
    val base = when (universe) {
        Universe.POS -> (held + bySymbols(listOf("BTC", "ETH"))).distinctBy { it.marketId }
        Universe.MAJORS -> bySymbols(MAJORS)
        Universe.ALTS -> listOfNotNull(btc) + crypto.filter { it.symbol !in MAJORS }.take(9)
        Universe.MACRO -> bySymbols(MACRO).distinctBy { it.symbol.replace("NAS100", "XYZ100") } + com.alex.mfppro.data.FactorFeed.ALL
        Universe.TOP20 -> (listOfNotNull(btc) + byVol).distinctBy { it.marketId }.take(20)
    }
    val set = (base + added).distinctBy { it.marketId }
    var adding by remember { mutableStateOf(false) }
    // Freeze the set per choice: live volume ranks swap constantly and would restart the load each time.
    val heldKey = held.map { it.marketId }.sorted().joinToString() + "|" + extraIds.joinToString()
    var frozen by remember(universe, heldKey) { mutableStateOf<List<Market>?>(null) }
    // Refreeze once when volume ranks first arrive, so volume presets aren't stuck on an alphabetical guess.
    // Ranks are trusted once most markets report volume (not just the first few to arrive).
    val haveVol = markets.isNotEmpty() && markets.count { stats[it.feedKey]?.dayNtlVlm != null } >= markets.size * 0.8
    var frozeWithVol by remember(universe, heldKey) { mutableStateOf(false) }
    if (frozen == null || frozen!!.size < set.size || (haveVol && !frozeWithVol)) { frozen = set; if (haveVol) frozeWithVol = true }
    val setKey = frozen.orEmpty().map { it.marketId }.sorted().joinToString(",")

    var matrix by remember { mutableStateOf<Matrix?>(null) }
    var loading by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(setKey, window) {
        val set = frozen.orEmpty()
        if (set.size < 2) return@LaunchedEffect
        loading = true; picked = null
        val rets = coroutineScope {
            set.map { m -> async { returns(runCatching { repo.history(m, window.interval, window.bars) }.getOrDefault(emptyList())) } }.awaitAll()
        }
        val raw = Array(set.size) { i -> Array(set.size) { j -> if (i <= j) pair(rets[i], rets[j]) else null } }
        for (i in set.indices) for (j in 0 until i) raw[i][j] = raw[j][i]
        // Order so related markets sit together: start at BTC (or the first), then always the closest remaining one.
        val order = ArrayList<Int>()
        val left = set.indices.toMutableSet()
        var cur = set.indexOfFirst { it.symbol == "BTC" }.takeIf { it >= 0 } ?: 0
        while (true) {
            order += cur; left -= cur
            cur = left.maxByOrNull { raw[cur][it]?.corr ?: -2.0 } ?: break
        }
        val cells = Array(order.size) { a -> Array(order.size) { b -> raw[order[a]][order[b]] } }
        matrix = Matrix(order.map { set[it] }, order.map { rets[it] }, cells)
        loading = false
    }

    if (adding) MarketPicker(repo, markets, classes, onDismiss = { adding = false }) { m ->
        adding = false
        if (m.marketId !in extraIds) { extraIds = extraIds + m.marketId; repo.prefs.corrExtra = extraIds }
    }
    LazyColumn(Modifier.fillMaxSize().background(C.bg), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Universe.entries.forEach { u -> Seg(u.label, universe == u, C.accent) { universe = u; repo.prefs.corrSet = u.name } }
                }
            }
            // Your own assets, added to whichever preset is on.
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("+ Asset", color = C.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, C.accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .clickable { adding = true }.padding(horizontal = 10.dp, vertical = 5.dp))
                added.forEach { m ->
                    Text(m.symbol + "  ✕", color = C.text, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(C.panel2)
                            .clickable { extraIds = extraIds - m.marketId; repo.prefs.corrExtra = extraIds }.padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
            LookbackRow(window) { w -> window = w; repo.prefs.corrWindow = w.name }
        }
        val mx = matrix
        item { Title("Correlation", if (loading) "loading…" else "do they move together?") }
        if (mx == null) item { Text(if (loading) "Loading candles…" else "Not enough markets", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
        else {
            item { Heatmap(mx, picked) { picked = it } }
            picked?.let { (i, j) -> item { PairDetail(mx, i, j, window) } }
            item { PairLists(mx) { picked = it } }
            item { PositionRisk(repo, mx, window) }
            item { Title("Volatility & sizing", "vs BTC") }
            item { VolBeta(mx, window, onOpenMarket) }
        }
        item { Title("Movers", "4h · 24h · 7d") }
        item { Movers(repo, markets, stats, extras, classes, onOpenMarket) }
        item { Title("Funding & OI", "extremes now") }
        item { FundingOi(markets, stats, extras, onOpenMarket) }
    }
}

/** Lookback chips (how much history) with the candle size they're measured on. */
@Composable
internal fun LookbackRow(window: Window, onPick: (Window) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Lookback", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(end = 2.dp))
        Window.entries.forEach { w -> Seg(w.label, window == w, C.accent) { onPick(w) } }
        Spacer(Modifier.weight(1f))
        Text(window.desc.substringAfter("· "), color = C.muted, fontSize = 11.sp)
    }
}

@Composable
internal fun Title(text: String, sub: String) {
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(sub, color = C.muted, fontSize = 11.sp)
    }
}

@Composable
internal fun Card(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.panel).padding(12.dp)) { content() }
}

/** Correlation colour: amber for moving together, cyan for opposite, dark near zero. */
/**
 * Sign keeps its colour (amber positive, cyan negative). Strength is stretched over this set's
 * own range of |ρ|, so differences show even when everything is correlated.
 */
private fun corrColor(c: Double?, lo: Double, hi: Double): Color {
    if (c == null) return C.panel2
    val top = maxOf(kotlin.math.abs(lo), kotlin.math.abs(hi))
    val bottom = if (lo >= 0) lo else if (hi <= 0) -hi else 0.0
    val f = if (top > bottom) ((kotlin.math.abs(c) - bottom) / (top - bottom)).toFloat().coerceIn(0f, 1f) else 1f
    return lerp(C.panel2, if (c >= 0) POS_C else NEG_C, 0.25f + 0.75f * f)
}

@Composable
private fun Heatmap(mx: Matrix, picked: Pair<Int, Int>?, onPick: (Pair<Int, Int>) -> Unit) {
    val n = mx.markets.size
    val off = (0 until n).flatMap { i -> (0 until i).mapNotNull { j -> mx.cells[i][j]?.corr } }
    val lo = off.minOrNull() ?: -1.0
    val hi = off.maxOrNull() ?: 1.0
    Card {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val label = 46.dp
            val cell = (maxWidth - label) / n
            val showNum = cell >= 24.dp
            // Lower triangle only: each pair once, with the symbol on the diagonal.
            Column {
                for (i in 0 until n) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(mx.markets[i].symbol.take(6), color = C.text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.width(label))
                    for (j in 0 until n) {
                        if (j > i) { Spacer(Modifier.width(cell)); continue }
                        val c = mx.cells[i][j]?.corr
                        val on = picked == (i to j) || picked == (j to i)
                        val bg = if (i == j) Color.Transparent else corrColor(c, lo, hi)
                        Box(
                            Modifier.width(cell).aspectRatio(1f).padding(1.dp).clip(RoundedCornerShape(4.dp)).background(bg)
                                .then(if (on) Modifier.border(2.dp, C.text, RoundedCornerShape(4.dp)) else Modifier)
                                .clickable(enabled = i != j) { onPick(i to j) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (i == j) Text(mx.markets[i].symbol.take(4), color = C.muted, fontSize = 8.sp, maxLines = 1)
                            else if (showNum && c != null) Text(String.format(Locale.US, "%.2f", c).replace("0.", "."),
                                style = num.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = onColor(bg)))
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(String.format(Locale.US, "%.2f", lo), style = num.copy(fontSize = 9.sp, color = C.muted))
            Box(Modifier.padding(horizontal = 6.dp).weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(if (lo >= 0) listOf(lerp(C.panel2, POS_C, 0.25f), POS_C) else if (hi <= 0) listOf(NEG_C, lerp(C.panel2, NEG_C, 0.25f)) else listOf(NEG_C, C.panel2, POS_C))))
            Text(String.format(Locale.US, "%.2f", hi), style = num.copy(fontSize = 9.sp, color = C.muted))
        }
        Text("Amber = same bet · cyan = natural hedge · tap a pair", color = C.muted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Most and least correlated pairs, as tappable rows. */
@Composable
private fun PairLists(mx: Matrix, onPick: (Pair<Int, Int>) -> Unit) {
    val n = mx.markets.size
    val pairs = (0 until n).flatMap { i -> (0 until i).mapNotNull { j -> mx.cells[i][j]?.let { Triple(i, j, it.corr) } } }
    if (pairs.size < 2) return
    Card {
        Row {
            Column(Modifier.weight(1f)) {
                Text("Same bet · don't stack", color = C.muted, fontSize = 11.sp)
                pairs.sortedByDescending { it.third }.take(4).forEach { (i, j, c) -> PairLine(mx, i, j, c) { onPick(i to j) } }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Spread risk", color = C.muted, fontSize = 11.sp)
                pairs.sortedBy { it.third }.take(4).forEach { (i, j, c) -> PairLine(mx, i, j, c) { onPick(i to j) } }
            }
        }
    }
}

@Composable
private fun PairLine(mx: Matrix, i: Int, j: Int, c: Double, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${mx.markets[i].symbol} / ${mx.markets[j].symbol}", color = C.text, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Text(String.format(Locale.US, "%.2f", c), style = num.copy(fontSize = 12.sp, color = if (c >= 0) POS_C else NEG_C))
    }
}

/**
 * One pair up close: both prices rebased to 0% over the window, rolling
 * correlation over the last ~day of bars, and beta of the first to the second.
 */
@Composable
private fun PairDetail(mx: Matrix, i: Int, j: Int, window: Window) {
    val a = mx.ret[i]; val b = mx.ret[j]
    val ca = mx.markets[i]; val cb = mx.markets[j]
    val c = mx.cells[i][j]
    val w = maxOf(12, (window.perDay).toInt().coerceAtLeast(12))
    // Computed once per pair; the screen recomposes every second on live stats.
    val (keys, series, rolling) = androidx.compose.runtime.remember(mx, i, j, w) {
        val keys = a.keys.filter { it in b }.sorted()
        // Cumulative returns in %.
        var sa = 0.0; var sb = 0.0
        val pa = keys.map { sa += a[it]!!; (kotlin.math.exp(sa) - 1) * 100 }
        val pb = keys.map { sb += b[it]!!; (kotlin.math.exp(sb) - 1) * 100 }
        val rolling = keys.indices.map { k ->
            if (k < w) null else {
                val sl = keys.subList(k - w, k)
                pair(sl.associateWith { a[it]!! }, sl.associateWith { b[it]!! })?.corr
            }
        }
        Triple(keys, pa to pb, rolling)
    }
    val (pa, pb) = series
    val vb = mx.cells[j][j]?.cov
    val beta = if (c != null && vb != null && vb > 0) c.cov / vb else null
    val colA = C.accent; val colB = Color(0xFF60A5FA)
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ca.symbol, color = colA, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("  ×  ", color = C.muted, fontSize = 14.sp)
            Text(cb.symbol, color = colB, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(c?.let { String.format(Locale.US, "ρ %.2f", it.corr) } ?: "—", style = num.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold))
        }
        Text(listOfNotNull(
            beta?.let { String.format(Locale.US, "β %.2f (%s moves %.2f%% per 1%% of %s)", it, ca.symbol, it, cb.symbol) },
            c?.let { "${it.n} bars" },
        ).joinToString(" · "), style = num.copy(fontSize = 10.sp, color = C.muted), modifier = Modifier.padding(top = 2.dp))
        if (keys.size > 2) {
            val lo = minOf(pa.min(), pb.min()); val hi = maxOf(pa.max(), pb.max())
            androidx.compose.foundation.Canvas(Modifier.padding(top = 8.dp).fillMaxWidth().height(110.dp)) {
                val span = (hi - lo).takeIf { it > 0 } ?: 1.0
                fun y(v: Double) = ((hi - v) / span * size.height).toFloat()
                fun x(k: Int) = k.toFloat() / (keys.size - 1) * size.width
                drawLine(C.line, androidx.compose.ui.geometry.Offset(0f, y(0.0)), androidx.compose.ui.geometry.Offset(size.width, y(0.0)), 1f)
                for ((series, col) in listOf(pa to colA, pb to colB)) {
                    val path = androidx.compose.ui.graphics.Path()
                    series.forEachIndexed { k, v -> if (k == 0) path.moveTo(x(k), y(v)) else path.lineTo(x(k), y(v)) }
                    drawPath(path, col, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                }
            }
            Row {
                Text(String.format(Locale.US, "%s %+.2f%%", ca.symbol, pa.last()), style = num.copy(fontSize = 11.sp, color = colA), modifier = Modifier.weight(1f))
                Text(String.format(Locale.US, "%s %+.2f%%", cb.symbol, pb.last()), style = num.copy(fontSize = 11.sp, color = colB))
            }
            // Rolling correlation: is the relationship stable or breaking down?
            val roll = rolling.filterNotNull()
            if (roll.size > 2) {
                Text("Rolling ρ (${w} bars)", color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                androidx.compose.foundation.Canvas(Modifier.padding(top = 4.dp).fillMaxWidth().height(48.dp)) {
                    fun y(v: Double) = ((1 - v) / 2 * size.height).toFloat()
                    drawLine(C.line, androidx.compose.ui.geometry.Offset(0f, y(0.0)), androidx.compose.ui.geometry.Offset(size.width, y(0.0)), 1f)
                    val path = androidx.compose.ui.graphics.Path()
                    roll.forEachIndexed { k, v -> val xx = k.toFloat() / (roll.size - 1) * size.width; if (k == 0) path.moveTo(xx, y(v)) else path.lineTo(xx, y(v)) }
                    drawPath(path, C.text, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                }
                Text(String.format(Locale.US, "now %.2f · min %.2f · max %.2f", roll.last(), roll.min(), roll.max()), style = num.copy(fontSize = 10.sp, color = C.muted))
            }
            // What the pair means for a trade.
            c?.let { cc ->
                val (tag, col, line) = when {
                    cc.corr >= 0.7 -> Triple("Same bet", C.down, "Long both = double risk · long one, short the other = hedged pair")
                    cc.corr >= 0.4 -> Triple("Linked", C.accent, "Partly the same bet: size them as one")
                    cc.corr > -0.4 -> Triple("Independent", C.up, "Holding both spreads risk")
                    else -> Triple("Opposite", POS_C, "Same-side positions offset each other")
                }
                Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.bg).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Tag(tag, col)
                        Spacer(Modifier.width(8.dp))
                        Text(line, color = C.text, fontSize = 12.sp)
                    }
                    beta?.takeIf { cc.corr >= 0.4 }?.let { b ->
                        Info("Hedge \$1,000 ${ca.symbol} with", "${fmtUsd(1000 * b)} ${cb.symbol}")
                    }
                    // Short rolling windows swing a lot; what matters is whether the recent link is weaker than the whole window's.
                    if (roll.size >= 8) {
                        val recent = roll.takeLast(roll.size / 4).average()
                        val weak = recent < cc.corr - 0.25
                        Info("Lately", if (weak) String.format(Locale.US, "Weakening (ρ %.2f) · hedge less reliable", recent) else String.format(Locale.US, "Holding (ρ %.2f)", recent), if (weak) C.down else C.up)
                    }
                }
                if (cc.corr >= 0.4 && ca.tradingEnabled && cb.tradingEnabled) SplitPairButton(ca, cb, Modifier.padding(top = 8.dp).fillMaxWidth()) {
                    PairPanel.open = PairRequest(ca.marketId, cb.marketId, true, window.name)
                }
            }
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
                Text("Bad-day loss (1 in 20)", color = C.muted, fontSize = 11.sp)
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
        val share = room?.takeIf { it > 0 }?.let { varDay / it * 100 }
        if (share != null) Text(
            when {
                share > 60 -> "Too big for your room: cut size or hedge"
                share > 30 -> "Elevated: don't add correlated risk"
                else -> "Room to add"
            },
            color = when { share > 60 -> C.down; share > 30 -> C.accent; else -> C.up }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp),
        )
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
                Tag(if (eff > 0) "Stacked · one bigger bet" else "Hedged · offset", if (eff > 0) C.down else C.up)
            }
        }
    }
}

@Composable
private fun VolBeta(mx: Matrix, window: Window, onOpenMarket: (String) -> Unit) {
    val bi = mx.markets.indexOfFirst { it.symbol == "BTC" }
    val bVar = if (bi >= 0) mx.cells[bi][bi]?.cov else null
    // Typical one-day move (1σ) in %, and the size that carries the same risk as $1,000 of BTC.
    fun dayMove(i: Int) = mx.cells[i][i]?.cov?.let { sqrt(it * window.perDay) * 100 }
    val btcDay = if (bi >= 0) dayMove(bi) else null
    Card {
        Row(Modifier.padding(bottom = 4.dp)) {
            Text("Market", color = C.muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("Normal day", color = C.muted, fontSize = 10.sp, textAlign = TextAlign.End, modifier = Modifier.width(68.dp))
            Text("β BTC", color = C.muted, fontSize = 10.sp, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
            Text("= \$1k BTC", color = C.muted, fontSize = 10.sp, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
        }
        mx.markets.indices.sortedByDescending { mx.cells[it][it]?.cov ?: 0.0 }.forEach { i ->
            val m = mx.markets[i]
            val day = dayMove(i)
            val c = if (bi >= 0) mx.cells[i][bi] else null
            val beta = if (c != null && bVar != null && bVar > 0) c.cov / bVar else null
            val same = if (day != null && day > 0 && btcDay != null) 1000 * btcDay / day else null
            Row(Modifier.fillMaxWidth().clickable(enabled = m.tradingEnabled) { onOpenMarket(m.marketId) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.symbol, color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(day?.let { String.format(Locale.US, "±%.2f%%", it) } ?: "—", style = num.copy(fontSize = 12.sp), textAlign = TextAlign.End, modifier = Modifier.width(68.dp))
                Text(beta?.let { String.format(Locale.US, "%.2f", it) } ?: "—", style = num.copy(fontSize = 12.sp, color = C.muted), textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
                Text(same?.let { "$" + String.format(Locale.US, "%,.0f", it) } ?: "—", style = num.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
            }
        }
        Text("Last column: size with the same risk as \$1,000 BTC · β 1.5 = moves 1.5% per 1% BTC", color = C.muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
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
            // Positive funding: longs pay shorts, so the long side is crowded (and vice versa).
            Row(Modifier.fillMaxWidth().clickable { onOpenMarket(m.marketId) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.symbol, color = C.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (a != null && abs(a) >= 20) { Tag(if (a > 0) "Longs crowded · shorts paid" else "Shorts crowded · longs paid", if (a > 0) C.down else C.up); Spacer(Modifier.width(8.dp)) }
                Text(a?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "—", style = num.copy(fontSize = 12.sp, color = pnlColor(a)))
            }
        }
        Text("OI change 24h", color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        oiUp.forEach { m ->
            val o = extras[m.marketId]?.oi24h
            val px = stats[m.feedKey]?.change24hPct
            // OI and price together say who is entering or leaving.
            val flow = if (o == null || px == null) null else when {
                o > 0 && px >= 0 -> "New longs" to C.up
                o > 0 -> "New shorts" to C.down
                px >= 0 -> "Shorts covering" to C.accent
                else -> "Longs exiting" to C.accent
            }
            Row(Modifier.fillMaxWidth().clickable { onOpenMarket(m.marketId) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.symbol, color = C.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                flow?.let { (t, c) -> Tag(t, c); Spacer(Modifier.width(8.dp)) }
                Text("px " + fmtPct(px), style = num.copy(fontSize = 11.sp, color = C.muted))
                Spacer(Modifier.width(8.dp))
                Text("OI " + fmtPct(o), style = num.copy(fontSize = 12.sp, color = pnlColor(o)))
            }
        }
    }
}
