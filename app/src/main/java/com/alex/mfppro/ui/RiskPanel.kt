package com.alex.mfppro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alex.mfppro.data.AccountState
import com.alex.mfppro.data.OSLO
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.RiskView
import com.alex.mfppro.data.riskOf
import kotlinx.coroutines.delay
import java.time.ZonedDateTime
import java.util.Locale

/** The account picked for trading, falling back to the first active one. */
@Composable
fun rememberTradeAccount(repo: Repo): AccountState? {
    val accounts by repo.account.accounts.collectAsStateWithLifecycle()
    val chosen by repo.tradeAccount.collectAsStateWithLifecycle()
    val active = accounts.values.filter { it.account.status == "active" }.sortedBy { it.account.name }
    return active.firstOrNull { it.account.id == chosen } ?: active.firstOrNull()
}

/** Whose positions, orders and chart lines the Trade tab shows: the trading account, or every active one. */
object TradeScope {
    var all by mutableStateOf(false)
}

private val ROOM_FULL get() = C.roomFull
private val TARGET_C get() = C.target

/**
 * Room left under MFP's exposure caps for a new order in [marketId]: the smaller of
 * the account cap minus gross notional and the per-position cap minus what's held there.
 */
fun exposureLeft(policy: com.alex.mfppro.data.TradingPolicy?, s: AccountState, marks: Map<String, Double>, marketId: String?): Double? {
    val gross = s.positions.values.sumOf { it.size * (marks[it.feedKey] ?: it.entryPrice) }
    val acct = policy?.maximumTotalNotionalUsd?.let { it - gross }
    val inMarket = s.positions.values.filter { it.marketId == marketId }.sumOf { it.size * (marks[it.feedKey] ?: it.entryPrice) }
    val pos = policy?.limits?.maxPositionValueUsd?.let { it - inMarket }
    return listOfNotNull(acct, pos).minOrNull()?.coerceAtLeast(0.0)
}

fun accountName(s: AccountState) = com.alex.mfppro.data.Nicknames.map[s.account.id] ?: s.account.name.ifBlank { "#" + s.account.accountNumber }

/** Trading account line plus live distance to each rule: max DD, daily loss and target. */
@Composable
fun RiskPanel(repo: Repo, modifier: Modifier = Modifier, showExposure: Boolean = false) {
    val marks by repo.market.marks.collectAsStateWithLifecycle()
    val acc = rememberTradeAccount(repo) ?: return
    val r = riskOf(acc, marks)
    val start = acc.account.startingBalance
    // One line (account, equity, room left) with a Show toggle; the rule lines fold out below it.
    var pick by androidx.compose.runtime.remember { mutableStateOf(false) }
    var open by androidx.compose.runtime.remember { mutableStateOf(repo.prefs.accountOpen) }
    // Tap anywhere on the card to switch account.
    Column(modifier.padding(horizontal = 8.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.panel).clickable { pick = true }.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccountSwitcher(repo, Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("Room left", color = C.muted, fontSize = 10.sp)
                Text(fmtCompactUsd(r.room), style = num.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = roomColor(r.roomFraction)))
            }
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).background(C.panel2).clickable { open = !open; repo.prefs.accountOpen = open }.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (open) "Hide" else "Show", color = C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(4.dp))
                Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = C.text, modifier = Modifier.size(18.dp))
            }
        }
        androidx.compose.animation.AnimatedVisibility(open, enter = androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.shrinkVertically()) {
        Column {
        val loaded = acc.account.risk != null
        // Room bars run light orange (all left) to red (none left).
        // DLL and MLL at one level: one line for the loss limit, not the same number twice.
        if (r.floorsSame) {
            val full = (if (r.bindingIsDaily) r.dailyAllowance else r.ddAllowance)?.takeIf { it > 0 }
            val f = r.roomFraction
            RuleLine("Loss limit left (LL)", r.room, f, androidx.compose.ui.graphics.lerp(C.down, ROOM_FULL, (f ?: 1.0).toFloat()), of = full)
        } else run {
            val f = r.ddAllowance?.takeIf { it > 0 }?.let { a -> r.ddRoom?.let { (it / a).coerceIn(0.0, 1.0) } }
            RuleLine("Max DD left", r.ddRoom, f, androidx.compose.ui.graphics.lerp(C.down, ROOM_FULL, (f ?: 1.0).toFloat()),
                empty = if (loaded && r.ddRoom == null && (r.ddAllowance ?: 0.0) <= 0) "off" else "—", of = r.ddAllowance?.takeIf { it > 0 })
        }
        // No daily loss rule on this account: nothing to show.
        if (!r.floorsSame && (!loaded || r.dailyFloor != null || (r.dailyAllowance ?: 0.0) > 0)) {
            val f = r.dailyAllowance?.takeIf { it > 0 }?.let { a -> r.dailyRoom?.let { (it / a).coerceIn(0.0, 1.0) } }
            RuleLine("Daily loss left", r.dailyRoom, f, androidx.compose.ui.graphics.lerp(C.down, ROOM_FULL, (f ?: 1.0).toFloat()),
                empty = if (loaded && r.dailyRoom == null && (r.dailyAllowance ?: 0.0) <= 0) "off" else "—", of = r.dailyAllowance?.takeIf { it > 0 })
        }
        r.target?.let { it - start }?.takeIf { it > 0 }?.let { goal ->
            RuleLine("To target", r.toTarget, ((r.equity - start) / goal).coerceIn(0.0, 1.0), TARGET_C, of = goal)
        }
        // Margin in use against balance.
        val margin = acc.positions.values.sumOf { it.marginAt() }
        val bal = acc.account.balance
        if (margin > 0 && bal > 0) {
            val used = (margin / bal).coerceIn(0.0, 1.0)
            RuleLine("Margin used", margin, used, if (used > 0.9) C.down else C.muted, of = bal)
        }
        val policy by repo.policies.collectAsStateWithLifecycle()
        // Only in the position view: hitting the cap just makes an order fail.
        if (showExposure) policy[acc.account.id]?.maximumTotalNotionalUsd?.takeIf { it > 0 }?.let { cap ->
            val gross = acc.positions.values.sumOf { it.size * (marks[it.feedKey] ?: it.entryPrice) }
            val used = (gross / cap).coerceIn(0.0, 1.0)
            RuleLine("Exposure", gross, used, if (used > 0.9) C.down else C.muted, of = cap)
            policy[acc.account.id]?.limits?.maxPositionValueUsd?.takeIf { it < cap }?.let {
                Text("Max per position " + fmtCompactUsd(it), style = num.copy(fontSize = 10.sp, color = C.muted), modifier = Modifier.padding(top = 2.dp))
            }
        }
        }
        }
        // Warnings stay visible when folded.
        RiskWarning(r)
        LockBanner(repo)
    }
    if (pick) AccountPickerSheet(repo) { pick = false }
}

/** One rule as a full-width line: label, bar of how much is left (or used), dollars and the allowance. */
@Composable
private fun RuleLine(label: String, usd: Double?, fill: Double?, color: androidx.compose.ui.graphics.Color, empty: String = "—", of: Double? = null) {
    Row(Modifier.padding(top = 5.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.muted, fontSize = 11.sp, modifier = Modifier.width(92.dp))
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.line)) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth((fill ?: 0.0).toFloat()).height(4.dp).background(color))
        }
        Text(usd?.let(::fmtCompactUsd) ?: empty, style = num.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (usd == null) C.muted else C.text),
            textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1, modifier = Modifier.width(72.dp))
        Text(of?.let { " / " + fmtCompactUsd(it) } ?: "", style = num.copy(fontSize = 10.sp, color = C.muted), maxLines = 1, modifier = Modifier.width(62.dp))
    }
}

private fun fmtCompactUsd(v: Double?) = if (v == null) "—" else if (kotlin.math.abs(v) >= 10_000) (if (v < 0) "-$" else "$") + fmtCompact(kotlin.math.abs(v)) else fmtUsdWhole(v)

/** Account pill like MFP's switcher: tap to pick from the active accounts. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitcher(repo: Repo, modifier: Modifier = Modifier) {
    val accounts by repo.account.accounts.collectAsStateWithLifecycle()
    val marks by repo.market.marks.collectAsStateWithLifecycle()
    val acc = rememberTradeAccount(repo) ?: return
    var open by androidx.compose.runtime.remember { mutableStateOf(false) }
    Column(modifier.clip(RoundedCornerShape(6.dp)).clickable { open = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(accountName(acc), color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.UnfoldMore, null, tint = C.muted, modifier = Modifier.size(12.dp))
            if (TradeScope.all) Text("+ all", color = C.accent, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(4.dp)).background(C.accent.copy(alpha = 0.14f)).padding(horizontal = 4.dp))
        }
        Text(fmtUsd(riskOf(acc, marks).equity), style = num.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = C.text))
    }
    if (open) AccountPickerSheet(repo) { open = false }
}

/** Bottom sheet listing the active accounts; picking one makes it the trading account. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AccountPickerSheet(repo: Repo, onDismiss: () -> Unit) {
    val accounts by repo.account.accounts.collectAsStateWithLifecycle()
    val marks by repo.market.marks.collectAsStateWithLifecycle()
    val current = rememberTradeAccount(repo)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.panel) {
        Text("Switch account", color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp))
        // What the Trade tab lists and draws; orders still go to the account picked below.
        Row(
            Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.panel2)
                .clickable { TradeScope.all = !TradeScope.all; repo.prefs.tradeShowAll = TradeScope.all }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Show all accounts", color = C.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("Positions, orders and chart lines from every active account", color = C.muted, fontSize = 11.sp)
            }
            AppSwitch(TradeScope.all, { TradeScope.all = it; repo.prefs.tradeShowAll = it })
        }
        com.alex.mfppro.ui.activeAccounts(accounts, includeComp = true).forEach { s ->
            val r = riskOf(s, marks)
            val on = s.account.id == current?.account?.id
            Row(
                Modifier.fillMaxWidth().background(if (on) C.panel2 else C.panel)
                    .clickable { repo.setTradeAccount(s.account.id); onDismiss() }.padding(16.dp, 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(accountName(s), color = C.text, fontWeight = FontWeight.Medium)
                    Text("${s.account.stage} · ${s.positions.size} open", color = C.muted, fontSize = 11.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(fmtUsd(r.equity), style = num.copy(fontWeight = FontWeight.SemiBold))
                    // The floor a loss hits first: LL when DLL and MLL are one, else whichever binds.
                    Text("${r.bindingLabel} left " + fmtUsd(r.room), style = num.copy(fontSize = 11.sp, color = roomColor(r.roomFraction)))
                }
                if (on) Text("  ✓", color = C.accent)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun SolidBtn(
    label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier, enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null, small: Boolean = false, onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier.height(if (small) 36.dp else 44.dp).clip(RoundedCornerShape(10.dp))
            .background(if (enabled) color else color.copy(alpha = 0.3f))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            androidx.compose.material3.Icon(it, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(if (small) 16.dp else 20.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
        }
        Text(label, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold, fontSize = if (small) 13.sp else 15.sp)
    }
}

fun roomColor(f: Double?) = when {
    f == null -> C.text
    f < 0.25 -> C.down
    f < 0.5 -> C.accent
    else -> C.up
}

@Composable
fun RiskWarning(risk: RiskView) {
    val f = risk.roomFraction ?: return
    if (f >= 0.5) return
    val which = if (risk.bindingIsDaily) "daily loss" else "max drawdown"
    Text(
        if (f < 0.25) "Under 25% of $which left" else "Half of $which used",
        color = if (f < 0.25) C.down else C.accent, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
fun LockBanner(repo: Repo) {
    var locked by androidx.compose.runtime.remember { mutableStateOf(repo.prefs.locked()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); locked = repo.prefs.locked() } }
    if (!locked) return
    Text(
        repo.prefs.lockMessage(),
        color = C.down, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp),
    )
}

