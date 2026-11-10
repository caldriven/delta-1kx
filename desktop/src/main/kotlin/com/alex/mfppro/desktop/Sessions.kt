package com.alex.mfppro.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alex.mfppro.data.Candle
import com.alex.mfppro.data.Killzones
import com.alex.mfppro.data.Market
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.Sub
import com.alex.mfppro.data.candleKey
import com.alex.mfppro.data.intervalMs
import com.alex.mfppro.ui.fmtPrice
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

fun fmtDur(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val d = s / 86_400; val h = s % 86_400 / 3600; val m = s % 3600 / 60; val sec = s % 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
        else -> String.format(Locale.US, "%02dm %02ds", m, sec)
    }
}

/**
 * Clock, killzones, market hours, next opens and the nearest levels for [m], with the soonest
 * event highlighted. Everything the chart leaves out to stay clean.
 */
@Composable
fun SessionsPanel(repo: Repo, m: Market, interval: String, modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    val zone = ZoneId.systemDefault()

    // Levels come from 15-minute bars (shared with any chart on 15m) and the dailies.
    Subscribe(repo, Sub("candles", m.provider, m.coin, "15m"))
    val candles by repo.market.candles.collectAsState()
    val marks by repo.market.marks.collectAsState()
    var days by remember(m.marketId) { mutableStateOf<List<Candle>>(emptyList()) }
    LaunchedEffect(m.marketId) {
        while (true) {
            val d = runCatching { repo.history(m, "1d", 40) }.getOrDefault(emptyList())
            if (d.isNotEmpty()) days = d
            delay(if (d.isEmpty()) 10_000 else 600_000)
        }
    }
    val bars = candles[candleKey(m.provider, m.coin, "15m")].orEmpty()
    val price = marks[m.feedKey] ?: bars.lastOrNull()?.c
    val levels = remember(bars.size, bars.lastOrNull()?.openTime, days) { Killzones.levels(bars, days) }

    val kz = Killzones.KILLZONES.map { Killzones.next(it, now) }
    val mk = Killzones.MARKETS.map { Killzones.next(it, now) }
    val today = LocalDate.now(ZoneOffset.UTC)
    val opens = listOf(
        "Daily open" to today.plusDays(1),
        "Weekly open" to today.plusDays(1).let { d -> var x = d; while (x.dayOfWeek != DayOfWeek.MONDAY) x = x.plusDays(1); x },
        "Monthly open" to today.withDayOfMonth(1).plusMonths(1),
    ).map { (n, d) -> n to d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    val soonest = (kz + mk).minOf { it.msToEvent(now) }.coerceAtMost(opens.minOf { it.second - now })

    Panel("Sessions", modifier) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Instant.ofEpochMilli(now).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)), style = num.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold))
                Text("  " + zone.id.substringAfter('/').replace('_', ' '), style = label)
                Box(Modifier.weight(1f))
                Text(Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("HH:mm", Locale.US)) + " UTC", style = num.copy(color = D.muted))
            }
            intervalMs(interval)?.let { ms ->
                val left = ms - Math.floorMod(now, ms)
                Row { Text("$interval candle closes in", Modifier.weight(1f), style = label.copy(fontSize = 12.sp)); Text(fmtDur(left), style = num) }
            }

            Head("Killzones")
            kz.forEach { Event(it, now, zone, soonest) }
            Head("Markets (weekdays)")
            mk.forEach { Event(it, now, zone, soonest) }
            Head("Next opens (UTC midnight)")
            opens.forEach { (n, t) ->
                val hot = t - now == soonest
                Line(Color(if (n.startsWith("D")) 0xFFFFCA28 else if (n.startsWith("W")) 0xFFFF8A65 else 0xFFF06292), n,
                    Instant.ofEpochMilli(t).atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.US)), "in " + fmtDur(t - now), hot, active = false)
            }

            Head("Levels · nearest first")
            if (price == null || levels.isEmpty()) Text("Waiting for candles…", style = label)
            else Killzones.nearest(levels, price).take(8).forEach { l ->
                val pct = (l.price / price - 1) * 100
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(l.kind.rgb).copy(alpha = if (abs(pct) <= 3) 1f else 0.4f)))
                    Text((if (l.price > price) "  ▲ " else "  ▼ ") + l.kind.short, Modifier.weight(1f), fontSize = 12.sp, color = if (abs(pct) <= 3) D.text else D.muted)
                    Text(fmtPrice(l.price, m), Modifier.width(96.dp), style = num.copy(fontSize = 12.sp), textAlign = TextAlign.End)
                    Text(String.format(Locale.US, "%+.2f%%", pct), Modifier.width(64.dp), style = num.copy(fontSize = 12.sp, color = if (pct >= 0) D.up else D.down), textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun Head(t: String) {
    Text(t.uppercase(), style = label.copy(fontSize = 10.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
}

@Composable
private fun Event(e: Killzones.Next, now: Long, zone: ZoneId, soonest: Long) {
    val hours = Instant.ofEpochMilli(e.start).atZone(zone).format(HM) + "–" + Instant.ofEpochMilli(e.end).atZone(zone).format(HM)
    Line(Color(e.window.rgb), e.window.short, hours, if (e.running) fmtDur(e.msToEvent(now)) + " left" else "in " + fmtDur(e.msToEvent(now)), e.msToEvent(now) == soonest, e.running)
}

@Composable
private fun Line(col: Color, name: String, mid: String, right: String, hot: Boolean, active: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(if (hot) D.accent.copy(alpha = 0.12f) else Color.Transparent).padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (active) col else col.copy(alpha = 0.35f)))
        Text("  $name", Modifier.weight(1f), fontSize = 12.sp, color = if (active) col else D.text, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
        Text(mid, style = num.copy(fontSize = 11.sp, color = D.muted), maxLines = 1)
        Text(right, Modifier.width(108.dp), style = num.copy(fontSize = 12.sp, color = if (hot) D.accent else if (active) col else D.text), textAlign = TextAlign.End, maxLines = 1)
    }
}
