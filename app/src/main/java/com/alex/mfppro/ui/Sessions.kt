package com.alex.mfppro.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alex.mfppro.data.Candle
import java.time.Instant
import java.time.ZoneId

/** Which session overlays to draw on intraday charts. */
data class SessionOpts(val eu: Boolean, val killzones: Boolean)

private val NY = ZoneId.of("America/New_York")
private val TOKYO = ZoneId.of("Asia/Tokyo")
private val LONDON = ZoneId.of("Europe/London")

private const val ASIA = 1
private const val EU = 2
private const val US = 4
private const val PRE = 8
private const val AFTER = 16
private const val KZ_ASIA = 32
private const val KZ_LDN = 64
private const val KZ_NYAM = 128
private const val KZ_NYPM = 256
private const val US_OPEN = 512
private const val US_CLOSE = 1024
private const val ASIA_OPEN = 2048

private val ASIA_C = Color(0xFF8B5CF6)
private val EU_C = Color(0xFF3B82F6)
private val US_C = Color(0xFFF59E0B)
private val KZ_C = Color(0xFFEF4444)

private class Band(val bit: Int, val color: Color, val alpha: Float, val label: String)

private val BANDS = listOf(
    Band(ASIA, ASIA_C, 0.045f, "Asia"),
    Band(EU, EU_C, 0.06f, "EU"),
    Band(PRE, US_C, 0.02f, "Pre-market"),
    Band(US, US_C, 0.045f, "US hours"),
    Band(AFTER, US_C, 0.02f, "After hours"),
)
private val ZONES = listOf(
    Band(KZ_ASIA, ASIA_C, 0.9f, "Asia KZ"),
    Band(KZ_LDN, EU_C, 0.9f, "London KZ"),
    Band(KZ_NYAM, KZ_C, 0.9f, "NY AM KZ"),
    Band(KZ_NYPM, US_C, 0.9f, "NY PM KZ"),
)

// Session flags per (candle start, bar length); cheap to recompute but drawn every frame.
private val cache = HashMap<Long, Int>()

private fun minuteOf(t: Long, zone: ZoneId): Int = Instant.ofEpochMilli(t).atZone(zone).let { it.hour * 60 + it.minute }

/** Bit flags for the bar starting at [t] lasting [stepMin] minutes. Session times follow each city's DST. */
private fun flags(t: Long, stepMin: Int): Int {
    val k = t * 31 + stepMin
    cache[k]?.let { return it }
    if (cache.size > 20_000) cache.clear()
    val ny = minuteOf(t, NY)
    val tk = minuteOf(t, TOKYO)
    val ld = minuteOf(t, LONDON)
    fun inR(m: Int, a: Int, b: Int) = if (a <= b) m in a until b else m >= a || m < b
    fun hits(m: Int, at: Int) = at >= m && at < m + stepMin
    var f = 0
    if (inR(tk, 9 * 60, 18 * 60)) f = f or ASIA
    if (inR(ld, 8 * 60, 16 * 60 + 30)) f = f or EU
    when {
        inR(ny, 9 * 60 + 30, 16 * 60) -> f = f or US
        inR(ny, 4 * 60, 9 * 60 + 30) -> f = f or PRE
        inR(ny, 16 * 60, 20 * 60) -> f = f or AFTER
    }
    if (inR(ny, 20 * 60, 24 * 60)) f = f or KZ_ASIA
    if (inR(ny, 2 * 60, 5 * 60)) f = f or KZ_LDN
    if (inR(ny, 8 * 60 + 30, 11 * 60)) f = f or KZ_NYAM
    if (inR(ny, 13 * 60 + 30, 16 * 60)) f = f or KZ_NYPM
    if (hits(ny, 9 * 60 + 30)) f = f or US_OPEN
    if (hits(ny, 16 * 60)) f = f or US_CLOSE
    if (hits(tk, 9 * 60)) f = f or ASIA_OPEN
    cache[k] = f
    return f
}

private val sessLabel = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 9.sp, fontWeight = FontWeight.Medium)

/**
 * Shaded session columns (Asia, optional EU, US pre/regular/after hours),
 * killzones as a coloured strip along the top, and dashed lines at the US
 * open/close and Tokyo open. Only meaningful for bars of an hour or less.
 */
internal fun DrawScope.sessions(slice: List<Candle>, xOf: (Int) -> Float, barW: Float, chartH: Float, opts: SessionOpts, tm: TextMeasurer, future: Int = 0) {
    if (slice.size < 2) return
    val step = slice[1].openTime - slice[0].openTime
    val stepMin = (step / 60_000).toInt()
    if (stepMin <= 0 || stepMin > 60) return
    // Bars to the right of the last candle get their times too, so sessions run on into the future.
    val times = slice.map { it.openTime } + (1..future).map { slice.last().openTime + it * step }
    val fl = IntArray(times.size) { flags(times[it], stepMin) }
    val stripH = 4.dp.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    for (b in BANDS) {
        if (b.bit == EU && !opts.eu) continue
        for (i in times.indices) {
            if (fl[i] and b.bit == 0) continue
            val x = xOf(i)
            drawRect(b.color.copy(alpha = b.alpha), Offset(x - barW / 2, 0f), Size(barW, chartH))
            if (i == 0 || fl[i - 1] and b.bit == 0) {
                val t = tm.measure(b.label, sessLabel.copy(color = b.color.copy(alpha = 0.8f)))
                drawText(t, topLeft = Offset(x + 3.dp.toPx(), stripH + 3.dp.toPx() + if (b.bit == EU) t.size.height else 0))
            }
        }
    }
    if (opts.killzones) for (z in ZONES) {
        for (i in times.indices) {
            if (fl[i] and z.bit == 0) continue
            val x = xOf(i)
            drawRect(z.color.copy(alpha = 0.75f), Offset(x - barW / 2, 0f), Size(barW, stripH))
        }
    }
    for (i in times.indices) {
        val x = xOf(i) - barW / 2
        val (label, col) = when {
            fl[i] and US_OPEN != 0 -> "US open" to US_C
            fl[i] and US_CLOSE != 0 -> "US close" to US_C
            fl[i] and ASIA_OPEN != 0 -> "Tokyo open" to ASIA_C
            else -> continue
        }
        drawLine(col.copy(alpha = 0.7f), Offset(x, 0f), Offset(x, chartH), 1f, pathEffect = dash)
        val t = tm.measure(label, sessLabel.copy(color = col))
        drawText(t, topLeft = Offset(x + 3.dp.toPx(), chartH - t.size.height - 3.dp.toPx()))
    }
}
