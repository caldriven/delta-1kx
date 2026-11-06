package com.alex.mfppro.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alex.mfppro.data.Repo
import kotlinx.coroutines.delay

/**
 * Result of the last trading action, large and unmistakable: green tick when it went through,
 * red cross with the reason when it was rejected, amber hourglass while queued. Slides down from
 * the top; tap to dismiss. Rejections stay up longer.
 */
@Composable
fun BoxScope.TradeResultBanner(repo: Repo) {
    var ev by remember { mutableStateOf<Repo.TradeEvent?>(null) }
    // Kept after dismiss so the card can animate out with its content.
    var last by remember { mutableStateOf<Repo.TradeEvent?>(null) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        repo.tradeEvents.collect {
            ev = it; last = it
            haptic.performHapticFeedback(if (it.ok == false) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
        }
    }
    LaunchedEffect(ev) {
        val e = ev ?: return@LaunchedEffect
        delay(if (e.ok == false) 9_000 else 4_000)
        if (ev === e) ev = null
    }
    AnimatedVisibility(
        ev != null, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp),
        enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
    ) {
        val e = last ?: return@AnimatedVisibility
        val col = when (e.ok) { true -> Color(0xFF22C55E); false -> Color(0xFFEF4444); null -> Color(0xFFF59E0B) }
        val shape = RoundedCornerShape(16.dp)
        Row(
            Modifier.fillMaxWidth().shadow(12.dp, shape).clip(shape).background(C.panel).border(2.dp, col, shape)
                .clickable { ev = null }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(col), contentAlignment = Alignment.Center) {
                Icon(when (e.ok) { true -> Icons.Filled.Check; false -> Icons.Filled.Close; null -> Icons.Filled.HourglassTop }, null,
                    tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(e.status, color = col, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(e.what, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (e.detail.isNotBlank()) Text(e.detail, color = if (e.ok == false) C.text else C.muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 4,
                    modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Close, "Dismiss", tint = C.muted, modifier = Modifier.size(18.dp))
        }
    }
}
