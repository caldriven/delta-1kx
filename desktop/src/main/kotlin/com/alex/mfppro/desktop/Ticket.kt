package com.alex.mfppro.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alex.mfppro.data.Market
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.riskOf
import com.alex.mfppro.data.roundSize
import com.alex.mfppro.ui.fmtPrice
import com.alex.mfppro.ui.fmtSize
import com.alex.mfppro.ui.fmtUsd
import com.alex.mfppro.ui.roundDec
import com.alex.mfppro.ui.naturalDecimals
import com.alex.mfppro.ui.snapTick
import com.alex.mfppro.ui.ticker
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.UUID

/** Market or limit order on the trading account, sized in USD. Same request as the phone's quick panel. */
@Composable
fun Ticket(repo: Repo, m: Market, modifier: Modifier) {
    val accounts by repo.account.accounts.collectAsState()
    val tradeId by repo.tradeAccount.collectAsState()
    val marks by repo.market.marks.collectAsState()
    val books by repo.market.books.collectAsState()
    val acc = accounts[tradeId]
    val scope = rememberCoroutineScope()

    var limit by remember { mutableStateOf(false) }
    var usd by remember { mutableStateOf("") }
    var px by remember(m.marketId) { mutableStateOf("") }
    val maxLev = m.maxLeverage.toInt().coerceAtLeast(1)
    var lev by remember(m.marketId) { mutableStateOf(repo.prefs.leverage(m.marketId, minOf(10, maxLev)).toString()) }
    var reduce by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<Triple<String, String, suspend () -> Unit>?>(null) }
    var pick by remember { mutableStateOf(false) }

    val mark = marks[m.feedKey]
    val book = books[m.feedKey]
    val held = acc?.positions?.values?.firstOrNull { it.inMarket(m) }
    val available = acc?.account?.risk?.availableBalance

    fun send(buy: Boolean) {
        val a = acc ?: return repo.postMessage("Pick an account to trade on first")
        val levV = (held?.leverage?.toInt() ?: lev.toIntOrNull())?.coerceIn(1, maxLev) ?: return repo.postMessage("Enter a leverage")
        val limitPx = if (limit) px.toDoubleOrNull()?.let { snapTick(it, m) } ?: return repo.postMessage("Enter a limit price") else null
        val bestAsk = book?.asks?.firstOrNull()?.px
        val bestBid = book?.bids?.firstOrNull()?.px
        val ref = limitPx ?: (if (buy) bestAsk else bestBid) ?: mark ?: return repo.postMessage("No price for ${m.symbol} yet")
        if (reduce && (held == null || buy == held.isLong)) return repo.postMessage("Reduce only needs an open position on the other side")
        var size = usd.toDoubleOrNull()?.let { roundSize(it / ref, m) }
            ?: if (reduce && held != null) held.size else return repo.postMessage("Enter a size in USD")
        if (reduce && held != null) size = minOf(size, held.size)
        val notional = size * ref
        if (size <= 0 || size < m.minSize || notional < m.minNotional) return repo.postMessage("Size below the market minimum (${fmtUsd(m.minNotional)})")
        // A limit at or through the touch fills straight away; the server wants that acknowledged.
        val crosses = limitPx != null && (if (buy) limitPx >= (bestAsk ?: Double.MAX_VALUE) else limitPx <= (bestBid ?: 0.0))
        val word = (if (limitPx != null) "Limit " else "") + if (buy) "Long" else "Short"
        val body = buildJsonObject {
            put("client_order_id", "mfppro-" + UUID.randomUUID().toString().take(13))
            put("account_id", a.account.id); put("market_id", m.marketId)
            put("side", if (buy) "buy" else "sell"); put("size", size)
            put("leverage", levV); put("margin_mode", held?.marginMode ?: repo.prefs.marginModeFor(a.account.id))
            if (reduce) put("reduce_only", true)
            if (limitPx != null) {
                put("type", "limit"); put("limit_price", limitPx)
                if (crosses) put("marketable_limit_acknowledged", true)
            } else {
                put("type", "market"); put("expected_price", ref)
                put("slippage_tolerance_bps", repo.prefs.slippageBps)
            }
        }
        val run: suspend () -> Unit = {
            if (held == null) repo.prefs.setLeverage(m.marketId, levV)
            repo.placeOrder("$word ${m.symbol}", body)
        }
        val breachMove = riskOf(a, marks).room?.takeIf { notional > 0 }?.let { it / notional * 100 }
        val detail = (if (limitPx != null) "Limit ${if (buy) "buy" else "sell"} at ${fmtPrice(limitPx, m)}" + (if (crosses) " (crosses the book: fills now)" else "")
            else "Market ${if (buy) "buy" else "sell"}") +
            " ${fmtSize(size)} ${ticker(m.symbol, m.provider)} ≈ ${fmtUsd(notional)} at ${levV}x" + (if (reduce) ", reduce only" else "") +
            " on ${accountName(a)}." +
            (if (!reduce && breachMove != null) String.format(Locale.US, "\nA %.2f%% move against it reaches your drawdown floor.", breachMove) else "")
        if (repo.prefs.confirmOrders) ask = Triple("$word ${ticker(m.symbol, m.provider)} ≈ ${fmtUsd(notional)}", detail, run)
        else scope.launch { busy = true; run(); busy = false }
    }

    Panel(
        "Order", modifier,
        actions = {
            Box {
                Text(
                    (acc?.let(::accountName) ?: "Pick account") + "  ▾",
                    Modifier.clickable { pick = true }.pointerHoverIcon(PointerIcon.Hand),
                    style = label.copy(color = if (acc == null) D.accent else D.text),
                )
                DropdownMenu(pick, { pick = false }) {
                    accounts.values.filter { it.account.status == "active" }.sortedBy(::accountName).forEach { s ->
                        DropdownMenuItem(
                            text = { Text(accountName(s) + "  ·  " + fmtUsd(s.account.risk?.equity ?: s.account.balance), fontSize = 13.sp) },
                            onClick = { repo.setTradeAccount(s.account.id); pick = false },
                        )
                    }
                }
            }
        },
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill("Market", !limit) { limit = false }
                Pill("Limit", limit) {
                    limit = true
                    if (px.isBlank()) mark?.let { px = roundDec(it, naturalDecimals(m, it)).toBigDecimal().toPlainString() }
                }
                Spacer(Modifier.weight(1f))
                Checkbox(reduce, { reduce = it }, colors = CheckboxDefaults.colors(checkedColor = D.accent, uncheckedColor = D.muted))
                Text("Reduce only", style = label)
            }
            if (limit) Field(px, { px = it }, Modifier.fillMaxWidth(), placeholder = "Limit price", numeric = true)
            Field(usd, { usd = it }, Modifier.fillMaxWidth(), placeholder = if (reduce) "Size (blank = whole position)" else "Size", numeric = true, suffix = "USD")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                repo.prefs.quickSizes.forEach { q -> Pill("$" + q, usd == q.toString()) { usd = q.toString() } }
                val lv = lev.toIntOrNull() ?: 1
                if (available != null && available > 0) Pill("Max", false) { usd = String.format(Locale.US, "%.0f", available * lv * 0.98) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Leverage", style = label)
                if (held != null) Text("${held.leverage.toInt()}x (open position)", style = num)
                else {
                    Field(lev, { v -> lev = v.filter(Char::isDigit).take(3) }, Modifier.width(70.dp), numeric = true, suffix = "x")
                    Text("max ${maxLev}x", style = label)
                }
            }
            Row {
                Text("Available", Modifier.weight(1f), style = label)
                Text(fmtUsd(available), style = num)
            }
            val sizeCoin = usd.toDoubleOrNull()?.let { u -> (if (limit) px.toDoubleOrNull() else mark)?.takeIf { it > 0 }?.let { roundSize(u / it, m) } }
            Row {
                Text("Quantity", Modifier.weight(1f), style = label)
                Text(sizeCoin?.let { fmtSize(it) + " " + m.coin } ?: "—", style = num)
            }
            if (held != null) Row {
                Text("Holding", Modifier.weight(1f), style = label)
                Text((if (held.isLong) "Long " else "Short ") + fmtSize(held.size) + " @ " + fmtPrice(held.entryPrice, m), style = num.copy(color = if (held.isLong) D.up else D.down))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Buy / Long", Modifier.weight(1f), color = D.up, textColor = Color.White, enabled = !busy && acc != null) { send(true) }
                Btn("Sell / Short", Modifier.weight(1f), color = D.down, textColor = Color.White, enabled = !busy && acc != null) { send(false) }
            }
        }
    }

    ask?.let { (title, detail, run) ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(title) },
            text = { Text(detail) },
            confirmButton = { TextButton({ ask = null; scope.launch { busy = true; run(); busy = false } }) { Text("Send order", color = D.accent) } },
            dismissButton = { TextButton({ ask = null }) { Text("Cancel") } },
        )
    }
}
