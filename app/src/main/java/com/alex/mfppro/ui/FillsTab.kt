package com.alex.mfppro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alex.mfppro.data.AccountState
import com.alex.mfppro.data.Fill
import com.alex.mfppro.data.Market
import com.alex.mfppro.data.OSLO
import com.alex.mfppro.data.Repo
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Fill history for the trading account under the chart: a PnL breakdown
 * (gross, fees, net, win rate, per market) and every execution, newest first.
 */
@Composable
fun FillsTab(repo: Repo, acc: AccountState?, market: Market, onPickMarket: (String) -> Unit) {
    var fills by remember { mutableStateOf<List<Fill>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var all by remember { mutableStateOf(repo.prefs.fillsAllMarkets) }
    var shown by remember { mutableIntStateOf(30) }
    val id = acc?.account?.id
    // Reload when a position opens or closes on this account.
    val posKey = acc?.positions?.keys?.sorted()?.joinToString()
    LaunchedEffect(id, reload, posKey) {
        if (id == null) return@LaunchedEffect
        error = null
        if (fills?.firstOrNull()?.accountId != id) { fills = null; shown = 30 }
        runCatching { repo.fills(id, maxPages = 5) }.onSuccess { fills = it }.onFailure { error = it.message ?: "Couldn't load fills" }
    }
    val list = fills.orEmpty().filter { all || it.marketId == market.marketId || it.symbol.equals(market.symbol, true) }
    Column(Modifier.padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(acc?.let(::accountName) ?: "", color = C.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("↻", color = C.muted, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { reload++ }.padding(horizontal = 8.dp, vertical = 2.dp))
            MiniSwitch(market.symbol, "All", !all) { all = !it; repo.prefs.fillsAllMarkets = all }
        }
        when {
            error != null -> Text(error!!, color = C.down, fontSize = 12.sp, modifier = Modifier.padding(16.dp, 8.dp))
            fills == null -> Text("Loading fills…", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp, 8.dp))
            list.isEmpty() -> Text("No fills", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp, 8.dp))
            else -> {
                Breakdown(list, all)
                list.take(shown).forEach { f -> androidx.compose.runtime.key(f.id) { FillRow(f) { onPickMarket(f.marketId) } } }
                if (list.size > shown) Text("Show more (${list.size - shown})", color = C.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clickable { shown += 30 }.padding(16.dp, 10.dp))
            }
        }
    }
}

/** Gross realized PnL, fees and net; win rate on closing fills; per-market split when showing all. */
@Composable
private fun Breakdown(list: List<Fill>, all: Boolean) {
    val closes = list.filter { it.realizedPnl != 0.0 }
    val gross = closes.sumOf { it.realizedPnl }
    val fees = list.sumOf { it.fee }
    val net = gross - fees
    val wins = closes.count { it.realizedPnl - it.fee > 0 }
    val maker = list.count { it.liquidityRole == "maker" }
    Column(Modifier.padding(horizontal = 8.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel).padding(12.dp)) {
        Row {
            Cell("Net PnL", fmtUsd(net, true), Modifier.weight(1f), pnlColor(net))
            Cell("Gross", fmtUsd(gross, true), Modifier.weight(1f), pnlColor(gross))
            Cell("Fees", "-" + fmtUsd(fees), Modifier.weight(1f), C.down)
            Cell("Win rate", if (closes.isEmpty()) "—" else String.format(Locale.US, "%.0f%% (%d)", wins * 100.0 / closes.size, closes.size), Modifier.weight(1f))
        }
        Text("${list.size} fills · ${maker} maker · ${list.size - maker} taker · volume $" + fmtCompact(list.sumOf { it.notional }),
            style = num.copy(fontSize = 10.sp, color = C.muted), modifier = Modifier.padding(top = 6.dp))
        if (all) {
            val by = closes.groupBy { it.symbol }.mapValues { (_, v) -> v.sumOf { it.realizedPnl - it.fee } }.entries.sortedByDescending { kotlin.math.abs(it.value) }.take(6)
            if (by.isNotEmpty()) Column(Modifier.padding(top = 6.dp)) {
                val max = by.maxOf { kotlin.math.abs(it.value) }.takeIf { it > 0 } ?: 1.0
                by.forEach { (sym, v) ->
                    Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(sym, color = C.text, fontSize = 11.sp, modifier = Modifier.width(70.dp))
                        androidx.compose.foundation.layout.Box(Modifier.weight(1f).height(6.dp)) {
                            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth((kotlin.math.abs(v) / max).toFloat()).height(6.dp).clip(RoundedCornerShape(3.dp)).background(pnlColor(v).copy(alpha = 0.8f)))
                        }
                        Text(fmtUsd(v, true), style = num.copy(fontSize = 11.sp, color = pnlColor(v)), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FillRow(f: Fill, onClick: () -> Unit) {
    val closing = f.realizedPnl != 0.0
    val buy = f.side == "buy"
    val kind = when {
        f.type == "liquidation" -> "Liquidation"
        f.type == "close_out" -> "Close-out"
        f.type.startsWith("stop") && f.reduceOnly -> "Stop loss"
        f.type == "take_profit" || (f.type.startsWith("take") && f.reduceOnly) -> "Take profit"
        closing -> if (buy) "Close short" else "Close long"
        else -> if (buy) "Open long" else "Open short"
    }
    Row(
        Modifier.padding(horizontal = 8.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.panel)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VenueTag(f.provider, 13.dp)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.symbol, color = C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Tag(kind, if (buy) C.up else C.down)
                if (f.liquidityRole == "maker") { Spacer(Modifier.width(4.dp)); Text("M", color = C.muted, fontSize = 9.sp) }
            }
            Text(
                Instant.ofEpochMilli(f.settledAt ?: f.requestedAt).atZone(OSLO).format(DateTimeFormatter.ofPattern("d MMM HH:mm:ss", Locale.US)) +
                    "  ·  " + "$" + fmtCompact(f.notional) + " @ " + fmtPrice(f.fillPrice),
                style = num.copy(fontSize = 10.sp, color = C.muted), maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (closing) Text(fmtUsd(f.realizedPnl - f.fee, true), style = num.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pnlColor(f.realizedPnl - f.fee)))
            Text("fee " + fmtUsd(f.fee), style = num.copy(fontSize = 10.sp, color = C.muted))
        }
    }
}
