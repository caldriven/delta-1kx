package com.alex.mfppro.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alex.mfppro.data.Candle
import com.alex.mfppro.data.Killzones
import com.alex.mfppro.data.Liquidity
import com.alex.mfppro.data.Market
import com.alex.mfppro.data.VolumeProfile
import com.alex.mfppro.ui.fmtCompact
import com.alex.mfppro.ui.fmtPct
import com.alex.mfppro.ui.fmtPrice
import com.alex.mfppro.ui.withLive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A horizontal price line drawn on the chart (position entry, liquidation, order). */
data class ChartLine(val price: Double, val color: Color, val label: String, val dotted: Boolean = false)

private val POC_C = Color(0xFFA78BFA)
private val VP_C = Color(0xFF6B7A90)

/** Indicator results for one chart, worked out when the bars change, not on every frame. */
private class Calc(
    val base: DoubleArray?,
    val bands: List<Liquidity.Band>,
    val pools: List<Liquidity.Pool>,
    val magnets: List<Liquidity.Magnet>,
    val compression: Double?,
    val npocs: List<Pair<Long, Double>>,
    val levels: List<Killzones.Level>,
)

private fun calc(bars: List<Candle>, days: List<Candle>, ind: Ind): Calc {
    val s = ind.liqSettings
    val base = if (ind.liqBands && bars.isNotEmpty()) Liquidity.base(bars, s) else null
    val pools = if ((ind.liqPools || ind.liqMagnets) && bars.size > 20) Liquidity.pools(bars, s) else emptyList()
    val last = bars.lastOrNull()?.c ?: 0.0
    return Calc(
        base = base,
        bands = if (base != null) Liquidity.bands(base, s) else emptyList(),
        pools = pools,
        magnets = if (ind.liqMagnets) Liquidity.magnets(pools, last, Liquidity.atrPct(bars)) else emptyList(),
        compression = Liquidity.compression(bars),
        npocs = if (ind.vp && ind.nakedPocs) VolumeProfile.nakedPocs(bars) else emptyList(),
        levels = if (ind.opens) Killzones.levels(bars, days).filter {
            when (it.kind) {
                Killzones.Kind.DAY -> ind.opensDay; Killzones.Kind.WEEK -> ind.opensWeek; Killzones.Kind.MONTH -> ind.opensMonth
                Killzones.Kind.NY_MIDNIGHT -> ind.opensNyMid; Killzones.Kind.PDH, Killzones.Kind.PDL -> ind.prevDay
            }
        } else emptyList(),
    )
}

/**
 * Candlestick chart with the terminal's indicators.
 * Wheel zooms around the cursor, Shift+wheel or drag pans, dragging the price axis stretches the
 * price scale, double-click resets.
 * [raw] are the stream's bars; [live] is the price folded into the forming bar.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChartView(
    raw: List<Candle>,
    live: Double?,
    m: Market,
    ind: Ind,
    lines: List<ChartLine>,
    days: List<Candle>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val bars = remember(raw, live) { raw.withLive(live) }
    val c = remember(raw, days, ind) { calc(raw, days, ind) }
    val tm = rememberTextMeasurer(cacheSize = 256)
    val density = LocalDensity.current
    val axisW = with(density) { (if (compact) 60.dp else 74.dp).toPx() }

    var count by remember(m.marketId) { mutableFloatStateOf(if (compact) 90f else 140f) }
    var shift by remember(m.marketId) { mutableFloatStateOf(-6f) }
    var manual by remember(m.marketId) { mutableStateOf<Pair<Double, Double>?>(null) }
    var hover by remember { mutableStateOf<Offset?>(null) }
    var drag by remember { mutableStateOf<Pair<Int, Offset>?>(null) } // mode (1 pan, 2 axis), last position
    var lastClick by remember { mutableLongStateOf(0L) }
    val geom = remember { DoubleArray(4) } // hi, lo, plotH, plotW
    val kzCache = remember { HashMap<Long, FloatArray>() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1_000); now = System.currentTimeMillis() } }

    fun maxShift() = max(0f, bars.size - 10f)
    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerHoverIcon(PointerIcon.Crosshair)
                .onPointerEvent(PointerEventType.Scroll) { e ->
                    val ch = e.changes.first()
                    val dy = ch.scrollDelta.y
                    val plotW = geom[3].toFloat().coerceAtLeast(1f)
                    if (e.keyboardModifiers.isShiftPressed || ch.scrollDelta.x != 0f) {
                        val d = if (ch.scrollDelta.x != 0f) ch.scrollDelta.x else dy
                        shift = (shift - d * count * 0.04f).coerceIn(-count * 0.5f, maxShift())
                    } else {
                        // Zoom around the bar under the cursor.
                        val x = ch.position.x.coerceIn(0f, plotW)
                        val anchor = shift + (plotW - x) / plotW * count
                        val next = (count * (1f + dy * 0.1f)).coerceIn(15f, max(30f, bars.size + 40f))
                        shift = (anchor - (plotW - x) / plotW * next).coerceIn(-next * 0.5f, maxShift())
                        count = next
                    }
                }
                .onPointerEvent(PointerEventType.Press) { e ->
                    val p = e.changes.first().position
                    val t = System.currentTimeMillis()
                    if (t - lastClick < 320) { count = if (compact) 90f else 140f; shift = -6f; manual = null; drag = null; lastClick = 0; return@onPointerEvent }
                    lastClick = t
                    drag = (if (p.x > geom[3]) 2 else 1) to p
                }
                .onPointerEvent(PointerEventType.Release) { drag = null }
                .onPointerEvent(PointerEventType.Move) { e ->
                    val p = e.changes.first().position
                    hover = p
                    val (mode, from) = drag ?: return@onPointerEvent
                    val d = p - from
                    val hi = geom[0]; val lo = geom[1]; val plotH = geom[2]
                    if (mode == 2) {
                        val (h0, l0) = manual ?: (hi to lo)
                        val f = exp(d.y * 0.006)
                        val mid = (h0 + l0) / 2; val half = (h0 - l0) / 2 * f
                        manual = (mid + half) to (mid - half)
                    } else {
                        val slot = geom[3].toFloat() / count
                        shift = (shift + d.x / slot).coerceIn(-count * 0.5f, maxShift())
                        manual?.let { (h0, l0) ->
                            val dp = d.y / plotH.coerceAtLeast(1.0) * (h0 - l0)
                            manual = (h0 + dp) to (l0 + dp)
                        }
                    }
                    drag = mode to p
                }
                .onPointerEvent(PointerEventType.Exit) { hover = null; drag = null },
        ) {
            if (bars.isEmpty()) return@Canvas
            val px = { v: Float -> v.dp.toPx() }
            val timeH = px(18f)
            val plotW = size.width - axisW
            val plotH = size.height - timeH
            val n = count
            val slot = plotW / n
            val last = bars.lastIndex
            fun xOf(i: Int) = plotW - (last - i + 0.5f + shift) * slot
            val iFrom = max(0, floor(last - shift - n).toInt())
            val iTo = min(last, ceil(last - shift).toInt())
            if (iTo < iFrom) return@Canvas
            val view = bars.subList(iFrom, iTo + 1)

            var hi: Double; var lo: Double
            val mr = manual
            if (mr != null) { hi = mr.first; lo = mr.second } else {
                hi = view.maxOf { it.h }; lo = view.minOf { it.l }
                val pad = (hi - lo).coerceAtLeast(hi * 1e-4) * 0.09
                hi += pad; lo -= pad
            }
            geom[0] = hi; geom[1] = lo; geom[2] = plotH.toDouble(); geom[3] = plotW.toDouble()
            fun y(p: Double) = ((hi - p) / (hi - lo) * plotH).toFloat()
            val step = if (bars.size >= 2) bars[last].openTime - bars[last - 1].openTime else 60_000L
            // Time to x, through the bars so market closes and gaps line up with the candles.
            fun xAt(t: Long): Float {
                if (t >= bars[last].openTime) return xOf(last) - slot / 2 + (t - bars[last].openTime).toFloat() / step * slot
                var a = 0; var b = last
                while (a < b) { val mid = (a + b) / 2; if (bars[mid].openTime < t) a = mid + 1 else b = mid }
                return xOf(a) - slot / 2
            }
            val fs = if (compact) 9.5f else 10.5f
            val axisStyle = num.copy(fontSize = fs.sp, color = D.muted)
            val dash = PathEffect.dashPathEffect(floatArrayOf(px(4f), px(4f)))
            val dot = PathEffect.dashPathEffect(floatArrayOf(px(1.5f), px(3f)))

            // ---- grid ---------------------------------------------------------------------------
            val gStep = niceStep((hi - lo) / max(3, (plotH / px(64f)).toInt()))
            val axisLabels = ArrayList<Pair<Float, String>>()
            run {
                var g = ceil(lo / gStep) * gStep
                while (g < hi) { val gy = y(g); drawLine(D.line.copy(alpha = 0.45f), Offset(0f, gy), Offset(plotW, gy), 1f); axisLabels += gy to fmtPrice(g, m); g += gStep }
            }
            val zone = ZoneId.systemDefault()
            val every = max(1, ceil(px(if (compact) 70f else 95f) / slot).toInt())
            val tfmt = when {
                step >= 86_400_000L -> DateTimeFormatter.ofPattern("d MMM", Locale.US)
                else -> DateTimeFormatter.ofPattern("HH:mm", Locale.US)
            }.withZone(zone)
            val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.US).withZone(zone)
            for (i in iFrom..iTo) {
                if (i % every != 0) continue
                val x = xOf(i)
                drawLine(D.line.copy(alpha = 0.3f), Offset(x, 0f), Offset(x, plotH), 1f)
                val t = bars[i].openTime
                val z = Instant.ofEpochMilli(t).atZone(zone)
                val txt = if (step < 86_400_000L && z.hour == 0 && z.minute < (step / 60_000)) dayFmt.format(Instant.ofEpochMilli(t)) else tfmt.format(Instant.ofEpochMilli(t))
                val r = tm.measure(txt, axisStyle)
                if (x - r.size.width / 2 > 0 && x + r.size.width / 2 < plotW) drawText(r, topLeft = Offset(x - r.size.width / 2f, plotH + px(3f)))
            }

            val sideTags = ArrayList<SideTag>()   // right-edge labels inside the plot
            val edgeTags = ArrayList<Triple<Boolean, String, Color>>() // above?, text, colour: levels off screen
            val axisTags = ArrayList<AxisTag>()   // filled tags on the price axis

            clipRect(0f, 0f, plotW, plotH) {
                // ---- sessions: killzones along the bottom, market hours along the top -------------
                val intraday = step <= 3_600_000L
                if (intraday && (ind.killzones || ind.markets)) {
                    if (kzCache.size > 6_000) kzCache.clear()
                    val stripH = px(4f)
                    val windows = Killzones.KILLZONES + Killzones.MARKETS
                    fun prog(t: Long) = kzCache.getOrPut(t * 7 + step) {
                        // Progress at the middle of the bar, so a bar half inside a window counts.
                        FloatArray(windows.size) { k -> Killzones.progress(windows[k], t + step / 2)?.toFloat() ?: -1f }
                    }
                    val labelStyle = TextStyle(fontFamily = Inter, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    for (i in iFrom..iTo) {
                        val p = prog(bars[i].openTime)
                        val x = xOf(i) - slot / 2
                        for (k in windows.indices) {
                            val isKz = k < Killzones.KILLZONES.size
                            if (p[k] < 0 || (isKz && !ind.killzones) || (!isKz && !ind.markets)) continue
                            val col = Color(windows[k].rgb)
                            // Light at the start of the window, full colour at its end.
                            val a = 0.25f + 0.7f * p[k]
                            val top = if (isKz) plotH - stripH else 0f
                            drawRect(col.copy(alpha = a), Offset(x, top), Size(slot + 0.5f, stripH))
                            val prev = if (i > 0) prog(bars[i - 1].openTime)[k] else -1f
                            if (prev < 0) {
                                val r = tm.measure(windows[k].short, labelStyle.copy(color = col.copy(alpha = 0.85f)))
                                val room = windows[k].lengthMin * 60_000f / step * slot
                                if (room > r.size.width + px(6f)) drawText(r, topLeft = Offset(x + px(2f), if (isKz) top - r.size.height - px(1f) else stripH + px(1f)))
                            }
                        }
                    }
                }

                // ---- volume profile (left edge) ----------------------------------------------------
                if (ind.vp) {
                    val src = if (ind.vpRange == "session") {
                        val dayStart = Math.floorDiv(now, 86_400_000L) * 86_400_000L
                        bars.filter { it.openTime >= dayStart }
                    } else view
                    VolumeProfile.of(src, (plotH / px(5f)).toInt().coerceIn(24, 120))?.let { pr ->
                        val maxW = plotW * 0.16f
                        val va = pr.valueArea
                        val lvn = pr.lvns.toSet()
                        for (i in 0 until pr.bins) {
                            val top = y(pr.lo + pr.step * (i + 1)); val bot = y(pr.lo + pr.step * i)
                            val h = max(1f, bot - top - 1f)
                            val inVa = i in va
                            val wB = (pr.buy[i] / pr.max * maxW).toFloat(); val wS = (pr.sell[i] / pr.max * maxW).toFloat()
                            val a = if (i == pr.poc) 0.6f else if (inVa) 0.3f else 0.12f
                            drawRect(D.up.copy(alpha = a), Offset(0f, top + 0.5f), Size(wB, h))
                            drawRect(D.down.copy(alpha = a), Offset(wB, top + 0.5f), Size(wS, h))
                            if (i in lvn) drawRect(D.accent.copy(alpha = 0.7f), Offset(wB + wS + px(2f), top + 0.5f), Size(px(3f), h))
                        }
                        if (ind.vpLevels) {
                            val pp = pr.price(pr.poc)
                            drawLine(POC_C.copy(alpha = 0.9f), Offset(0f, y(pp)), Offset(plotW, y(pp)), px(1f))
                            sideTags += SideTag(y(pp), "POC", POC_C)
                            for ((v, name) in listOf(pr.vah to "VAH", pr.vaLow to "VAL")) {
                                drawLine(VP_C.copy(alpha = 0.8f), Offset(0f, y(v)), Offset(plotW, y(v)), 1f, pathEffect = dash)
                                sideTags += SideTag(y(v), name, Color(0xFF9AA8BC))
                            }
                        }
                    }
                    for ((start, poc) in c.npocs) {
                        if (poc !in lo..hi) continue
                        val x0 = xAt(start).coerceAtLeast(0f)
                        if (x0 >= plotW) continue
                        drawLine(POC_C.copy(alpha = 0.6f), Offset(x0, y(poc)), Offset(plotW, y(poc)), 1f, pathEffect = dot)
                        sideTags += SideTag(y(poc), "nPOC", POC_C.copy(alpha = 0.8f))
                    }
                }

                // ---- liquidity pools (right edge heat) -------------------------------------------
                if (ind.liqPools && c.pools.isNotEmpty()) {
                    val rows = (plotH / px(3f)).toInt().coerceAtLeast(10)
                    Liquidity.heat(c.pools, lo, hi, rows)?.takeIf { it.max > 0 }?.let { h ->
                        val maxW = plotW * 0.12f
                        val rowH = plotH / rows
                        for (i in 0 until rows) {
                            for ((w, col) in listOf(h.longs[i] to D.down, h.shorts[i] to D.up)) {
                                if (w <= 0) continue
                                val f = sqrt(w / h.max).toFloat()
                                val yy = y(h.price(i)) - rowH / 2
                                drawRect(col.copy(alpha = 0.15f + 0.55f * f), Offset(plotW - f * maxW, yy), Size(f * maxW, max(1f, rowH - 0.5f)))
                            }
                        }
                    }
                }

                // ---- leverage bands ---------------------------------------------------------------
                if (ind.liqBands) {
                    fun series(values: DoubleArray, col: Color, width: Float, effect: PathEffect? = null) {
                        val path = Path(); var started = false
                        for (i in iFrom..iTo) {
                            val v = values[i]
                            if (v.isNaN()) { started = false; continue }
                            val pt = Offset(xOf(i), y(v))
                            if (!started) { path.moveTo(pt.x, pt.y); started = true } else path.lineTo(pt.x, pt.y)
                        }
                        drawPath(path, col, style = Stroke(width, pathEffect = effect))
                    }
                    c.base?.let { series(it, D.text.copy(alpha = 0.35f), px(1f), dash) }
                    for (b in c.bands) {
                        val col = tierColor(b.lev).copy(alpha = 0.6f)
                        series(b.longs, col, px(1f)); series(b.shorts, col, px(1f))
                        b.longs.getOrNull(iTo)?.takeIf { !it.isNaN() }?.let { sideTags += SideTag(y(it), "${b.lev}x L", tierColor(b.lev)) }
                        b.shorts.getOrNull(iTo)?.takeIf { !it.isNaN() }?.let { sideTags += SideTag(y(it), "${b.lev}x S", tierColor(b.lev)) }
                    }
                }

                // ---- opens and previous day --------------------------------------------------------
                val ref = bars.last().c
                for (l in c.levels) {
                    if (l.price !in lo..hi) continue
                    val x0 = xAt(l.start).coerceAtLeast(0f)
                    val x1 = if (l.current) plotW else xAt(l.end).coerceAtMost(plotW)
                    if (x1 <= x0) continue
                    val near = abs(l.price / ref - 1) * 100 <= ind.fadePct
                    val col = Color(l.kind.rgb)
                    val a = when { l.current && near -> 0.95f; l.current -> 0.45f; near -> 0.45f; else -> 0.2f }
                    val prev = l.kind == Killzones.Kind.PDH || l.kind == Killzones.Kind.PDL
                    drawLine(col.copy(alpha = a), Offset(x0, y(l.price)), Offset(x1, y(l.price)),
                        if (l.current) px(1.4f) else px(1f), pathEffect = if (prev) dash else dot)
                    if (l.current) sideTags += SideTag(y(l.price), l.kind.short, col.copy(alpha = if (near) 1f else 0.55f))
                }

                // ---- candles ------------------------------------------------------------------------
                val bw = max(1f, slot * 0.68f)
                for (i in iFrom..iTo) {
                    val k = bars[i]
                    val x = xOf(i)
                    val col = if (k.c >= k.o) D.up else D.down
                    drawLine(col, Offset(x, y(k.h)), Offset(x, y(k.l)), max(1f, px(0.8f)))
                    val top = y(max(k.o, k.c)); val bot = y(min(k.o, k.c))
                    drawRect(col, Offset(x - bw / 2, top), Size(bw, max(1f, bot - top)))
                }

                // ---- volume (bottom overlay) --------------------------------------------------------
                if (ind.volume) {
                    val volH = plotH * 0.13f
                    val maxV = view.maxOf { it.v }.coerceAtLeast(1e-12)
                    val base = plotH - if (ind.killzones && intraday) px(5f) else 0f
                    for (i in iFrom..iTo) {
                        val k = bars[i]
                        val h = (k.v / maxV * volH).toFloat()
                        drawRect((if (k.c >= k.o) D.up else D.down).copy(alpha = 0.28f), Offset(xOf(i) - bw / 2, base - h), Size(bw, h))
                    }
                }

                // ---- sweep targets ------------------------------------------------------------------
                for (mg in c.magnets) {
                    val col = if (mg.longs) D.down else D.up
                    val text = (if (mg.longs) "▼ long liqs " else "▲ short liqs ") + fmtPct(mg.distPct)
                    // Off the visible range: pinned to the edge it lies beyond, so the target is never lost.
                    if (mg.price !in lo..hi) { edgeTags += Triple(mg.price > hi, text, col); continue }
                    val my = y(mg.price)
                    drawLine(col.copy(alpha = 0.85f), Offset(xOf(iTo), my), Offset(plotW, my), px(1.2f), pathEffect = dash)
                    sideTags += SideTag(my, text, col, bold = true)
                }

                // ---- positions -------------------------------------------------------------------------
                for (l in lines) {
                    if (l.price !in lo..hi) continue
                    val ly = y(l.price)
                    drawLine(l.color, Offset(0f, ly), Offset(plotW, ly), px(1.2f), pathEffect = if (l.dotted) dot else dash)
                    axisTags += AxisTag(ly, l.color, fmtPrice(l.price, m), Color.Black)
                    if (l.label.isNotBlank()) sideTags += SideTag(ly, l.label, l.color, bold = true)
                }
            }

            // ---- right-edge labels, spread so none overlap ----------------------------------------------
            val tagStyle = TextStyle(fontFamily = Inter, fontSize = (fs - 0.5f).sp, fontWeight = FontWeight.Medium)
            var nextTop = -1f
            // Merge labels at the same height (D open and W open on one price) into one.
            val merged = sideTags.sortedBy { it.y }.fold(ArrayList<SideTag>()) { acc, t ->
                val prev = acc.lastOrNull()
                if (prev != null && abs(prev.y - t.y) < 1.5f) acc[acc.lastIndex] = prev.copy(text = prev.text + " · " + t.text) else acc += t
                acc
            }
            for (t in merged) {
                val r = tm.measure(t.text, tagStyle.copy(color = t.color, fontWeight = if (t.bold) FontWeight.Bold else FontWeight.Medium))
                val top = max(t.y - r.size.height / 2f, nextTop)
                if (top + r.size.height > plotH) continue
                val left = plotW - r.size.width - px(6f)
                drawRoundRect(D.bg.copy(alpha = 0.75f), Offset(left - px(3f), top), Size(r.size.width + px(6f), r.size.height.toFloat()), CornerRadius(px(3f)))
                drawText(r, topLeft = Offset(left, top))
                nextTop = top + r.size.height + 1f
            }

            var edgeTop = px(26f); var edgeBottom = plotH - px(24f)
            for ((above, text, col) in edgeTags) {
                val r = tm.measure(text, tagStyle.copy(color = col, fontWeight = FontWeight.Bold))
                val top = if (above) edgeTop else edgeBottom - r.size.height
                val left = plotW - r.size.width - px(6f)
                drawRoundRect(D.bg.copy(alpha = 0.85f), Offset(left - px(4f), top - px(1f)), Size(r.size.width + px(8f), r.size.height + px(2f)), CornerRadius(px(3f)))
                drawRoundRect(col.copy(alpha = 0.6f), Offset(left - px(4f), top - px(1f)), Size(r.size.width + px(8f), r.size.height + px(2f)), CornerRadius(px(3f)), style = Stroke(1f))
                drawText(r, topLeft = Offset(left, top))
                if (above) edgeTop += r.size.height + px(4f) else edgeBottom -= r.size.height + px(4f)
            }

            // ---- price axis ------------------------------------------------------------------------------
            drawRect(D.panel, Offset(plotW, 0f), Size(axisW, size.height))
            drawLine(D.line, Offset(plotW, 0f), Offset(plotW, size.height), 1f)
            drawLine(D.line, Offset(0f, plotH), Offset(plotW, plotH), 1f)
            val lastBar = bars.last()
            val ly = y(lastBar.c)
            val tagH = px(16f)
            val reserved = ArrayList<ClosedFloatingPointRange<Float>>()
            if (ly in 0f..plotH) reserved += (ly - tagH / 2)..(ly + tagH / 2 + px(13f))
            axisTags.forEach { reserved += (it.y - tagH / 2)..(it.y + tagH / 2) }
            for ((gy, txt) in axisLabels) {
                if (reserved.any { gy in (it.start - px(8f))..(it.endInclusive + px(8f)) }) continue
                val r = tm.measure(txt, axisStyle)
                drawText(r, topLeft = Offset(plotW + px(6f), gy - r.size.height / 2f))
            }
            for (t in axisTags) {
                drawRect(t.bg, Offset(plotW, t.y - tagH / 2), Size(axisW, tagH))
                val r = tm.measure(t.text, axisStyle.copy(color = t.fg, fontWeight = FontWeight.Bold))
                drawText(r, topLeft = Offset(plotW + px(6f), t.y - r.size.height / 2f))
            }
            if (ly in 0f..plotH) {
                val col = if (lastBar.c >= lastBar.o) D.up else D.down
                drawLine(col.copy(alpha = 0.8f), Offset(0f, ly), Offset(plotW, ly), 1f, pathEffect = dot)
                drawRect(col, Offset(plotW, ly - tagH / 2), Size(axisW, tagH))
                val r = tm.measure(fmtPrice(lastBar.c, m), axisStyle.copy(color = Color.White, fontWeight = FontWeight.Bold))
                drawText(r, topLeft = Offset(plotW + px(6f), ly - r.size.height / 2f))
                // Time left in the forming candle.
                val left = ((lastBar.openTime + step - now) / 1000).coerceAtLeast(0)
                val cd = if (left >= 3600) String.format(Locale.US, "%d:%02d:%02d", left / 3600, left / 60 % 60, left % 60) else String.format(Locale.US, "%02d:%02d", left / 60, left % 60)
                drawRect(col.copy(alpha = 0.55f), Offset(plotW, ly + tagH / 2), Size(axisW, px(13f)))
                val cr = tm.measure(cd, axisStyle.copy(color = Color.White, fontSize = (fs - 1).sp))
                drawText(cr, topLeft = Offset(plotW + px(6f), ly + tagH / 2 + (px(13f) - cr.size.height) / 2))
            }

            // ---- crosshair and the bar readout -------------------------------------------------------
            val h = hover
            val hi0 = h?.takeIf { it.x < plotW && it.y < plotH }
            val hoverIdx = hi0?.let { ((last + 0.5f + shift) - (plotW - it.x) / slot).roundToInt().coerceIn(0, last) }
            if (hi0 != null) {
                val cx = xOf(hoverIdx!!)
                drawLine(D.muted.copy(alpha = 0.6f), Offset(cx, 0f), Offset(cx, plotH), 1f, pathEffect = dash)
                drawLine(D.muted.copy(alpha = 0.6f), Offset(0f, hi0.y), Offset(plotW, hi0.y), 1f, pathEffect = dash)
                val cp = hi - hi0.y / plotH * (hi - lo)
                drawRect(D.panel2, Offset(plotW, hi0.y - tagH / 2), Size(axisW, tagH))
                val r = tm.measure(fmtPrice(cp, m), axisStyle.copy(color = D.text))
                drawText(r, topLeft = Offset(plotW + px(6f), hi0.y - r.size.height / 2f))
                val tt = tm.measure(DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.US).withZone(zone).format(Instant.ofEpochMilli(bars[hoverIdx].openTime)), axisStyle.copy(color = D.text))
                val tx = (cx - tt.size.width / 2f).coerceIn(0f, plotW - tt.size.width)
                drawRect(D.panel2, Offset(tx - px(4f), plotH + 1f), Size(tt.size.width + px(8f), timeH - 1f))
                drawText(tt, topLeft = Offset(tx, plotH + px(3f)))
            }
            val k = bars[hoverIdx ?: last]
            val chg = if (k.o > 0) (k.c / k.o - 1) * 100 else 0.0
            val kc = if (k.c >= k.o) D.up else D.down
            val legend = buildString {
                append("O ").append(fmtPrice(k.o, m)).append("  H ").append(fmtPrice(k.h, m)).append("  L ").append(fmtPrice(k.l, m)).append("  C ").append(fmtPrice(k.c, m))
                append("  ").append(fmtPct(chg))
                if (!compact) append("  Vol $").append(fmtCompact(k.v * k.c))
            }
            drawText(tm.measure(legend, num.copy(fontSize = (fs + 0.5f).sp, color = kc)), topLeft = Offset(px(8f), px(6f)))
            // A compressed range with pools stacked close on both sides is when a sweep tends to come.
            val comp = c.compression
            if (comp != null && comp < 0.65 && (ind.liqPools || ind.liqMagnets)) {
                val txt = "Range compressed · ATR ${String.format(Locale.US, "%.2f", comp)}× normal · sweep risk"
                val r = tm.measure(txt, TextStyle(fontFamily = Inter, fontSize = (fs - 0.5f).sp, fontWeight = FontWeight.SemiBold, color = D.accent))
                val top = px(8f) + r.size.height + px(6f)
                drawRoundRect(D.accent.copy(alpha = 0.14f), Offset(px(6f), top - px(2f)), Size(r.size.width + px(10f), r.size.height + px(4f)), CornerRadius(px(4f)))
                drawText(r, topLeft = Offset(px(11f), top))
            }
            if (manual != null) {
                val r = tm.measure("manual scale · double-click to fit", axisStyle.copy(fontSize = (fs - 1).sp))
                drawText(r, topLeft = Offset(plotW - r.size.width - px(8f), plotH - r.size.height - px(8f)))
            }
        }
    }
}

private data class SideTag(val y: Float, val text: String, val color: Color, val bold: Boolean = false)
private class AxisTag(val y: Float, val bg: Color, val text: String, val fg: Color)

/** 1, 2 or 5 × 10^k at or above [v], for grid lines. */
fun niceStep(v: Double): Double {
    if (v <= 0) return 1.0
    val p = Math.pow(10.0, floor(kotlin.math.log10(v)))
    return p * (if (v / p > 5) 10 else if (v / p > 2) 5 else if (v / p > 1) 2 else 1)
}
