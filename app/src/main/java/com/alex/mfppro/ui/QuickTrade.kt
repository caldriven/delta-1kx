package com.alex.mfppro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alex.mfppro.data.Market
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.riskOf
import com.alex.mfppro.data.roundSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.UUID

/**
 * One-tap trading for the position-management view. Each button opens a market
 * position worth (available balance × leverage), shown on the button, at 1x,
 * half of max and max leverage. Plus reduce-only and a 25/50/100% close row
 * for the trading account's position in this market.
 */
@Composable
fun QuickTrade(repo: Repo, market: Market) {
    val marks by repo.market.marks.collectAsStateWithLifecycle()
    val books by repo.market.books.collectAsStateWithLifecycle()
    val acc = rememberTradeAccount(repo) ?: return
    val scope = rememberCoroutineScope()
    val held = acc.positions.values.firstOrNull { it.inMarket(market) }

    val maxLev = market.maxLeverage.toInt().coerceAtLeast(1)
    // Four sizes: 1x..4x on low-leverage markets, otherwise quarters of the max (10x -> 2.5/5/7.5/10).
    val presets = if (maxLev <= 4) (1..maxLev).map { it.toDouble() } else (1..4).map { maxLev * it / 4.0 }
    var buySide by remember { mutableStateOf(true) }
    var reduceOnly by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<Triple<String, String, suspend () -> Unit>?>(null) }

    val book = books[market.feedKey]
    val mark = marks[market.feedKey]
    val margin = acc.positions.values.sumOf { it.marginAt() }
    // Keep a little back for fees and slippage so the order isn't rejected for margin.
    val available = (acc.account.balance - margin).coerceAtLeast(0.0) * 0.97
    val locked = repo.prefs.locked() && !reduceOnly

    // Button read-outs refresh every 15 s so they're readable rather than flickering.
    val risk = riskOf(acc, marks)
    val live = Snap(book?.bids?.firstOrNull()?.px ?: mark, book?.asks?.firstOrNull()?.px ?: mark, risk.dailyRoom, risk.ddRoom, available)
    val latest by rememberUpdatedState(live)
    var snap by remember(market.marketId) { mutableStateOf<Snap?>(null) }
    LaunchedEffect(market.marketId) {
        while (true) {
            val l = latest
            if (l.bid != null && l.ask != null) { snap = l; delay(15_000) } else delay(1_000)
        }
    }
    val heldSigned = held?.let { if (it.isLong) it.size else -it.size } ?: 0.0
    // An open position keeps its leverage, so free margin only buys that much more.
    val policies by repo.policies.collectAsStateWithLifecycle()
    // MFP's exposure caps limit what a new order can add.
    val capLeft = if (reduceOnly) null else exposureLeft(policies[acc.account.id], acc, marks, market.marketId)
    fun effMult(mult: Double) = if (held != null && !reduceOnly) minOf(mult, held.leverage.coerceAtLeast(1.0)) else mult
    // Reduce only needs something to reduce: an open position, traded against.
    fun sideOk(buy: Boolean) = !reduceOnly || (held != null && buy != held.isLong)

    fun send(buy: Boolean, mult: Double) {
        val px = (if (buy) book?.asks?.firstOrNull()?.px else book?.bids?.firstOrNull()?.px) ?: mark ?: return
        var size = roundSize(minOf(available * effMult(mult), capLeft ?: Double.MAX_VALUE) / px, market)
        // Reduce only can't exceed the open position.
        if (reduceOnly && held != null) size = minOf(size, held.size)
        val usd = size * px
        if (size < market.minSize || usd < market.minNotional) {
            repo.postMessage("Size below the market minimum (${fmtUsd(market.minNotional)})"); return
        }
        // An open position keeps its leverage; the multiple only sets the size.
        val lev = held?.leverage?.toInt() ?: kotlin.math.ceil(mult).toInt()
        val word = if (buy) "Long" else "Short"
        val run: suspend () -> Unit = {
            if (held == null) repo.prefs.setLeverage(market.marketId, lev)
            repo.placeOrder("$word ${market.symbol}", buildJsonObject {
                put("client_order_id", "mfppro-" + UUID.randomUUID().toString().take(13))
                put("account_id", acc.account.id); put("market_id", market.marketId)
                put("side", if (buy) "buy" else "sell"); put("size", size)
                put("leverage", lev); put("margin_mode", held?.marginMode ?: repo.prefs.marginMode)
                if (reduceOnly) put("reduce_only", true)
                put("type", "market"); put("expected_price", px)
                put("slippage_tolerance_bps", repo.prefs.slippageBps)
            })
        }
        // How far price can move against this position before the account hits its floor.
        val breachMove = riskOf(acc, marks).room?.takeIf { usd > 0 }?.let { it / usd * 100 }
        val detail = "Market ${if (buy) "buy" else "sell"} ${market.symbol} ≈ ${fmtUsd(usd)} at ${lev}x" +
            (if (reduceOnly) ", reduce only" else "") + " on ${accountName(acc)}." +
            (if (!reduceOnly && breachMove != null) String.format(Locale.US, "\nA %.2f%% move against it reaches your drawdown floor.", breachMove) else "")
        if (repo.prefs.confirmOrders) ask = Triple("$word ${market.symbol} ≈ ${fmtUsd(usd)}", detail, run)
        else scope.launch { busy = true; run(); busy = false }
    }

    Column(
        Modifier.padding(horizontal = 8.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.panel)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        // Side switch with reduce-only beside it; margin in use lives under the account line.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SideToggle(buySide) { buySide = it } }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.padding(top = 6.dp)) { ReduceChip(reduceOnly) { reduceOnly = !reduceOnly } }
        }
        Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            val buy = buySide
            presets.forEach { mult ->
                val sn = snap
                val px = if (buy) sn?.ask else sn?.bid
                var usd = minOf((sn?.available ?: available) * effMult(mult), capLeft ?: Double.MAX_VALUE)
                if (reduceOnly && held != null && px != null) usd = minOf(usd, held.size * px)
                // Net exposure in this market after the fill decides where each floor is hit.
                val size = if (px != null && px > 0) usd / px else 0.0
                val net = heldSigned + if (buy) size else -size
                val dll = sn?.dailyRoom?.let { floorPrice(px, net, it) }
                val dd = sn?.ddRoom?.let { floorPrice(px, net, it) }
                QuickBtn(
                    (if (mult % 1.0 == 0.0) mult.toInt().toString() else String.format(Locale.US, "%.1f", mult)) + "x",
                    (if (reduceOnly && held != null) "≤$" else "$") + fmtCompact(usd),
                    dll, dd, px,
                    if (buy) C.up else C.down, enabled = !busy && !locked && sideOk(buy), modifier = Modifier.weight(1f),
                ) { send(buy, mult) }
            }
        }
        if (locked) Text("Night lock until ${repo.prefs.lockEnd}:00", color = C.down, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        if (held != null) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (f in listOf(0.25, 0.5, 1.0)) {
                val pct = (f * 100).toInt()
                Act(if (f == 1.0) "Close all" else "Close $pct%", color = if (f == 1.0) Color.White else C.text, fill = if (f == 1.0) C.down.copy(alpha = 0.85f) else null,
                    enabled = !busy, modifier = Modifier.weight(1f)) {
                    val run: suspend () -> Unit = { repo.closePosition(held, f) }
                    if (repo.prefs.confirmOrders || (f == 1.0 && repo.prefs.confirmCloseAll)) ask = Triple("Close $pct% ${held.symbol}", "Market close ${fmtUsd(held.size * f * (mark ?: held.entryPrice))} of ${held.symbol}", run)
                    else scope.launch { busy = true; run(); busy = false }
                }
            }
        }
    }

    ask?.let { (headline, detail, run) ->
        ConfirmDialog("Confirm", headline, C.accent, note = detail, confirm = "Send", onDismiss = { ask = null }) {
            ask = null; scope.launch { busy = true; run(); busy = false }
        }
    }
}

private data class Snap(val bid: Double?, val ask: Double?, val dailyRoom: Double?, val ddRoom: Double?, val available: Double)

/**
 * Price at which an account with [room] left hits the floor, holding [net]
 * signed size in this market from [px]. Null when flat or out of reach.
 */
private fun floorPrice(px: Double?, net: Double, room: Double): Double? {
    if (px == null || kotlin.math.abs(net) < 1e-12) return null
    val p = px - room / net
    return p.takeIf { it > 0 }
}

/** Size button: position value first, then the leverage multiple and where the floor would be hit. */
@Composable
private fun QuickBtn(title: String, value: String, dll: Double?, dd: Double?, ref: Double?, color: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = if (enabled) 0.14f else 0.05f))
            .border(1.dp, color.copy(alpha = if (enabled) 0.55f else 0.2f), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = num.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (enabled) C.text else C.muted), maxLines = 1)
        Text(title, color = if (enabled) color else C.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        // Whichever floor price is nearer is the one that breaches first.
        val near = listOfNotNull(dll?.let { "DLL" to it }, dd?.let { "DD" to it }).minByOrNull { kotlin.math.abs(it.second - (ref ?: it.second)) }
        near?.let { (k, v) -> Text("$k " + fmtPrice(v), style = num.copy(fontSize = 9.sp, color = C.down), maxLines = 1) }
    }
}

@Composable
private fun ReduceChip(on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Row(
        Modifier.clip(shape).background(if (on) C.accent.copy(alpha = 0.18f) else C.panel2)
            .border(1.dp, if (on) C.accent else C.line, shape).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.UnfoldLess, null, tint = if (on) C.accent else C.muted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("Reduce", color = if (on) C.accent else C.text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * One swap button: shows the side you're on in its colour; tap (or swipe) flips
 * it, with the label sliding out and the other side sliding in.
 */
@Composable
private fun SideToggle(buy: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val col by androidx.compose.animation.animateColorAsState(if (buy) C.up else C.down, androidx.compose.animation.core.tween(220), label = "sideCol")
    val change by androidx.compose.runtime.rememberUpdatedState(onChange)
    val cur by androidx.compose.runtime.rememberUpdatedState(buy)
    Box(
        Modifier.padding(top = 6.dp).fillMaxWidth().height(40.dp).clip(shape).background(col.copy(alpha = 0.18f)).border(1.dp, col.copy(alpha = 0.75f), shape)
            .pointerInput(Unit) { detectTapGestures { change(!cur) } }
            .pointerInput(Unit) {
                var acc = 0f
                // A deliberate swipe (over a third of the button) is needed, so a brush while scrolling never flips the side.
                detectHorizontalDragGestures(onDragStart = { acc = 0f }, onDragEnd = { if (kotlin.math.abs(acc) > maxOf(80.dp.toPx(), size.width / 3f)) change(!cur) }) { ch, dx -> ch.consume(); acc += dx }
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.animation.AnimatedContent(
            buy, label = "side",
            transitionSpec = {
                val dir = if (targetState) -1 else 1
                (androidx.compose.animation.slideInHorizontally { it * dir / 2 } + androidx.compose.animation.fadeIn())
                    .togetherWith(androidx.compose.animation.slideOutHorizontally { -it * dir / 2 } + androidx.compose.animation.fadeOut())
            },
        ) { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (b) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown, null, tint = if (b) C.up else C.down, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (b) "Long" else "Short", color = if (b) C.up else C.down, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
        // Swap hint at the right edge.
        Text("⇄", color = col.copy(alpha = 0.8f), fontSize = 16.sp, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp))
    }
}
