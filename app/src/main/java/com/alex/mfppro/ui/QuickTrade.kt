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
import androidx.compose.foundation.layout.wrapContentHeight
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

/** Limit mode of the quick panel. Its price is a line on the chart that can be dragged. */
class LimitDraft {
    var on by mutableStateOf(false)
    var price by mutableStateOf<Double?>(null)
    var buy by mutableStateOf(true)
    /** Price decimals picked for this market; null = the market's own tick. */
    var decimals by mutableStateOf<Int?>(null)

    /** [px] rounded to the picked decimals, never finer than the tick. */
    fun snap(px: Double, m: Market): Double {
        val d = decimals ?: return snapTick(px, m)
        return if (d >= tickDecimals(m)) snapTick(px, m) else java.math.BigDecimal(px).setScale(d, java.math.RoundingMode.HALF_UP).toDouble()
    }

    /** One step of the − / + buttons at the picked decimals. */
    fun step(m: Market, px: Double): Double {
        val d = decimals
        return if (d != null && d < tickDecimals(m)) Math.pow(10.0, -d.toDouble()) else if (m.tickSize > 0) m.tickSize else px * 0.0005
    }
}

/** Decimals of the market's tick (0.001 -> 3); 6 when the market has none. */
fun tickDecimals(m: Market): Int =
    if (m.tickSize > 0) java.math.BigDecimal(m.tickSize.toString()).stripTrailingZeros().scale().coerceAtLeast(0) else 6

/** Price snapped to the market's tick. */
fun snapTick(px: Double, m: Market): Double =
    if (m.tickSize > 0) java.math.BigDecimal(px / m.tickSize).setScale(0, java.math.RoundingMode.HALF_UP).toDouble() * m.tickSize
    else java.math.BigDecimal(px).round(java.math.MathContext(6)).toDouble()

/**
 * One-tap trading for the position-management view. Each button opens a position
 * worth (available balance × leverage), shown on the button, at market or, in Limit
 * mode, as a limit order at the [draft] price. Plus reduce-only and a 25/50/100%
 * close row for the trading account's position in this market.
 */
@Composable
fun QuickTrade(repo: Repo, market: Market, draft: LimitDraft) {
    val marks by repo.market.marks.collectAsStateWithLifecycle()
    // Only this market's book, at full speed: it prices the size buttons and market orders.
    val book by repo.market.books.collectKey(market.feedKey)
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

    LaunchedEffect(buySide) { draft.buy = buySide }
    val limitPx = draft.price?.takeIf { draft.on }

    fun send(buy: Boolean, mult: Double) {
        val px = limitPx ?: (if (buy) book?.asks?.firstOrNull()?.px else book?.bids?.firstOrNull()?.px) ?: mark ?: return
        var size = roundSize(minOf(available * effMult(mult), capLeft ?: Double.MAX_VALUE) / px, market)
        // Reduce only can't exceed the open position.
        if (reduceOnly && held != null) size = minOf(size, held.size)
        val usd = size * px
        if (size < market.minSize || usd < market.minNotional) {
            repo.postMessage("Size below the market minimum (${fmtUsd(market.minNotional)})"); return
        }
        // An open position keeps its leverage; the multiple only sets the size.
        val lev = held?.leverage?.toInt() ?: kotlin.math.ceil(mult).toInt()
        val word = (if (limitPx != null) "Limit " else "") + if (buy) "Long" else "Short"
        // A limit at or through the touch fills straight away; the server wants that acknowledged.
        val crosses = limitPx != null && (if (buy) limitPx >= (book?.asks?.firstOrNull()?.px ?: Double.MAX_VALUE) else limitPx <= (book?.bids?.firstOrNull()?.px ?: 0.0))
        val run: suspend () -> Unit = {
            if (held == null) repo.prefs.setLeverage(market.marketId, lev)
            repo.placeOrder("$word ${market.symbol}", buildJsonObject {
                put("client_order_id", "mfppro-" + UUID.randomUUID().toString().take(13))
                put("account_id", acc.account.id); put("market_id", market.marketId)
                put("side", if (buy) "buy" else "sell"); put("size", size)
                put("leverage", lev); put("margin_mode", held?.marginMode ?: repo.prefs.marginMode)
                if (reduceOnly) put("reduce_only", true)
                if (limitPx != null) {
                    put("type", "limit"); put("limit_price", limitPx)
                    if (crosses) put("marketable_limit_acknowledged", true)
                } else {
                    put("type", "market"); put("expected_price", px)
                    put("slippage_tolerance_bps", repo.prefs.slippageBps)
                }
            })
        }
        // How far price can move against this position before the account hits its floor.
        val breachMove = riskOf(acc, marks).room?.takeIf { usd > 0 }?.let { it / usd * 100 }
        val detail = (if (limitPx != null) "Limit ${if (buy) "buy" else "sell"} at ${fmtPrice(limitPx)}" + (if (crosses) " (crosses the book: fills now)" else "") else "Market ${if (buy) "buy" else "sell"}") +
            " ${market.symbol} ≈ ${fmtUsd(usd)} at ${lev}x" +
            (if (reduceOnly) ", reduce only" else "") + " on ${accountName(acc)}." +
            (if (!reduceOnly && breachMove != null) String.format(Locale.US, "\nA %.2f%% move against it reaches your drawdown floor.", breachMove) else "")
        if (repo.prefs.confirmOrders) ask = Triple("$word ${market.symbol} ≈ ${fmtUsd(usd)}", detail, run)
        else scope.launch { busy = true; run(); busy = false }
    }

    Column(
        Modifier.padding(horizontal = 8.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Row 1: Long | Short as one switch, both sides always visible.
        SlideSwitch(listOf("Long", "Short"), if (buySide) 0 else 1, listOf(C.up, C.down), Modifier.fillMaxWidth(), height = 32.dp,
            icons = listOf(Icons.AutoMirrored.Filled.TrendingUp, Icons.AutoMirrored.Filled.TrendingDown)) { buySide = it == 0 }
        // Row 2: Market | Limit with reduce-only beside it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            SlideSwitch(listOf("Market", "Limit"), if (draft.on) 1 else 0, listOf(C.accent, C.accent), Modifier.weight(1f), height = 28.dp) { i ->
                if (i == 1 && draft.price == null) draft.price = ((if (buySide) book?.bids?.firstOrNull()?.px else book?.asks?.firstOrNull()?.px) ?: mark)?.let { draft.snap(it, market) }
                draft.on = i == 1
            }
            Spacer(Modifier.width(6.dp))
            ReduceChip(reduceOnly) { reduceOnly = !reduceOnly }
        }
        // Row 3, Limit only: price with − / + (or drag the line on the chart) and the decimals it snaps to.
        if (draft.on) Row(verticalAlignment = Alignment.CenterVertically) {
            val p = draft.price
            val tickDec = tickDecimals(market)
            val dec = draft.decimals ?: tickDec
            Nudge("−") { p?.let { draft.price = draft.snap(it - draft.step(market, it), market) } }
            Row(Modifier.weight(1f).padding(horizontal = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
                Text(p?.let { java.math.BigDecimal(it).setScale(dec, java.math.RoundingMode.HALF_UP).toPlainString() } ?: "—",
                    style = num.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (buySide) C.up else C.down), maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Text(p?.let { pp -> mark?.let { m -> fmtPct((pp / m - 1) * 100) } } ?: "", style = num.copy(fontSize = 10.sp, color = C.muted), modifier = Modifier.padding(bottom = 2.dp))
            }
            Nudge("+") { p?.let { draft.price = draft.snap(it + draft.step(market, it), market) } }
            Spacer(Modifier.width(6.dp))
            // Decimals: the market's tick, then 1, 2, 3 fewer; remembered per market.
            Text("${dec} dp", style = num.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (dec < tickDec) C.accent else C.text),
                modifier = Modifier.height(28.dp).clip(RoundedCornerShape(8.dp)).background(C.panel2).border(1.dp, C.line, RoundedCornerShape(8.dp))
                    .clickable {
                        val next = if (dec <= maxOf(0, tickDec - 3)) tickDec else dec - 1
                        draft.decimals = next.takeIf { it < tickDec }
                        repo.prefs.setPriceDecimals(market.marketId, draft.decimals)
                        p?.let { draft.price = draft.snap(it, market) }
                    }.wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val buy = buySide
            presets.forEach { mult ->
                val sn = snap
                val px = limitPx ?: if (buy) sn?.ask else sn?.bid
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
        if (locked) Text("Night lock until ${repo.prefs.lockEnd}:00", color = C.down, fontSize = 11.sp)
        if (held != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

/**
 * Floor price rounded to a coarse step, always towards the current price [ref], so the number shown
 * is hit a little before the real breach: 0.05 steps, or ~4 significant digits on cheap coins.
 */
private fun safeFloor(v: Double, ref: Double?): Double {
    if (v <= 0) return v
    val e = kotlin.math.floor(kotlin.math.log10(v)).toInt()
    val step = minOf(0.05, 5 * Math.pow(10.0, (e - 3).toDouble()))
    val n = v / step
    // Below the price (long side floor): the next step up. Above (short side): the next step down.
    // Always a full step past the exact floor, so even an exact multiple keeps some margin.
    val r = if (ref == null || v < ref) kotlin.math.floor(n) + 1 else kotlin.math.ceil(n) - 1
    return r * step
}

/** Size button: position value first, then the leverage multiple and where the floor would be hit. */
@Composable
private fun QuickBtn(title: String, value: String, dll: Double?, dd: Double?, ref: Double?, color: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    // Fixed height with the floor line always reserved, so every button in the row lines up.
    // Value first and largest; the multiple sits beside it small, the floor price under it.
    Column(
        modifier.height(40.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = if (enabled) 0.14f else 0.05f))
            .border(1.dp, color.copy(alpha = if (enabled) 0.55f else 0.2f), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = num.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (enabled) C.text else C.muted), maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Text(title, color = if (enabled) color else C.muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(bottom = 1.dp))
        }
        // Whichever floor price is nearer is the one that breaches first.
        val near = listOfNotNull(dll?.let { "DLL" to it }, dd?.let { "DD" to it }).minByOrNull { kotlin.math.abs(it.second - (ref ?: it.second)) }
        Text(near?.let { (k, v) -> "$k " + fmtPrice(safeFloor(v, ref)) } ?: "no floor in reach", style = num.copy(fontSize = 9.sp, color = if (near != null) C.down else C.muted), maxLines = 1)
    }
}

/**
 * Two-way switch with both options always visible: a pill in the active option's colour slides
 * under it. Tap a side, or swipe across, to change.
 */
@Composable
fun SlideSwitch(
    options: List<String>, selected: Int, colors: List<Color>, modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 40.dp, icons: List<androidx.compose.ui.graphics.vector.ImageVector>? = null, onSelect: (Int) -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val pick by rememberUpdatedState(onSelect)
    val cur by rememberUpdatedState(selected)
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.height(height).clip(shape).background(C.bg).border(1.dp, C.line, shape).padding(3.dp)
            .pointerInput(Unit) {
                var acc = 0f
                detectHorizontalDragGestures(onDragStart = { acc = 0f }, onDragEnd = {
                    if (kotlin.math.abs(acc) > 40.dp.toPx()) pick(if (acc > 0) (cur + 1).coerceAtMost(1) else (cur - 1).coerceAtLeast(0))
                }) { ch, dx -> ch.consume(); acc += dx }
            },
    ) {
        val w = maxWidth / options.size
        val x by androidx.compose.animation.core.animateDpAsState(w * selected, androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 500f), label = "pill")
        val col by androidx.compose.animation.animateColorAsState(colors[selected], label = "pillCol")
        Box(Modifier.offset(x = x).width(w).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(col.copy(alpha = 0.22f)).border(1.dp, col.copy(alpha = 0.8f), RoundedCornerShape(8.dp)))
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                val tint by androidx.compose.animation.animateColorAsState(if (on) colors[i] else C.muted, label = "optTint")
                Row(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).clickable { pick(i) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    icons?.getOrNull(i)?.let { Icon(it, null, tint = tint, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
                    Text(label, color = tint, fontSize = if (height >= 44.dp) 15.sp else 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

/** Small square − / + button for nudging a price by one tick. */
@Composable
private fun Nudge(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(C.panel2).border(1.dp, C.line, RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun ReduceChip(on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.height(28.dp).clip(shape).background(if (on) C.accent.copy(alpha = 0.18f) else C.panel2)
            .border(1.dp, if (on) C.accent else C.line, shape).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.UnfoldLess, null, tint = if (on) C.accent else C.muted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("Reduce", color = if (on) C.accent else C.text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
