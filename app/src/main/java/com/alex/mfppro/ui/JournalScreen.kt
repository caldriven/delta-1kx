package com.alex.mfppro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.alex.mfppro.data.Fill
import com.alex.mfppro.data.OSLO
import com.alex.mfppro.data.Repo
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun dayOf(f: Fill): LocalDate = Instant.ofEpochMilli(f.settledAt ?: f.requestedAt).atZone(OSLO).toLocalDate()

/** A closing fill: one realized result. */
private class Closed(val f: Fill) {
    val net get() = f.realizedPnl - f.fee
    val day: LocalDate get() = dayOf(f)
}

/**
 * Trading journal for the trading account: realized results per day as a
 * calendar, headline stats, and the list of closing fills (tap a day to filter).
 */
@Composable
fun JournalScreen(repo: Repo, onOpenSettings: () -> Unit) {
    val acc = rememberTradeAccount(repo)
    var fills by remember { mutableStateOf<List<Fill>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf(false) }
    val accountId = acc?.account?.id
    LaunchedEffect(accountId, reload) {
        if (accountId == null) return@LaunchedEffect
        fills = null; error = null
        runCatching { repo.fills(accountId) }.onSuccess { fills = it }.onFailure { error = it.message ?: "Couldn't load fills" }
    }
    val closed = fills.orEmpty().filter { it.realizedPnl != 0.0 }.map(::Closed)
    var month by remember { mutableStateOf(YearMonth.now(OSLO)) }
    var day by remember { mutableStateOf<LocalDate?>(null) }
    // Day results count every fee that day (opening fills too); realized PnL is before fees.
    val byDay = fills.orEmpty().groupBy(::dayOf).mapValues { (_, v) -> v.sumOf { it.realizedPnl - it.fee } }.filterValues { it != 0.0 }

    LazyColumn(Modifier.fillMaxSize().background(C.bg), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(14.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Journal", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = C.text)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.Refresh, "Reload", tint = C.muted, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { reload++ }.padding(6.dp).size(20.dp))
            }
        }
        if (acc == null) { item { Text("No active account", color = C.muted, modifier = Modifier.padding(16.dp)) }; return@LazyColumn }
        item {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel)
                    .clickable { picker = true }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(accountName(acc), color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.KeyboardArrowDown, "Switch account", tint = C.muted)
            }
        }
        when {
            error != null -> item { Text(error!!, color = C.down, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
            fills == null -> item { Text("Loading fills…", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
            else -> {
                val scope = if (day != null) closed.filter { it.day == day } else closed.filter { YearMonth.from(it.day) == month }
                val periodFees = fills.orEmpty().filter { if (day != null) dayOf(it) == day else YearMonth.from(dayOf(it)) == month }.sumOf { it.fee }
                item { Stats(scope, periodFees, if (day != null) day!!.format(DateTimeFormatter.ofPattern("d MMM", Locale.US)) else month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US))) }
                item {
                    Calendar(month, byDay, day,
                        onMonth = { month = it; day = null },
                        onDay = { d -> day = if (day == d) null else d })
                }
                item {
                    Text(if (day != null) "Trades on ${day!!.format(DateTimeFormatter.ofPattern("d MMM", Locale.US))}" else "Trades", color = C.text, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 4.dp))
                }
                if (scope.isEmpty()) item { Text("No closed trades", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp, 6.dp)) }
                items(scope, key = { it.f.id }) { TradeRow(it) }
            }
        }
    }
    if (picker) AccountPickerSheet(repo) { picker = false }
}

/** Solid-style headline numbers for the selected month or day. */
@Composable
private fun Stats(trades: List<Closed>, fees: Double, period: String) {
    // Net counts opening fees as well as closing ones.
    val net = trades.sumOf { it.f.realizedPnl } - fees
    val wins = trades.filter { it.net > 0 }
    val losses = trades.filter { it.net < 0 }
    val winRate = if (trades.isNotEmpty()) wins.size * 100.0 / trades.size else null
    val gross = losses.sumOf { -it.net }
    val pf = if (gross > 0) wins.sumOf { it.net } / gross else null
    GlowCard(pnlColor(net), Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            Text(period, color = C.muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(Modifier.weight(1.3f), horizontalAlignment = Alignment.CenterHorizontally) {
                    BigNum(fmtUsd(kotlin.math.abs(net)).removePrefix("$"), pnlColor(net), prefix = (if (net < 0) "-" else "+") + "$", size = 26.sp)
                    Text("Net PnL", color = pnlColor(net), fontSize = 12.sp)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    BigNum(winRate?.let { String.format(Locale.US, "%.0f", it) } ?: "—", C.text, suffix = if (winRate != null) "%" else "", size = 26.sp)
                    Text("Win rate", color = C.muted, fontSize = 12.sp)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    BigNum("${trades.size}", C.text, size = 26.sp)
                    Text("Trades", color = C.muted, fontSize = 12.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = 14.dp, end = 14.dp)) {
                MiniStat("Avg win", fmtUsd(wins.takeIf { it.isNotEmpty() }?.let { w -> w.sumOf { it.net } / w.size }), C.up, Modifier.weight(1f))
                MiniStat("Avg loss", fmtUsd(losses.takeIf { it.isNotEmpty() }?.let { l -> l.sumOf { it.net } / l.size }), C.down, Modifier.weight(1f))
                MiniStat("Profit factor", pf?.let { String.format(Locale.US, "%.2f", it) } ?: "—", C.text, Modifier.weight(1f))
                MiniStat("Fees", fmtUsd(fees), C.muted, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = C.muted, fontSize = 10.sp)
        Text(value, style = num.copy(fontSize = 12.sp, color = color), maxLines = 1)
    }
}

/** Month grid; each day shaded by its net result, tap to filter. */
@Composable
private fun Calendar(month: YearMonth, byDay: Map<LocalDate, Double>, selected: LocalDate?, onMonth: (YearMonth) -> Unit, onDay: (LocalDate) -> Unit) {
    val maxAbs = byDay.filterKeys { YearMonth.from(it) == month }.values.maxOfOrNull { kotlin.math.abs(it) }?.takeIf { it > 0 } ?: 1.0
    Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.panel).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.KeyboardArrowLeft, "Previous month", tint = C.text, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onMonth(month.minusMonths(1)) }.padding(4.dp))
            Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)), color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.KeyboardArrowRight, "Next month", tint = C.text, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onMonth(month.plusMonths(1)) }.padding(4.dp))
        }
        Row(Modifier.padding(top = 6.dp)) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
                Text(it, color = C.muted, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value - 1
        val cells = lead + month.lengthOfMonth()
        val rows = (cells + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (c in 0 until 7) {
                    val n = r * 7 + c - lead + 1
                    if (n < 1 || n > month.lengthOfMonth()) { Spacer(Modifier.weight(1f)); continue }
                    val d = month.atDay(n)
                    val pnl = byDay[d]
                    val col = pnlColor(pnl)
                    val f = pnl?.let { (kotlin.math.abs(it) / maxAbs).toFloat() } ?: 0f
                    val on = d == selected
                    Column(
                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                            .background(if (pnl != null) col.copy(alpha = 0.12f + 0.4f * f) else C.panel2.copy(alpha = 0.5f))
                            .then(if (on) Modifier.border(1.5.dp, C.text, RoundedCornerShape(8.dp)) else Modifier)
                            .clickable { onDay(d) }.padding(3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("$n", color = if (pnl != null) C.text else C.muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.Start))
                        Spacer(Modifier.weight(1f))
                        pnl?.let { Text(fmtCompactSigned(it), style = num.copy(fontSize = 9.sp, color = col, fontWeight = FontWeight.SemiBold), maxLines = 1) }
                    }
                }
            }
        }
    }
}

private fun fmtCompactSigned(v: Double) = (if (v < 0) "-" else "+") + "$" + fmtCompact(kotlin.math.abs(v)).removeSuffix(".00")

@Composable
private fun TradeRow(t: Closed) {
    val f = t.f
    // A closing sell ends a long, a closing buy ends a short.
    val wasLong = f.side == "sell"
    Row(
        Modifier.padding(horizontal = 10.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.panel)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VenueTag(f.provider, 14.dp)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ticker(f.symbol, f.provider), color = C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Tag(if (wasLong) "Long" else "Short", if (wasLong) C.up else C.down)
            }
            Text(
                Instant.ofEpochMilli(f.requestedAt).atZone(OSLO).format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.US)) +
                    "  ·  " + "$" + fmtCompact(f.notional) + " @ " + fmtPrice(f.fillPrice),
                style = num.copy(fontSize = 10.sp, color = C.muted), maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fmtUsd(t.net, true), style = num.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pnlColor(t.net)))
            Text("fee " + fmtUsd(f.fee), style = num.copy(fontSize = 10.sp, color = C.muted))
        }
    }
}
