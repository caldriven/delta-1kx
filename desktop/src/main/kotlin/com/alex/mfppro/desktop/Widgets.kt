package com.alex.mfppro.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Cursor

/** A titled box; panels sit edge to edge like a terminal, separated by splitters. */
@Composable
fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.background(D.panel)) {
        Row(
            Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title.uppercase(), style = label.copy(fontSize = 10.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold))
            Box(Modifier.weight(1f))
            actions()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(D.line))
        content()
    }
}

/** Drag handle between two side-by-side panels. [onDrag] gets the move in dp. */
@Composable
fun VSplit(onDone: () -> Unit = {}, onDrag: (Dp) -> Unit) = Split(Orientation.Horizontal, onDone, onDrag)

/** Drag handle between two stacked panels. */
@Composable
fun HSplit(onDone: () -> Unit = {}, onDrag: (Dp) -> Unit) = Split(Orientation.Vertical, onDone, onDrag)

@Composable
private fun Split(o: Orientation, onDone: () -> Unit, onDrag: (Dp) -> Unit) {
    val density = LocalDensity.current
    val drag by rememberUpdatedState(onDrag)
    val done by rememberUpdatedState(onDone)
    val horizontal = o == Orientation.Horizontal
    Box(
        (if (horizontal) Modifier.width(5.dp).fillMaxHeight() else Modifier.height(5.dp).fillMaxWidth())
            .background(D.bg)
            .pointerHoverIcon(PointerIcon(Cursor(if (horizontal) Cursor.E_RESIZE_CURSOR else Cursor.N_RESIZE_CURSOR)))
            .draggable(
                rememberDraggableState { px -> drag(with(density) { px.toDp() }) },
                o,
                onDragStopped = { done() },
            ),
    )
}

/** Small selectable pill. */
@Composable
fun Pill(text: String, on: Boolean, color: Color = D.accent, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(if (on) color.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(text, style = label.copy(color = if (on) color else D.muted, fontWeight = FontWeight.SemiBold))
    }
}

/** Solid button. */
@Composable
fun Btn(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = D.panel2,
    textColor: Color = D.text,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier.clip(RoundedCornerShape(5.dp)).background(if (enabled) color else color.copy(alpha = 0.35f))
            .clickable(enabled = enabled, onClick = onClick).pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) textColor else textColor.copy(alpha = 0.5f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Compact single-line input. */
@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    numeric: Boolean = false,
    secret: Boolean = false,
    suffix: String? = null,
) {
    Row(
        modifier.clip(RoundedCornerShape(5.dp)).background(D.bg).border(1.dp, D.line, RoundedCornerShape(5.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = num.copy(color = D.muted.copy(alpha = 0.6f)))
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = num.copy(fontSize = 13.sp),
                cursorBrush = SolidColor(D.accent),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (suffix != null) Text(suffix, style = label)
    }
}

/** One table cell: fixed share of the row, single line. */
@Composable
fun RowScope.Cell(
    text: String,
    w: Float,
    color: Color = D.text,
    align: TextAlign = TextAlign.End,
    style: TextStyle = num,
) {
    Text(text, Modifier.weight(w), style = style.copy(color = color), textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun RowScope.HeadCell(text: String, w: Float, align: TextAlign = TextAlign.End) {
    Text(text, Modifier.weight(w), style = label.copy(fontSize = 10.sp), textAlign = align, maxLines = 1)
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Text(text, style = label) }
}
