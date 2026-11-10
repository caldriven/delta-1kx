package com.alex.mfppro.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alex.mfppro.data.Candle
import com.alex.mfppro.data.Killzones
import java.time.DayOfWeek
import java.time.Instant

/** Which session overlays to draw on intraday charts. [markets]: the market-hours strip along the top. */
data class SessionOpts(val markets: Boolean, val killzones: Boolean)

private val WINDOWS = Killzones.KILLZONES + Killzones.MARKETS
private val KZ_COUNT = Killzones.KILLZONES.size

// Progress through each window per (bar start, bar length); recomputed rarely, drawn every frame.
private val cache = HashMap<Long, FloatArray>()

private fun progress(t: Long, step: Long): FloatArray {
    val k = t * 31 + step
    cache[k]?.let { return it }
    if (cache.size > 20_000) cache.clear()
    // The middle of the bar, so a bar half inside a window counts.
    val p = FloatArray(WINDOWS.size) { Killzones.progress(WINDOWS[it], t + step / 2)?.toFloat() ?: -1f }
    cache[k] = p
    return p
}

/** Friday 17:00 to Sunday 18:00 New York, when the traditional markets are shut. */
private fun weekend(t: Long): Boolean {
    val z = Instant.ofEpochMilli(t).atZone(Killzones.NY)
    val m = z.hour * 60 + z.minute
    return (z.dayOfWeek == DayOfWeek.FRIDAY && m >= 17 * 60) || z.dayOfWeek == DayOfWeek.SATURDAY || (z.dayOfWeek == DayOfWeek.SUNDAY && m < 18 * 60)
}

private val sessLabel = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)

/**
 * Sessions kept out of the way of the candles: killzones as a thin strip along the bottom that fills
 * in from light to full colour as the window runs, market hours (Tokyo, London, New York) as the same
 * strip along the top, and the weekend as a faint grey band. Each window is named once, where it
 * starts, when there is room. Only for bars of an hour or less.
 */
internal fun DrawScope.sessions(slice: List<Candle>, xOf: (Int) -> Float, barW: Float, chartH: Float, opts: SessionOpts, tm: TextMeasurer, future: Int = 0) {
    if (slice.size < 2) return
    val step = slice[1].openTime - slice[0].openTime
    if (step <= 0 || step > 3_600_000L) return
    // Bars to the right of the last candle get their times too, so the strips run on into the future.
    val times = slice.map { it.openTime } + (1..future).map { slice.last().openTime + it * step }
    val stripH = 4.dp.toPx()
    var prevWeekend = false
    for (i in times.indices) {
        val x = xOf(i) - barW / 2
        val wk = weekend(times[i])
        if (wk) {
            drawRect(Color(0xFF808080).copy(alpha = 0.06f), Offset(x, 0f), Size(barW, chartH))
            if (!prevWeekend) {
                val t = tm.measure("Weekend", sessLabel.copy(color = Color(0xFF9CA3AF)))
                drawText(t, topLeft = Offset(x + 3.dp.toPx(), stripH + 3.dp.toPx()))
            }
        }
        prevWeekend = wk
        val p = progress(times[i], step)
        val prev = if (i > 0) progress(times[i - 1], step) else null
        for (k in WINDOWS.indices) {
            val isKz = k < KZ_COUNT
            if (p[k] < 0 || (isKz && !opts.killzones) || (!isKz && !opts.markets)) continue
            val col = Color(WINDOWS[k].rgb)
            val top = if (isKz) chartH - stripH else 0f
            drawRect(col.copy(alpha = 0.25f + 0.7f * p[k]), Offset(x, top), Size(barW + 0.5f, stripH))
            if (prev == null || prev[k] < 0) {
                val t = tm.measure(WINDOWS[k].short, sessLabel.copy(color = col.copy(alpha = 0.85f)))
                val room = WINDOWS[k].lengthMin * 60_000f / step * barW
                if (room > t.size.width + 6.dp.toPx())
                    drawText(t, topLeft = Offset(x + 2.dp.toPx(), if (isKz) top - t.size.height - 1.dp.toPx() else stripH + 1.dp.toPx()))
            }
        }
    }
}
