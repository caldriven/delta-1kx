package com.alex.mfppro.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alex.mfppro.data.AccountState
import com.alex.mfppro.data.Conn
import com.alex.mfppro.data.Nicknames
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.SANDBOX_REST
import com.alex.mfppro.data.restBase
import com.alex.mfppro.data.riskOf
import com.alex.mfppro.ui.TERMS_VERSION
import com.alex.mfppro.ui.fmtUsd
import kotlinx.coroutines.delay
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

fun accountName(s: AccountState) = Nicknames.map[s.account.id] ?: s.account.name.ifBlank { "#" + s.account.accountNumber }

/**
 * The terminal: watchlist | accounts, chart and blotter | ticket, book and tape.
 * Every divider can be dragged; widths are remembered in layout.json.
 */
@Composable
fun Workspace(repo: Repo, layout: JsonFileStore, keyPrompt: Boolean = true) {
    val key by repo.apiKey.collectAsState()
    val markets by repo.markets.collectAsState()
    var marketId by remember { mutableStateOf(repo.prefs.lastMarketId) }
    val market = markets.firstOrNull { it.marketId == marketId }
        ?: markets.firstOrNull { it.symbol == "BTC" } ?: markets.firstOrNull()
    var editKey by remember { mutableStateOf(false) }
    var skipped by remember { mutableStateOf(false) }
    var termsOk by remember { mutableStateOf(repo.prefs.termsAccepted >= TERMS_VERSION) }

    var leftW by remember { mutableStateOf(layout.getInt("left", 290).dp) }
    var rightW by remember { mutableStateOf(layout.getInt("right", 360).dp) }
    var bottomH by remember { mutableStateOf(layout.getInt("bottom", 300).dp) }
    fun save() = layout.edit().putInt("left", leftW.value.toInt()).putInt("right", rightW.value.toInt())
        .putInt("bottom", bottomH.value.toInt()).apply()

    fun select(id: String) { marketId = id; repo.prefs.lastMarketId = id }

    Box(Modifier.fillMaxSize().background(D.bg)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(repo, onKey = { editKey = true })
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Watchlist(repo, market?.marketId, ::select, Modifier.width(leftW).fillMaxHeight())
                VSplit(::save) { leftW = (leftW + it).coerceIn(200.dp, 520.dp) }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    AccountsStrip(repo)
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (market != null) ChartPanel(repo, market, Modifier.fillMaxSize())
                        else Panel("Chart", Modifier.fillMaxSize()) { Empty("Loading markets…") }
                    }
                    HSplit(::save) { bottomH = (bottomH - it).coerceIn(120.dp, 800.dp) }
                    Blotter(repo, ::select, Modifier.height(bottomH).fillMaxWidth())
                }
                VSplit(::save) { rightW = (rightW - it).coerceIn(280.dp, 600.dp) }
                Column(Modifier.width(rightW).fillMaxHeight()) {
                    if (market != null) {
                        Ticket(repo, market, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(5.dp))
                        BookPanel(repo, market, Modifier.weight(1.2f).fillMaxWidth())
                        Spacer(Modifier.height(5.dp))
                        TapePanel(repo, market, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
        TradeToast(repo, Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
    // First start asks for a key (it can be skipped to browse market data); a key needs the current terms.
    if (keyPrompt && (key == null && !skipped) || editKey || (keyPrompt && key != null && !termsOk)) KeyDialog(repo, onClose = {
        editKey = false; skipped = true; termsOk = repo.prefs.termsAccepted >= TERMS_VERSION
    })
}

@Composable
private fun TopBar(repo: Repo, onKey: () -> Unit) {
    val conn by repo.account.conn.collectAsState()
    val live by repo.market.live.collectAsState()
    val rtt by repo.account.rtt.collectAsState()
    val key by repo.apiKey.collectAsState()
    val accounts by repo.account.accounts.collectAsState()
    val marks by repo.market.marks.collectAsState()
    val selected by repo.selected.collectAsState()
    var now by remember { mutableStateOf(ZonedDateTime.now(ZoneOffset.UTC)) }
    LaunchedEffect(Unit) { while (true) { now = ZonedDateTime.now(ZoneOffset.UTC); delay(1_000) } }

    val shown = accounts.values.filter { selected.isEmpty() || it.account.id in selected }
    val risks = shown.map { riskOf(it, marks) }
    Row(
        Modifier.fillMaxWidth().height(38.dp).background(D.panel).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Δ1KX", color = D.accent, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 1.sp)
        if (key != null && restBase(key!!) == SANDBOX_REST) Badge("SANDBOX", D.accent)
        Status("Account", when (conn) {
            Conn.Live -> D.up; Conn.Connecting, Conn.Reconnecting -> D.accent; Conn.AuthFailed -> D.down; Conn.Off -> D.muted
        }, conn.name + (rtt?.let { " · ${it} ms" } ?: ""))
        Status("Market", if (live) D.up else D.muted, if (live) "Live" else "Idle")
        Box(Modifier.weight(1f))
        Stat("Equity", fmtUsd(risks.sumOf { it.equity }.takeIf { shown.isNotEmpty() }))
        val upnl = risks.sumOf { it.upnl }
        Stat("uPnL", fmtUsd(upnl.takeIf { shown.isNotEmpty() }, signed = true), pnlColor(upnl))
        Text(now.format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " UTC", style = num.copy(color = D.muted))
        Btn(if (key == null) "Add API key" else "API key", onClick = onKey)
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text, Modifier.clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
    )
}

@Composable
private fun Status(name: String, color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(name, style = label)
        Text(text, style = num.copy(fontSize = 11.sp))
    }
}

@Composable
private fun Stat(name: String, value: String, color: Color = D.text) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, style = label)
        Text(value, style = num.copy(color = color, fontWeight = FontWeight.SemiBold, fontSize = 13.sp))
    }
}

/**
 * One card per account. Click: trade on it. The dot: include it in the totals and blotter
 * (none ticked = all accounts).
 */
@Composable
private fun AccountsStrip(repo: Repo) {
    val accounts by repo.account.accounts.collectAsState()
    val marks by repo.market.marks.collectAsState()
    val selected by repo.selected.collectAsState()
    val trade by repo.tradeAccount.collectAsState()
    val list = accounts.values.sortedWith(compareBy({ it.account.status != "active" }, { accountName(it) }))
    LaunchedEffect(list.isNotEmpty(), trade) {
        if (list.isNotEmpty() && list.none { it.account.id == trade }) list.firstOrNull { it.account.status == "active" }?.let { repo.setTradeAccount(it.account.id) }
    }
    if (list.isEmpty()) return
    LazyRow(
        Modifier.fillMaxWidth().background(D.bg).padding(top = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        items(list, key = { it.account.id }) { s ->
            val r = riskOf(s, marks)
            val isTrade = s.account.id == trade
            val inSet = selected.isEmpty() || s.account.id in selected
            Column(
                Modifier.width(250.dp).clip(RoundedCornerShape(6.dp)).background(D.panel)
                    .border(1.dp, if (isTrade) D.accent else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable { repo.setTradeAccount(s.account.id) }.pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(9.dp).clip(CircleShape).background(if (inSet) D.accent else D.line)
                            .clickable {
                                val all = accounts.keys
                                val cur = if (selected.isEmpty()) all else selected
                                val next = if (s.account.id in cur) cur - s.account.id else cur + s.account.id
                                repo.setSelected(if (next == all) emptySet() else next)
                            },
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(accountName(s), Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(s.account.stage.uppercase().ifBlank { s.account.status.uppercase() }, style = label.copy(fontSize = 9.sp))
                }
                Row {
                    Text(fmtUsd(r.equity), Modifier.weight(1f), style = num.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                    Text(fmtUsd(r.upnl, signed = true), style = num.copy(color = pnlColor(r.upnl)))
                }
                Row {
                    Text("Today " + fmtUsd(r.todayPnl, signed = true), Modifier.weight(1f), style = num.copy(fontSize = 11.sp, color = pnlColor(r.todayPnl)))
                    Text("Room " + fmtUsd(r.room), style = num.copy(fontSize = 11.sp, color = D.muted))
                }
                val f = r.roomFraction
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(D.line)) {
                    if (f != null) Box(
                        Modifier.fillMaxWidth(f.toFloat()).fillMaxHeight()
                            .background(if (f < 0.25) D.down else if (f < 0.5) D.accent else D.up),
                    )
                }
            }
        }
    }
}

/** The latest trade result for a few seconds, bottom right. */
@Composable
private fun TradeToast(repo: Repo, modifier: Modifier) {
    var event by remember { mutableStateOf<Repo.TradeEvent?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { repo.tradeEvents.collect { event = it; message = null } }
    LaunchedEffect(Unit) { repo.messages.collect { message = it } }
    LaunchedEffect(event) { if (event?.ok != null) { delay(6_000); event = null } }
    LaunchedEffect(message) { if (message != null) { delay(4_000); message = null } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
        AnimatedVisibility(message != null, enter = fadeIn(), exit = fadeOut()) {
            Text(
                message.orEmpty(), Modifier.clip(RoundedCornerShape(6.dp)).background(D.panel2).padding(horizontal = 14.dp, vertical = 10.dp),
                fontSize = 13.sp,
            )
        }
        AnimatedVisibility(event != null, enter = fadeIn(), exit = fadeOut()) {
            val e = event ?: return@AnimatedVisibility
            val c = when (e.ok) { true -> D.up; false -> D.down; null -> D.accent }
            Column(
                Modifier.width(340.dp).clip(RoundedCornerShape(6.dp)).background(D.panel2)
                    .border(1.dp, c, RoundedCornerShape(6.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row {
                    Text(e.what, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(e.status, color = c, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                if (e.detail.isNotBlank()) Text(e.detail, style = label.copy(fontSize = 12.sp))
            }
        }
    }
}
