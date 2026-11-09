package com.alex.mfppro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alex.mfppro.data.AccountState
import com.alex.mfppro.data.Conn
import com.alex.mfppro.data.Position
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.pctToFraction
import com.alex.mfppro.data.breachPrice
import com.alex.mfppro.data.riskOf
import kotlin.math.abs

/** Failed, passed and closed accounts can't trade, so they stay out of the app. */
fun activeAccounts(m: Map<String, AccountState>, includeComp: Boolean = false) =
    m.values.filter { it.account.status == "active" && (includeComp || !Comp.hide || !isComp(it)) }.sortedBy { it.account.name }

/** Competitions have no drawdown, daily loss or target rules. */
fun isComp(s: AccountState): Boolean {
    if (com.alex.mfppro.data.isCompetition(s.account)) return true
    val req = s.account.risk?.requirements ?: return false
    return (req.dailyLossPct ?: 0.0) <= 0 && (req.maxDrawdownPct ?: 0.0) <= 0 && (req.profitTargetPct ?: 0.0) <= 0
}

@Composable
private fun KeyNotices(repo: Repo, onOpenSettings: () -> Unit): Boolean {
    val key by repo.apiKey.collectAsStateWithLifecycle()
    val conn by repo.account.conn.collectAsStateWithLifecycle()
    if (key == null) {
        Panel(Modifier.clickable(onClick = onOpenSettings)) { Text("Add your API key in Settings.", color = C.text) }
        return false
    }
    if (conn == Conn.AuthFailed) Panel { Text("The API key was rejected. Check it in Settings.", color = C.down) }
    return true
}

/** The one screen header: title, the Live badge (tap to refresh) and the settings gear. */
@Composable
fun ScreenHeader(repo: Repo, title: String, onSettings: (() -> Unit)? = null, logo: Boolean = false) {
    val conn by repo.account.conn.collectAsStateWithLifecycle()
    val rtt by repo.account.rtt.collectAsStateWithLifecycle()
    val upstream by repo.account.upstream.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxWidth()) {
        // Brand mark centred over the header row.
        if (logo) BrandMark(Modifier.align(Alignment.Center))
    Row(Modifier.fillMaxWidth().padding(14.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = C.text)
        Spacer(Modifier.weight(1f))
        // Tap the badge to pull everything fresh.
        val scope = rememberCoroutineScope()
        Box(Modifier.clip(RoundedCornerShape(50)).clickable { scope.launch { repo.refreshAndWait() } }.padding(horizontal = 6.dp, vertical = 4.dp)) { ConnBadge(conn, rtt, upstream) }
        onSettings?.let {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Settings, "Settings", tint = C.muted,
                modifier = Modifier.padding(start = 10.dp).clip(CircleShape).clickable(onClick = it).padding(6.dp).size(20.dp))
        }
    }
    }
}

/**
 * Position management first: every position of the chosen accounts with its
 * actions always visible, grouped under its own account because each account
 * has its own rules and room.
 */
@Composable
fun PositionsScreen(repo: Repo, onOpenMarket: (String) -> Unit, onOpenSettings: () -> Unit) {
    val accounts by repo.account.accounts.collectAsStateWithLifecycle()
    val marks by repo.market.marks.collectAsStateWithLifecycle()
    val venue by repo.market.venueMarks.collectThrottled(500)
    val tradeAcc = rememberTradeAccount(repo)
    var dashAll by remember { mutableStateOf(repo.prefs.dashAll) }
    var closeAllFor by remember { mutableStateOf<AccountState?>(null) }
    var nukeAsk by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    val scope = rememberCoroutineScope()

    val all = activeAccounts(accounts)
    // The account picked in the card, or every active account.
    val shown = if (dashAll || tradeAcc == null) all else listOf(tradeAcc)

    RefreshBox(repo) {
    LazyColumn(Modifier.fillMaxSize().background(C.bg), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        item { ScreenHeader(repo, "", onOpenSettings, logo = true) }
        item { if (!KeyNotices(repo, onOpenSettings)) return@item }
        tradeAcc?.let { acc -> item { AccountHero(repo, acc, marks) } }
        item {
            val openPnl = shown.sumOf { riskOf(it, marks).upnl }
            val count = shown.sumOf { it.positions.size }
            val upnl = net(openPnl) ?: 0.0
            GlowCard(pnlColor(openPnl), Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                    Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.CenterHorizontally) {
                        BigNum(fmtUsd(kotlin.math.abs(upnl)).removePrefix("$"), pnlColor(openPnl), prefix = (if (upnl < 0) "-" else "+") + "$")
                        Text("Open PnL" + splitTag(), color = pnlColor(openPnl), fontSize = 12.sp)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        BigNum("$count", C.text)
                        Text(if (count == 1) "Position" else "Positions", color = C.muted, fontSize = 12.sp)
                    }
                }
            }
        }
        val total = shown.sumOf { it.positions.size }
        if (total > 0) item {
            // The nuke: hold to market-close every position on every shown account.
            HoldButton("Hold to close ALL $total position${if (total > 1) "s" else ""}", C.down, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                val ids = shown.filter { it.positions.isNotEmpty() }.map { it.account.id }
                if (repo.prefs.confirmCloseAll) nukeAsk = ids to total else scope.launch { repo.closeAll(ids) }
            }
        }
        if (all.size > 1) item {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Positions", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                MiniSwitch("Account", "All", !dashAll) { dashAll = !it; repo.prefs.dashAll = dashAll }
            }
        }
        for (s in shown) {
            val positions = s.positions.values.byFocus(repo.prefs.lastMarketId, marks)
            if (dashAll) item(key = "h_" + s.account.id) {
                AccountStrip(s, marks) {
                    // A small button next to the account name: always asks, unlike the hold-to-close one above.
                    closeAllFor = s
                }
            }
            if (positions.isEmpty()) item(key = "e_" + s.account.id) {
                Text("No open positions", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp, 4.dp, 16.dp, 10.dp))
            }
            items(positions, key = { it.id }) { p ->
                Column(Modifier.padding(horizontal = 8.dp, vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(C.panel)) {
                    val m = marks[p.feedKey]
                    val br = m?.let { mk -> riskOf(s, marks).room?.let { breachPrice(p, mk, it) } }
                    PositionRow(p, m, "", showAccount = false, breach = br, venueMark = venue[p.feedKey], tp = repo.exitPrice(p, "tp"), sl = repo.exitPrice(p, "sl"), market = repo.market(p.marketId)) { repo.setTradeAccount(p.accountId); onOpenMarket(p.marketId) }
                    PositionActions(repo, p, marks[p.feedKey])
                }
            }
            val orders = s.orders.values.sortedBy { it.symbol }
            if (orders.isNotEmpty()) {
                item(key = "oh_" + s.account.id) { Text("Open orders (${orders.size})", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp)) }
                items(orders, key = { "o_" + it.id }) { o -> OrderRow(repo, o, "") }
            }
        }
    }
    }

    nukeAsk?.let { (ids, n) ->
        ConfirmDialog("Close everything", "Close ALL $n positions", C.down,
            listOf(ConfirmRow("Accounts", "${ids.size}")), note = "Market close on every shown account.", confirm = "Close all",
            onDismiss = { nukeAsk = null }) { nukeAsk = null; scope.launch { repo.closeAll(ids) } }
    }

    closeAllFor?.let { acc ->
        ConfirmDialog("Close all", accountName(acc), C.down,
            listOf(ConfirmRow("Positions", "${acc.positions.size}"), ConfirmRow("Open PnL", fmtUsd(riskOf(acc, marks).upnl, true), pnlColor(riskOf(acc, marks).upnl))),
            confirm = "Close all", onDismiss = { closeAllFor = null }) { closeAllFor = null; scope.launch { repo.closeAll(listOf(acc.account.id)) } }
    }
}

/** One-line account context above its positions: equity, today, room to breach, close all. */
@Composable
private fun AccountStrip(s: AccountState, marks: Map<String, Double>, onCloseAll: () -> Unit) {
    val r = riskOf(s, marks)
    val f = r.roomFraction
    val roomCol = when { f == null -> C.text; f < 0.25 -> C.down; f < 0.5 -> C.accent; else -> C.up }
    Column(Modifier.padding(12.dp, 6.dp, 12.dp, 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(accountName(s), color = C.text, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Tag(s.account.stage.ifBlank { "account" }, C.muted)
            if (s.lockedOut) { Spacer(Modifier.width(4.dp)); Tag("locked", C.down) }
            Spacer(Modifier.weight(1f))
            if (s.positions.isNotEmpty()) Act("Close all", color = C.down, modifier = Modifier.padding(start = 6.dp), onClick = onCloseAll)
        }
        Row(Modifier.padding(top = 4.dp)) {
            Cell("Equity", fmtUsd(r.equity), Modifier.weight(1.2f))
            Cell("Open PnL" + splitTag(), fmtUsd(net(r.upnl), true), Modifier.weight(1f), pnlColor(r.upnl))
            if (r.todayPnl != null) Cell("Today", fmtUsd(net(r.todayPnl), true), Modifier.weight(1f), pnlColor(r.todayPnl))
            Cell("Room", fmtUsd(r.room), Modifier.weight(1f), roomCol)
        }
    }
}

/** [breach] = price where this position takes its account onto the nearest floor. */
@Composable
fun PositionRow(p: Position, mark: Double?, account: String, showAccount: Boolean, breach: Double? = null, venueMark: Double? = null, tp: Double? = null, sl: Double? = null, market: com.alex.mfppro.data.Market? = null, onClick: () -> Unit) {
    val pnl = net(mark?.let(p::pnlAt))
    val roe = pnl?.let { it / p.marginAt() * 100 }
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The market symbol, as the market list uses for its logos; the venue's coin id doesn't always match.
            CoinLogo(p.symbol.uppercase().removeSuffix("USDT").removeSuffix("-PERP").ifBlank { p.coin }, p.provider, 20.dp)
            Spacer(Modifier.width(6.dp))
            Text(ticker(p.symbol, p.provider), color = C.text, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Tag("${if (p.isLong) "Long" else "Short"} ${p.leverage.toInt()}x", if (p.isLong) C.up else C.down)
            Spacer(Modifier.width(6.dp))
            VenueTag(p.provider, 16.dp)
            if (showAccount) { Spacer(Modifier.width(6.dp)); Text(account, color = C.muted, fontSize = 11.sp) }
            Spacer(Modifier.weight(1f))
            // Press and hold the PnL for the whole-trade breakdown (fees, swap, banked PnL, breakeven).
            var peek by remember { mutableStateOf(false) }
            val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            Column(
                Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { tryAwaitRelease(); peek = false },
                        onLongPress = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); peek = true },
                        onTap = { onClick() },
                    )
                },
                horizontalAlignment = Alignment.End,
            ) {
                Text(fmtUsd(pnl, signed = true), style = num.copy(color = pnlColor(pnl), fontWeight = FontWeight.Medium))
                Text(fmtPct(roe), style = num.copy(fontSize = 11.sp, color = pnlColor(pnl)))
                if (peek && mark != null) PnlBreakdown(p, mark)
            }
        }
        Row(Modifier.padding(top = 4.dp)) {
            Cell("Value", "$" + fmtCompact(p.size * (mark ?: p.entryPrice)), Modifier.weight(1f))
            Cell("Entry", fmtPrice(p.entryPrice, market), Modifier.weight(1f))
            Cell("Last", fmtPrice(mark, market), Modifier.weight(1f))
            // Mark is the venue's price, so its logo sits beside it.
            Column(Modifier.weight(1f)) {
                Text("Mark", color = C.muted, fontSize = 10.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtPrice(venueMark, market), style = num.copy(fontSize = 12.sp, color = C.muted))
                }
            }
        }
        // Second row lines up with the first: breach price and move (shorts +, longs -), then TP and SL.
        val away = if (breach != null && mark != null && mark > 0) (breach / mark - 1) * 100 else null
        Row(Modifier.padding(top = 6.dp)) {
            Cell("Breach", fmtPrice(breach, market), Modifier.weight(1f), C.down)
            Cell("To breach", fmtPct(away), Modifier.weight(1f), C.down)
            Cell("TP", fmtPrice(tp, market), Modifier.weight(1f), if (tp != null) C.up else C.muted)
            Cell("SL", fmtPrice(sl, market), Modifier.weight(1f), if (sl != null) C.down else C.muted)
        }
    }
}

/** Whole-trade card shown while the PnL is held, like MFP's tooltip: what the trade nets after costs. */
@Composable
fun PnlBreakdown(p: com.alex.mfppro.data.Position, mark: Double) {
    val total = p.netAt(mark)
    androidx.compose.ui.window.Popup(alignment = Alignment.TopEnd, offset = androidx.compose.ui.unit.IntOffset(0, -20)) {
        Column(
            Modifier.width(250.dp).clip(RoundedCornerShape(10.dp)).background(C.panel2).border(1.dp, C.line, RoundedCornerShape(10.dp)).padding(12.dp),
        ) {
            @Composable
            fun line(label: String, v: String, color: Color = C.text, bold: Boolean = false) = Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(label, color = if (bold) C.text else C.muted, fontSize = 12.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                Text(v, style = num.copy(fontSize = 12.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, color = color))
            }
            line("Total trade", fmtUsd(total, true), pnlColor(total), bold = true)
            line("Unrealized PnL", fmtUsd(p.pnlAt(mark), true), pnlColor(p.pnlAt(mark)))
            line("Gross realized PnL", fmtUsd(p.realizedPnl, true), pnlColor(p.realizedPnl))
            line("Fees (paid)", fmtUsd(-p.fees, true), if (p.fees > 0) C.down else C.text)
            line("Swap fees", fmtUsd(p.funding, true), pnlColor(p.funding))
            line("Breakeven price", fmtPrice(p.breakeven))
            if (Split.pct < 100) line("Your share (${Split.pct}%)", fmtUsd(net(total), true), pnlColor(total))
            Text(if (total >= 0) "Net positive after costs." else "Net PnL turns positive once the mark passes breakeven.",
                color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * Pull down to refresh: a fresh account snapshot, market socket and REST data,
 * without restarting the app.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RefreshBox(repo: Repo, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { refreshing = true; scope.launch { repo.refreshAndWait(); refreshing = false } },
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
}

@Composable
fun ConnBadge(conn: Conn, rtt: Long? = null, upstream: Boolean = true) {
    val (col, txt) = when (conn) {
        Conn.Live -> if (!upstream) C.accent to "Delayed (MFP)" else C.up to ("Live" + (rtt?.let { " · ${it}ms" } ?: ""))
        Conn.Connecting, Conn.Reconnecting -> C.accent to "Syncing"
        Conn.AuthFailed -> C.down to "Auth failed"
        Conn.Off -> C.muted to "Offline"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(col))
        Spacer(Modifier.width(6.dp))
        Text(txt, color = col, fontSize = 12.sp)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.padding(horizontal = 10.dp, vertical = 3.dp).fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)).background(C.panel).padding(horizontal = 12.dp, vertical = 10.dp)
    ) { content() }
}

@Composable
fun Cell(label: String, value: String, modifier: Modifier = Modifier, color: Color = C.text) {
    Column(modifier) {
        Text(label, color = C.muted, fontSize = 10.sp)
        Text(value, style = num.copy(fontSize = 12.sp, color = color))
    }
}

@Composable
fun Tag(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 10.sp,
        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Press and hold to fire; releasing early cancels. For irreversible bulk actions. */
@Composable
fun HoldButton(label: String, color: Color, modifier: Modifier = Modifier, holdMs: Long = 1200, onFire: () -> Unit) {
    val progress = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // The gesture is set up once; always fire the latest callback (current accounts and count).
    val fire by androidx.compose.runtime.rememberUpdatedState(onFire)
    Box(
        modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.18f))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, androidx.compose.animation.core.tween(holdMs.toInt(), easing = androidx.compose.animation.core.LinearEasing))
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        fire()
                    }
                    tryAwaitRelease()
                    job.cancel()
                    scope.launch { progress.animateTo(0f) }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(progress.value).fillMaxHeight().background(color.copy(alpha = 0.7f)))
        Text(label, color = if (progress.value > 0.5f) onColor(color) else C.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

/** Price of a position's resting TP or SL exit, if it has one. */
fun Repo.exitPrice(p: Position, group: String) = exitsOf(p).firstOrNull { it.exitGroup == group }?.exitPrice

/** The market on the chart first, then the rest by position value, largest first. */
fun Collection<Position>.byFocus(marketId: String?, marks: Map<String, Double>) =
    sortedWith(compareByDescending<Position> { it.marketId == marketId }.thenByDescending { it.size * (marks[it.feedKey] ?: it.entryPrice) })

/**
 * MFP-style account card: name, stage and a picker chevron, then equity and
 * each rule's remaining room as "left / allowance" with a bar.
 */
@Composable
private fun AccountHero(repo: Repo, acc: AccountState, marks: Map<String, Double>) {
    var open by remember { mutableStateOf(false) }
    val r = riskOf(acc, marks)
    val start = acc.account.startingBalance
    val shape = RoundedCornerShape(16.dp)
    Column(
        // The whole card opens the account picker.
        Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth().clip(shape).background(C.panel)
            .border(1.dp, C.line, shape).clickable { open = true }.padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(accountName(acc), color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(acc.account.stage.replaceFirstChar { it.uppercase() } + " · $" + fmtCompact(acc.account.startingBalance), color = C.muted, fontSize = 11.sp)
            }
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown, "Switch account", tint = C.muted, modifier = Modifier.size(22.dp))
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Equity", color = C.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(fmtUsd(r.equity), style = num.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold, color = C.text))
        }
        if ((r.ddAllowance ?: 0.0) > 0) RuleLine("Max Drawdown Remaining", r.ddRoom, r.ddAllowance, floor = r.ddFloor)
        if ((r.dailyAllowance ?: 0.0) > 0) RuleLine("Daily Loss Remaining", r.dailyRoom, r.dailyAllowance, floor = r.dailyFloor)
        val goal = r.target?.let { it - start }?.takeIf { it > 0 }
        if (goal != null) RuleLine("Profit Target Remaining", r.toTarget, goal, target = true)
    }
    if (open) AccountPickerSheet(repo) { open = false }
}

/** "left / of" with a bar: room left fades light orange to red; a target fills up in cyan. */
@Composable
private fun RuleLine(label: String, left: Double?, of: Double?, target: Boolean = false, floor: Double? = null) {
    val f = if (left != null && of != null && of > 0) (left / of).coerceIn(0.0, 1.0) else null
    val col = if (target) C.target else androidx.compose.ui.graphics.lerp(C.down, C.roomFull, (f ?: 1.0).toFloat())
    val fill = if (target) 1.0 - (f ?: 1.0) else (f ?: 0.0)
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(fmtUsd(left), style = num.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = col))
        Text(if (floor != null) " · floor " + fmtUsdWhole(floor) else " / " + fmtUsd(of), style = num.copy(fontSize = 13.sp, color = C.muted))
    }
    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.line)) {
        Box(Modifier.fillMaxWidth(fill.toFloat()).height(5.dp).background(col))
    }
}
