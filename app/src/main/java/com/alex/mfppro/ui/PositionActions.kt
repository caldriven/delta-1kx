package com.alex.mfppro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
    var pending by remember { mutableStateOf<Pending?>(null) }
    var tpslOpen by remember { mutableStateOf(false) }
    var limitOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val exits = repo.exitsOf(p)
    val tp = exits.firstOrNull { it.exitGroup == "tp" }?.exitPrice
    val sl = exits.firstOrNull { it.exitGroup == "sl" }?.exitPrice

    fun act(title: String, detail: String, danger: Boolean, force: Boolean = false, run: suspend () -> Unit) {
        val job = Pending(title, detail, danger, run)
        if (repo.prefs.confirmOrders || force) pending = job
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
            // A stop at entry only makes sense while the position is in profit.
            val inProfit = mark != null && p.pnlAt(mark) > 0
            PillBtn("Breakeven", w, enabled = !busy && inProfit) {
                act("Breakeven ${p.symbol}", "Move stop loss to entry ${fmtPrice(p.entryPrice)}", false) { repo.breakeven(p) }
            }
            PillBtn("Guard SL", w, enabled = !busy && guardSl != null) {
                guardSl?.let { px ->
                    act("Guard stop ${p.symbol}", "Market stop at ${fmtPrice(px)} (${fmtUsd(p.pnlAt(px), true)}), leaving 25% of the drawdown allowance.", false) { repo.setTpSl(p, tp, px) }
                }
            }
            // Reversing opens a new position, so the night rule blocks it.
            PillBtn("⇅ Flip", w, enabled = !busy && !repo.prefs.locked()) {
                act("Reverse ${p.symbol}", "Market ${if (p.isLong) "sell" else "buy"} ${usd(p.size * 2)} to flip to ${if (p.isLong) "short" else "long"}", true) { repo.reverse(p) }
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
                act("Close ${p.symbol}", "Market close all ${usd(p.size)} of ${p.symbol}" + (mark?.let { " · PnL ${fmtUsd(p.pnlAt(it), true)}" } ?: ""), true, force = repo.prefs.confirmCloseAll) { repo.closePosition(p) }
            }
        }
    }

    pending?.let { job ->
        ConfirmDialog(
            if (job.danger) "Confirm" else "Confirm", job.title, if (job.danger) C.down else C.accent, note = job.detail,
            confirm = if (job.danger) "Send" else "Confirm", onDismiss = { pending = null },
        ) { pending = null; scope.launch { busy = true; job.run(); busy = false } }
    }

    if (tpslOpen) {
        var tpText by remember { mutableStateOf(tp?.let(::plain) ?: "") }
        var slText by remember { mutableStateOf(sl?.let(::plain) ?: "") }
        val newTp = tpText.toDoubleOrNull(); val newSl = slText.toDoubleOrNull()
        val px = mark ?: p.entryPrice
        val dir = if (p.isLong) 1.0 else -1.0
        val equity = risk?.equity
        // Price where the position gains/loses [usd] more from here.
        fun at(usd: Double) = (px + dir * usd / p.size).takeIf { it > 0 }
        // Pass target as a price for this position alone.
        val targetPx = risk?.toTarget?.takeIf { it > 0 }?.let { at(it) }
        fun set(price: Double?, tpSide: Boolean) { price?.let { v -> val t = roundTick(v); if (tpSide) tpText = plain(t) else slText = plain(t) } }
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
                        val pnl = mark?.let(p::pnlAt)
                        Text(fmtUsd(pnl, true), style = num.copy(fontSize = 15.sp, color = pnlColor(pnl), fontWeight = FontWeight.SemiBold))
                    }
                    Row(Modifier.padding(top = 8.dp)) {
                        Cell("Value", "$" + fmtCompact(p.size * (mark ?: p.entryPrice)), Modifier.weight(1f))
                        Cell("Entry", fmtPrice(p.entryPrice), Modifier.weight(1f))
                        Cell("Last", fmtPrice(mark), Modifier.weight(1f))
                        Cell("Breach", fmtPrice(breach), Modifier.weight(1f), C.down)
                    }
                }
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // Mini chart: drag the TP and SL lines; the fields below follow.
                    val candles by repo.market.candles.collectAsStateWithLifecycle()
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
                        breach?.let { PriceLine(it, C.down, "Breach", parts = listOf("Breach", fmtPrice(it))) },
                        targetPx?.let { PriceLine(it, Color(0xFF22D3EE), "Target", parts = listOf("Target", fmtPrice(it))) },
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
                        Text("Slider: 0% = now · 100% = account breach ${fmtPrice(breach)}", color = C.muted, fontSize = 10.sp)
                    }
                    NumField("SL price", slText, { slText = it }, Modifier.fillMaxWidth())
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
                TextButton(enabled = !tpBad && !slBad, onClick = { tpslOpen = false; scope.launch { busy = true; repo.setTpSl(p, newTp, newSl); busy = false } }) {
                    Text(when { tpBad -> "TP on wrong side"; slBad -> "SL on wrong side"; else -> "Save" }, color = if (tpBad || slBad) C.down else C.accent)
                }
            },
            dismissButton = { TextButton(onClick = { tpslOpen = false }) { Text("Cancel", color = C.muted) } },
        )
    }

    if (limitOpen) {
        var pxText by remember { mutableStateOf(mark?.let(::plain) ?: "") }
        var pctText by remember { mutableStateOf("100") }
        val px = pxText.toDoubleOrNull()
        val frac = (pctText.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 100.0) / 100
        AlertDialog(
            onDismissRequest = { limitOpen = false }, containerColor = C.panel,
            title = { Text("Limit close ${p.symbol}", color = C.text) },
            text = {
                Column {
                    NumField("Price", pxText, { pxText = it }, Modifier.fillMaxWidth())
                    NumField("% of position", pctText, { pctText = it }, Modifier.fillMaxWidth())
                    px?.let { Info("PnL", fmtUsd(p.pnlAt(it) * frac, true), pnlColor(p.pnlAt(it))) }
                }
            },
            confirmButton = {
                TextButton(enabled = px != null && frac > 0, onClick = {
                    limitOpen = false
                    scope.launch { busy = true; repo.closePosition(p, frac, limitPrice = px); busy = false }
                }) { Text("Place", color = C.accent) }
            },
            dismissButton = { TextButton(onClick = { limitOpen = false }) { Text("Cancel", color = C.muted) } },
        )
    }
}

@Composable
fun OrderRow(repo: Repo, o: Order, account: String) {
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.symbol, color = C.text, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Tag(o.exitGroup?.uppercase() ?: o.type.replace('_', ' '), if (o.side == "buy") C.up else C.down)
                Spacer(Modifier.width(6.dp))
                Text(account, color = C.muted, fontSize = 11.sp)
            }
            Text(
                "${o.side} ${(o.limitPrice ?: o.triggerPrice)?.let { fmtUsd(o.size * it) } ?: fmtSize(o.size)} @ ${fmtPrice(o.limitPrice ?: o.triggerPrice)}" + (if (o.triggerPrice != null && o.limitPrice != null) " (trigger ${fmtPrice(o.triggerPrice)})" else ""),
                style = num.copy(fontSize = 12.sp, color = C.muted),
            )
        }
        Act("Cancel", color = C.down) { scope.launch { repo.cancelOrder(o) } }
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

/** Outlined round icon button. */
@Composable
private fun RoundBtn(color: Color = C.text, enabled: Boolean = true, glyph: @Composable (Color) -> Unit, onClick: () -> Unit) {
    val tint = if (enabled) color else C.muted.copy(alpha = 0.6f)
    androidx.compose.foundation.layout.Box(
        Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .border(1.dp, if (enabled) color.copy(alpha = 0.6f) else C.line, androidx.compose.foundation.shape.CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { glyph(tint) }
}

/** Rounds to 5 significant digits, enough for any tick on MFP markets. */
private fun roundTick(v: Double): Double = java.math.BigDecimal(v).round(java.math.MathContext(6)).toDouble()
