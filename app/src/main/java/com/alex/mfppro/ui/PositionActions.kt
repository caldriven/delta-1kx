package com.alex.mfppro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alex.mfppro.data.Order
import com.alex.mfppro.data.Position
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.breachPrice
import com.alex.mfppro.data.riskOf
import kotlinx.coroutines.launch

private data class Pending(val title: String, val detail: String, val danger: Boolean, val run: suspend () -> Unit)

/** Close / partial / reverse / breakeven / TP-SL buttons under a position, like the web terminal. */
@Composable
fun PositionActions(repo: Repo, p: Position, mark: Double?) {
    val scope = rememberCoroutineScope()
    val pm = remember(p.marketId) { repo.market(p.marketId) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var tpslOpen by remember { mutableStateOf(false) }
    var limitOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val exits = repo.exitsOf(p)
    val tp = exits.firstOrNull { it.exitGroup == "tp" }?.exitPrice
    val sl = exits.firstOrNull { it.exitGroup == "sl" }?.exitPrice
    var slRemoveAsk by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun act(title: String, detail: String, danger: Boolean, kind: com.alex.mfppro.data.Confirm = com.alex.mfppro.data.Confirm.Order, run: suspend () -> Unit) {
        val job = Pending(title, detail, danger, run)
        if (repo.prefs.needsConfirm(kind)) pending = job
        else scope.launch { busy = true; run(); busy = false }
    }

    val risk = repo.account.accounts.value[p.accountId]?.let { riskOf(it, repo.market.marks.value) }
    val breach = if (mark != null) risk?.room?.let { breachPrice(p, mark, it) } else null

    Column(Modifier.padding(horizontal = 10.dp).padding(bottom = 6.dp)) {
        if (sl != null && risk?.nearestFloor != null && mark != null && breach != null &&
            (if (p.isLong) sl <= breach else sl >= breach)
        ) Text("SL is past breach", color = C.down, fontSize = 11.sp)
        fun usd(sz: Double) = fmtUsd(sz * (mark ?: p.entryPrice))
        // Stop where the account would still have 25% of its binding allowance left.
        val guardSl = if (mark != null && risk != null) {
            val full = (if (risk.bindingIsDaily) risk.dailyAllowance else risk.ddAllowance)
            val room = risk.room
            if (full != null && room != null && room - full * 0.25 > 0) breachPrice(p, mark, room - full * 0.25) else null
        } else null
        // Row 1 protects the position, row 2 exits it. Same size everywhere; only the full close is filled.
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            val w = Modifier.weight(1f)
            PillBtn("TP / SL", w, color = C.accent, enabled = !busy) { tpslOpen = true }
            // In profit: a stop at entry. Underwater: a stop there is impossible, so with the setting on it
            // becomes a reduce-only limit at entry that closes the position if price comes back.
            val inProfit = mark != null && p.pnlAt(mark) > 0
            val inLoss = mark != null && p.pnlAt(mark) < 0
            val beLimit = repo.prefs.breakevenLimit && inLoss
            PillBtn(if (beLimit) "BE limit" else "Breakeven", w, enabled = !busy && (inProfit || beLimit)) {
                if (beLimit) act("Breakeven limit ${p.symbol}",
                    "Reduce-only limit ${if (p.isLong) "sell" else "buy"} of ${usd(p.size)} at entry ${fmtPrice(p.entryPrice, pm)}. " +
                        "It rests on the book and closes the ${if (p.isLong) "long" else "short"} if price comes back to entry. " +
                        "It shows as the position's TP.", false) { repo.breakevenLimit(p) }
                else act("Breakeven ${p.symbol}", "Move stop loss to entry ${fmtPrice(p.entryPrice, pm)}", false) { repo.breakeven(p) }
            }
            PillBtn("Guard SL", w, enabled = !busy && guardSl != null) {
                guardSl?.let { px ->
                    act("Guard stop ${p.symbol}", "Market stop at ${fmtPrice(px, pm)} (${fmtUsd(p.pnlAt(px), true)}), leaving 25% of the drawdown allowance.", false) { repo.setTpSl(p, tp, px) }
                }
            }
            // Reversing opens a new position, so the night rule blocks it.
            PillBtn("⇅ Reverse", w, enabled = !busy) {
                if (repo.prefs.locked()) { repo.postMessage(repo.prefs.lockMessage()); return@PillBtn }
                act("Reverse ${ticker(p.symbol, p.provider)}", "Market ${if (p.isLong) "sell" else "buy"} ${usd(p.size * 2)} to flip to ${if (p.isLong) "short" else "long"}", true) { repo.reverse(p) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            val w = Modifier.weight(1f)
            PillBtn("Close 25%", w, enabled = !busy) {
                act("Close 25% ${p.symbol}", "Market close ${usd(p.size * 0.25)} of ${p.symbol}", true) { repo.closePosition(p, 0.25) }
            }
            PillBtn("Close 50%", w, enabled = !busy) {
                act("Close 50% ${p.symbol}", "Market close ${usd(p.size * 0.5)} of ${p.symbol}", true) { repo.closePosition(p, 0.5) }
            }
            PillBtn("Limit close", w, enabled = !busy) { limitOpen = true }
            PillBtn("Close all", w, color = Color.White, fill = C.down, enabled = !busy) {
                act("Close ${ticker(p.symbol, p.provider)}", "Market close all ${usd(p.size)} of ${ticker(p.symbol, p.provider)}" + (mark?.let { " · PnL ${fmtUsd(net(p.pnlAt(it)), true)}" } ?: ""), true, kind = com.alex.mfppro.data.Confirm.Full) { repo.closePosition(p) }
            }
        }
    }

    pending?.let { job ->
        ConfirmDialog(
            "Confirm", job.title, if (job.danger) C.down else C.accent, note = job.detail,
            confirm = "Confirm", onDismiss = { pending = null },
        ) { pending = null; scope.launch { busy = true; job.run(); busy = false } }
    }

    slRemoveAsk?.let { go ->
        ConfirmDialog("Remove stop loss", "${ticker(p.symbol, p.provider)} SL ${fmtPrice(sl, pm)}", C.down,
            note = "The position stays open without a stop.", confirm = "Remove", onDismiss = { slRemoveAsk = null }) { slRemoveAsk = null; go() }
    }

    if (tpslOpen) {
        var tpText by remember { mutableStateOf(tp?.let(::plain) ?: "") }
        var slText by remember { mutableStateOf(sl?.let(::plain) ?: "") }
        var tpExec by remember { mutableStateOf(exits.firstOrNull { it.exitGroup == "tp" }?.exitExecution ?: repo.prefs.tpExecution) }
        var slExec by remember { mutableStateOf(exits.firstOrNull { it.exitGroup == "sl" }?.exitExecution ?: repo.prefs.slExecution) }
        val newTp = tpText.toDoubleOrNull(); val newSl = slText.toDoubleOrNull()
        val px = mark ?: p.entryPrice
        val dir = if (p.isLong) 1.0 else -1.0
        val equity = risk?.equity
        // Price where the position gains/loses [usd] more from here.
        fun at(usd: Double) = (px + dir * usd / p.size).takeIf { it > 0 }
        // Pass target as a price for this position alone.
        val targetPx = risk?.toTarget?.takeIf { it > 0 }?.let { at(it) }
        val mkt = remember(p.marketId) { repo.market(p.marketId) }
        fun set(price: Double?, tpSide: Boolean) { price?.let { v -> val t = mkt?.let { snapTick(v, it) } ?: roundTick(v); if (tpSide) tpText = plain(t) else slText = plain(t) } }
        AlertDialog(
            onDismissRequest = { tpslOpen = false }, containerColor = C.panel,
            title = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VenueTag(p.provider, 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(ticker(p.symbol, p.provider), color = C.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(6.dp))
                        Tag("${if (p.isLong) "Long" else "Short"} ${p.leverage.toInt()}x", if (p.isLong) C.up else C.down)
                        Spacer(Modifier.weight(1f))
                        val pnl = net(mark?.let(p::pnlAt))
                        Text(fmtUsd(pnl, true), style = num.copy(fontSize = 15.sp, color = pnlColor(pnl), fontWeight = FontWeight.SemiBold))
                    }
                    Row(Modifier.padding(top = 8.dp)) {
                        Cell("Value", "$" + fmtCompact(p.size * (mark ?: p.entryPrice)), Modifier.weight(1f))
                        Cell("Entry", fmtPrice(p.entryPrice, pm), Modifier.weight(1f))
                        Cell("Last", fmtPrice(mark, pm), Modifier.weight(1f))
                        Cell("Breach", fmtPrice(breach, pm), Modifier.weight(1f), C.down)
                    }
                }
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // Mini chart: drag the TP and SL lines; the fields below follow.
                    val candles by repo.market.candles.collectThrottled(500)
                    androidx.compose.runtime.DisposableEffect(p.feedKey) {
                        val sub = com.alex.mfppro.data.Sub("candles", p.provider, p.coin, "5m")
                        repo.market.acquire(sub)
                        onDispose { repo.market.release(sub) }
                    }
                    val tpLine = newTp ?: equity?.let { at(it * 0.01) }
                    val slLine = newSl ?: equity?.let { at(-it * 0.01) }
                    fun pnlTag(v: Double) = fmtUsd(p.pnlAt(v), true)
                    val lines = listOfNotNull(
                        PriceLine(p.entryPrice, C.muted, "Entry", solid = true),
                        tpLine?.let { PriceLine(it, C.up, "TP", parts = listOf(if (newTp == null) "TP?" else "TP", pnlTag(it)),
                            drag = LineDrag({ v -> "TP " + pnlTag(v) to C.up }) { v -> set(v, true) }) },
                        slLine?.let { PriceLine(it, C.down, "SL", parts = listOf(if (newSl == null) "SL?" else "SL", pnlTag(it)),
                            drag = LineDrag({ v -> "SL " + pnlTag(v) to C.down }) { v -> set(v, false) }) },
                        breach?.let { PriceLine(it, C.down, "Breach", parts = listOf("Breach", fmtPrice(it, pm))) },
                        targetPx?.let { PriceLine(it, C.target, "Target", parts = listOf("Target", fmtPrice(it, pm))) },
                    )
                    CandleChart(
                        candles[com.alex.mfppro.data.candleKey(p.provider, p.coin, "5m")].orEmpty().withLive(mark), lines,
                        Modifier.fillMaxWidth().height(220.dp),
                    )

                    // ---- Take profit ----
                    Text("Take profit", color = C.up, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    if (equity != null) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (pct in listOf(1, 2, 3)) Seg("+$pct%", false, C.up) { set(at(equity * pct / 100), true) }
                        targetPx?.let { t -> Seg("Target", false, C.up) { set(t, true) } }
                    }
                    // Slide from here to the pass target (or +5% of equity).
                    val tpEnd = targetPx ?: equity?.let { at(it * 0.05) }
                    if (tpEnd != null) {
                        val f = newTp?.let { ((it - px) / (tpEnd - px)).toFloat().coerceIn(0f, 1f) } ?: 0f
                        PctSlider(f, C.up, Modifier.padding(top = 4.dp)) { set(px + it * (tpEnd - px), true) }
                    }
                    NumField("TP price", tpText, { tpText = it }, Modifier.fillMaxWidth())
                    Box(Modifier.padding(top = 6.dp)) { ExitExecPicker(true, tpExec, p.provider) { tpExec = it } }
                    newTp?.let { v -> Info("PnL at TP", fmtUsd(p.pnlAt(v), true) + (equity?.let { e -> String.format(java.util.Locale.US, "  (%+.2f%% eq)", p.pnlAt(v) / e * 100) } ?: ""), C.up) }

                    // ---- Stop loss ----
                    Text("Stop loss", color = C.down, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                    if (equity != null) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (pct in listOf(1, 2, 3)) Seg("-$pct%", false, C.down) { set(at(-equity * pct / 100), false) }
                        if (p.pnlAt(px) > 0) Seg("BE", false, C.text) { set(p.entryPrice, false) }
                    }
                    // Slide from here to the account breach price: how much of the room the stop may use.
                    if (breach != null) {
                        val f = newSl?.let { ((it - px) / (breach - px)).toFloat().coerceIn(0f, 1f) } ?: 0f
                        PctSlider(f, C.down, Modifier.padding(top = 4.dp)) { set(px + it * (breach - px), false) }
                        Text("Slider: 0% = now · 100% = account breach ${fmtPrice(breach, pm)}", color = C.muted, fontSize = 10.sp)
                    }
                    NumField("SL price", slText, { slText = it }, Modifier.fillMaxWidth())
                    Box(Modifier.padding(top = 6.dp)) { ExitExecPicker(false, slExec, p.provider) { slExec = it } }
                    newSl?.let { v ->
                        val loss = p.pnlAt(v) - p.pnlAt(px)
                        Info("PnL at SL", fmtUsd(p.pnlAt(v), true), C.down)
                        Info("From here", fmtUsd(loss, true) + (equity?.let { e -> String.format(java.util.Locale.US, "  (%+.2f%% eq)", loss / e * 100) } ?: ""), C.down)
                        risk?.room?.takeIf { it > 0 }?.let { room -> Info("Room used", String.format(java.util.Locale.US, "%.0f%%", -loss / room * 100), if (-loss > room) C.down else C.text) }
                    }
                    Text("Leave a field empty to remove that order.", color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = {
                // TP beyond the price in profit direction, SL on the loss side; a typo never silently removes an order.
                val tpBad = tpText.isNotBlank() && (newTp == null || (if (p.isLong) newTp <= px else newTp >= px))
                val slBad = slText.isNotBlank() && (newSl == null || (if (p.isLong) newSl >= px else newSl <= px))
                val save = {
                    tpslOpen = false
                    repo.prefs.tpExecution = tpExec; repo.prefs.slExecution = slExec
                    scope.launch { busy = true; repo.setTpSl(p, newTp, newSl, tpExec = tpExec, slExec = slExec); busy = false }
                    Unit
                }
                TextButton(enabled = !tpBad && !slBad, onClick = { if (newSl == null && sl != null) slRemoveAsk = save else save() }) {
                    Text(when {
                        tpBad -> "Take profit must be ${if (p.isLong) "above" else "below"} the price"
                        slBad -> "Stop loss must be ${if (p.isLong) "below" else "above"} the price"
                        else -> "Save"
                    }, color = if (tpBad || slBad) C.down else C.accent)
                }
            },
            dismissButton = { TextButton(onClick = { tpslOpen = false }) { Text("Cancel", color = C.muted) } },
        )
    }

    if (limitOpen) LimitCloseDialog(repo, p, mark, onDismiss = { limitOpen = false }) { run ->
        limitOpen = false
        scope.launch { busy = true; run(); busy = false }
    }
}

/** Offsets for the quick passive close, in % from the mid towards profit. */
private val FAST_OFFSETS = listOf(0.02, 0.05, 0.1)

/**
 * Reduce-only close without crossing the book, in two ways:
 * - Limit: how much (slider, snaps at quarters) and where (slider centred on the mid, ± a chosen
 *   range, quick mid ± 0.05% chips, or drag the line on the chart). Post only by default: MFP
 *   rejects the order rather than filling it at market if it would cross.
 * - Chase: rests at the best ask (bid for a short) and follows it on MFP's server until filled.
 */
@Composable
private fun LimitCloseDialog(repo: Repo, p: Position, mark: Double?, onDismiss: () -> Unit, onPlace: (suspend () -> Unit) -> Unit) {
    val books by repo.market.books.collectThrottled(250)
    val book = books[p.feedKey]
    val m = remember(p.marketId) { repo.market(p.marketId) }
    fun tick(v: Double) = m?.let { snapTick(v, it) } ?: roundTick(v)
    val bid = book?.bids?.firstOrNull()?.px; val ask = book?.asks?.firstOrNull()?.px
    val mid = if (bid != null && ask != null) (bid + ask) / 2 else mark ?: p.entryPrice
    // Towards profit: up for a long, down for a short.
    val dir = if (p.isLong) 1.0 else -1.0
    var chase by remember { mutableStateOf(false) }
    var frac by remember { mutableStateOf(1.0) }
    var range by remember { mutableStateOf(0.5) }
    // Start as the fast passive close: mid ± 0.05% on the profit side, so it rests and fills on the next small move.
    var offPct by remember { mutableStateOf(0.05 * dir) }
    var postOnly by remember { mutableStateOf(repo.prefs.postOnly) }
    var chasePct by remember { mutableStateOf(0.0) }
    var capPct by remember { mutableStateOf<Double?>(null) }
    // Closing a long sells on the ask side; closing a short buys on the bid side.
    val touch = (if (p.isLong) ask else bid) ?: mid
    val chaseOffset = if (chasePct > 0) tick(touch * chasePct / 100) else 0.0
    val capPx = capPct?.let { tick(mid * (1 - dir * it / 100)) }
    val px = if (chase) tick(touch + dir * chaseOffset) else tick(mid * (1 + offPct / 100))
    val closeSize = p.size * frac
    // Fee rate as this position has paid so far (fees over entry notional), else MFP's crypto rate.
    val feeRate = (p.fees / (p.size * p.entryPrice)).takeIf { it > 0 && it < 0.01 } ?: 0.0003
    val gross = p.pnlAt(px) * frac
    val fee = closeSize * px * feeRate
    // The closed part's share of fees and swap already on the position.
    val carried = (p.realizedPnl - p.fees + p.funding) * frac
    val net = gross - fee + carried
    // Closing a long with a sell at or below the bid (or a short with a buy at or above the ask) fills at once.
    val crosses = !chase && (if (p.isLong) bid != null && px <= bid else ask != null && px >= ask)
    val ok = frac > 0 && closeSize * px >= 1.0 && !(crosses && postOnly)

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.panel,
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Close", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Text(ticker(p.symbol, p.provider), color = C.muted, fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                    Tag("Reduce only", C.accent)
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Seg("Limit", !chase, C.accent) { chase = false }
                    Seg("Chase", chase, C.accent) { chase = true }
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val candles by repo.market.candles.collectThrottled(500)
                androidx.compose.runtime.DisposableEffect(p.feedKey) {
                    val subs = listOf(com.alex.mfppro.data.Sub("candles", p.provider, p.coin, "5m"), com.alex.mfppro.data.Sub("books", p.provider, p.coin))
                    subs.forEach { repo.market.acquire(it) }
                    onDispose { subs.forEach { repo.market.release(it) } }
                }
                val col = if (gross >= 0) C.up else C.down
                val closeLine = if (chase) PriceLine(px, col, "Chase", parts = listOf("Chase ${kotlin.math.round(frac * 100).toInt()}%", fmtUsd(gross, true)))
                    else PriceLine(px, col, "Close", parts = listOf("Close ${kotlin.math.round(frac * 100).toInt()}%", fmtUsd(gross, true)),
                        drag = LineDrag({ v -> "Close ${fmtPrice(v, m)} · ${fmtUsd(p.pnlAt(v) * frac, true)}" to (if (p.pnlAt(v) >= 0) C.up else C.down) }) { v ->
                            val o = (v / mid - 1) * 100
                            if (kotlin.math.abs(o) > range) range = listOf(0.5, 2.0, 5.0, 10.0).firstOrNull { it >= kotlin.math.abs(o) } ?: 10.0
                            offPct = o.coerceIn(-range, range)
                        })
                CandleChart(
                    candles[com.alex.mfppro.data.candleKey(p.provider, p.coin, "5m")].orEmpty().withLive(mark),
                    listOfNotNull(
                        PriceLine(p.entryPrice, C.muted, "Entry", solid = true),
                        closeLine,
                        capPx?.let { PriceLine(it, C.muted, "Cap", parts = listOf("Cap", fmtPrice(it, m))) },
                    ),
                    Modifier.fillMaxWidth().height(190.dp),
                )

                // ---- Amount ----
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                    Text("Amount", color = C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${kotlin.math.round(frac * 100).toInt()}% · ${fmtUsd(closeSize * px)}", style = num.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold))
                }
                PctSlider(frac.toFloat(), C.accent, Modifier.padding(top = 2.dp)) { frac = it.toDouble().coerceAtLeast(0.01) }

                if (!chase) {
                    // ---- Price, centred on the mid ----
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                        Text("Price", color = C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(fmtPrice(px, m), style = num.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold))
                        // Coloured by what it means for this position: profit side or loss side.
                        Text("  " + fmtOffset(offPct) + " mid", style = num.copy(fontSize = 11.sp, color = if (p.pnlAt(px) >= p.pnlAt(mid)) C.up else C.down))
                    }
                    // Quick passive closes: just past the mid on the profit side.
                    Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Fast", color = C.muted, fontSize = 11.sp, maxLines = 1)
                        Seg("Mid", offPct == 0.0, C.text) { offPct = 0.0 }
                        for (f in FAST_OFFSETS) Seg("mid ${if (p.isLong) "+" else "−"}${fmtOffset(f, false)}", offPct == f * dir, C.up) {
                            range = 0.5; offPct = f * dir
                        }
                    }
                    CenterSlider((offPct / range).toFloat(), Modifier.padding(top = 4.dp)) { offPct = it * range }
                    Row(Modifier.fillMaxWidth()) {
                        Text(fmtOffset(-range), style = num.copy(fontSize = 10.sp, color = C.muted), modifier = Modifier.weight(1f))
                        Text("mid ${fmtPrice(mid, m)}", style = num.copy(fontSize = 10.sp, color = C.muted))
                        Text(fmtOffset(range), style = num.copy(fontSize = 10.sp, color = C.muted), textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
                    }
                    Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Range", color = C.muted, fontSize = 11.sp, maxLines = 1)
                        for (r in listOf(0.5, 2.0, 5.0, 10.0)) Seg("±" + fmtOffset(r, false), range == r, C.accent) {
                            range = r; offPct = offPct.coerceIn(-r, r)
                        }
                    }
                    Row(Modifier.padding(top = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 10.dp)) {
                            Text("Post only", color = C.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Never fills at market: MFP rejects it if it would cross the book.", color = C.muted, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                        AppSwitch(postOnly, { postOnly = it; repo.prefs.postOnly = it })
                    }
                    if (crosses) Text(
                        if (postOnly) "Crosses the book: move it past the ${if (p.isLong) "bid" else "ask"} or turn off post only"
                        else "Crosses the book: fills now at market",
                        color = if (postOnly) C.down else C.accent, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    // ---- Chase ----
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                        Text("Starts at", color = C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(fmtPrice(px, m), style = num.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold))
                        Text("  best ${if (p.isLong) "ask" else "bid"}" + (if (chasePct > 0) " ${if (p.isLong) "+" else "−"}${fmtOffset(chasePct, false)}" else ""),
                            style = num.copy(fontSize = 11.sp, color = C.muted))
                    }
                    Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Offset", color = C.muted, fontSize = 11.sp, maxLines = 1)
                        for (o in listOf(0.0, 0.02, 0.05)) Seg(if (o == 0.0) "Join" else fmtOffset(o, false), chasePct == o, C.accent) { chasePct = o }
                    }
                    Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (p.isLong) "Lowest" else "Highest", color = C.muted, fontSize = 11.sp, maxLines = 1)
                        Seg("No cap", capPct == null, C.text) { capPct = null }
                        for (c in listOf(0.25, 0.5, 1.0)) Seg("${if (p.isLong) "−" else "+"}${fmtOffset(c, false)}", capPct == c, C.down) { capPct = c }
                    }
                    Text(
                        "Rests at the best ${if (p.isLong) "ask" else "bid"} and moves with it on MFP's server until filled, so it keeps working with the app closed" +
                            (capPx?.let { ". Stops following ${if (p.isLong) "below" else "above"} ${fmtPrice(it, m)}" } ?: "") +
                            ". A chase can't be edited: cancel it under Orders.",
                        color = C.muted, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // ---- Result ----
                Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.bg).padding(10.dp)) {
                    Info(if (chase) "PnL at the current touch" else "PnL on closed part", fmtUsd(gross, true), pnlColor(gross))
                    Info("Est. close fee (${fmtOffset(feeRate * 100, false)})", fmtUsd(-fee, true), C.down)
                    if (carried != 0.0) Info("Its share of fees, swap, banked", fmtUsd(carried, true), pnlColor(carried))
                    Info("Est. net", fmtUsd(net, true), pnlColor(net))
                    if (frac < 1) Info("Left open", "${kotlin.math.round((1 - frac) * 100).toInt()}% · ${fmtUsd(p.size * (1 - frac) * px)}")
                }
                Text("MFP charges maker and taker fills the same fee. Resting saves the spread and slippage, not the fee.",
                    color = C.muted, fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = {
                val f = frac; val at = px; val post = postOnly; val off = chaseOffset; val cap = capPx
                if (chase) onPlace { repo.chaseClose(p, f, off, cap) } else onPlace { repo.closePosition(p, f, limitPrice = at, postOnly = post) }
            }) {
                Text(if (chase) "Chase ${kotlin.math.round(frac * 100).toInt()}%" else "Place ${kotlin.math.round(frac * 100).toInt()}% at ${fmtPrice(px, m)}", color = if (ok) C.accent else C.muted, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = C.muted) } },
    )
}

/** A small % offset with only the decimals it needs: 0.05%, 0.5%, 2%. */
private fun fmtOffset(v: Double, signed: Boolean = true): String {
    val s = java.math.BigDecimal.valueOf(kotlin.math.abs(v)).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    return (if (!signed || v == 0.0) "" else if (v > 0) "+" else "−") + s + "%"
}

/** Slider from −1 to +1 with the zero mark in the middle; tap or drag. */
@Composable
private fun CenterSlider(value: Float, modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    val cb by androidx.compose.runtime.rememberUpdatedState(onChange)
    val line = C.line; val up = C.up; val down = C.down; val text = C.text
    androidx.compose.foundation.Canvas(
        modifier.fillMaxWidth().height(30.dp)
            .pointerInput(Unit) { detectTapGestures { cb(((it.x / size.width) * 2 - 1).coerceIn(-1f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { ch, _ -> ch.consume(); cb(((ch.position.x / size.width) * 2 - 1).coerceIn(-1f, 1f)) }
            },
    ) {
        val r = 9.dp.toPx(); val y = size.height / 2; val w = size.width - r * 2
        val cx = r + w / 2; val x = r + w * (value.coerceIn(-1f, 1f) + 1) / 2
        val col = if (value >= 0) up else down
        drawLine(line, Offset(r, y), Offset(r + w, y), 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(col, Offset(cx, y), Offset(x, y), 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(text, Offset(cx, y - 8.dp.toPx()), Offset(cx, y + 8.dp.toPx()), 2.dp.toPx())
        drawCircle(Color.White, r, Offset(x, y))
        drawCircle(col, r - 3.dp.toPx(), Offset(x, y))
    }
}

@Composable
fun OrderRow(repo: Repo, o: Order, account: String) {
    val scope = rememberCoroutineScope()
    val om = remember(o.marketId) { repo.market(o.marketId) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ticker(o.symbol, om?.provider ?: ""), color = C.text, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Tag(o.exitGroup?.uppercase() ?: o.type.replace('_', ' '), if (o.side == "buy") C.up else C.down)
                Spacer(Modifier.width(6.dp))
                Text(account, color = C.muted, fontSize = 11.sp)
            }
            Text(
                if (o.type == "chase") "${o.side} ${fmtSize(o.size)} ${om?.symbol ?: ""} · chasing the best ${if (o.side == "buy") "bid" else "ask"}" +
                    (o.chaseCapPrice?.let { " · cap ${fmtPrice(it, om)}" } ?: "")
                else "${o.side} ${(o.limitPrice ?: o.triggerPrice)?.let { fmtUsd(o.size * it) } ?: fmtSize(o.size)} @ ${fmtPrice(o.limitPrice ?: o.triggerPrice, om)}" + (if (o.triggerPrice != null && o.limitPrice != null) " (trigger ${fmtPrice(o.triggerPrice, om)})" else ""),
                style = num.copy(fontSize = 12.sp, color = C.muted),
            )
        }
        // Removing a stop loss leaves the position unprotected: that one asks first.
        var ask by remember { mutableStateOf(false) }
        var askCancel by remember { mutableStateOf(false) }
        Act("Cancel", color = C.down, modifier = Modifier.padding(start = 8.dp)) {
            when {
                o.exitGroup == "sl" -> ask = true
                repo.prefs.needsConfirm(com.alex.mfppro.data.Confirm.Order) -> askCancel = true
                else -> scope.launch { repo.cancelOrder(o) }
            }
        }
        if (askCancel) ConfirmDialog("Cancel order", "${ticker(o.symbol, om?.provider ?: "")} ${fmtPrice(o.limitPrice ?: o.triggerPrice, om)}", C.down,
            confirm = "Cancel order", onDismiss = { askCancel = false }) {
            askCancel = false; scope.launch { repo.cancelOrder(o) }
        }
        if (ask) ConfirmDialog("Remove stop loss", "${ticker(o.symbol, om?.provider ?: "")} SL ${fmtPrice(o.triggerPrice ?: o.limitPrice, om)}", C.down,
            note = "The position stays open without a stop.", confirm = "Remove", onDismiss = { ask = false }) {
            ask = false; scope.launch { repo.cancelOrder(o) }
        }
    }
}

@Composable
fun Act(label: String, color: Color = C.text, enabled: Boolean = true, modifier: Modifier = Modifier, fill: Color? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Text(
        label, color = if (enabled) color else C.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier.clip(shape)
            .then(if (fill != null) Modifier.background(if (enabled) fill else fill.copy(alpha = 0.3f)) else Modifier.background(C.panel2).border(1.dp, C.line, shape))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp, vertical = 7.dp),
    )
}

/** Outlined pill button, like the exchange position cards. */
@Composable
private fun PillBtn(label: String, modifier: Modifier, color: Color = C.text, enabled: Boolean = true, fill: Color? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        label, color = if (enabled) color else C.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier.height(34.dp).clip(shape)
            .then(if (fill != null) Modifier.background(if (enabled) fill.copy(alpha = 0.85f) else fill.copy(alpha = 0.25f))
                else Modifier.background(C.panel2).border(1.dp, if (enabled && color != C.text) color.copy(alpha = 0.6f) else C.line, shape))
            .clickable(enabled = enabled, onClick = onClick).wrapContentHeight(Alignment.CenterVertically),
    )
}

/** Fallback when the market isn't loaded: 6 significant digits, without binary noise. */
private fun roundTick(v: Double): Double = java.math.BigDecimal.valueOf(v).round(java.math.MathContext(6)).toDouble()

/**
 * The quick-trade screen's exits, one row: a post-only limit close, a chase close and a market close.
 * The full set of position tools lives on the Dashboard.
 *
 * Post-only close: a reduce-only limit for the whole position at the touch on its own side (the best ask
 * when closing a long, the best bid when closing a short), [Prefs.closeMarginBps] further out. Sitting a
 * little behind the touch, it rests on the book as a maker order instead of being refused when the
 * price ticks while it travels.
 */
@Composable
fun QuickClose(repo: Repo, p: Position, mark: Double?, mini: Boolean = false) {
    val scope = rememberCoroutineScope()
    val pm = remember(p.marketId) { repo.market(p.marketId) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun act(title: String, detail: String, danger: Boolean, kind: com.alex.mfppro.data.Confirm = com.alex.mfppro.data.Confirm.Order, run: suspend () -> Unit) {
        val job = Pending(title, detail, danger, run)
        if (repo.prefs.needsConfirm(kind)) pending = job else scope.launch { busy = true; run(); busy = false }
    }
    val book = repo.market.books.value[p.feedKey]
    val margin = repo.prefs.closeMarginBps / 10_000.0
    // Closing a long sells: rest at or above the best ask. Closing a short buys: at or below the best bid.
    val touch = if (p.isLong) book?.asks?.firstOrNull()?.px ?: mark else book?.bids?.firstOrNull()?.px ?: mark
    val limitPx = touch?.let { t ->
        val raw = if (p.isLong) t * (1 + margin) else t * (1 - margin)
        // Round away from the book so tick rounding never pulls it across.
        val tick = pm?.tickSize?.takeIf { it > 0 }
        if (tick == null) raw else (if (p.isLong) kotlin.math.ceil(raw / tick) else kotlin.math.floor(raw / tick)) * tick
    }
    val bpsTxt = String.format(java.util.Locale.US, "%.0f bp", repo.prefs.closeMarginBps)
    // Mini: three short buttons that sit beside the position instead of under it.
    Row(if (mini) Modifier else Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(top = 2.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(if (mini) 3.dp else 5.dp)) {
        val w = if (mini) Modifier.size(46.dp, 28.dp) else Modifier.weight(1f)
        PillBtn(if (mini) "Post" else "Post-only close", w, color = C.accent, enabled = !busy && limitPx != null) {
            val px = limitPx ?: return@PillBtn
            act("Post-only close ${ticker(p.symbol, p.provider)}",
                "Reduce-only limit ${if (p.isLong) "sell" else "buy"} of ${fmtUsd(p.size * px)} at ${fmtPrice(px, pm)}: the best ${if (p.isLong) "ask" else "bid"} + $bpsTxt. " +
                    "It rests on the book as a maker order" + (mark?.let { " · PnL at that price ${fmtUsd(net(p.pnlAt(px)), true)}" } ?: "") + ".", false) {
                repo.closePosition(p, 1.0, limitPrice = px, postOnly = true)
            }
        }
        PillBtn(if (mini) "Chase" else "Chase close", w, enabled = !busy) {
            act("Chase close ${ticker(p.symbol, p.provider)}",
                "Reduce-only limit that MFP keeps on the best ${if (p.isLong) "ask" else "bid"} until the whole position is closed.", false) {
                repo.chaseClose(p, 1.0, 0.0, null)
            }
        }
        PillBtn(if (mini) "Close" else "Market close", w, color = Color.White, fill = C.down, enabled = !busy) {
            act("Close ${ticker(p.symbol, p.provider)}", "Market close all ${fmtUsd(p.size * (mark ?: p.entryPrice))} of ${ticker(p.symbol, p.provider)}" +
                (mark?.let { " · PnL ${fmtUsd(net(p.pnlAt(it)), true)}" } ?: ""), true, kind = com.alex.mfppro.data.Confirm.Full) { repo.closePosition(p) }
        }
    }
    pending?.let { job ->
        ConfirmDialog("Confirm", job.title, if (job.danger) C.down else C.accent, note = job.detail, confirm = "Confirm", onDismiss = { pending = null }) {
            pending = null; scope.launch { busy = true; job.run(); busy = false }
        }
    }
}

/** One position on a single line for the mini window: market and side, net PnL, and the three closes. */
@Composable
fun MiniPosition(repo: Repo, p: Position, mark: Double?, onClick: () -> Unit) {
    val pnl = net(mark?.let(p::pnlAt))
    val roe = pnl?.let { it / p.marginAt() * 100 }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.panel).clickable(onClick = onClick).padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(p.symbol, color = C.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("${if (p.isLong) "Long" else "Short"} ${p.leverage.toInt()}x", color = if (p.isLong) C.up else C.down, fontSize = 9.sp, maxLines = 1)
        }
        androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.End) {
            Text(fmtUsd(pnl, true), style = num.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = pnlColor(pnl)), maxLines = 1)
            Text(roe?.let { String.format(java.util.Locale.US, "%+.2f%%", it) } ?: "—", style = num.copy(fontSize = 9.sp, color = pnlColor(pnl)), maxLines = 1)
        }
        QuickClose(repo, p, mark, mini = true)
    }
}
