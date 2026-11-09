package com.alex.mfppro.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.alex.mfppro.data.Candle
import com.alex.mfppro.data.Market
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.Sub
import com.alex.mfppro.data.candleKey
import com.alex.mfppro.ui.fmtPct
import com.alex.mfppro.ui.fmtPrice
import com.alex.mfppro.ui.ticker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

private val INTERVALS = listOf("1m", "5m", "15m", "1h", "4h", "1d")

@Composable
fun ChartPanel(repo: Repo, m: Market, modifier: Modifier) {
    var interval by remember { mutableStateOf(repo.prefs.lastInterval.takeIf { it in INTERVALS } ?: "15m") }
    var history by remember(m.marketId, interval) { mutableStateOf<List<Candle>>(emptyList()) }
    LaunchedEffect(m.marketId, interval) {
        history = runCatching { repo.history(m, interval, 500) }.getOrElse { emptyList() }
    }
    Subscribe(repo, Sub("candles", m.provider, m.coin, interval))
    val live by repo.market.candles.collectAsState()
    val marks by repo.market.marks.collectAsState()
    val accounts by repo.account.accounts.collectAsState()

    // History, then the live bars on top of it (same open time = the live one wins).
    val bars = remember(history, live[candleKey(m.provider, m.coin, interval)]) {
        val byTime = java.util.TreeMap<Long, Candle>()
        history.forEach { byTime[it.openTime] = it }
        live[candleKey(m.provider, m.coin, interval)]?.forEach { byTime[it.openTime] = it }
        byTime.values.toList()
    }
    val mark = marks[m.feedKey] ?: bars.lastOrNull()?.c
    val entries = accounts.values.flatMap { it.positions.values }.filter { it.inMarket(m) }
        .map { Triple(it.entryPrice, it.isLong, it.liquidationPrice) }

    val first = bars.lastOrNull { it.openTime <= System.currentTimeMillis() - 86_400_000 }?.c ?: bars.firstOrNull()?.o
    Panel(
        ticker(m.symbol, m.provider) + "  " + fmtPrice(mark, m), modifier,
        actions = {
            if (mark != null && first != null && first > 0) Text("24h " + fmtPct((mark / first - 1) * 100), style = num.copy(color = pnlColor(mark - first)))
            INTERVALS.forEach { iv -> Pill(iv, iv == interval) { interval = iv; repo.prefs.lastInterval = iv } }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            if (bars.isEmpty()) Empty("Loading candles…")
            else CandleChart(bars, m, mark, entries, interval)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CandleChart(bars: List<Candle>, m: Market, mark: Double?, entries: List<Triple<Double, Boolean, Double?>>, interval: String) {
    val tm = rememberTextMeasurer()
    var count by remember(interval) { mutableFloatStateOf(120f) }
    var shift by remember(interval) { mutableFloatStateOf(0f) }   // bars scrolled back from the newest
    var hover by remember { mutableStateOf<Offset?>(null) }
    var dragFrom by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val axisW = 74f
    val fmt = DateTimeFormatter.ofPattern(if (interval == "1d") "d MMM" else "d MMM HH:mm", java.util.Locale.US).withZone(ZoneId.systemDefault())

    Canvas(
        Modifier.fillMaxSize()
            .onPointerEvent(PointerEventType.Scroll) { e ->
                val dy = e.changes.first().scrollDelta.y
                count = (count * (1f + dy * 0.12f)).coerceIn(20f, max(20f, bars.size.toFloat()))
            }
            .onPointerEvent(PointerEventType.Press) { e -> dragFrom = e.changes.first().position.x }
            .onPointerEvent(PointerEventType.Release) { dragFrom = null }
            .onPointerEvent(PointerEventType.Move) { e ->
                val p = e.changes.first().position
                hover = p
                dragFrom?.let { from ->
                    val slot = (widthPx - axisW) / count
                    shift = (shift + (p.x - from) / slot).coerceIn(0f, max(0f, bars.size - count))
                    dragFrom = p.x
                }
            }
            .onPointerEvent(PointerEventType.Exit) { hover = null; dragFrom = null },
    ) {
        widthPx = size.width
        val plotW = size.width - axisW
        val plotH = size.height - 22f
        val n = count.roundToInt().coerceAtMost(bars.size)
        val end = (bars.size - shift.roundToInt()).coerceIn(n, bars.size)
        val view = bars.subList(end - n, end)
        if (view.isEmpty()) return@Canvas
        var lo = view.minOf { it.l }; var hi = view.maxOf { it.h }
        val pad = (hi - lo).coerceAtLeast(hi * 1e-4) * 0.08
        lo -= pad; hi += pad
        fun y(p: Double) = (plotH * (1 - (p - lo) / (hi - lo))).toFloat()
        fun price(yPx: Float) = hi - (yPx / plotH) * (hi - lo)
        val slot = plotW / n
        val body = (slot * 0.68f).coerceAtLeast(1f)

        // Grid and price axis.
        val step = niceStep((hi - lo) / 7)
        var g = kotlin.math.ceil(lo / step) * step
        while (g < hi) {
            val gy = y(g)
            drawLine(D.line.copy(alpha = 0.5f), Offset(0f, gy), Offset(plotW, gy), 1f)
            axisLabel(tm, fmtPrice(g, m), gy, plotW, D.muted, null)
            g += step
        }
        // Candles.
        view.forEachIndexed { i, c ->
            val x = i * slot + slot / 2
            val up = c.c >= c.o
            val col = if (up) D.up else D.down
            drawLine(col, Offset(x, y(c.h)), Offset(x, y(c.l)), 1f)
            val top = y(maxOf(c.o, c.c)); val bot = y(minOf(c.o, c.c))
            drawRect(col, Offset(x - body / 2, top), Size(body, (bot - top).coerceAtLeast(1f)))
        }
        // Time labels.
        val every = max(1, (n / (plotW / 140f)).roundToInt())
        for (i in view.indices step every) {
            val r = tm.measure(fmt.format(Instant.ofEpochMilli(view[i].openTime)), num.copy(fontSize = 10.sp, color = D.muted))
            drawText(r, topLeft = Offset(i * slot + slot / 2 - r.size.width / 2f, plotH + 4f))
        }
        // Positions held in this market: entry (green long / red short), liquidation dotted.
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))
        entries.forEach { (entry, long, liq) ->
            val col = if (long) D.up else D.down
            if (entry in lo..hi) {
                drawLine(col, Offset(0f, y(entry)), Offset(plotW, y(entry)), 1.2f, pathEffect = dash)
                axisLabel(tm, fmtPrice(entry, m), y(entry), plotW, Color.Black, col)
            }
            if (liq != null && liq in lo..hi) drawLine(Color(0xFFFF7A00), Offset(0f, y(liq)), Offset(plotW, y(liq)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 4f)))
        }
        // Last price.
        if (mark != null && mark in lo..hi && shift < 1f) {
            drawLine(D.accent.copy(alpha = 0.7f), Offset(0f, y(mark)), Offset(plotW, y(mark)), 1f, pathEffect = dash)
            axisLabel(tm, fmtPrice(mark, m), y(mark), plotW, Color.Black, D.accent)
        }
        // Crosshair.
        hover?.takeIf { it.x < plotW && it.y < plotH }?.let { h ->
            drawLine(D.muted.copy(alpha = 0.6f), Offset(h.x, 0f), Offset(h.x, plotH), 1f, pathEffect = dash)
            drawLine(D.muted.copy(alpha = 0.6f), Offset(0f, h.y), Offset(plotW, h.y), 1f, pathEffect = dash)
            axisLabel(tm, fmtPrice(price(h.y), m), h.y, plotW, D.text, D.panel2)
            val c = view.getOrNull((h.x / slot).toInt())
            if (c != null) {
                val ohlc = "O ${fmtPrice(c.o, m)}  H ${fmtPrice(c.h, m)}  L ${fmtPrice(c.l, m)}  C ${fmtPrice(c.c, m)}  " +
                    fmtPct((c.c / c.o - 1) * 100) + "   " + fmt.format(Instant.ofEpochMilli(c.openTime))
                drawText(tm.measure(ohlc, num.copy(fontSize = 11.sp, color = D.text)), topLeft = Offset(8f, 6f))
            }
        }
    }
}

private fun DrawScope.axisLabel(tm: TextMeasurer, text: String, y: Float, x: Float, color: Color, bg: Color?) {
    val r = tm.measure(text, num.copy(fontSize = 10.sp, color = color, fontWeight = if (bg != null) FontWeight.Bold else FontWeight.Normal))
    val top = (y - r.size.height / 2f).coerceIn(0f, size.height - r.size.height)
    if (bg != null) drawRect(bg, Offset(x + 2f, top - 1f), Size(size.width - x - 2f, r.size.height + 2f))
    drawText(r, topLeft = Offset(x + 6f, top))
}

/** Largest 1, 2 or 5 × 10^k at or above [v], for grid lines. */
private fun niceStep(v: Double): Double {
    if (v <= 0) return 1.0
    val p = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(v)))
    return p * (if (v / p > 5) 10 else if (v / p > 2) 5 else if (v / p > 1) 2 else 1)
}
