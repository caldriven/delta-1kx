package com.alex.mfppro.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.animation.togetherWith
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Pill toggle in the app's colours. */
@Composable
fun AppSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, color: Color = C.accent) {
    val track by animateColorAsState(if (checked) color else C.line, label = "track")
    val x by animateDpAsState(if (checked) 18.dp else 2.dp, label = "thumb")
    Box(
        Modifier.width(40.dp).height(24.dp).clip(CircleShape).background(if (enabled) track else track.copy(alpha = 0.4f))
            .clickable(enabled = enabled) { onChange(!checked) },
    ) {
        Box(Modifier.offset(x = x).align(Alignment.CenterStart).size(20.dp).clip(CircleShape).background(Color.White))
    }
}

/** Square check with label. */
@Composable
fun AppCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))
                .background(if (checked) C.accent else Color.Transparent)
                .border(1.5.dp, if (checked) C.accent else C.muted, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Text("✓", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(10.dp))
        Text(label, color = C.text, fontSize = 13.sp)
    }
}

/** Filled input with a small label, optional unit/action on the right. */
@Composable
fun AppInput(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    secret: Boolean = false,
    placeholder: String = "",
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier.padding(top = 6.dp)) {
        if (label.isNotEmpty()) Text(label, color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        Row(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(C.bg)
                .border(1.dp, C.line, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder.ifEmpty { if (numeric) "0.00" else "" }, color = C.muted.copy(alpha = 0.6f), style = if (numeric) num.copy(fontSize = 15.sp) else TextStyle(fontSize = 14.sp))
                BasicTextField(
                    value, { s ->
                        // Numbers: digits and one decimal point; a second point is ignored rather than accepted.
                        if (!numeric) onChange(s)
                        else s.replace(',', '.').filter { it.isDigit() || it == '.' }.takeIf { t -> t.count { it == '.' } <= 1 }?.let(onChange)
                    },
                    singleLine = true,
                    textStyle = (if (numeric) num.copy(fontSize = 15.sp) else TextStyle(fontFamily = Inter, fontSize = 14.sp)).copy(color = C.text),
                    cursorBrush = SolidColor(C.accent),
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            trailing?.invoke()
        }
    }
}

/**
 * [mode] (0/1) marks a button that flips between two views: while it's the open tab its label
 * reads as a switch ([modes], e.g. Chart / Trade) with the active side lit in that view's colour.
 */
data class NavItem(val label: String, val icon: ImageVector, val id: Int, val mode: Int? = null, val modes: List<String>? = null)

/** One colour per view of a two-view button. */
private val MODE_COLORS get() = listOf(C.accent, Color(0xFF60A5FA))

/**
 * Bottom bar with a pill that slides to the selected item. The pill can also be dragged across
 * the first [pages] items: [onDrag] reports the fractional position so the screens follow the
 * finger, and [onDragEnd] snaps to the nearest one. Small finger moves stay taps.
 */
@Composable
fun BottomBar(
    items: List<NavItem>,
    selected: Int,
    pages: Int = 0,
    dragPos: Float? = null,
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onSwipeDown: (() -> Unit)? = null,
    onSelect: (Int) -> Unit,
) {
    val down by androidx.compose.runtime.rememberUpdatedState(onSwipeDown)
    Column(
        Modifier.fillMaxWidth().background(C.panel).navigationBarsPadding()
            // Swipe the bar down (on the full chart) to tuck it away.
            .pointerInput(Unit) {
                var dy = 0f
                detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy > 40.dp.toPx()) down?.invoke() }) { ch, d -> if (down != null) { ch.consume(); dy += d } }
            },
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 6.dp, vertical = 5.dp)) {
            val w = maxWidth / items.size
            val wPx = with(androidx.compose.ui.platform.LocalDensity.current) { w.toPx() }
            val idx = items.indexOfFirst { it.id == selected }
            val animated by animateDpAsState(w * maxOf(idx, 0), androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 400f), label = "pill")
            val x = dragPos?.let { w * it } ?: animated
            val start by androidx.compose.runtime.rememberUpdatedState(idx)
            val drag by androidx.compose.runtime.rememberUpdatedState(onDrag)
            val end by androidx.compose.runtime.rememberUpdatedState(onDragEnd)
            val dragSlop = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.toPx() }
            if (idx >= 0 || dragPos != null) Box(
                Modifier.offset(x = x).width(w).fillMaxHeight().padding(horizontal = 3.dp).clip(RoundedCornerShape(14.dp))
                    .background(C.panel2).border(1.dp, C.line, RoundedCornerShape(14.dp)),
            )
            Row(
                Modifier.fillMaxSize().pointerInput(pages, wPx) {
                    if (pages < 2) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val from = start.takeIf { it in 0 until pages } ?: return@awaitEachGesture
                        var total = 0f
                        var dragging = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            total += ch.positionChange().x
                            // Needs a deliberate slide before it counts, so taps never become swipes.
                            if (!dragging && kotlin.math.abs(total) > dragSlop) dragging = true
                            if (dragging) {
                                ch.consume()
                                drag((from + total / wPx).coerceIn(0f, (pages - 1).toFloat()))
                            }
                        }
                        if (dragging) end()
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { item ->
                    val on = item.id == selected
                    val m = item.mode?.takeIf { on && item.modes != null }
                    val tint by animateColorAsState(if (!on) C.muted else if (m != null) MODE_COLORS[m] else C.accent, label = "navTint")
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp)).clickable { onSelect(item.id) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        // A two-view button pops its icon when it flips.
                        androidx.compose.animation.AnimatedContent(
                            item.icon, label = "navIcon",
                            transitionSpec = {
                                (androidx.compose.animation.scaleIn(initialScale = 0.6f) + androidx.compose.animation.fadeIn())
                                    .togetherWith(androidx.compose.animation.scaleOut(targetScale = 0.6f) + androidx.compose.animation.fadeOut())
                            },
                        ) { ic -> Icon(ic, item.label, tint = tint, modifier = Modifier.size(20.dp)) }
                        if (m == null) Text(item.label, color = tint, fontSize = 10.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                        else Row(verticalAlignment = Alignment.CenterVertically) {
                            item.modes!!.forEachIndexed { i, l ->
                                if (i > 0) Text("/", color = C.line, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 2.dp))
                                val c by animateColorAsState(if (i == m) MODE_COLORS[i] else C.muted, label = "modeTint")
                                Text(l, color = c, fontSize = 10.sp, fontWeight = if (i == m) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The venue's own logo, small; falls back to a muted dot when there's no logo. */
@Composable
fun VenueTag(provider: String, size: androidx.compose.ui.unit.Dp = 13.dp) {
    val url = com.alex.mfppro.data.Logos.exchange(provider.lowercase())
    if (provider.isBlank()) return
    if (url == null) { Box(Modifier.size(6.dp).clip(CircleShape).background(C.muted)); return }
    coil.compose.AsyncImage(url, provider, Modifier.size(size).clip(RoundedCornerShape(3.dp)))
}


/** One line in a [ConfirmDialog]. */
data class ConfirmRow(val label: String, val value: String, val color: Color = C.text)

/**
 * Confirmation card: what is about to happen in big type, the numbers that
 * matter underneath, and a coloured confirm button next to a plain Cancel.
 */
@Composable
fun ConfirmDialog(
    title: String,
    headline: String,
    color: Color,
    rows: List<ConfirmRow> = emptyList(),
    note: String? = null,
    confirm: String = "Confirm",
    onDismiss: () -> Unit,
    /** Extra choices shown under the rows, e.g. how a TP or SL executes. */
    extra: (@Composable () -> Unit)? = null,
    onConfirm: () -> Unit,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    androidx.compose.ui.window.Dialog(onDismiss) {
        val shape = RoundedCornerShape(16.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(C.panel).border(1.dp, color.copy(alpha = 0.55f), shape).padding(18.dp)) {
            Text(title.uppercase(), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(headline, style = num.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = C.text), modifier = Modifier.padding(top = 6.dp))
            if (rows.isNotEmpty()) Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.bg).padding(horizontal = 12.dp, vertical = 8.dp)) {
                rows.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.label, color = C.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(r.value, style = num.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = r.color), maxLines = 1)
                    }
                }
            }
            extra?.let { Box(Modifier.padding(top = 10.dp)) { it() } }
            note?.let { Text(it, color = C.text.copy(alpha = 0.8f), fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp)) }
            Row(Modifier.padding(top = 16.dp).fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, C.line, RoundedCornerShape(10.dp)).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { Text("Cancel", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                Box(
                    Modifier.weight(1.4f).height(48.dp).clip(RoundedCornerShape(10.dp)).background(color).clickable { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onConfirm() },
                    contentAlignment = Alignment.Center,
                ) { Text(confirm, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Solid-style big number: small superscript prefix/suffix around a large light figure. */
@Composable
fun BigNum(value: String, color: Color, prefix: String = "", suffix: String = "", size: androidx.compose.ui.unit.TextUnit = 30.sp) {
    Row(verticalAlignment = Alignment.Top) {
        if (prefix.isNotEmpty()) Text(prefix, color = color, fontSize = size * 0.45f, fontWeight = FontWeight.Normal, modifier = Modifier.padding(top = 4.dp, end = 1.dp))
        Text(value, color = color, fontSize = size, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp, maxLines = 1)
        if (suffix.isNotEmpty()) Text(suffix, color = color, fontSize = size * 0.45f, fontWeight = FontWeight.Normal, modifier = Modifier.padding(top = 4.dp, start = 1.dp))
    }
}

/** Rounded card with a soft glow from the top in [glow]. */
@Composable
fun GlowCard(glow: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier.fillMaxWidth().clip(shape)
            .background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(glow.copy(alpha = 0.22f), C.panel), radius = 700f, center = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY / 2, 0f)))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(glow.copy(alpha = 0.10f), Color.Transparent)))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape),
    ) { content() }
}

/** Δ1KX mark, matching the app icon: glowing amber triangle with "1KX" inside. */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    val tm = androidx.compose.ui.text.rememberTextMeasurer()
    val grad = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFFFD682), Color(0xFFF0A030), Color(0xFFE0661F)))
    androidx.compose.foundation.Canvas(modifier.size(width = 44.dp, height = 38.dp)) {
        val w = size.width; val h = size.height
        val stroke = w * 0.085f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w / 2, stroke); lineTo(w - stroke, h - stroke); lineTo(stroke, h - stroke); close()
        }
        // Glow, then the outline.
        drawPath(path, Color(0xFFF0A030).copy(alpha = 0.10f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke * 2.2f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawPath(path, grad, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        val t = tm.measure("1KX", androidx.compose.ui.text.TextStyle(brush = grad, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = (h * 0.3f / density).sp, letterSpacing = (-0.5).sp))
        drawText(t, topLeft = androidx.compose.ui.geometry.Offset(w / 2 - t.size.width / 2, h * 0.84f - t.size.height))
    }
}

/**
 * One entry of a keyed feed: recomposes only when that key's value changes, not on every update to
 * any key. With [ms] > 0 it also emits at most that often.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun <K, V> kotlinx.coroutines.flow.StateFlow<Map<K, V>>.collectKey(key: K, ms: Long = 0): androidx.compose.runtime.State<V?> {
    val f = androidx.compose.runtime.remember(this, key, ms) {
        val one = map { it[key] }.distinctUntilChanged()
        if (ms > 0) kotlinx.coroutines.flow.flow { emit(value[key]); emitAll(one.sample(ms)) } else one
    }
    return f.collectAsStateWithLifecycle(initialValue = value[key])
}

/** Collects a fast-ticking flow at most every [ms], for screens that don't need every price tick. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectThrottled(ms: Long): androidx.compose.runtime.State<T> {
    val f = androidx.compose.runtime.remember(this, ms) { kotlinx.coroutines.flow.flow { emit(value); emitAll(this@collectThrottled.sample(ms)) } }
    return f.collectAsStateWithLifecycle(initialValue = value)
}

/** What a venue's TP/SL triggers watch (MFP trading guide: conditional trigger policy). */
fun triggerSource(provider: String): String = when (provider) {
    "veranta", "ostium" -> "the venue mid price"
    "binance", "bybit", "hyperliquid" -> "the last traded price"
    else -> "the chart price"
}

/**
 * Market vs limit for a take profit ([tp]) or stop loss, with a line on what
 * each does and which price triggers it.
 */
@Composable
fun ExitExecPicker(tp: Boolean, exec: String, provider: String, onChange: (String) -> Unit) {
    val col = if (tp) C.up else C.down
    Column {
        // A TP either waits for a trigger or rests on the book as a plain reduce-only limit.
        if (tp) Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            Seg("Trigger", exec != "rest", col) { if (exec == "rest") onChange("limit") }
            Seg("Resting limit · reduce only", exec == "rest", col) { onChange("rest") }
        }
        if (!tp || exec != "rest") Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            Seg(if (tp) "Then market" else "Stop market", exec == "market", col) { onChange("market") }
            Seg(if (tp) "Then limit" else "Stop limit · reduce only", exec == "limit", col) { onChange("limit") }
        }
        val src = triggerSource(provider)
        Text(
            when {
                tp && exec == "rest" -> "Post only, reduce only: a limit sits on the book at your price now, no trigger, and fills as maker when price trades there. MFP refuses it if price is already past."
                tp && exec == "market" -> "Triggers when $src reaches it, then closes at market."
                tp -> "Triggers on $src, then a reduce-only limit at your price: never a worse fill, but a quick touch may not fill."
                exec == "market" -> "Triggers when $src reaches it, then closes at market: fills in a fast move, with slippage."
                else -> "Triggers on $src, then a reduce-only limit at your price: no slippage, but a gap past it leaves you open."
            },
            color = C.muted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp),
        )
    }
}
