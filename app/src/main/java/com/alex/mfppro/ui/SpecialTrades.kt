package com.alex.mfppro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alex.mfppro.data.AssetClasses
import com.alex.mfppro.data.Market
import com.alex.mfppro.data.OSLO
import com.alex.mfppro.data.PairStat
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.TradeResult
import com.alex.mfppro.data.legSplit
import com.alex.mfppro.data.pairStat
import com.alex.mfppro.data.riskOf
import com.alex.mfppro.data.roundSize
import com.alex.mfppro.data.tradable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.math.roundToInt
import java.util.UUID
import kotlin.math.abs

/** A pair to open: legs A and B, which one is long, and the window its statistics come from. */
data class PairRequest(val aId: String = "", val bId: String = "", val longA: Boolean = true, val window: String = "H4")

/** The pair order panel. MainActivity draws it over the screen it's opened from. */
object PairPanel {
    var open by mutableStateOf<PairRequest?>(null)
}

private enum class Scan(val label: String, val n: Int) { T20("Top 20", 20), T40("Top 40", 40) }

private class Found(val a: Market, val b: Market, val s: PairStat)

/** z-score where a pair is a trade, and where it's worth watching. */
private const val Z_ENTER = 2.0
private const val Z_WATCH = 1.5
private const val Z_STOP = 3.5

private fun hours(bars: Double, w: Window) = bars * 24 / w.perDay
private fun fmtHours(h: Double) = if (h < 48) String.format(Locale.US, "%.0fh", h) else String.format(Locale.US, "%.1fd", h / 24)
private fun fmtZ(z: Double) = String.format(Locale.US, "%+.2f", z)

private suspend fun loadStat(repo: Repo, a: Market, b: Market, w: Window): PairStat? = coroutineScope {
    val ca = async { runCatching { repo.market.candleHistory(a.provider, a.coin, w.interval, w.bars) }.getOrDefault(emptyList()) }
    val cb = async { runCatching { repo.market.candleHistory(b.provider, b.coin, w.interval, w.bars) }.getOrDefault(emptyList()) }
    pairStat(ca.await(), cb.await())
}

/**
 * Special trades: pair-trade scanner over the busiest crypto markets. Every pair is fitted
 * on log prices (hedge ratio β); pairs that correlate, revert and sit far from their mean are
 * suggested as long the cheap leg, short the rich one. Tap a pair for its spread, then open it.
 */
@Composable
fun SpecialTrades(repo: Repo) {
    val markets by repo.markets.collectAsStateWithLifecycle()
    val stats by repo.market.stats.collectThrottled(1000)
    val classes by repo.classes.collectAsStateWithLifecycle()
    var scan by remember { mutableStateOf(Scan.entries.firstOrNull { it.name == repo.prefs.pairScan } ?: Scan.T20) }
    var window by remember { mutableStateOf(Window.entries.firstOrNull { it.name == repo.prefs.pairWindow } ?: Window.H4) }
    var showAll by remember { mutableStateOf(false) }

    val crypto = markets.filter { AssetClasses.classOf(it, classes) == "Crypto" }.sortedByDescending { stats[it.feedKey]?.dayNtlVlm ?: 0.0 }
    // Same freeze as the correlation view: live volume ranks would restart the scan every second.
    val haveVol = markets.isNotEmpty() && markets.count { stats[it.feedKey]?.dayNtlVlm != null } >= markets.size * 0.8
    var frozen by remember(scan) { mutableStateOf<List<Market>?>(null) }
    var frozeWithVol by remember(scan) { mutableStateOf(false) }
    val set = crypto.take(scan.n)
    if (frozen == null || frozen!!.size < set.size || (haveVol && !frozeWithVol)) { frozen = set; if (haveVol) frozeWithVol = true }
    val setKey = frozen.orEmpty().map { it.marketId }.sorted().joinToString(",")

    var found by remember { mutableStateOf<List<Found>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Found?>(null) }
    LaunchedEffect(setKey, window) {
        val ms = frozen.orEmpty()
        if (ms.size < 2) return@LaunchedEffect
        loading = true; picked = null
        val candles = coroutineScope {
            ms.map { m -> async { runCatching { repo.market.candleHistory(m.provider, m.coin, window.interval, window.bars) }.getOrDefault(emptyList()) } }.awaitAll()
        }
        found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            buildList {
                for (i in ms.indices) for (j in i + 1 until ms.size) {
                    val s = pairStat(candles[i], candles[j]) ?: continue
                    if (s.tradable(window.bars)) add(Found(ms[i], ms[j], s))
                }
            }.sortedByDescending { abs(it.s.z) }
        }
        loading = false
    }

    val all = found.orEmpty()
    val shown = if (showAll) all else all.filter { abs(it.s.z) >= Z_WATCH }
    LazyColumn(Modifier.fillMaxSize().background(C.bg), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Scan.entries.forEach { s -> Seg(s.label, scan == s, C.accent) { scan = s; repo.prefs.pairScan = s.name } }
                Spacer(Modifier.width(6.dp))
                Text("+ Pair", color = C.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, C.accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .clickable { PairPanel.open = PairRequest(window = window.name) }.padding(horizontal = 10.dp, vertical = 5.dp))
            }
            LookbackRow(window) { w -> window = w; repo.prefs.pairWindow = w.name }
        }
        item { Title("Pair trades", if (loading) "loading…" else "${all.size} pairs fit") }
        item {
            Text("Pairs that move together (ρ ≥ 0.60) and keep snapping back to their usual gap. Ranked by how stretched that gap is now (z). " +
                "Long the leg that's cheap against the other, short the rich one, exit as z returns to 0.",
                color = C.muted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
        }
        if (found == null || (loading && all.isEmpty())) item { Text("Loading candles…", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
        else {
            item {
                Card {
                    Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Seg("Signals", !showAll, C.accent) { showAll = false }
                        Spacer(Modifier.width(4.dp))
                        Seg("All", showAll, C.accent) { showAll = true }
                        Spacer(Modifier.weight(1f))
                        Text("z", color = C.muted, fontSize = 10.sp, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
                    }
                    if (shown.isEmpty()) Text("No pair beyond ±${String.format(Locale.US, "%.1f", Z_WATCH)} z", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                    shown.take(25).forEach { f ->
                        PairRow(f, window, picked === f) { picked = if (picked === f) null else f }
                        // The pair opens up right under its row.
                        if (picked === f) SpreadDetail(f, window)
                    }
                }
            }
        }
    }
}

@Composable
private fun PairRow(f: Found, w: Window, on: Boolean, onClick: () -> Unit) {
    val s = f.s
    val (long, short) = if (s.longA) f.a to f.b else f.b to f.a
    val z = abs(s.z)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (on) C.panel2 else C.panel).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${f.a.symbol} / ${f.b.symbol}", color = C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Row {
                Text("Long ${long.symbol}", color = C.up, fontSize = 11.sp)
                Text(" · ", color = C.muted, fontSize = 11.sp)
                Text("Short ${short.symbol}", color = C.down, fontSize = 11.sp)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(String.format(Locale.US, "ρ %.2f · β %.2f", s.corr, s.beta), style = num.copy(fontSize = 10.sp, color = C.muted))
            Text("½ " + fmtHours(hours(s.halfLife ?: 0.0, w)), style = num.copy(fontSize = 10.sp, color = C.muted))
        }
        Text(fmtZ(s.z), style = num.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (z >= Z_STOP) C.down else if (z >= Z_ENTER) C.accent else C.text),
            textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
        Spacer(Modifier.width(6.dp))
        // Beyond the stop band the link itself may have broken (one leg moving on its own news).
        when {
            z >= Z_STOP -> Tag("Past stop", C.down)
            z >= Z_ENTER -> Tag("Enter", C.accent)
            z >= Z_WATCH -> Tag("Watch", C.muted)
            else -> Tag("—", C.muted)
        }
    }
}

/** Why the pair is suggested, its spread or price-vs-fair chart, and the price levels; opens the pair panel. */
@Composable
private fun SpreadDetail(f: Found, w: Window) {
    val s = f.s
    val (long, short) = if (s.longA) f.a to f.b else f.b to f.a
    Column(Modifier.padding(top = 2.dp, bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.bg).padding(10.dp)) {
        PairWhy(f.a, f.b, s, w)
        PairCharts(f.a, f.b, s)
        PairLevels(f.a, f.b, s, s.longA)
        SplitPairButton(long, short, Modifier.padding(top = 8.dp).fillMaxWidth()) {
            PairPanel.open = PairRequest(f.a.marketId, f.b.marketId, s.longA, w.name)
        }
    }
}

/** Ticker as the venues write it: ETHUSDT. */
internal fun ticker(m: Market) = m.symbol.uppercase() + "USDT"

/** One button in two halves: the long leg in green, the short leg in red. Either half fires [onClick]. */
@Composable
internal fun SplitPairButton(long: Market?, short: Market?, modifier: Modifier = Modifier, enabled: Boolean = true, label: String? = null, onClick: () -> Unit) {
    val a = if (enabled) 1f else 0.35f
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(10.dp))) {
            for ((m, isLong) in listOf(long to true, short to false)) {
                val col = (if (isLong) C.up else C.down).copy(alpha = a)
                Column(
                    Modifier.weight(1f).fillMaxSize().background(col).clickable(enabled = enabled, onClick = onClick),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Text(if (isLong) "LONG" else "SHORT", color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(m?.let(::ticker) ?: "—", color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                if (isLong) Spacer(Modifier.width(2.dp))
            }
        }
        label?.let { Text(it, color = C.muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 3.dp)) }
    }
}

/** The case for the trade in one sentence: who is cheap, by how much, how fast it usually reverts. */
@Composable
private fun PairWhy(a: Market, b: Market, s: PairStat, w: Window) {
    val (cheap, rich) = if (s.longA) a to b else b to a
    Text(
        String.format(Locale.US, "%s is %.2fσ cheap against %s versus their usual gap over the %s (ρ %.2f). " +
            "A gap like this has typically halved in %s. Gap to close: %.2f%%.",
            cheap.symbol, abs(s.z), rich.symbol, w.desc.substringBefore(" ·"), s.corr,
            fmtHours(hours(s.halfLife ?: 0.0, w)), abs(s.z) * s.sd * 100),
        color = C.text, fontSize = 12.sp, lineHeight = 17.sp,
    )
    if (abs(s.z) >= Z_STOP) Text(
        String.format(Locale.US, "Past the ±%.1f stop: the gap is wider than this pair normally allows. Often one leg moved on its own news and the link has broken. Wait for z to come back inside ±%.1f.", Z_STOP, Z_STOP),
        color = C.down, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp),
    )
}

/** Spread z with entry/stop bands, or A's price against the price B implies; either can be shown large. */
@Composable
private fun PairCharts(a: Market, b: Market, s: PairStat) {
    var price by remember { mutableStateOf(false) }
    var big by remember { mutableStateOf(false) }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Seg("Spread z", !price, C.accent) { price = false }
        Spacer(Modifier.width(4.dp))
        Seg("${a.symbol} vs fair", price, C.accent) { price = true }
        Spacer(Modifier.weight(1f))
        Seg(if (big) "Smaller" else "Bigger", big, C.accent) { big = !big }
    }
    val h = if (big) 260.dp else 120.dp
    if (price) {
        FairChart(s, Modifier.padding(top = 6.dp).fillMaxWidth().height(h))
        Row(Modifier.padding(top = 2.dp)) {
            Text("${a.symbol} ${fmtPrice(s.lastA)}", style = num.copy(fontSize = 10.sp, color = C.text), modifier = Modifier.weight(1f))
            Text("fair from ${b.symbol} ${fmtPrice(s.fairA.last())}", style = num.copy(fontSize = 10.sp, color = C.accent))
        }
    } else {
        ZChart(s.zs, Modifier.padding(top = 6.dp).fillMaxWidth().height(h))
        Row(Modifier.padding(top = 2.dp)) {
            Text("±${String.format(Locale.US, "%.1f", Z_ENTER)} entry", style = num.copy(fontSize = 10.sp, color = C.accent), modifier = Modifier.weight(1f))
            Text("±${String.format(Locale.US, "%.1f", Z_STOP)} stop", style = num.copy(fontSize = 10.sp, color = C.down))
        }
    }
}

@Composable
private fun FairChart(s: PairStat, modifier: Modifier) {
    val pa = s.pricesA; val fa = s.fairA
    if (pa.size < 2) return
    val lo = minOf(pa.min(), fa.min()); val hi = maxOf(pa.max(), fa.max())
    val text = C.text; val accent = C.accent
    Canvas(modifier) {
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        fun y(v: Double) = ((hi - v) / span * size.height).toFloat()
        fun x(k: Int) = k.toFloat() / (pa.size - 1) * size.width
        for ((series, col, wd) in listOf(Triple(fa, accent, 1.5f), Triple(pa, text, 2f))) {
            val path = Path()
            series.forEachIndexed { k, v -> if (k == 0) path.moveTo(x(k), y(v)) else path.lineTo(x(k), y(v)) }
            drawPath(path, col, style = Stroke(wd))
        }
        drawLine(accent.copy(alpha = 0.5f), Offset(size.width, y(pa.last())), Offset(size.width, y(fa.last())), 3.dp.toPx())
    }
}

/**
 * Price levels: where each leg would be at the mean (target) and at the stop band, if the other
 * leg stood still. The real move is usually some of each.
 */
@Composable
private fun PairLevels(a: Market, b: Market, s: PairStat, longA: Boolean) {
    // The stop is the spread stretching further the way it already leans.
    val zStop = if (s.z >= 0) Z_STOP else -Z_STOP
    Column(Modifier.padding(top = 8.dp)) {
        Row {
            Text("Leg alone", color = C.muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("Now", color = C.muted, fontSize = 10.sp, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
            Text("Target z 0", color = C.muted, fontSize = 10.sp, textAlign = TextAlign.End, modifier = Modifier.width(84.dp))
            Text("Stop", color = C.muted, fontSize = 10.sp, textAlign = TextAlign.End, modifier = Modifier.width(84.dp))
        }
        for ((m, isA) in listOf(a to true, b to false)) {
            val long = if (isA) longA else !longA
            val now = if (isA) s.lastA else s.lastB
            val tgt = if (isA) s.priceA(0.0) else s.priceB(0.0)
            val stp = if (abs(s.z) < Z_STOP) (if (isA) s.priceA(zStop) else s.priceB(zStop)) else null
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text((if (long) "Long " else "Short ") + m.symbol, color = if (long) C.up else C.down, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(fmtPrice(now), style = num.copy(fontSize = 11.sp), textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
                Column(Modifier.width(84.dp), horizontalAlignment = Alignment.End) {
                    Text(fmtPrice(tgt), style = num.copy(fontSize = 11.sp, color = C.up))
                    Text(fmtPct((tgt / now - 1) * 100), style = num.copy(fontSize = 9.sp, color = C.muted))
                }
                Column(Modifier.width(84.dp), horizontalAlignment = Alignment.End) {
                    Text(stp?.let { fmtPrice(it) } ?: "—", style = num.copy(fontSize = 11.sp, color = C.down))
                    Text(stp?.let { fmtPct((it / now - 1) * 100) } ?: "", style = num.copy(fontSize = 9.sp, color = C.muted))
                }
            }
        }
    }
}

@Composable
private fun ZChart(zs: List<Double>, modifier: Modifier) {
    if (zs.size < 2) return
    val lim = maxOf(Z_STOP + 0.5, zs.maxOf { abs(it) })
    val line = C.line; val accent = C.accent; val down = C.down; val text = C.text
    Canvas(modifier) {
        fun y(v: Double) = ((lim - v) / (2 * lim) * size.height).toFloat()
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        drawLine(line, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1f)
        for (b in listOf(Z_ENTER, -Z_ENTER)) drawLine(accent.copy(alpha = 0.6f), Offset(0f, y(b)), Offset(size.width, y(b)), 1f, pathEffect = dash)
        for (b in listOf(Z_STOP, -Z_STOP)) drawLine(down.copy(alpha = 0.6f), Offset(0f, y(b)), Offset(size.width, y(b)), 1f, pathEffect = dash)
        val path = Path()
        zs.forEachIndexed { k, v -> val x = k.toFloat() / (zs.size - 1) * size.width; if (k == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v)) }
        drawPath(path, text, style = Stroke(1.5f))
        drawCircle(accent, 3.dp.toPx(), Offset(size.width, y(zs.last())))
    }
}

/**
 * Pair order panel: two market orders, long one leg and short the other, sized by the hedge
 * ratio (or dollar neutral). Leg A goes first; if leg B is rejected, A can be closed from here.
 */
@Composable
fun PairTicket(repo: Repo, request: PairRequest, onDismiss: () -> Unit) {
    val markets by repo.markets.collectAsStateWithLifecycle()
    val classes by repo.classes.collectAsStateWithLifecycle()
    val marks by repo.market.marks.collectThrottled(500)
    val stats by repo.market.stats.collectThrottled(1000)
    val policies by repo.policies.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val window = Window.entries.firstOrNull { it.name == request.window } ?: Window.H4

    var aId by remember { mutableStateOf(request.aId) }
    var bId by remember { mutableStateOf(request.bId) }
    var longA by remember { mutableStateOf(request.longA) }
    var picking by remember { mutableStateOf<Char?>(null) }
    val a = markets.firstOrNull { it.marketId == aId }
    val b = markets.firstOrNull { it.marketId == bId }

    var stat by remember { mutableStateOf<PairStat?>(null) }
    LaunchedEffect(aId, bId) {
        stat = null
        if (a != null && b != null && a.marketId != b.marketId) stat = loadStat(repo, a, b, window)
    }

    val acc = rememberTradeAccount(repo)
    val risk = acc?.let { riskOf(it, marks) }
    val heldA = a?.let { m -> acc?.positions?.values?.firstOrNull { it.inMarket(m) } }
    val heldB = b?.let { m -> acc?.positions?.values?.firstOrNull { it.inMarket(m) } }
    val pol = acc?.account?.id?.let { policies[it] }
    val maxLev = minOf(a?.let { com.alex.mfppro.data.maxLeverage(it, acc?.account, pol) } ?: 1, b?.let { com.alex.mfppro.data.maxLeverage(it, acc?.account, pol) } ?: 1)
    var lev by remember(aId, bId, maxLev) { mutableStateOf(minOf(repo.prefs.leverage(aId, 5), maxLev).coerceAtLeast(1)) }
    var isolated by remember { mutableStateOf(repo.prefs.marginMode == "isolated") }
    var beta by remember { mutableStateOf(true) }
    var usdText by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // Leg A filled but leg B didn't: A is open on its own.
    var orphan by remember { mutableStateOf<String?>(null) }

    val hedge = if (beta) stat?.beta ?: 1.0 else 1.0
    val gross = usdText.toDoubleOrNull() ?: 0.0
    val (usdA, usdB) = legSplit(gross, hedge)
    // Live marks only cover subscribed markets; the stats feed has every market's mark.
    val pxA = a?.let { marks[it.feedKey] ?: stats[it.feedKey]?.markPx }; val pxB = b?.let { marks[it.feedKey] ?: stats[it.feedKey]?.markPx }
    val sizeA = if (a != null && pxA != null && pxA > 0) roundSize(usdA / pxA, a) else 0.0
    val sizeB = if (b != null && pxB != null && pxB > 0) roundSize(usdB / pxB, b) else 0.0
    val margin = acc?.positions?.values?.sumOf { it.marginAt() } ?: 0.0
    val available = ((acc?.account?.balance ?: 0.0) - margin).coerceAtLeast(0.0)
    val capLeft = acc?.let { exposureLeft(policies[it.account.id], it, marks, null) }
    val maxGross = minOf(available * lev, capLeft ?: Double.MAX_VALUE)
    val locked = repo.prefs.locked()
    val problem = when {
        acc == null -> "Pick an account"
        a == null || b == null -> "Pick both legs"
        a.marketId == b.marketId -> "Pick two different markets"
        pxA == null || pxB == null -> "Waiting for prices"
        gross <= 0 -> "Enter a size"
        usdA < a.minNotional || sizeA < a.minSize || sizeA <= 0 -> "${a.symbol} leg below minimum ${fmtUsd(a.minNotional)}"
        usdB < b.minNotional || sizeB < b.minSize || sizeB <= 0 -> "${b.symbol} leg below minimum ${fmtUsd(b.minNotional)}"
        heldA != null && heldA.isLong != longA -> "You hold ${a.symbol} the other way"
        heldB != null && heldB.isLong == longA -> "You hold ${b.symbol} the other way"
        else -> null
    }

    fun leg(m: Market, buy: Boolean, size: Double, px: Double, held: com.alex.mfppro.data.Position?) = buildJsonObject {
        put("client_order_id", "mfppro-pair-" + UUID.randomUUID().toString().take(8))
        put("account_id", acc!!.account.id); put("market_id", m.marketId)
        put("side", if (buy) "buy" else "sell"); put("size", size)
        put("type", "market"); put("expected_price", px)
        put("slippage_tolerance_bps", repo.prefs.slippageBps)
        // A leg added to a position keeps that position's leverage and margin mode.
        put("leverage", held?.leverage?.toInt() ?: lev)
        put("margin_mode", held?.marginMode ?: if (isolated) "isolated" else "cross")
    }

    fun submit() {
        val ma = a ?: return; val mb = b ?: return
        val bodyA = leg(ma, longA, sizeA, pxA!!, heldA); val bodyB = leg(mb, !longA, sizeB, pxB!!, heldB)
        repo.prefs.tradeAccountId = acc!!.account.id
        repo.prefs.setLeverage(ma.marketId, lev); repo.prefs.setLeverage(mb.marketId, lev)
        repo.prefs.marginMode = if (isolated) "isolated" else "cross"
        busy = true; orphan = null
        scope.launch {
            val r1 = repo.placeOrder("Pair ${if (longA) "long" else "short"} ${ma.symbol}", bodyA)
            if (!r1.accepted) { busy = false; return@launch }
            val r2 = repo.placeOrder("Pair ${if (longA) "short" else "long"} ${mb.symbol}", bodyB)
            busy = false
            if (r2.accepted) onDismiss() else orphan = ma.marketId
        }
    }

    if (picking != null) MarketPicker(repo, markets, classes, onDismiss = { picking = null }) { m ->
        if (picking == 'A') aId = m.marketId else bId = m.marketId
        picking = null
    }

    SidePanel(onDismiss) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Pair trade", color = C.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(window.desc, color = C.muted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Clear, "Close", tint = C.muted,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onDismiss).padding(8.dp).size(20.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AccountSwitcher(repo, Modifier.weight(1f))
                risk?.let {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Drawdown left", color = C.muted, fontSize = 10.sp)
                        Text(fmtUsd(it.room), style = num.copy(fontWeight = FontWeight.Bold, color = roomColor(it.roomFraction)))
                    }
                }
            }
            risk?.let { RiskWarning(it) }

            // The two legs; Swap flips which one is long.
            LegRow("A", a, longA, pxA, usdA, Modifier.padding(top = 10.dp)) { picking = 'A' }
            LegRow("B", b, !longA, pxB, usdB, Modifier.padding(top = 6.dp)) { picking = 'B' }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QuickBtn("Swap sides", C.text, Modifier.weight(1f)) { longA = !longA }
                stat?.let { s -> if (s.longA != longA) QuickBtn("Follow z", C.accent, Modifier.weight(1f)) { longA = s.longA } }
            }

            val s0 = stat
            if (s0 == null) {
                if (a != null && b != null && a.marketId != b.marketId) Text("Loading spread…", color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            } else s0.let { s ->
                Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.bg).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Spread z", color = C.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(fmtZ(s.z), style = num.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (abs(s.z) >= Z_ENTER) C.accent else C.text))
                    }
                    if (s.longA != longA) Info("Direction", "Against the spread", C.down)
                    if (a != null && b != null) {
                        Spacer(Modifier.height(6.dp))
                        PairWhy(a, b, s, window)
                        PairCharts(a, b, s)
                        PairLevels(a, b, s, longA)
                    }
                }
            }

            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Hedge", color = C.muted, fontSize = 12.sp, modifier = Modifier.width(60.dp))
                Seg("Beta " + (stat?.let { String.format(Locale.US, "%.2f", it.beta) } ?: "—"), beta, C.accent, enabled = stat != null) { beta = true }
                Spacer(Modifier.width(4.dp))
                Seg("Dollar neutral", !beta || stat == null, C.accent) { beta = false }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Margin", color = C.muted, fontSize = 12.sp, modifier = Modifier.width(60.dp))
                Seg("Cross", !isolated, C.accent) { isolated = false }
                Spacer(Modifier.width(4.dp))
                Seg("Isolated", isolated, C.accent) { isolated = true }
            }
            Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Text("Leverage", color = C.muted, fontSize = 12.sp, modifier = Modifier.width(60.dp))
                listOf(1, 2, 3, 5, 10, 20).filter { it < maxLev }.plus(maxLev).distinct().forEach { l ->
                    Seg("${l}×", l == lev, C.accent) { lev = l }; Spacer(Modifier.width(4.dp))
                }
            }

            NumField("Size, both legs (USD)", usdText, { usdText = it }, Modifier.fillMaxWidth())
            val pct = if (maxGross > 0) (gross / maxGross).coerceIn(0.0, 1.0) else 0.0
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                PctSlider(pct.toFloat(), C.accent, Modifier.weight(1f)) { f -> usdText = String.format(Locale.US, "%.0f", f * maxGross) }
                Text("${(pct * 100).roundToInt()}%", style = num.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
            }
            Text("Buying power ${fmtUsd(maxGross)}", color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repo.prefs.quickSizes.forEach { q -> QuickBtn("$" + String.format(Locale.US, "%,d", q * 2), C.accent, Modifier.weight(1f)) { usdText = (q * 2).toString() } }
            }

            // What the spread is worth: back to the mean, or out to the stop band (spread × leg A dollars).
            val room = risk?.room
            stat?.takeIf { beta && gross > 0 && it.longA == longA }?.let { s ->
                val toMean = usdA * abs(s.z) * s.sd
                val toStop = if (abs(s.z) < Z_STOP) usdA * (Z_STOP - abs(s.z)) * s.sd else null
                Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.bg).padding(10.dp)) {
                    Info("At z 0", fmtUsd(toMean, true), C.up)
                    toStop?.let { Info(String.format(Locale.US, "At z ±%.1f", Z_STOP), fmtUsd(-it, true), C.down) }
                    if (room != null && toStop != null) Info("Room left after stop", fmtUsd(room - toStop), if (room - toStop <= 0) C.down else if (room - toStop < room * 0.25) C.accent else C.up)
                }
            }

            Column(Modifier.padding(vertical = 10.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.bg).padding(10.dp)) {
                Info("Gross notional", fmtUsd(gross))
                Info("Net exposure", fmtUsd(if (longA) usdA - usdB else usdB - usdA, true))
                Info("Margin", fmtUsd(gross / lev))
                Info("Max slippage", String.format(Locale.US, "%.2f%%", repo.prefs.slippageBps / 100.0))
            }

            orphan?.let { id ->
                val m = markets.firstOrNull { it.marketId == id }
                val p = m?.let { mm -> acc?.positions?.values?.firstOrNull { it.inMarket(mm) } }
                Column(Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, C.down, RoundedCornerShape(8.dp)).padding(10.dp)) {
                    Text("Second leg rejected: ${m?.symbol ?: "leg A"} is open alone", color = C.down, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SolidBtn("Close ${m?.symbol ?: ""}", C.down, Modifier.weight(1f), enabled = p != null && !busy, small = true) {
                            val pos = p ?: return@SolidBtn
                            busy = true
                            scope.launch { val r = repo.closePosition(pos); busy = false; if (r is TradeResult.Ok) orphan = null }
                        }
                        SolidBtn("Retry ${b?.symbol ?: ""}", C.accent, Modifier.weight(1f), enabled = b != null && pxB != null && !busy, small = true) {
                            val mb = b ?: return@SolidBtn
                            busy = true
                            scope.launch {
                                val r = repo.placeOrder("Pair ${if (longA) "short" else "long"} ${mb.symbol}", leg(mb, !longA, sizeB, pxB!!, heldB))
                                busy = false
                                if (r is TradeResult.Ok) onDismiss()
                            }
                        }
                    }
                }
            }
            if (locked) Text(
                String.format(Locale.US, "No new trades before %02d:00 Oslo (now %s).", repo.prefs.lockEnd, java.time.LocalTime.now(OSLO).withNano(0).withSecond(0)),
                color = C.down, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Column(Modifier.fillMaxWidth().background(C.panel).padding(horizontal = 14.dp, vertical = 8.dp)) {
            SplitPairButton(
                if (longA) a else b, if (longA) b else a, Modifier.fillMaxWidth(), enabled = problem == null && !busy && !locked && orphan == null,
                label = if (busy) "Sending…" else if (locked) "Locked until ${repo.prefs.lockEnd}:00 Oslo" else if (gross > 0) "${fmtUsd(usdA)} + ${fmtUsd(usdB)} = ${fmtUsd(gross)}" else null,
            ) { if (repo.prefs.confirmOrders) confirm = true else submit() }
            problem?.let { Text(it, color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp)) }
        }
    }

    if (confirm && a != null && b != null) ConfirmDialog(
        "Pair trade", fmtUsd(gross), C.accent,
        listOfNotNull(
            ConfirmRow((if (longA) "Long " else "Short ") + ticker(a), fmtUsd(usdA), if (longA) C.up else C.down),
            ConfirmRow((if (longA) "Short " else "Long ") + ticker(b), fmtUsd(usdB), if (longA) C.down else C.up),
            ConfirmRow("Hedge", if (beta && stat != null) String.format(Locale.US, "β %.2f", hedge) else "Dollar neutral"),
            stat?.let { ConfirmRow("Spread z", fmtZ(it.z)) },
            ConfirmRow("Leverage", "${lev}x ${if (isolated) "isolated" else "cross"}"),
            ConfirmRow("Account", acc?.account?.name ?: ""),
        ),
        confirm = "Send both", onDismiss = { confirm = false },
    ) { confirm = false; submit() }
}

@Composable
private fun LegRow(tag: String, m: Market?, long: Boolean, px: Double?, usd: Double, modifier: Modifier, onPick: () -> Unit) {
    val col = if (long) C.up else C.down
    Row(
        modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(10.dp)).background(C.bg).clickable(onClick = onPick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tag, color = C.muted, fontSize = 11.sp, modifier = Modifier.width(16.dp))
        Text(if (long) "Long" else "Short", color = col, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(48.dp))
        if (m == null) Text("Pick market", color = C.accent, fontSize = 14.sp, modifier = Modifier.weight(1f))
        else {
            Text(m.symbol, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            VenueTag(m.provider)
            Spacer(Modifier.width(6.dp))
            Text(fmtPrice(px), style = num.copy(fontSize = 12.sp, color = C.muted), modifier = Modifier.weight(1f))
        }
        Text(if (usd > 0) fmtUsd(usd) else "", style = num.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = col))
    }
}
