package com.alex.mfppro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.filled.Clear
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alex.mfppro.data.Market
import com.alex.mfppro.data.OSLO
import com.alex.mfppro.data.maxLeverage
import com.alex.mfppro.data.riskOf
import com.alex.mfppro.data.Quote
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.TradeResult
import com.alex.mfppro.data.roundSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

data class TicketRequest(val buy: Boolean, val limitPrice: Double? = null)

/** The single order panel: which market it's open on and how. MainActivity draws it; anything can open or close it. */
object OrderPanel {
    var open by androidx.compose.runtime.mutableStateOf<Pair<String, TicketRequest>?>(null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderTicket(repo: Repo, market: Market, request: TicketRequest, onDismiss: () -> Unit) {
    val accounts by repo.account.accounts.collectAsStateWithLifecycle()
    val marks by repo.market.marks.collectAsStateWithLifecycle()
    val bookState = repo.market.books.collectKey(market.feedKey)
    val book = bookState.value
    val scope = rememberCoroutineScope()

    val list = accounts.values.filter { it.account.status == "active" }.sortedBy { it.account.name }
    val acc = rememberTradeAccount(repo)
    val accountId = acc?.account?.id
    val risk = acc?.let { riskOf(it, marks) }
    // Keep the leverage and margin mode of an existing position in this market.
    val held = acc?.positions?.values?.firstOrNull { it.inMarket(market) }

    var buy by remember { mutableStateOf(request.buy) }
    var limit by remember { mutableStateOf(request.limitPrice != null) }
    // Scaled ladder: start/end price, rungs, distribution.
    var scaled by remember { mutableStateOf(false) }
    // Stop entry (market or limit at the trigger) and chase (limit that follows the touch).
    var stop by remember { mutableStateOf(false) }
    var stopLimit by remember { mutableStateOf(false) }
    var triggerText by remember { mutableStateOf("") }
    var chase by remember { mutableStateOf(false) }
    var chasePct by remember { mutableStateOf(0.0) }
    var capText by remember { mutableStateOf("") }
    fun pick(m: String) { limit = m == "limit"; scaled = m == "scaled"; stop = m == "stop"; chase = m == "chase" }
    var startText by remember { mutableStateOf("") }
    var endText by remember { mutableStateOf("") }
    var rungs by remember { mutableStateOf(5) }
    var dist by remember { mutableStateOf("flat") }
    var priceText by remember { mutableStateOf(request.limitPrice?.let(::plain) ?: "") }
    var usdText by remember { mutableStateOf("") }
    val policies by repo.policies.collectAsStateWithLifecycle()
    // The account's own cap: Elite accounts get more on some indices and commodities.
    val maxLev = maxLeverage(market, acc?.account, accountId?.let { policies[it] })
    // Follow the account: its position in this market sets leverage and margin mode.
    var isolated by remember(accountId) { mutableStateOf((held?.marginMode ?: repo.prefs.marginModeFor(accountId)) == "isolated") }
    // The panel's leverage is the setting: remembered per market, and what quick orders use too.
    var lev by remember(accountId, maxLev) { mutableStateOf(held?.leverage?.toInt() ?: repo.prefs.leverage(market.marketId, minOf(10, maxLev)).coerceIn(1, maxLev)) }
    var reduceOnly by remember { mutableStateOf(false) }
    var postOnly by remember { mutableStateOf(repo.prefs.postOnly) }
    var closeAsk by remember { mutableStateOf<Double?>(null) }
    var tpsl by remember { mutableStateOf(false) }
    var tpText by remember { mutableStateOf("") }
    var slText by remember { mutableStateOf("") }
    var quote by remember { mutableStateOf<Quote?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val mark = marks[market.feedKey]
    // Limit prices filled in for you use the decimals picked for this market (Trade view, "dp" chip).
    val dec = repo.prefs.priceDecimals(market.marketId)
    fun px(v: Double) = plain(naturalDecimals(market, v).let { n -> roundDec(v, minOf(dec ?: n, n)) })
    val limitPx = priceText.toDoubleOrNull()
    val startPx = startText.toDoubleOrNull(); val endPx = endText.toDoubleOrNull()
    val triggerPx = triggerText.toDoubleOrNull(); val capPx = capText.toDoubleOrNull()
    // A buy ladder above the best ask (or a sell below the best bid) fills those rungs at market.
    val ladderCrosses = scaled && startPx != null && endPx != null && book != null && run {
        val far = if (buy) maxOf(startPx, endPx) else minOf(startPx, endPx)
        if (buy) far >= (book.asks.firstOrNull()?.px ?: Double.MAX_VALUE) else far <= (book.bids.firstOrNull()?.px ?: 0.0)
    }
    val refPx = if (stop) (if (stopLimit) limitPx ?: triggerPx else triggerPx)
        else if (chase) ((if (buy) book?.bids?.firstOrNull()?.px else book?.asks?.firstOrNull()?.px) ?: mark)
        else if (scaled) (if (startPx != null && endPx != null) (startPx + endPx) / 2 else null) else if (limit) limitPx else (if (buy) book?.asks?.firstOrNull()?.px else book?.bids?.firstOrNull()?.px) ?: mark
    val usd = usdText.toDoubleOrNull() ?: 0.0
    val size = if (refPx != null && refPx > 0) roundSize(usd / refPx, market) else 0.0
    val margin = acc?.positions?.values?.sumOf { it.marginAt() } ?: 0.0
    val available = ((acc?.account?.balance ?: 0.0) - margin).coerceAtLeast(0.0)
    val capLeft = acc?.let { exposureLeft(policies[it.account.id], it, marks, market.marketId) }
    // Buying power, limited by MFP's exposure caps.
    val maxNotional = minOf(available * lev, capLeft ?: Double.MAX_VALUE)
    val marketable = limit && limitPx != null && book != null &&
        ((buy && limitPx >= (book.asks.firstOrNull()?.px ?: Double.MAX_VALUE)) || (!buy && limitPx <= (book.bids.firstOrNull()?.px ?: 0.0)))
    // Attached exits only go with opening single orders (not reduce-only, not a scaled ladder).
    val tp = if (tpsl && !reduceOnly && !scaled) tpText.toDoubleOrNull() else null
    val sl = if (tpsl && !reduceOnly && !scaled) slText.toDoubleOrNull() else null
    val problem = when {
        acc == null -> "Pick an account"
        limit && !scaled && (limitPx == null || limitPx <= 0) -> "Enter a limit price"
        stop && (triggerPx == null || triggerPx <= 0) -> "Enter a trigger price"
        stop && stopLimit && (limitPx == null || limitPx <= 0) -> "Enter the limit price used after the trigger"
        // A stop entry buys a breakout above the market or sells a breakdown below it.
        stop && mark != null && triggerPx != null && (if (buy) triggerPx <= mark else triggerPx >= mark) -> if (buy) "A buy stop must be above the price" else "A sell stop must be below the price"
        scaled && (startPx == null || endPx == null || startPx <= 0 || endPx <= 0 || startPx == endPx) -> "Enter a start and end price"
        // The smallest rung: 1 of n(n+1)/2 weights when weighted, 1/n when flat.
        scaled && usd * (if (dist == "flat") 1.0 / rungs else 2.0 / (rungs * (rungs + 1))) < market.minNotional -> "Smallest rung is below the ${fmtUsd(market.minNotional)} minimum"
        size <= 0 -> "Enter a size"
        marketable && postOnly -> "Post only: this price crosses the book. Move it past the ${if (buy) "ask" else "bid"} or turn post only off"
        ladderCrosses && postOnly -> "Post only: some rungs cross the book. Move the range or turn post only off"
        size < market.minSize -> "Below minimum size ${fmtSize(market.minSize)} ${market.symbol}"
        usd < market.minNotional -> "Below minimum ${fmtUsd(market.minNotional)}"
        tp != null && refPx != null && (if (buy) tp <= refPx else tp >= refPx) -> "Take profit must be ${if (buy) "above" else "below"} the price"
        sl != null && refPx != null && (if (buy) sl >= refPx else sl <= refPx) -> "Stop loss must be ${if (buy) "below" else "above"} the price"
        else -> null
    }
    val sideCol = if (buy) C.up else C.down
    val sideWord = if (buy) "Buy / Long" else "Sell / Short"

    // Size-aware quote for market orders, refreshed while the ticket is open.
    // Keyed on what the user typed, not on size (which moves with every book tick); size is read fresh each loop.
    val sizeNow by androidx.compose.runtime.rememberUpdatedState(size)
    LaunchedEffect(usdText, buy, limit, stop, chase) {
        quote = null
        val key = repo.apiKey.value ?: return@LaunchedEffect
        if (limit || stop || chase || usdText.isBlank()) return@LaunchedEffect
        delay(300)
        while (true) {
            if (sizeNow > 0) runCatching { repo.trade.quote(key, market.marketId, if (buy) "buy" else "sell", sizeNow) }.onSuccess { quote = it }
            delay(3_000)
        }
    }

    // Prices go out on the market's tick, as the confirmation shows them.
    fun snapPx(v: Double) = snapTick(v, market)

    fun submit() {
        val a = acc ?: return
        repo.prefs.tradeAccountId = a.account.id
        repo.prefs.setLeverage(market.marketId, lev)
        repo.prefs.setMarginModeFor(a.account.id, if (isolated) "isolated" else "cross")
        // Scaled ladders go their own way; the single-order body below would need a limit price.
        if (scaled) {
            busy = true
            val ladder = buildJsonObject {
                put("account_id", a.account.id); put("market_id", market.marketId)
                put("side", if (buy) "buy" else "sell"); put("size", size)
                put("start_price", snapPx(startPx!!)); put("end_price", snapPx(endPx!!))
                put("rung_count", rungs); put("distribution", dist)
                put("leverage", lev); put("margin_mode", if (isolated) "isolated" else "cross")
                if (reduceOnly) put("reduce_only", true)
                if (ladderCrosses && !postOnly) put("marketable_limit_acknowledged", true)
            }
            scope.launch {
                val r = repo.placeScaled("Scaled $sideWord ${market.symbol}", ladder)
                busy = false
                if (r.accepted) onDismiss()
            }
            return
        }
        val body = buildJsonObject {
            put("client_order_id", "mfppro-" + UUID.randomUUID().toString().take(13))
            put("account_id", a.account.id); put("market_id", market.marketId)
            put("side", if (buy) "buy" else "sell"); put("size", size)
            put("leverage", lev); put("margin_mode", if (isolated) "isolated" else "cross")
            if (reduceOnly) put("reduce_only", true)
            if (stop) {
                put("type", if (stopLimit) "stop_limit" else "stop_market"); put("trigger_price", snapPx(triggerPx!!))
                if (stopLimit) put("limit_price", snapPx(limitPx!!))
            } else if (chase) {
                put("type", "chase")
                put("chase_offset", if (chasePct > 0) snapTick((refPx ?: 0.0) * chasePct / 100, market) else 0.0)
                capPx?.let { put("chase_cap_price", snapPx(it)) }
            } else if (limit) {
                put("type", "limit"); put("limit_price", snapPx(limitPx!!))
                if (marketable && !postOnly) put("marketable_limit_acknowledged", true)
            } else {
                put("type", "market")
                put("expected_price", quote?.estimatedFillPrice ?: refPx ?: 0.0)
                put("slippage_tolerance_bps", repo.prefs.slippageBps)
            }
            if (!reduceOnly) {
                tp?.let { put("take_profit_price", snapPx(it)) }
                sl?.let { put("stop_loss_price", snapPx(it)) }
                if (tp != null || sl != null) put("replace_overlapping_exits", true)
            }
        }
        busy = true
        scope.launch {
            val r = repo.placeOrder("$sideWord ${market.symbol}", body)
            busy = false
            if (r.accepted) onDismiss()
        }
    }

    SidePanel(onDismiss) {
        // 1-minute chart of this market above the form.
        val candles by repo.market.candles.collectThrottled(500)
        androidx.compose.runtime.DisposableEffect(market.marketId) {
            val subs = listOf(com.alex.mfppro.data.Sub("candles", market.provider, market.coin, "1m"), com.alex.mfppro.data.Sub("books", market.provider, market.coin))
            subs.forEach { repo.market.acquire(it) }
            onDispose { subs.forEach { repo.market.release(it) } }
        }
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(market.symbol, color = C.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.width(6.dp))
            VenueTag(market.provider)
            Spacer(Modifier.width(6.dp))
            Text(fmtPrice(mark), style = num.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.up))
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Clear, "Close", tint = C.muted,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onDismiss).padding(8.dp).size(20.dp))
        }
        val mid = book?.let { b -> val bb = b.bids.firstOrNull()?.px; val aa = b.asks.firstOrNull()?.px; if (bb != null && aa != null) (bb + aa) / 2 else null } ?: mark
        CandleChart(
            candles[com.alex.mfppro.data.candleKey(market.provider, market.coin, "1m")].orEmpty().withLive(mid),
            listOfNotNull(held?.let { PriceLine(it.entryPrice, if (it.isLong) C.up else C.down, if (it.isLong) "Long" else "Short", solid = true) }),
            Modifier.fillMaxWidth().height((androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.26f).dp),
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                AccountSwitcher(repo, Modifier.weight(1f))
                risk?.let {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Room left", color = C.muted, fontSize = 10.sp)
                        Text(fmtUsd(it.room), style = num.copy(fontWeight = FontWeight.Bold, color = roomColor(it.roomFraction)))
                    }
                }
            }
            risk?.let { RiskWarning(it) }

            // Margin mode and leverage, side by side like the web terminal.
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(Modifier.weight(1f).clickable(enabled = held == null) { isolated = !isolated }) {
                    Text(if (isolated) "Isolated" else "Cross", color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("⇅", color = C.muted)
                }
                // Full-height − / + targets; with a position open the leverage is the position's and can't change.
                Row(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(10.dp)).background(C.bg), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(48.dp).fillMaxHeight().clickable(enabled = held == null && lev > 1) { lev = (lev - 1).coerceAtLeast(1) }, contentAlignment = Alignment.Center) {
                        Text("−", color = if (held == null && lev > 1) C.text else C.muted, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${lev}x", style = num.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold))
                        Text(if (held != null) "position's" else "max ${maxLev}x", color = C.muted, fontSize = 9.sp)
                    }
                    Box(Modifier.width(48.dp).fillMaxHeight().clickable(enabled = held == null && lev < maxLev) { lev = (lev + 1).coerceAtMost(maxLev) }, contentAlignment = Alignment.Center) {
                        Text("+", color = if (held == null && lev < maxLev) C.text else C.muted, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (held == null) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 2, 3, 5, 10, 20, 25, 40, 50).filter { it < maxLev }.plus(maxLev).distinct().forEach { l ->
                    Seg("${l}x", l == lev, C.accent) { lev = l }
                }
            }

            Row(Modifier.padding(top = 10.dp)) {
                UnderTab("Market", !limit && !scaled && !stop && !chase, Modifier.weight(1f)) { pick("market") }
                UnderTab("Limit", limit && !scaled, Modifier.weight(1f)) { pick("limit"); if (priceText.isBlank()) priceText = mark?.let(::px) ?: "" }
                UnderTab("Stop", stop, Modifier.weight(1f)) {
                    pick("stop")
                    // Default trigger 0.5% beyond the price on the breakout side.
                    val m = mark
                    if (m != null && triggerText.isBlank()) triggerText = plain(snapPx(if (buy) m * 1.005 else m * 0.995))
                }
                UnderTab("Chase", chase, Modifier.weight(1f)) { pick("chase") }
                UnderTab("Scaled", scaled, Modifier.weight(1f)) {
                    pick("scaled"); limit = true
                    // Default ladder: from the touch to 1% away on our side.
                    val m = mark
                    if (m != null && startText.isBlank()) { startText = plain(m); endText = plain(snapPx(if (buy) m * 0.99 else m * 1.01)) }
                }
            }
            Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.bg).padding(3.dp)) {
                SideTab("Buy / Long", buy, C.up, Modifier.weight(1f)) { buy = true }
                SideTab("Sell / Short", !buy, C.down, Modifier.weight(1f)) { buy = false }
            }

            if (stop) {
                NumField("Trigger price", triggerText, { triggerText = it }, Modifier.fillMaxWidth())
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Seg("Market at trigger", !stopLimit, C.accent) { stopLimit = false }
                    Spacer(Modifier.width(6.dp))
                    Seg("Limit at trigger", stopLimit, C.accent) { stopLimit = true; if (priceText.isBlank()) priceText = triggerText }
                }
                if (stopLimit) NumField("Limit price", priceText, { priceText = it }, Modifier.fillMaxWidth())
                Text(if (buy) "Buys when the price rises to the trigger" else "Sells when the price falls to the trigger", color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            } else if (chase) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Offset", color = C.muted, fontSize = 12.sp, modifier = Modifier.width(52.dp))
                    for (pct in listOf(0.0, 0.02, 0.05, 0.1)) {
                        Seg(if (pct == 0.0) "Join" else String.format(Locale.US, "%.2f%%", pct), chasePct == pct, C.accent) { chasePct = pct }
                        Spacer(Modifier.width(4.dp))
                    }
                }
                NumField(if (buy) "Max price (optional)" else "Min price (optional)", capText, { capText = it }, Modifier.fillMaxWidth())
                Text("Rests at the best ${if (buy) "bid" else "ask"}${if (chasePct > 0) (if (buy) " minus" else " plus") + " the offset" else ""} and follows it until filled" +
                    (capPx?.let { ", never past ${fmtPrice(it)}" } ?: ""), color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            } else if (scaled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Start price", startText, { startText = it }, Modifier.weight(1f))
                    NumField("End price", endText, { endText = it }, Modifier.weight(1f))
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Rungs", color = C.muted, fontSize = 12.sp, modifier = Modifier.width(52.dp))
                    for (n in listOf(2, 3, 5, 7, 10)) { Seg("$n", rungs == n, C.accent) { rungs = n }; Spacer(Modifier.width(4.dp)) }
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Weight", color = C.muted, fontSize = 12.sp, modifier = Modifier.width(52.dp))
                    for ((k, l) in listOf("flat" to "Flat", "ascending" to "More at end", "descending" to "More at start")) { Seg(l, dist == k, C.accent) { dist = k }; Spacer(Modifier.width(4.dp)) }
                }
                // Preview of each rung: price and dollar size, weighted like the server does (linear 1..n).
                if (startPx != null && endPx != null && usd > 0) {
                    val w = (1..rungs).map { i -> when (dist) { "ascending" -> i.toDouble(); "descending" -> (rungs + 1 - i).toDouble(); else -> 1.0 } }
                    val total = w.sum()
                    Column(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.bg).padding(8.dp)) {
                        for (i in 0 until rungs) {
                            val px = if (rungs == 1) startPx else startPx + (endPx - startPx) * i / (rungs - 1)
                            Row {
                                Text("#${i + 1}", color = C.muted, fontSize = 11.sp, modifier = Modifier.width(28.dp))
                                Text(fmtPrice(px), style = num.copy(fontSize = 11.sp), modifier = Modifier.weight(1f))
                                Text(fmtUsd(usd * w[i] / total), style = num.copy(fontSize = 11.sp, color = sideCol))
                            }
                        }
                    }
                }
                Check("Post only", postOnly) { postOnly = it; repo.prefs.postOnly = it }
            } else if (limit) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumField("Limit price", priceText, { priceText = it }, Modifier.weight(1f))
                    // Join our own side of the book: best bid for a buy, best ask for a sell.
                    TextButton(onClick = {
                        val best = if (buy) book?.bids?.firstOrNull()?.px else book?.asks?.firstOrNull()?.px
                        priceText = (best ?: mark)?.let(::px) ?: ""
                    }) { Text("Best", color = C.accent) }
                }
                Check("Post only", postOnly) { postOnly = it; repo.prefs.postOnly = it }
                Text(
                    when {
                        marketable && postOnly -> "Crosses the book: post only blocks it"
                        marketable -> "Crosses the book: fills immediately as a market order"
                        postOnly -> "Rests on the book as maker; refused if it would fill at once"
                        else -> "Rests on the book, or fills at market if the price is already past"
                    },
                    color = if (marketable) C.accent else C.muted, fontSize = 11.sp,
                )
            }
            NumField("Size (USD)", usdText, { usdText = it }, Modifier.fillMaxWidth())
            // Size as a share of buying power (available × leverage).
            val pct = if (maxNotional > 0) (usd / maxNotional).coerceIn(0.0, 1.0) else 0.0
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                PctSlider(pct.toFloat(), sideCol, Modifier.weight(1f)) { f -> usdText = String.format(Locale.US, "%.0f", f * maxNotional) }
                Text("${(pct * 100).roundToInt()}%", style = num.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
            }
            Text("Buying power ${fmtUsd(maxNotional)}" + (if (capLeft != null && capLeft <= available * lev) " (exposure cap)" else ""), color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))

            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (buy) "Long" else "Short", color = C.muted, fontSize = 12.sp, modifier = Modifier.width(44.dp))
                repo.prefs.quickSizes.forEach { q -> QuickBtn("$" + String.format(Locale.US, "%,d", q), sideCol, Modifier.weight(1f)) { usdText = q.toString() } }
            }
            // Quick close of the position already held in this market on this account.
            held?.let { h ->
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Close", color = C.muted, fontSize = 12.sp, modifier = Modifier.width(44.dp))
                    for (f in listOf(0.25, 0.5, 1.0)) {
                        QuickBtn("${(f * 100).toInt()}%", C.text, Modifier.weight(1f)) {
                            if (busy) return@QuickBtn
                            if (repo.prefs.needsConfirm(if (f == 1.0) com.alex.mfppro.data.Confirm.Full else com.alex.mfppro.data.Confirm.Order)) closeAsk = f
                            else { busy = true; scope.launch { val r = repo.closePosition(h, f); busy = false; if (r is TradeResult.Ok && f == 1.0) onDismiss() } }
                        }
                    }
                }
            }

            Check("Reduce only", reduceOnly) { reduceOnly = it }
            if (!reduceOnly && !scaled) Check("Take profit / Stop loss", tpsl) { tpsl = it }
            if (tpsl && !reduceOnly && !scaled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("TP price", tpText, { tpText = it }, Modifier.weight(1f))
                    NumField("SL price", slText, { slText = it }, Modifier.weight(1f))
                }
                if (refPx != null && size > 0) Row {
                    val dir = if (buy) 1 else -1
                    Text("TP ${tp?.let { fmtUsd((it - refPx) * size * dir, true) } ?: "—"}", style = num.copy(fontSize = 11.sp, color = C.up), modifier = Modifier.weight(1f))
                    Text("SL ${sl?.let { fmtUsd((it - refPx) * size * dir, true) } ?: "—"}", style = num.copy(fontSize = 11.sp, color = C.down), modifier = Modifier.weight(1f))
                }
            }

            // What this trade does to the rules: loss at the stop vs room left, or the move that breaches.
            val room = risk?.room
            if (room != null && size > 0 && refPx != null && !reduceOnly) {
                val fee = quote?.estimatedFee ?: 0.0
                val slLoss = sl?.let { kotlin.math.abs(refPx - it) * size + fee * 2 }
                Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.bg).padding(10.dp)) {
                    if (slLoss != null) {
                        val after = room - slLoss
                        Info("Loss if SL hits", fmtUsd(-slLoss), C.down)
                        Info("Room left after SL", fmtUsd(after), if (after <= 0) C.down else if (after < room * 0.25) C.accent else C.up)
                        if (after <= 0) Text("This stop is beyond your floor: hitting it BREACHES the account.", color = C.down, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        val move = room / (size * refPx) * 100
                        val px = if (buy) refPx * (1 - move / 100) else refPx * (1 + move / 100)
                        Info("No SL: breach after", String.format(Locale.US, "%.2f%% against you", move), if (move < 1) C.down else C.accent)
                        Info("Breach price (this trade alone)", fmtPrice(px), C.down)
                    }
                }
            }
            // Hard rule: no new exposure in the night window. Reduce-only orders still go through.
            val lockedNow = repo.prefs.locked() && !reduceOnly
            if (lockedNow) Text(
                repo.prefs.lockMessage(),
                color = C.down, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp),
            )


            Column(Modifier.padding(vertical = 10.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.bg).padding(10.dp)) {
                Info(if (scaled) "Average price" else "Estimated price", fmtPrice(if (scaled) refPx else if (limit) limitPx else quote?.estimatedFillPrice ?: refPx))
                if (ladderCrosses) Info("Warning", "Some rungs cross the book and fill at market", C.down)
                if (!limit) Info("Max slippage", String.format(Locale.US, "%.2f%%", repo.prefs.slippageBps / 100.0))
                Info("Notional", fmtUsd(usd))
                Info("Margin", fmtUsd(usd / lev))
                quote?.estimatedFee?.let { Info("Est. fee", fmtUsd(it)) }
                quote?.takeIf { it.fillable == false }?.let { Info("Quote", it.unfillableReason ?: "unfillable", C.down) }
            }
        }
        // Always reachable: the send button sits under the form, not at the end of it.
        val lockedSend = repo.prefs.locked() && !reduceOnly
        Column(Modifier.fillMaxWidth().background(C.panel).padding(horizontal = 14.dp, vertical = 8.dp)) {
            SolidBtn(
                if (busy) "Sending…" else if (lockedSend) "Locked until ${repo.prefs.lockUntil()} Oslo" else "$sideWord ${market.symbol}" + if (usd > 0) "  ${fmtUsd(usd)}" else "",
                sideCol, Modifier.fillMaxWidth(), enabled = problem == null && !busy && !lockedSend,
            ) { if (repo.prefs.confirmOrders) confirm = true else submit() }
            problem?.let { Text(it, color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp)) }
        }
    }

    if (confirm) ConfirmDialog(
        "$sideWord ${market.symbol}", fmtUsd(usd), sideCol,
        listOfNotNull(
            ConfirmRow("Type", when {
                stop -> (if (stopLimit) "Stop limit ${fmtPrice(limitPx?.let(::snapPx), market)}" else "Stop market") + " · trigger ${fmtPrice(triggerPx?.let(::snapPx), market)}"
                chase -> "Chase" + (if (chasePct > 0) String.format(Locale.US, " %s%.2f%%", if (buy) "−" else "+", chasePct) else " at touch") + (capPx?.let { " · cap ${fmtPrice(snapPx(it), market)}" } ?: "")
                scaled -> "Scaled ${fmtPrice(startPx?.let(::snapPx), market)} → ${fmtPrice(endPx?.let(::snapPx), market)} · $rungs rungs"
                limit -> "Limit @ ${fmtPrice(limitPx?.let(::snapPx), market)}" + if (postOnly) " · post only" else ""
                else -> "Market"
            }),
            if (!limit && !stop && !chase && !scaled) ConfirmRow("Size", "${fmtSize(size)} ${market.symbol}") else null,
            if (!limit && !stop && !chase && !scaled) quote?.estimatedFillPrice?.let { ConfirmRow("Est. fill", fmtPrice(it, market)) } else null,
            if (!limit && !stop && !chase && !scaled) ConfirmRow("Max slippage", String.format(Locale.US, "%.2f%%", repo.prefs.slippageBps / 100.0)) else null,
            ConfirmRow("Leverage", "${lev}x ${if (isolated) "isolated" else "cross"}"),
            ConfirmRow("Account", acc?.let(::accountName) ?: ""),
            if (reduceOnly) ConfirmRow("Reduce only", "yes") else null,
            tp?.let { ConfirmRow("Take profit", fmtPrice(snapPx(it), market), C.up) },
            sl?.let { ConfirmRow("Stop loss", fmtPrice(snapPx(it), market), C.down) },
        ),
        confirm = sideWord, onDismiss = { confirm = false },
    ) { confirm = false; submit() }

    closeAsk?.let { f ->
        val h = held
        if (h == null) closeAsk = null
        else ConfirmDialog(
            "Market close", "Close ${(f * 100).toInt()}% ${h.symbol}", C.down,
            listOf(ConfirmRow("Value", fmtUsd(h.size * f * (mark ?: h.entryPrice)))), confirm = "Close", onDismiss = { closeAsk = null },
        ) {
            closeAsk = null; busy = true
            scope.launch { val r = repo.closePosition(h, f); busy = false; if (r is TradeResult.Ok && f == 1.0) onDismiss() }
        }
    }
}

/** Plain decimal text without grouping, for editable fields. */
fun plain(v: Double): String = java.math.BigDecimal.valueOf(v).let {
    // About six significant digits, like prices on the chart.
    if (it.scale() > priceDecimals(v)) it.setScale(priceDecimals(v), java.math.RoundingMode.HALF_UP) else it
}.stripTrailingZeros().toPlainString()

@Composable
fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    AppInput(label, value, onChange, modifier, numeric = true)
}

@Composable
fun Seg(label: String, on: Boolean, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label, fontSize = 12.sp, color = if (on) color else C.muted,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (on) color.copy(alpha = 0.14f) else C.panel2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun SideTab(label: String, on: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(if (on) color else Color.Transparent).clickable(onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) Color.White else C.muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
}

@Composable
fun QuickBtn(label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f)).clickable(onClick = onClick).padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Check(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    AppCheck(label, on, onChange)
}

@Composable
fun Info(label: String, value: String, color: Color = C.text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = C.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, style = num.copy(fontSize = 12.sp, color = color))
    }
}

@Composable
private fun Field(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.height(46.dp).clip(RoundedCornerShape(10.dp)).background(C.bg).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, content = content,
    )
}

@Composable
private fun UnderTab(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = if (on) C.text else C.muted, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp))
        Box(Modifier.width(40.dp).height(2.dp).background(if (on) C.text else Color.Transparent))
    }
}

/** Slim 0–100 % slider with tick stops at each quarter; tap or drag. */
@Composable
fun PctSlider(value: Float, color: Color, modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    val snap = { f: Float -> listOf(0f, 0.25f, 0.5f, 0.75f, 1f).firstOrNull { kotlin.math.abs(it - f) < 0.03f } ?: f }
    // Gestures are set up once; read the latest callback (it captures buying power).
    val cb by androidx.compose.runtime.rememberUpdatedState(onChange)
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(28.dp)
                .pointerInput(Unit) { detectTapGestures { cb(snap((it.x / size.width).coerceIn(0f, 1f))) } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ -> change.consume(); cb(snap((change.position.x / size.width).coerceIn(0f, 1f))) }
                },
        ) {
            val r = 9.dp.toPx()
            val y = size.height / 2
            val w = size.width - r * 2
            val x = r + w * value
            drawLine(C.line, Offset(r, y), Offset(r + w, y), 4.dp.toPx(), cap = StrokeCap.Round)
            drawLine(color, Offset(r, y), Offset(x, y), 4.dp.toPx(), cap = StrokeCap.Round)
            for (i in 0..4) {
                val tx = r + w * i / 4
                drawCircle(if (tx <= x) color else C.muted, 3.dp.toPx(), Offset(tx, y))
            }
            drawCircle(Color.White, r, Offset(x, y))
            drawCircle(color, r - 3.dp.toPx(), Offset(x, y))
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("0%", "25%", "50%", "75%", "100%").forEachIndexed { i, t ->
                Text(t, color = C.muted, fontSize = 10.sp, modifier = Modifier.weight(1f).clickable { onChange(i / 4f) },
                    textAlign = when (i) { 0 -> TextAlign.Start; 4 -> TextAlign.End; else -> TextAlign.Center })
            }
        }
    }
}

/**
 * Panel that slides in from the right over the screen it's opened from. Drawn in place,
 * so the bottom bar stays visible and usable below it.
 */
@Composable
fun SidePanel(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) = PanelBody(onDismiss, content)

@Composable
private fun PanelBody(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val shown = remember { androidx.compose.animation.core.MutableTransitionState(false) }.apply { targetState = true }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        androidx.compose.animation.AnimatedVisibility(
            shown, Modifier.align(Alignment.CenterEnd),
            enter = androidx.compose.animation.slideInHorizontally { it },
        ) {
            Column(
                Modifier.fillMaxHeight().fillMaxWidth(0.96f).clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)).background(C.panel)
                    .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
                    .imePadding(),
                content = content,
            )
        }
    }
}
