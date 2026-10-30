package com.alex.mfppro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alex.mfppro.data.Level
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.Trade
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val WINDOW_MS = 180_000L
private const val SAMPLE_MS = 500L

private class BookSample(val time: Long, val bids: List<Level>, val asks: List<Level>)

private val HEAT_LO = Color(0xFF0B2545)
private val HEAT_HI = Color(0xFFFACC15)
private val flowAxis = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, color = C.muted, fontFeatureSettings = "tnum")

/**
 * Bookmap/aggr-style order flow for the last three minutes: resting book
 * liquidity as a heatmap behind the price, and every trade as a bubble sized
 * by its dollar value (blue/green buys, orange/red sells).
 */
@Composable
fun FlowChart(repo: Repo, key: String, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    // Local history: the shared trade buffer is short, so keep our own window here.
    val trades = remember(key) { ArrayDeque<Trade>() }
    val seen = remember(key) { HashSet<String>() }
    val samples = remember(key) { ArrayDeque<BookSample>() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(key) {
        repo.market.trades.collect { m ->
            // Newest first in the feed; add oldest first so the deque stays time-ordered.
            for (t in m[key].orEmpty().asReversed()) if (seen.add(t.id)) trades.addLast(t)
        }
    }
    LaunchedEffect(key) {
        while (true) {
            val t = System.currentTimeMillis()
            repo.market.books.value[key]?.let { b -> samples.addLast(BookSample(t, b.bids.take(40), b.asks.take(40))) }
            while (samples.isNotEmpty() && samples.first().time < t - WINDOW_MS) samples.removeFirst()
            while (trades.isNotEmpty() && trades.first().time < t - WINDOW_MS) seen.remove(trades.removeFirst().id)
            now = t
            delay(SAMPLE_MS)
        }
    }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val axisW = 56.dp.toPx()
            val w = size.width - axisW
            val h = size.height
            // Until three minutes have been collected, stretch what we have across the width.
            val oldest = minOf(samples.firstOrNull()?.time ?: now, trades.firstOrNull()?.time ?: now)
            val span = (now - oldest).coerceIn(20_000L, WINDOW_MS)
            val t0 = now - span
            val x = { t: Long -> ((t - t0).toFloat() / span * w * 0.96f) }

            // Price range: trades and best bid/ask over the window, padded.
            var hi = Double.MIN_VALUE; var lo = Double.MAX_VALUE
            for (s in samples) {
                s.bids.firstOrNull()?.let { lo = min(lo, it.px); hi = max(hi, it.px) }
                s.asks.firstOrNull()?.let { lo = min(lo, it.px); hi = max(hi, it.px) }
            }
            for (t in trades) { lo = min(lo, t.price); hi = max(hi, t.price) }
            if (hi <= lo) {
                drawText(tm, "Collecting order flow…", Offset(16.dp.toPx(), h / 2), flowAxis)
                return@Canvas
            }
            val pad = (hi - lo).coerceAtLeast(hi * 2e-4) * 0.25
            hi += pad; lo -= pad
            val y = { p: Double -> ((hi - p) / (hi - lo) * h).toFloat() }

            // Heatmap: one column per book sample, each level shaded by its size.
            val maxSz = samples.maxOfOrNull { s -> (s.bids + s.asks).maxOfOrNull { it.px * it.sz } ?: 0.0 }?.takeIf { it > 0 } ?: 1.0
            val colW = max(1f, w * SAMPLE_MS / span + 0.5f)
            for (s in samples) {
                val sx = x(s.time)
                for (side in listOf(s.bids, s.asks)) {
                    for (i in side.indices) {
                        val l = side[i]
                        if (l.px !in lo..hi) continue
                        val next = side.getOrNull(i + 1)?.px ?: continue
                        val y1 = y(l.px); val y2 = y(next)
                        val f = sqrt(l.px * l.sz / maxSz).toFloat().coerceIn(0f, 1f)
                        if (f < 0.08f) continue
                        drawRect(lerp(HEAT_LO, HEAT_HI, f).copy(alpha = 0.25f + 0.65f * f), Offset(sx - colW, min(y1, y2)), Size(colW, max(1f, kotlin.math.abs(y2 - y1))))
                    }
                }
            }

            // Best bid / ask trail.
            for ((pick, col) in listOf<Pair<(BookSample) -> Double?, Color>>({ s: BookSample -> s.bids.firstOrNull()?.px } to C.up, { s: BookSample -> s.asks.firstOrNull()?.px } to C.down)) {
                val path = Path()
                var started = false
                for (s in samples) {
                    val p = pick(s) ?: continue
                    if (!started) { path.moveTo(x(s.time), y(p)); started = true } else path.lineTo(x(s.time), y(p))
                }
                drawPath(path, col.copy(alpha = 0.8f), style = Stroke(1.5f))
            }

            // Trade bubbles: area follows notional.
            val maxN = trades.maxOfOrNull { it.price * it.size }?.takeIf { it > 0 } ?: 1.0
            val maxR = 22.dp.toPx(); val minR = 2.dp.toPx()
            val bigCut = trades.map { it.price * it.size }.sorted().let { if (it.size >= 20) it[(it.size * 0.95).toInt()] else Double.MAX_VALUE }
            val labelled = ArrayList<androidx.compose.ui.geometry.Rect>()
            for (t in trades) {
                val n = t.price * t.size
                val r = minR + (maxR - minR) * sqrt(n / maxN).toFloat()
                val c = Offset(x(t.time), y(t.price))
                val col = if (t.buy) C.up else C.down
                drawCircle(col.copy(alpha = 0.35f), r, c)
                drawCircle(col.copy(alpha = 0.9f), r, c, style = Stroke(1.2f))
                if (n >= bigCut) {
                    val txt = tm.measure("$" + fmtCompact(n), flowAxis.copy(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                    val r = androidx.compose.ui.geometry.Rect(c.x - txt.size.width / 2, c.y - txt.size.height / 2, c.x + txt.size.width / 2, c.y + txt.size.height / 2)
                    if (labelled.none { it.overlaps(r) }) { labelled += r; drawText(txt, topLeft = r.topLeft) }
                }
            }

            // Price axis.
            drawLine(C.line, Offset(w, 0f), Offset(w, h), 1f)
            for (i in 0..5) {
                val p = lo + (hi - lo) * i / 5
                val yy = y(p)
                drawLine(C.line.copy(alpha = 0.3f), Offset(0f, yy), Offset(w, yy), 1f)
                drawText(tm, fmtPrice(p), Offset(w + 4.dp.toPx(), yy - 7.dp.toPx()), flowAxis)
            }
            trades.lastOrNull()?.let { t ->
                val yy = y(t.price)
                val col = if (t.buy) C.up else C.down
                drawRect(col, Offset(w, yy - 8.dp.toPx()), Size(axisW, 16.dp.toPx()))
                drawText(tm, fmtPrice(t.price), Offset(w + 4.dp.toPx(), yy - 7.dp.toPx()), flowAxis.copy(color = Color.White))
            }
        }
    }
}
