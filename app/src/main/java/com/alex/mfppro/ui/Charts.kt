package com.alex.mfppro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alex.mfppro.data.Candle
import com.alex.mfppro.data.OiPoint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A horizontal level on the chart. Position levels (entry, TP, SL, floors)
 * show as an edge marker when they're outside the visible range; [ref]
 * levels (session opens) are thin context lines that simply hide off-screen.
 */
data class PriceLine(
    val price: Double,
    val color: Color,
    val label: String,
    val solid: Boolean = false,
    val ref: Boolean = false,
    val drag: LineDrag? = null,
    val box: PosBox? = null,
    /** Segmented tag like the position box: first part is the filled label, the rest are values. */
    val parts: List<String> = emptyList(),
)

/**
 * Exchange-style position box on the entry line: [Net PnL | value | TP | SL | reverse | close].
 * TP and SL are dragged out to a price; reverse and close are taps.
 */
class PosBox(val pnl: String, val tp: LineDrag, val sl: LineDrag, val onReverse: () -> Unit, val onClose: () -> Unit)

/** One of your past fills, marked on its candle: ▲ under it for a buy, ▼ over it for a sell. */
data class FillMark(val time: Long, val buy: Boolean, val price: Double)

/** A touch target drawn on the chart: either dragged to a price or tapped. */
private class Hit(val rect: Rect, val drag: LineDrag? = null, val tap: (() -> Unit)? = null)

private val MARK_C = Color(0xFFFDBA74)
private val POC_C = Color(0xFFA78BFA)

/**
 * A line whose pill can be dragged to a new price. [preview] labels the ghost
 * line while dragging (and may pick its colour); [onDrop] gets the release price.
 */
class LineDrag(val preview: (Double) -> Pair<String, Color>, val onDrop: (Double) -> Unit)

// TradingView-style labels: the system sans (Roboto) with tabular figures, not monospace.
private val axisStyle get() = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif, fontSize = 11.sp, color = C.muted, fontFeatureSettings = "tnum", letterSpacing = 0.sp)
private val vpColor = Color(0xFF6B7A90)

/**
 * Candlestick chart.
 * - One finger sideways pans time; vertical swipes scroll the page.
 * - Pinch zooms time.
 * - Drag the price axis up/down to stretch or squeeze the price scale. After that,
 *   one finger pans freely and an "Auto" chip fits back to the candles.
 * - Double-tap resets everything.
 * [vpSince] is the session start for the volume profile and its POC (null = visible range).
 */
@Composable
fun CandleChart(
    candles: List<Candle>,
    lines: List<PriceLine>,
    modifier: Modifier = Modifier,
    showVp: Boolean = false,
    showPoc: Boolean = false,
    vpSince: Long? = null,
    sessions: SessionOpts? = null,
    crossKey: Int = 0,
    fills: List<FillMark> = emptyList(),
    // Narrow price axis with short labels, for small charts (multi-chart grid).
    compactAxis: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val axisDp = if (compactAxis) 40.dp else 56.dp
    // Room for every label a busy chart draws (axis, tags, pills, crosshair box): the default 8 kept
    // missing and measuring all text again each frame.
    val tm = rememberTextMeasurer(cacheSize = 96)
    var visible by remember { mutableFloatStateOf(70f) }
    // Bars scrolled back from the newest; negative leaves empty space right of the last candle.
    var offset by remember { mutableFloatStateOf(RIGHT_GAP) }
    // Manual price range (hi, lo); null = auto-fit to the visible candles.
    var manual by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val count by rememberUpdatedState(candles.size)
    // Last drawn range and plot height, for converting drags to prices.
    val geom = remember { DoubleArray(3) } // hi, lo, chartH px
    // Pill hit boxes of draggable lines from the last frame, and the line being dragged with its y.
    val hits = remember { ArrayList<Hit>() }
    var dragging by remember { mutableStateOf<Pair<LineDrag, Float>?>(null) }
    // Crosshair: long-press to show, drag to move, tap to clear.
    var cross by remember { mutableStateOf<Offset?>(null) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // The crosshair button: each press toggles it, starting at the chart centre.
    val canvasSize = remember { FloatArray(2) }
    // Only a press made while this chart is up toggles it: a chart rebuilt for a new market or interval
    // starts with the key already above zero and must not pop the crosshair open by itself.
    var seenCross by remember { mutableIntStateOf(crossKey) }
    LaunchedEffect(crossKey) {
        if (crossKey != seenCross) { seenCross = crossKey; cross = if (cross == null) Offset(canvasSize[0] * 0.45f, canvasSize[1] * 0.4f) else null }
    }
    // Ticks once a second for the next-candle countdown.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1_000); now = System.currentTimeMillis() } }

    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(axisDp) {
                    val axisW = axisDp.toPx()
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 0 undecided, 1 time pan, 2 price-axis scale, 3 free pan, -1 page scroll, 4 pinch,
                        // 5 line drag, 6 pressing a chart button (tap or start of a drag)
                        // An exact hit wins over a neighbour's padding, so ⇅ and ✕ side by side never swap.
                        val hit = hits.firstOrNull { it.rect.contains(down.position) }
                            ?: hits.firstOrNull { it.rect.inflate(6.dp.toPx()).contains(down.position) }
                        var mode = when {
                            hit != null -> { down.consume(); 6 }
                            // While the crosshair is up, every one-finger drag moves it and never the chart,
                            // even with a manual price range or starting on the axis.
                            cross != null -> 7
                            down.position.x > size.width - axisW -> 2
                            manual != null -> 3
                            else -> 0
                        }
                        var total = Offset.Zero
                        // Dropping a dragged line on the Cancel pill at the top abandons it.
                        val cancelZone = Rect(size.width / 2f - 70.dp.toPx(), 0f, size.width / 2f + 70.dp.toPx(), 44.dp.toPx())
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.count { it.pressed } >= 2) {
                                val z = ev.calculateZoom()
                                if (z != 1f) visible = (visible / z).coerceIn(15f, 400f)
                                ev.changes.forEach { it.consume() }
                                dragging = null
                                mode = 4
                                continue
                            }
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) {
                                when {
                                    // Release after dragging a line: hand back the price under the finger.
                                    mode == 5 && cancelZone.contains(ch.position) -> Unit
                                    mode == 5 -> dragging?.let { (d, yy) ->
                                        val hi = geom[0]; val lo = geom[1]
                                        val px = hi - yy / geom[2].coerceAtLeast(1.0) * (hi - lo)
                                        if (px > 0) d.onDrop(px)
                                    }
                                    mode == 6 && total.getDistance() < slop -> hit?.tap?.invoke()
                                }
                                dragging = null
                                break
                            }
                            val d = ch.positionChange()
                            total += d
                            // Ignore finger jitter until the move is clearly a gesture.
                            if (total.getDistance() < slop && mode != 5) continue
                            if (mode == 6) {
                                ch.consume()
                                val dr = hit?.drag ?: continue
                                mode = 5
                                dragging = dr to (down.position.y + total.y).coerceIn(0f, geom[2].toFloat())
                                continue
                            }
                            if (mode == 0) mode = if (cross != null) 7 else if (abs(total.x) > abs(total.y)) 1 else -1
                            // TradingView style: while the crosshair is on, any drag moves it by the same amount,
                            // so it never hides under the thumb; releasing leaves it where it is.
                            if (mode == 7) {
                                cross = cross?.let { c -> Offset((c.x + d.x).coerceIn(0f, size.width - axisW), (c.y + d.y).coerceIn(0f, size.height.toFloat())) }
                                ch.consume(); continue
                            }
                            val barW = (size.width - axisW) / visible
                            when (mode) {
                                5 -> { dragging = dragging?.let { it.first to (it.second + d.y).coerceIn(0f, geom[2].toFloat()) }; ch.consume() }
                                1 -> { offset = (offset + d.x / barW).coerceIn(-visible * 0.6f, max(0f, count - 10f)); ch.consume() }
                                2 -> {
                                    val (hi, lo) = manual ?: (geom[0] to geom[1])
                                    if (hi > lo) {
                                        // Drag down squeezes candles (wider range), drag up stretches them.
                                        val f = exp(d.y * 0.006)
                                        val mid = (hi + lo) / 2
                                        val half = (hi - lo) / 2 * f
                                        manual = (mid + half) to (mid - half)
                                    }
                                    ch.consume()
                                }
                                3 -> {
                                    offset = (offset + d.x / barW).coerceIn(-visible * 0.6f, max(0f, count - 10f))
                                    manual?.let { (hi, lo) ->
                                        val dp = d.y / geom[2].coerceAtLeast(1.0) * (hi - lo)
                                        manual = (hi + dp) to (lo + dp)
                                    }
                                    ch.consume()
                                }
                            }
                        }
                    }
                }
                .pointerInput(axisDp) {
                    detectTapGestures(
                        onDoubleTap = { offset = RIGHT_GAP; visible = 70f; manual = null; cross = null },
                        // Long press shows it a thumb's height above the finger.
                        onLongPress = {
                            // Not on the price axis: that press is scaling the axis.
                            if (it.x < size.width - axisDp.toPx()) {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                cross = Offset(it.x, (it.y - 60.dp.toPx()).coerceAtLeast(0f))
                            }
                        },
                        onTap = { cross = null },
                    )
                }
        ) {
            val axisW = axisDp.toPx()
            // Axis price text: full precision normally, short and small on a compact axis.
            fun ap(p: Double) = if (!compactAxis) fmtPrice(p) else kotlin.math.abs(p).let { a ->
                String.format(java.util.Locale.US, if (a >= 1000) "%.0f" else if (a >= 100) "%.1f" else if (a >= 1) "%.3f" else "%.5f", p)
            }
            val axStyle = if (compactAxis) axisStyle.copy(fontSize = 9.sp) else axisStyle
            canvasSize[0] = size.width - axisW; canvasSize[1] = size.height
            // Bottom strip for the time axis.
            val timeH = 14.dp.toPx()
            val volH = (size.height - timeH) * 0.12f
            val chartW = size.width - axisW
            val chartH = size.height - timeH - volH - 12.dp.toPx()
            // No candles yet (new market): drop the old market tap targets too.
            if (candles.isEmpty()) { hits.clear(); return@Canvas }

            val gap = max(0, -offset.roundToInt())
            val end = (candles.size - max(0, offset.roundToInt())).coerceIn(1, candles.size)
            val start = max(0, end - (visible.roundToInt() - gap).coerceAtLeast(1))
            val slice = candles.subList(start, end)
            val barW = chartW / visible
            var hi: Double
            var lo: Double
            val m = manual
            if (m != null) { hi = m.first; lo = m.second } else {
                hi = slice.maxOf { it.h }; lo = slice.minOf { it.l }
                val pad = (hi - lo).coerceAtLeast(hi * 1e-4) * 0.08
                hi += pad; lo -= pad
            }
            geom[0] = hi; geom[1] = lo; geom[2] = chartH.toDouble()
            val y = { p: Double -> ((hi - p) / (hi - lo) * chartH).toFloat() }

            // grid + price axis; grid prices give way to the tags (last price, lines) near them
            val taken = listOf(y(candles.last().c) + 6.dp.toPx()) + lines.filterNot { it.ref }.filter { it.price in lo..hi }.map { y(it.price) }
            for (i in 0..5) {
                val p = lo + (hi - lo) * i / 5
                val yy = y(p)
                drawLine(C.line.copy(alpha = 0.35f), Offset(0f, yy), Offset(chartW, yy), 1f)
                if (taken.none { kotlin.math.abs(it - yy) < 18.dp.toPx() }) label(tm, ap(p), Offset(chartW + 3.dp.toPx(), yy - 6.dp.toPx()), axStyle)
            }
            drawLine(C.line, Offset(chartW, 0f), Offset(chartW, size.height), 1f)

            clipRect(0f, 0f, chartW, chartH) {
                sessions?.let { sessions(slice, { i -> chartW - (slice.size - i + gap) * barW + barW / 2 }, barW, chartH, it, tm, future = gap + 2) }
                if (showVp) volumeProfile(candles, slice, vpSince, chartW, y)
                slice.forEachIndexed { i, c ->
                    val x = chartW - (slice.size - i + gap) * barW + barW / 2
                    val col = if (c.c >= c.o) C.up else C.down
                    drawLine(col, Offset(x, y(c.h)), Offset(x, y(c.l)), 1.2f)
                    val top = y(max(c.o, c.c))
                    val h = max(1f, y(min(c.o, c.c)) - top)
                    val bw = max(1f, barW * 0.7f)
                    drawRect(col, Offset(x - bw / 2, top), Size(bw, h))
                }
                // Your fills: buys as ▲ under the candle, sells as ▼ over it; several on one candle stack outward.
                if (fills.isNotEmpty() && slice.size >= 2) {
                    val step = slice[1].openTime - slice[0].openTime
                    val first = slice.first().openTime
                    val tri = max(4.dp.toPx(), min(barW * 0.45f, 7.dp.toPx()))
                    val stack = HashMap<Pair<Int, Boolean>, Int>()
                    for (f in fills) {
                        if (f.time < first || f.time >= slice.last().openTime + step) continue
                        val i = ((f.time - first) / step).toInt().coerceIn(0, slice.size - 1)
                        val c = slice[i]
                        val x = chartW - (slice.size - i + gap) * barW + barW / 2
                        val n = stack.merge(i to f.buy, 1, Int::plus)!! - 1
                        val pad = 4.dp.toPx() + n * (tri * 1.6f)
                        val path = androidx.compose.ui.graphics.Path()
                        if (f.buy) {
                            val yTop = y(c.l) + pad
                            path.moveTo(x, yTop); path.lineTo(x - tri, yTop + tri * 1.4f); path.lineTo(x + tri, yTop + tri * 1.4f)
                        } else {
                            val yBot = y(c.h) - pad
                            path.moveTo(x, yBot); path.lineTo(x - tri, yBot - tri * 1.4f); path.lineTo(x + tri, yBot - tri * 1.4f)
                        }
                        path.close()
                        drawPath(path, if (f.buy) C.up else C.down)
                    }
                }
            }
            val maxVol = slice.maxOf { it.v }.coerceAtLeast(1e-12)
            val volBottom = size.height - timeH
            val volTop = volBottom - volH
            slice.forEachIndexed { i, c ->
                val x = chartW - (slice.size - i + gap) * barW + barW / 2
                val bw = max(1f, barW * 0.7f)
                val vh = (c.v / maxVol * volH * 0.9).toFloat()
                drawRect((if (c.c >= c.o) C.up else C.down).copy(alpha = 0.3f), Offset(x - bw / 2, volBottom - vh), Size(bw, vh))
            }
            drawLine(C.line.copy(alpha = 0.6f), Offset(0f, volTop), Offset(chartW, volTop), 1f)
            // Time axis: a label about every 70 dp, on whole multiples so labels don't jump while panning.
            drawLine(C.line.copy(alpha = 0.6f), Offset(0f, volBottom), Offset(size.width, volBottom), 1f)
            if (slice.size >= 2) {
                val step = slice[1].openTime - slice[0].openTime
                val every = max(1, kotlin.math.ceil(70.dp.toPx() / barW).toInt())
                val tz = java.time.ZoneId.systemDefault()
                val tStyle = axisStyle.copy(fontSize = 9.sp)
                for (i in slice.indices) {
                    if ((start + i) % every != 0) continue
                    val x = chartW - (slice.size - i + gap) * barW + barW / 2
                    val zt = java.time.Instant.ofEpochMilli(slice[i].openTime).atZone(tz)
                    val txt = when {
                        step >= 7 * 86_400_000L -> zt.format(java.time.format.DateTimeFormatter.ofPattern("MMM yy", java.util.Locale.US))
                        step >= 86_400_000L -> zt.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.US))
                        zt.hour == 0 && zt.minute == 0 -> zt.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.US))
                        else -> zt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.US))
                    }
                    val t = tm.measure(txt, tStyle)
                    val tx = x - t.size.width / 2
                    if (tx < 0 || tx + t.size.width > chartW) continue
                    drawLine(C.line.copy(alpha = 0.25f), Offset(x, 0f), Offset(x, chartH), 1f)
                    drawText(t, topLeft = Offset(tx, volBottom + 1.dp.toPx()))
                }
            }

            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            val dot = PathEffect.dashPathEffect(floatArrayOf(2f, 5f))
            val pillStyle = axisStyle.copy(color = Color.White, fontSize = 10.sp)
            val refStyle = axisStyle.copy(fontSize = 9.sp)
            val padX = 5.dp.toPx()
            val pillH = 16.dp.toPx()
            var topEdge = 2.dp.toPx()
            var bottomEdge = chartH - pillH - 2.dp.toPx()
            // Labels that would collide on the left are pushed right instead of overlapping.
            val placed = ArrayList<Rect>()
            fun slot(w: Float, top: Float): Float {
                var x = 6.dp.toPx()
                for (r in placed.sortedBy { it.left }) {
                    if (top < r.bottom && top + pillH > r.top && x < r.right && x + w > r.left) x = r.right + 4.dp.toPx()
                }
                placed += Rect(x, top, x + w, top + pillH)
                return x
            }
            // Text labels at the right edge of the plot (opens, sPOC) and price tags on the axis are
            // collected and laid out at the end so neither overlaps.
            val sideLabels = ArrayList<Pair<Float, androidx.compose.ui.text.TextLayoutResult>>()
            val axisTags = ArrayList<AxisTag>()
            for (l in lines.filter { it.ref }) {
                if (l.price !in lo..hi) continue
                val yy = y(l.price)
                drawLine(l.color.copy(alpha = 0.7f), Offset(0f, yy), Offset(chartW, yy), 1f, pathEffect = dot)
                sideLabels += yy to tm.measure(l.label, refStyle.copy(color = l.color))
            }
            hits.clear()
            // Position boxes are drawn last so nothing paints over them.
            val boxes = ArrayList<Triple<PosBox, Color, Float>>()
            // Lines first; their labels are queued and drawn after the price line and sPOC so they sit on top.
            val queued = ArrayList<() -> Unit>()
            // Above the plot stack from the top edge down, below it from the bottom edge up: order each side
            // farthest first so the level nearest the price ends up closest to the chart.
            val ordered = lines.filterNot { it.ref }.let { ls -> ls.filter { it.price >= lo }.sortedByDescending { it.price } + ls.filter { it.price < lo }.sortedBy { it.price } }
            for (l in ordered) {
                val parts = l.parts.ifEmpty { listOf(l.label) }
                if (l.price in lo..hi) {
                    val yy = y(l.price)
                    drawLine(l.color, Offset(0f, yy), Offset(chartW, yy), if (l.solid) 1.6f else 1.3f, pathEffect = if (l.solid) null else dash)
                    axisTags += AxisTag(yy, l.color, fmtPrice(l.price), Color.White)
                    val bx = l.box
                    if (bx != null) boxes += Triple(bx, l.color, yy)
                    else {
                        val shown = if (l.drag != null) parts + "⇕" else parts
                        val w = tagWidth(tm, shown, pillStyle)
                        val px0 = slot(w, yy - pillH / 2)
                        queued += { tagBox(tm, shown, l.color, Offset(px0, yy - pillH / 2), pillStyle, pillH) }
                        l.drag?.let { hits += Hit(Rect(px0, yy - pillH / 2, px0 + w, yy + pillH / 2), drag = it) }
                    }
                } else if (l.box == null) {
                    // Off-screen level: stack a tag on the edge it lies beyond, with its price.
                    val above = l.price > hi
                    val shown = listOf((if (above) "▲ " else "▼ ") + parts.first()) + parts.drop(1) + fmtPrice(l.price)
                    val at = if (above) topEdge else bottomEdge
                    queued += { tagBox(tm, shown, l.color, Offset(6.dp.toPx(), at), pillStyle, pillH) }
                    if (above) topEdge += pillH + 3.dp.toPx() else bottomEdge -= pillH + 3.dp.toPx()
                }
            }

            if (showPoc) profileOf(if (vpSince != null) candles.filter { it.openTime >= vpSince } else slice)?.let { pr ->
                val pp = pr.price(pr.poc)
                if (pp in lo..hi) {
                    val py = y(pp)
                    drawLine(POC_C, Offset(0f, py), Offset(chartW, py), 1.5f)
                    axisTags += AxisTag(py, POC_C, fmtPrice(pp), Color.Black)
                    sideLabels += py to tm.measure("sPOC", axisStyle.copy(color = POC_C, fontSize = 9.sp, fontWeight = FontWeight.Bold))
                }
            }

            // Ghost line under the finger while dragging a line, plus a Cancel target.
            dragging?.let {
                val t = tm.measure("✕  Cancel", pillStyle.copy(fontWeight = FontWeight.Bold, color = C.text))
                val cw = t.size.width + 28.dp.toPx(); val ch = 30.dp.toPx()
                val cx = size.width / 2 - cw / 2; val cy = 6.dp.toPx()
                drawRoundRect(C.bg.copy(alpha = 0.92f), Offset(cx, cy), Size(cw, ch), androidx.compose.ui.geometry.CornerRadius(15f, 15f))
                drawRoundRect(C.muted, Offset(cx, cy), Size(cw, ch), androidx.compose.ui.geometry.CornerRadius(15f, 15f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                drawText(t, topLeft = Offset(cx + 14.dp.toPx(), cy + (ch - t.size.height) / 2))
            }
            dragging?.let { (d, gy) ->
                val gp = hi - gy / chartH * (hi - lo)
                val (text, col) = d.preview(gp)
                drawLine(col, Offset(0f, gy), Offset(chartW, gy), 2f, pathEffect = dash)
                val w = tm.measure(text, pillStyle).size.width + padX * 2
                pill(tm, text, col, Offset(chartW - w - 6.dp.toPx(), gy - pillH - 3.dp.toPx()), pillStyle, padX, pillH)
                drawRect(col, Offset(chartW, gy - pillH / 2), Size(axisW, pillH))
                label(tm, fmtPrice(gp), Offset(chartW + 4.dp.toPx(), gy - 6.dp.toPx()), pillStyle)
            }

            val last = candles.last()
            val ly = y(last.c).coerceIn(0f, chartH)
            drawLine(MARK_C.copy(alpha = 0.8f), Offset(0f, ly), Offset(chartW, ly), 1f, pathEffect = dash)
            // Last price with the countdown under it stays put; other axis tags make way around it.
            val tagH = 16.dp.toPx()
            val cdH = 13.dp.toPx()
            val fixedTop = ly - tagH / 2
            val fixedBottom = ly + tagH / 2 + cdH
            val above = axisTags.filter { it.y < ly }.sortedByDescending { it.y }
            val below = axisTags.filter { it.y >= ly }.sortedBy { it.y }
            var limit = fixedTop
            for (t in above) { val top = minOf(t.y - tagH / 2, limit - tagH - 1f); drawAxisTag(tm, t, top, chartW, axisW, tagH); limit = top }
            limit = fixedBottom
            for (t in below) { val top = maxOf(t.y - tagH / 2, limit + 1f); drawAxisTag(tm, t, top, chartW, axisW, tagH); limit = top + tagH }
            drawRect(MARK_C, Offset(chartW, fixedTop), Size(axisW, tagH))
            label(tm, ap(last.c), Offset(chartW + 3.dp.toPx(), ly - 6.dp.toPx()), axStyle.copy(color = Color.Black))
            // Time left in the current candle, under the price tag.
            if (candles.size >= 2) {
                val step = candles[candles.size - 1].openTime - candles[candles.size - 2].openTime
                val left = ((last.openTime + step - now) / 1000).coerceAtLeast(0)
                val txt = if (left >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", left / 3600, left / 60 % 60, left % 60)
                    else String.format(java.util.Locale.US, "%02d:%02d", left / 60, left % 60)
                drawRect(MARK_C.copy(alpha = 0.75f), Offset(chartW, ly + tagH / 2), Size(axisW, cdH))
                label(tm, txt, Offset(chartW + 4.dp.toPx(), ly + tagH / 2 + 0.5.dp.toPx()), axisStyle.copy(color = Color.Black, fontSize = 9.sp))
            }
            // Right-edge text labels, spread apart top to bottom.
            var nextTop = -1f
            for ((yy, t) in sideLabels.sortedBy { it.first }) {
                val top = maxOf(yy - t.size.height - 1.dp.toPx(), nextTop)
                drawText(t, topLeft = Offset(chartW - t.size.width - 4.dp.toPx(), top))
                nextTop = top + t.size.height
            }
            queued.forEach { it() }
            for ((bx, col, by) in boxes) posBox(tm, bx, col, chartW, by, pillStyle, hits)

            // Crosshair with the candle under it: O/H/L/C, change, range and mid.
            cross?.let { c ->
                val cy = c.y.coerceIn(0f, chartH)
                // Snap the vertical line to the middle of the candle under it, so it steps cleanly bar to bar.
                val left = chartW - (slice.size + gap) * barW
                val i = ((c.x.coerceIn(0f, chartW) - left) / barW).toInt()
                val cx = (left + (i + 0.5f) * barW).coerceIn(0f, chartW)
                val dot = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))
                drawLine(C.text.copy(alpha = 0.6f), Offset(cx, 0f), Offset(cx, size.height), 1f, pathEffect = dot)
                drawLine(C.text.copy(alpha = 0.6f), Offset(0f, cy), Offset(chartW, cy), 1f, pathEffect = dot)
                val cp = hi - cy / chartH * (hi - lo)
                drawRect(C.text, Offset(chartW, cy - 8.dp.toPx()), Size(axisW, 16.dp.toPx()))
                label(tm, ap(cp), Offset(chartW + 3.dp.toPx(), cy - 6.dp.toPx()), axStyle.copy(color = C.bg))
                slice.getOrNull(i)?.let { k ->
                    val chg = if (k.o > 0) (k.c / k.o - 1) * 100 else 0.0
                    val rng = if (k.l > 0) (k.h / k.l - 1) * 100 else 0.0
                    val col = if (k.c >= k.o) C.up else C.down
                    val t = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.US).format(java.util.Date(k.openTime))
                    val lines = listOf(
                        t to C.muted,
                        "O ${fmtPrice(k.o)}  H ${fmtPrice(k.h)}" to C.text,
                        "L ${fmtPrice(k.l)}  C ${fmtPrice(k.c)}" to C.text,
                        String.format(java.util.Locale.US, "Chg %+.2f%%   Range %.2f%%", chg, rng) to col,
                        "Vol $" + fmtCompact(k.v * k.c) to C.text,
                        "Mid ${fmtPrice((k.h + k.l) / 2)}" to C.text,
                    )
                    val st = axisStyle.copy(fontSize = 10.sp)
                    val ms = lines.map { (txt, cl) -> tm.measure(txt, st.copy(color = cl)) }
                    val bw = ms.maxOf { it.size.width } + 16.dp.toPx()
                    val bh = ms.sumOf { it.size.height } + 12.dp.toPx()
                    // Keep the box on the side away from the finger.
                    val bx = if (cx > chartW / 2) 6.dp.toPx() else chartW - bw - 6.dp.toPx()
                    val by = 30.dp.toPx()
                    drawRoundRect(C.bg.copy(alpha = 0.92f), Offset(bx, by), Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(8f, 8f))
                    drawRoundRect(C.line, Offset(bx, by), Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(8f, 8f), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                    var yy = by + 6.dp.toPx()
                    ms.forEach { m -> drawText(m, topLeft = Offset(bx + 8.dp.toPx(), yy)); yy += m.size.height }
                }
            }
        }
        overlay()
        // Fit to screen: drops the manual price range.
        if (manual != null) androidx.compose.foundation.layout.Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 4.dp)
                .clip(RoundedCornerShape(6.dp)).background(C.panel2).border(1.dp, C.accent.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .clickable { manual = null }.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.FitScreen, null, tint = C.accent, modifier = Modifier.size(14.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
            Text("Auto", color = C.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private const val RIGHT_GAP = -4f

/** Volume by price: each candle's volume spread evenly over its high-low range in 48 bins. */
private class Profile(val lo: Double, val step: Double, val bins: DoubleArray) {
    val poc = bins.indices.maxBy { bins[it] }
    fun price(i: Int) = lo + step * (i + 0.5)
    /** Value area: grown outward from the POC until 70% of volume is covered. */
    val valueArea: IntRange by lazy {
        var a = poc; var b = poc
        var covered = bins[poc]
        val total = bins.sum()
        while (covered < total * 0.7 && (a > 0 || b < bins.size - 1)) {
            val up = if (b < bins.size - 1) bins[b + 1] else -1.0
            val dn = if (a > 0) bins[a - 1] else -1.0
            if (up >= dn) { b++; covered += up } else { a--; covered += dn }
        }
        a..b
    }
}

private fun profileOf(src: List<Candle>): Profile? {
    if (src.isEmpty()) return null
    val pHi = src.maxOf { it.h }
    val pLo = src.minOf { it.l }
    if (pHi <= pLo) return null
    val n = 48
    val step = (pHi - pLo) / n
    val bins = DoubleArray(n)
    for (c in src) {
        val a = ((c.l - pLo) / step).toInt().coerceIn(0, n - 1)
        val b = ((c.h - pLo) / step).toInt().coerceIn(0, n - 1)
        val share = c.v / (b - a + 1)
        for (i in a..b) bins[i] += share
    }
    return if (bins.max() > 0) Profile(pLo, step, bins) else null
}

/** Volume profile bars from the right edge; POC bin highlighted, value area brighter. */
private fun DrawScope.volumeProfile(all: List<Candle>, slice: List<Candle>, since: Long?, chartW: Float, y: (Double) -> Float) {
    val pr = profileOf(if (since != null) all.filter { it.openTime >= since } else slice) ?: return
    val maxV = pr.bins.max()
    val va = pr.valueArea
    val maxW = chartW * 0.3f
    for (i in pr.bins.indices) {
        val top = y(pr.lo + pr.step * (i + 1))
        val bot = y(pr.lo + pr.step * i)
        val w = (pr.bins[i] / maxV * maxW).toFloat()
        val col = when (i) {
            pr.poc -> C.accent.copy(alpha = 0.75f)
            in va -> vpColor.copy(alpha = 0.45f)
            else -> vpColor.copy(alpha = 0.2f)
        }
        drawRect(col, Offset(chartW - w, top + 0.5f), Size(w, max(1f, bot - top - 1f)))
    }
}

/** Open interest over the samples received since the market was opened. */
@Composable
fun OiSparkline(points: List<OiPoint>, modifier: Modifier = Modifier) {
    // Room for every label a busy chart draws (axis, tags, pills, crosshair box): the default 8 kept
    // missing and measuring all text again each frame.
    val tm = rememberTextMeasurer(cacheSize = 96)
    Canvas(modifier) {
        if (points.size < 2) {
            label(tm, "Collecting open interest…", Offset(8.dp.toPx(), size.height / 2 - 6.dp.toPx()), axisStyle)
            return@Canvas
        }
        val hi = points.maxOf { it.oi }
        val lo = points.minOf { it.oi }
        val span = (hi - lo).coerceAtLeast(hi * 1e-6)
        val t0 = points.first().time
        val tSpan = (points.last().time - t0).coerceAtLeast(1)
        val w = size.width - 56.dp.toPx()
        val path = Path()
        points.forEachIndexed { i, p ->
            val x = ((p.time - t0).toFloat() / tSpan) * w
            val yy = ((hi - p.oi) / span * (size.height - 4.dp.toPx())).toFloat() + 2.dp.toPx()
            if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
        }
        val rising = points.last().oi >= points.first().oi
        drawPath(path, if (rising) C.up else C.down, style = Stroke(2f))
        label(tm, fmtCompact(hi), Offset(w + 4.dp.toPx(), 0f), axisStyle)
        label(tm, fmtCompact(lo), Offset(w + 4.dp.toPx(), size.height - 12.dp.toPx()), axisStyle)
    }
}

/** A price tag on the right axis. */
private class AxisTag(val y: Float, val bg: Color, val text: String, val fg: Color)

private fun DrawScope.drawAxisTag(tm: TextMeasurer, t: AxisTag, top: Float, chartW: Float, axisW: Float, h: Float) {
    drawRect(t.bg, Offset(chartW, top), Size(axisW, h))
    val l = tm.measure(t.text, axisStyle.copy(color = t.fg, fontSize = 10.sp))
    drawText(l, topLeft = Offset(chartW + 4.dp.toPx(), top + (h - l.size.height) / 2))
}

/** Segmented position box right-aligned on the entry line; registers its buttons as hits. */
private fun DrawScope.posBox(tm: TextMeasurer, b: PosBox, col: Color, chartW: Float, yy: Float, style: TextStyle, hits: MutableList<Hit>) {
    val h = 20.dp.toPx()
    val padX = 6.dp.toPx()
    val top = yy - h / 2
    val bold = style.copy(fontWeight = FontWeight.Bold)
    val segs = listOf(
        Triple("Net PnL", bold.copy(color = Color.White), 0),
        Triple(b.pnl, bold.copy(color = col), 1),
        Triple("TP", bold.copy(color = C.up), 2),
        Triple("SL", bold.copy(color = C.down), 3),
        Triple("⇅", style.copy(color = C.text), 4),
        Triple("✕", style.copy(color = C.text), 5),
    )
    val layouts = segs.map { tm.measure(it.first, it.second) }
    // Reverse and close get wider targets than the labels.
    val widths = layouts.mapIndexed { i, l -> l.size.width + padX * (if (segs[i].third >= 4) 4 else 2) }
    var x = chartW - widths.sum() - 6.dp.toPx()
    val left = x
    drawRoundRect(C.bg, Offset(left, top), Size(widths.sum(), h), androidx.compose.ui.geometry.CornerRadius(5f, 5f))
    segs.forEachIndexed { i, sg ->
        val w = widths[i]
        if (sg.third == 0) drawRoundRect(col, Offset(x, top), Size(w, h), androidx.compose.ui.geometry.CornerRadius(5f, 5f))
        else drawLine(col.copy(alpha = 0.5f), Offset(x, top + 3f), Offset(x, top + h - 3f), 1f)
        drawText(layouts[i], topLeft = Offset(x + (w - layouts[i].size.width) / 2, top + (h - layouts[i].size.height) / 2))
        val r = Rect(x, top, x + w, top + h)
        when (sg.third) {
            2 -> hits += Hit(r, drag = b.tp)
            3 -> hits += Hit(r, drag = b.sl)
            4 -> hits += Hit(r, tap = b.onReverse)
            5 -> hits += Hit(r, tap = b.onClose)
        }
        x += w
    }
    drawRoundRect(col, Offset(left, top), Size(widths.sum(), h), androidx.compose.ui.geometry.CornerRadius(5f, 5f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
}

private fun DrawScope.tagWidth(tm: TextMeasurer, parts: List<String>, style: TextStyle): Float =
    parts.sumOf { (tm.measure(it, style.copy(fontWeight = FontWeight.Bold)).size.width + 6.dp.toPx() * 2).toDouble() }.toFloat()

/** Segmented tag in the position-box style: filled label, then values in the line colour, bordered. */
private fun DrawScope.tagBox(tm: TextMeasurer, parts: List<String>, col: Color, at: Offset, style: TextStyle, h: Float) {
    val padX = 6.dp.toPx()
    val bold = style.copy(fontWeight = FontWeight.Bold)
    val layouts = parts.mapIndexed { i, t -> tm.measure(t, bold.copy(color = if (i == 0) Color.White else col)) }
    val widths = layouts.map { it.size.width + padX * 2 }
    val total = widths.sum()
    val r = androidx.compose.ui.geometry.CornerRadius(5f, 5f)
    drawRoundRect(C.bg, at, Size(total, h), r)
    var x = at.x
    layouts.forEachIndexed { i, l ->
        if (i == 0) drawRoundRect(col, Offset(x, at.y), Size(widths[i], h), r)
        else drawLine(col.copy(alpha = 0.5f), Offset(x, at.y + 3f), Offset(x, at.y + h - 3f), 1f)
        drawText(l, topLeft = Offset(x + padX, at.y + (h - l.size.height) / 2))
        x += widths[i]
    }
    drawRoundRect(col, at, Size(total, h), r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
}

private fun DrawScope.pill(tm: TextMeasurer, s: String, bg: Color, at: Offset, style: TextStyle, padX: Float, h: Float) {
    val layout = tm.measure(s, style)
    drawRoundRect(bg, at, Size(layout.size.width + padX * 2, h), androidx.compose.ui.geometry.CornerRadius(4f, 4f))
    drawText(layout, topLeft = Offset(at.x + padX, at.y + (h - layout.size.height) / 2))
}

private fun DrawScope.label(tm: TextMeasurer, s: String, at: Offset, style: TextStyle) =
    drawText(tm, s, at, style)

/** Cumulative depth: bids stepping down to the left of mid, asks stepping up to the right. */
@Composable
fun DepthChart(book: com.alex.mfppro.data.Book?, modifier: Modifier = Modifier) {
    // Room for every label a busy chart draws (axis, tags, pills, crosshair box): the default 8 kept
    // missing and measuring all text again each frame.
    val tm = rememberTextMeasurer(cacheSize = 96)
    Canvas(modifier) {
        val b = book ?: return@Canvas
        val bids = b.bids.take(50); val asks = b.asks.take(50)
        if (bids.isEmpty() || asks.isEmpty()) return@Canvas
        val mid = (bids.first().px + asks.first().px) / 2
        val span = maxOf(mid - bids.last().px, asks.last().px - mid).coerceAtLeast(mid * 1e-6)
        val lo = mid - span; val hi = mid + span
        val bottom = 16.dp.toPx()
        val h = size.height - bottom
        var cum = 0.0
        val bidPts = bids.map { cum += it.sz; it.px to cum }
        cum = 0.0
        val askPts = asks.map { cum += it.sz; it.px to cum }
        val maxCum = maxOf(bidPts.last().second, askPts.last().second)
        val x = { p: Double -> ((p - lo) / (hi - lo) * size.width).toFloat() }
        val y = { c: Double -> (h - c / maxCum * h * 0.92).toFloat() }
        fun side(pts: List<Pair<Double, Double>>, col: Color) {
            val path = Path().apply {
                moveTo(x(pts.first().first), h)
                var prevY = h
                for ((px, c) in pts) { lineTo(x(px), prevY); prevY = y(c); lineTo(x(px), prevY) }
                lineTo(x(pts.last().first), h); close()
            }
            drawPath(path, col.copy(alpha = 0.25f))
            drawPath(path, col, style = Stroke(1.5f))
        }
        side(bidPts, C.up); side(askPts, C.down)
        drawLine(C.muted, Offset(x(mid), 0f), Offset(x(mid), h), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        label(tm, fmtPrice(mid), Offset(x(mid) - 24.dp.toPx(), 2.dp.toPx()), axisStyle.copy(color = C.text))
        label(tm, fmtPrice(lo), Offset(0f, h + 2.dp.toPx()), axisStyle)
        val hiTxt = fmtPrice(hi)
        label(tm, hiTxt, Offset(size.width - tm.measure(hiTxt, axisStyle).size.width, h + 2.dp.toPx()), axisStyle)
        label(tm, fmtSize(bidPts.last().second), Offset(2.dp.toPx(), y(bidPts.last().second) - 14.dp.toPx()), axisStyle.copy(color = C.up))
        val askTxt = fmtSize(askPts.last().second)
        label(tm, askTxt, Offset(size.width - tm.measure(askTxt, axisStyle).size.width - 2.dp.toPx(), y(askPts.last().second) - 14.dp.toPx()), axisStyle.copy(color = C.down))
    }
}
