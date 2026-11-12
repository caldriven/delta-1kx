package com.alex.mfppro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alex.mfppro.data.AssetClasses
import com.alex.mfppro.data.Candle
import com.alex.mfppro.data.Market
import com.alex.mfppro.data.MarketExtra
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.Stats
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** Hours of 1h candles: a week, enough for a "normal" volatility and a stable beta. */
private const val BARS = 168
/** "Now" for volatility and volume: the last 12 hours. */
private const val RECENT = 12

/** One coin's short-term picture against BTC and against its own week. */
private class Setup(
    val m: Market,
    val beta: Double,        // moves this much per 1% BTC move
    val rs24: Double,        // 24h return beyond what its beta to BTC explains, %
    val volRatio: Double,    // last 12h volatility ÷ its 7d normal
    val volumeRatio: Double, // last 12h volume ÷ its 7d average 12h
    val chg24: Double,
) {
    val tags = mutableListOf<Pair<String, Color>>()
}

private enum class SetupFilter(val label: String) { LONG("Long setups"), SHORT("Short setups"), BETA("High beta"), COILED("Coiled"), AVOID("Avoid"), ALL("All") }

private fun rets(c: List<Candle>): Map<Long, Double> {
    val out = HashMap<Long, Double>()
    for (i in 1 until c.size) if (c[i - 1].c > 0 && c[i].c > 0) out[c[i].openTime] = ln(c[i].c / c[i - 1].c)
    return out
}

private fun sd(x: List<Double>): Double {
    if (x.size < 3) return 0.0
    val m = x.average()
    return sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1))
}

private fun build(m: Market, c: List<Candle>, btc: Map<Long, Double>, btc24: Double): Setup? {
    if (c.size < 48) return null
    val r = rets(c)
    val times = r.keys.sorted()
    val series = times.map { r[it]!! }
    val base = sd(series)
    if (base <= 0) return null
    val volRatio = sd(series.takeLast(RECENT)) / base
    // Beta on the hours both traded.
    val both = times.filter { it in btc }
    val xs = both.map { btc[it]!! }; val ys = both.map { r[it]!! }
    val mx = xs.average(); val my = ys.average()
    val varX = xs.sumOf { (it - mx) * (it - mx) }
    val beta = if (varX > 0) xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / varX else 1.0
    val last = c.last().c
    val back = c.getOrNull(c.size - 25)?.c ?: c.first().c
    val chg24 = (last / back - 1) * 100
    // Volume in dollars, last 12h against the week's average 12h.
    val usd = c.map { it.v * it.c }
    val recentVol = usd.takeLast(RECENT).sum()
    val avg12 = usd.sum() / usd.size * RECENT
    val volumeRatio = if (avg12 > 0) recentVol / avg12 else 1.0
    return Setup(m, beta, chg24 - beta * btc24, volRatio, volumeRatio, chg24)
}

/**
 * Short-term setups across the busiest crypto: which coins lead BTC with high beta, which are
 * coiling (volatility compressed while volume and open interest build), and which to stay away
 * from (overheated, dead, or lagging BTC). Refreshed every 5 minutes while on screen.
 */
@Composable
internal fun SetupScanner(
    repo: Repo, markets: List<Market>, stats: Map<String, Stats>, extras: Map<String, MarketExtra>,
    classes: Map<String, String>, onOpenMarket: (String) -> Unit,
) {
    val chg1h by repo.chg1h.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(SetupFilter.LONG) }
    var setups by remember { mutableStateOf<List<Setup>?>(null) }
    // Freeze the universe once volume ranks are in, so live re-ranking doesn't restart the scan.
    val crypto = markets.filter { AssetClasses.classOf(it, classes) == "Crypto" }
    val haveVol = crypto.isNotEmpty() && crypto.count { stats[it.feedKey]?.dayNtlVlm != null } >= crypto.size * 0.8
    var universe by remember { mutableStateOf<List<Market>>(emptyList()) }
    if (universe.isEmpty() && haveVol) universe = crypto.sortedByDescending { stats[it.feedKey]?.dayNtlVlm ?: 0.0 }.take(30)
        .let { top -> (listOfNotNull(crypto.firstOrNull { it.symbol == "BTC" }) + top).distinctBy { it.marketId } }

    LaunchedEffect(universe) {
        if (universe.isEmpty()) return@LaunchedEffect
        while (true) {
            repo.load1h(universe)
            val candles = coroutineScope {
                universe.map { m -> async { runCatching { repo.history(m, "1h", BARS + 1) }.getOrDefault(emptyList()) } }.awaitAll()
            }
            val btcIdx = universe.indexOfFirst { it.symbol == "BTC" }
            val btcC = candles.getOrNull(btcIdx).orEmpty()
            val btcR = rets(btcC)
            val btc24 = if (btcC.size > 25) (btcC.last().c / btcC[btcC.size - 25].c - 1) * 100 else 0.0
            setups = universe.indices.filter { it != btcIdx }.mapNotNull { i -> build(universe[i], candles[i], btcR, btc24) }
            delay(5 * 60_000L)
        }
    }

    Card {
        Text(
            "Long the leaders: high beta to BTC and stronger than BTC. Short the laggards: high beta but falling behind BTC, or run hot with longs paying funding. Coiled = calm price while volume and OI build, often before a move.",
            color = C.muted, fontSize = 11.sp, lineHeight = 15.sp,
        )
        Row(Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SetupFilter.entries.forEach { f -> Seg(f.label, filter == f, C.accent) { filter = f } }
        }
        val list = setups
        if (list == null) {
            Text(if (universe.isEmpty()) "Waiting for market volumes…" else "Scanning ${universe.size} markets…", color = C.muted, fontSize = 12.sp)
            return@Card
        }
        // Tags, in the order they matter for a long.
        for (s in list) {
            s.tags.clear()
            val oi = extras[s.m.marketId]?.oi24h
            if (s.volRatio < 0.7 && s.volumeRatio > 1.2 && (oi ?: 0.0) > 2) s.tags += "Coiled" to C.target
            if (s.beta >= 1.2 && s.rs24 > 0) s.tags += "Leader" to C.up
            if (s.rs24 < -2) s.tags += "Weak vs BTC" to C.accent
            if (s.volRatio > 2) s.tags += "Overheated" to C.down
            if (s.volRatio < 0.5 && s.volumeRatio < 0.8) s.tags += "Dead" to C.muted
            // The short side: a coin that moves more than BTC yet trails it, or one that has run hot
            // while longs pay to hold it (funding positive), which tends to unwind.
            val funding = stats[s.m.feedKey]?.fundingRate ?: 0.0
            if (s.beta >= 1.0 && s.rs24 < -2) s.tags += "Laggard" to C.down
            if (s.volRatio > 1.5 && s.chg24 > 0 && funding > 0 && (oi ?: 0.0) > 0) s.tags += "Crowded long" to C.down
        }
        fun has(s: Setup, t: String) = s.tags.any { it.first == t }
        val shown = when (filter) {
            SetupFilter.LONG -> list.filter { has(it, "Leader") || (has(it, "Coiled") && it.rs24 >= 0) }.sortedByDescending { it.rs24 }
            SetupFilter.SHORT -> list.filter { has(it, "Laggard") || has(it, "Crowded long") }.sortedBy { it.rs24 }
            SetupFilter.BETA -> list.filter { it.beta >= 1.2 }.sortedByDescending { it.rs24 }
            SetupFilter.COILED -> list.filter { it.volRatio < 0.8 }.sortedBy { it.volRatio }
            SetupFilter.AVOID -> list.filter { has(it, "Overheated") || has(it, "Dead") || has(it, "Weak vs BTC") }.sortedBy { it.rs24 }
            SetupFilter.ALL -> list.sortedByDescending { it.rs24 }
        }
        if (shown.isEmpty()) Text("Nothing matches right now.", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
        shown.forEach { s -> SetupRow(s, chg1h[s.m.marketId], extras[s.m.marketId]?.oi24h) { onOpenMarket(s.m.marketId) } }
        Text(
            "β: moves per 1% BTC move · vs BTC: 24h move beyond what β explains · Vol: last 12h vs its week (<0.7 calm, >2 wild) · Volume: last 12h vs average · OI: 24h change",
            color = C.muted, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun SetupRow(s: Setup, chg1h: Double?, oi: Double?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.m.symbol, color = C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            s.tags.forEach { (t, col) ->
                Spacer(Modifier.width(5.dp))
                Text(t, color = col, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(col.copy(alpha = 0.14f)).padding(horizontal = 5.dp, vertical = 1.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("1h ", color = C.muted, fontSize = 10.sp)
            Text(fmtPct(chg1h), style = num.copy(fontSize = 12.sp, color = pnlColor(chg1h)))
            Text("  24h ", color = C.muted, fontSize = 10.sp)
            Text(fmtPct(s.chg24), style = num.copy(fontSize = 12.sp, color = pnlColor(s.chg24)))
        }
        // One line of numbers, each coloured by whether it helps a long.
        val st = SpanStyle(color = C.muted)
        Text(buildAnnotatedString {
            fun part(label: String, value: String, col: Color) { withStyle(st) { append(label) }; withStyle(SpanStyle(color = col)) { append(value) }; withStyle(st) { append("   ") } }
            part("β ", String.format(Locale.US, "%.2f", s.beta), if (s.beta >= 1.2) C.up else C.text)
            part("vs BTC ", fmtPct(s.rs24), pnlColor(s.rs24))
            part("Vol ", String.format(Locale.US, "%.1f×", s.volRatio), when { s.volRatio > 2 -> C.down; s.volRatio < 0.7 -> C.target; else -> C.text })
            part("Volume ", String.format(Locale.US, "%.1f×", s.volumeRatio), if (s.volumeRatio > 1.2) C.up else if (s.volumeRatio < 0.8) C.muted else C.text)
            part("OI ", fmtPct(oi), pnlColor(oi))
        }, style = num.copy(fontSize = 11.sp), maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}
