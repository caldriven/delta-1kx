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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.alex.mfppro.data.ClosedPosition
import com.alex.mfppro.data.OSLO
import com.alex.mfppro.data.Repo
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One whole trade from a closed position. realized_pnl is gross; fees are entry plus exit;
 * funding is the swap cash flow (negative when paid).
 */
private class Closed(val p: ClosedPosition) {
    val net get() = p.realizedPnl - p.fees + p.funding
    val day: LocalDate get() = Instant.ofEpochMilli(p.closedAt ?: p.openedAt).atZone(OSLO).toLocalDate()
}

/**
 * Trading journal for the trading account: realized results per day as a
 * calendar, headline stats, and the list of closing fills (tap a day to filter).
 */
@Composable
fun JournalScreen(repo: Repo, onOpenSettings: () -> Unit) {
    val acc = rememberTradeAccount(repo)
    var trades by remember { mutableStateOf<List<ClosedPosition>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf(false) }
    val accountId = acc?.account?.id
    val tick by repo.refreshTick.collectAsStateWithLifecycle()
    LaunchedEffect(accountId, reload, tick) {
        if (accountId == null) return@LaunchedEffect
        trades = null; error = null
        runCatching { repo.closedPositions(accountId) }.onSuccess { trades = it }.onFailure { error = it.message ?: "Couldn't load trades" }
    }
    val closed = trades.orEmpty().map(::Closed)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var month by remember { mutableStateOf(YearMonth.now(OSLO)) }
    var day by remember { mutableStateOf<LocalDate?>(null) }
    val byDay = closed.groupBy { it.day }.mapValues { (_, v) -> v.sumOf { it.net } }

    RefreshBox(repo) {
    LazyColumn(Modifier.fillMaxSize().background(C.bg), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
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
            trades == null -> item { Text("Loading trades…", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
            else -> {
                val scope = if (day != null) closed.filter { it.day == day } else closed.filter { YearMonth.from(it.day) == month }
                item(key = "stats") { Stats(scope, if (day != null) day!!.format(DateTimeFormatter.ofPattern("d MMM", Locale.US)) else month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US))) }
                item(key = "cal") {
                    Calendar(month, byDay, day,
                        onMonth = { month = it; day = null },
                        onDay = { d -> day = if (day == d) null else d })
                }
                item(key = "title") {
                    Text(if (day != null) "Trades on ${day!!.format(DateTimeFormatter.ofPattern("d MMM", Locale.US))}" else "Trades", color = C.text, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 4.dp))
                }
                if (scope.isEmpty()) item { Text("No closed trades", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp, 6.dp)) }
                items(scope, key = { it.p.id }) { TradeRow(it) }
            }
        }
    }
    }
    if (picker) AccountPickerSheet(repo) { picker = false }
}

/** Solid-style headline numbers for the selected month or day. */
@Composable
private fun Stats(trades: List<Closed>, period: String) {
    val net = trades.sumOf { it.net }
    val fees = trades.sumOf { it.p.fees }
    val swap = trades.sumOf { it.p.funding }
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
                MiniStat("Fees", "-" + fmtUsd(fees), C.muted, Modifier.weight(1f))
                MiniStat("Swap", fmtUsd(swap, true), C.muted, Modifier.weight(1f))
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

private fun fmtCompactSigned(v: Double) = (if (v < 0) "-" else "+") + "$" + fmtCompact(kotlin.math.abs(v))

@Composable
private fun TradeRow(t: Closed) {
    val p = t.p
    var open by remember { mutableStateOf(false) }
    val reason = when (p.closeReason) {
        "take_profit" -> "TP"; "stop_loss" -> "SL"; "liquidation" -> "Liquidated"; "reversal" -> "Flipped"; "system" -> "System"; else -> null
    }
    val held = ((p.closedAt ?: p.openedAt) - p.openedAt) / 60_000
    val holdTxt = when { held >= 1440 -> "${held / 1440}d ${held / 60 % 24}h"; held >= 60 -> "${held / 60}h ${held % 60}m"; else -> "${held}m" }
    Column(
        Modifier.padding(horizontal = 10.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.panel)
            .clickable { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VenueTag(p.provider, 14.dp)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ticker(p.symbol, p.provider), color = C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Tag("${if (p.isLong) "Long" else "Short"} ${p.leverage.toInt()}x", if (p.isLong) C.up else C.down)
                    reason?.let { Spacer(Modifier.width(4.dp)); Tag(it, C.muted) }
                }
                Text(
                    Instant.ofEpochMilli(p.closedAt ?: p.openedAt).atZone(OSLO).format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.US)) +
                        "  ·  $" + fmtCompact(p.size * p.entryPrice) + "  ·  " + holdTxt,
                    style = num.copy(fontSize = 10.sp, color = C.muted), maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtUsd(t.net, true), style = num.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pnlColor(t.net)))
                p.realizedMarginBasis?.takeIf { it > 0 }?.let { Text(String.format(Locale.US, "%+.2f%% ROE", t.net / it * 100), style = num.copy(fontSize = 10.sp, color = pnlColor(t.net))) }
            }
        }
        // Tap for the breakdown: prices, gross, fees and swap.
        if (open) Text("Entry ${fmtPrice(p.entryPrice)}  →  exit ${fmtPrice(p.exitPrice)}", style = num.copy(fontSize = 11.sp, color = C.text), modifier = Modifier.padding(top = 6.dp))
        if (open) Row(Modifier.padding(top = 4.dp)) {
            Cell("Gross", fmtUsd(p.realizedPnl, true), Modifier.weight(1f), pnlColor(p.realizedPnl))
            Cell("Fees", "-" + fmtUsd(p.fees), Modifier.weight(0.9f), C.muted)
            Cell("Swap", fmtUsd(p.funding, true), Modifier.weight(0.9f), C.muted)
        }
    }
}
