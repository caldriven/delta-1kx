package com.alex.mfppro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alex.mfppro.data.Market
import com.alex.mfppro.data.OiHistory
import com.alex.mfppro.data.OiPoint
import com.alex.mfppro.data.OiRange
import com.alex.mfppro.data.OiSample
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.changeOver
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val HOUR = 3_600_000L

@Composable
fun TradingData(repo: Repo, market: Market, liveOi: Double?, mark: Double?, sessionOi: List<OiPoint>) {
    if (!repo.oiHistory.supported(market.provider)) {
        Column(Modifier.padding(12.dp)) {
            Text("Open interest " + "$" + fmtCompact(liveOi), style = num)
            Text("${market.provider} publishes no OI history; showing what this session has seen.", color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp))
            OiSparkline(sessionOi, Modifier.fillMaxWidth().height(160.dp))
        }
        return
    }
    var range by rememberSaveable { mutableStateOf(OiRange.H24) }
    var hist by remember(market.marketId, range) { mutableStateOf<OiHistory?>(null) }
    var day by remember(market.marketId) { mutableStateOf<OiHistory?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Venue data is 5-minute granular; refresh once a minute while visible.
    LaunchedEffect(market.marketId, range) {
        while (true) {
            runCatching {
                val h = repo.oiHistory.load(market.provider, market.coin, range, mark)
                hist = h
                if (range == OiRange.H24) day = h
                else if (day == null) day = repo.oiHistory.load(market.provider, market.coin, OiRange.H24, mark)
                error = null
            }.onFailure { error = it.message }
            delay(60_000)
        }
    }

    val samples = hist?.samples.orEmpty()
    val daySamples = day?.samples.orEmpty()
    val coins = daySamples.lastOrNull()?.coins
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box_("Positions", "$" + fmtCompact(liveOi ?: daySamples.lastOrNull()?.usd), coins?.let { "≈ ${fmtCompact(it)} ${market.symbol}" }, Modifier.weight(1.3f))
            Box_("OI 1h", fmtPct(daySamples.changeOver(HOUR)), null, Modifier.weight(1f), pnlColor(daySamples.changeOver(HOUR)))
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, h) in listOf("4h" to 4, "12h" to 12, "24h" to 24)) {
                val c = daySamples.changeOver(h * HOUR)
                Box_("OI $label", fmtPct(c), null, Modifier.weight(1f), pnlColor(c))
            }
        }
        (hist?.longPct ?: day?.longPct)?.let { LongShortBar(it) }

        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Historical OI", color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            OiRange.entries.forEach { r -> Seg(r.label, r == range, C.text) { range = r } }
        }
        Row(Modifier.padding(top = 4.dp)) {
            Text("● Open interest  ", color = C.up, fontSize = 11.sp)
            if (samples.any { it.price != null }) Text("● ${market.symbol} price", color = C.accent, fontSize = 11.sp)
        }
        when {
            samples.size >= 2 -> OiHistoryChart(samples, range, Modifier.fillMaxWidth().height(240.dp).padding(top = 6.dp))
            error != null -> Text("Couldn't load OI history: $error", color = C.down, fontSize = 12.sp, modifier = Modifier.padding(vertical = 20.dp))
            else -> Text("Loading…", color = C.muted, modifier = Modifier.padding(vertical = 20.dp))
        }
        Text("Source: ${market.provider} public futures data", color = C.muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Box_(label: String, value: String, sub: String?, modifier: Modifier, color: androidx.compose.ui.graphics.Color = C.text) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(C.panel).padding(10.dp)) {
        Text(label, color = C.muted, fontSize = 11.sp)
        Text(value, style = num.copy(fontSize = 14.sp, color = color))
        sub?.let { Text(it, style = num.copy(fontSize = 11.sp, color = C.muted)) }
    }
}

@Composable
private fun LongShortBar(longPct: Double) {
    Column(Modifier.padding(top = 10.dp)) {
        Row {
            Text("Long ${String.format(Locale.US, "%.1f%%", longPct)}", color = C.up, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text("Accounts ratio", color = C.muted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text("Short ${String.format(Locale.US, "%.1f%%", 100 - longPct)}", color = C.down, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp).clip(RoundedCornerShape(3.dp))) {
            Box(Modifier.weight(longPct.toFloat().coerceIn(1f, 99f)).fillMaxHeight().background(C.up))
            Spacer(Modifier.width(2.dp))
            Box(Modifier.weight((100 - longPct).toFloat().coerceIn(1f, 99f)).fillMaxHeight().background(C.down))
        }
    }
}

private val axis get() = TextStyle(fontFamily = Mono, fontSize = 10.sp, color = C.muted, fontFeatureSettings = "tnum")

@Composable
private fun OiHistoryChart(samples: List<OiSample>, range: OiRange, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val fmt = remember(range) { SimpleDateFormat(if (range == OiRange.H24) "HH:mm" else "dd MMM", Locale.US) }
    Canvas(modifier) {
        val left = 48.dp.toPx(); val right = 52.dp.toPx(); val bottom = 16.dp.toPx()
        val w = size.width - left - right
        val h = size.height - bottom
        val t0 = samples.first().time; val t1 = samples.last().time
        val x = { t: Long -> left + (t - t0).toFloat() / (t1 - t0).coerceAtLeast(1) * w }

        val oHi = samples.maxOf { it.usd }; val oLo = samples.minOf { it.usd }
        val oy = { v: Double -> ((oHi - v) / (oHi - oLo).coerceAtLeast(1e-9) * h * 0.9 + h * 0.05).toFloat() }
        val area = Path().apply {
            moveTo(x(t0), h)
            samples.forEach { lineTo(x(it.time), oy(it.usd)) }
            lineTo(x(t1), h); close()
        }
        drawPath(area, Brush.verticalGradient(listOf(C.up.copy(alpha = 0.35f), C.up.copy(alpha = 0.02f)), 0f, h))
        val line = Path().apply { samples.forEachIndexed { i, s -> if (i == 0) moveTo(x(s.time), oy(s.usd)) else lineTo(x(s.time), oy(s.usd)) } }
        drawPath(line, C.up, style = Stroke(2f))

        val priced = samples.filter { it.price != null }
        if (priced.size >= 2) {
            val pHi = priced.maxOf { it.price!! }; val pLo = priced.minOf { it.price!! }
            val py = { v: Double -> ((pHi - v) / (pHi - pLo).coerceAtLeast(1e-12) * h * 0.9 + h * 0.05).toFloat() }
            val pl = Path().apply { priced.forEachIndexed { i, s -> if (i == 0) moveTo(x(s.time), py(s.price!!)) else lineTo(x(s.time), py(s.price!!)) } }
            drawPath(pl, C.accent, style = Stroke(1.6f))
            for (i in 0..3) {
                val v = pLo + (pHi - pLo) * i / 3
                drawText(tm, fmtPrice(v), Offset(left + w + 4.dp.toPx(), py(v) - 6.dp.toPx()), axis)
            }
        }
        for (i in 0..3) {
            val v = oLo + (oHi - oLo) * i / 3
            val yy = oy(v)
            drawLine(C.line.copy(alpha = 0.4f), Offset(left, yy), Offset(left + w, yy), 1f)
            drawText(tm, "$" + fmtCompact(v), Offset(0f, yy - 6.dp.toPx()), axis)
        }
        for (i in 0..3) {
            val t = t0 + (t1 - t0) * i / 3
            drawText(tm, fmt.format(Date(t)), Offset(x(t) - 14.dp.toPx(), h + 2.dp.toPx()), axis)
        }
    }
}
