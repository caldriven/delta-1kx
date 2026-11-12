package com.alex.mfppro.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.breachPrice
import com.alex.mfppro.data.riskOf
import com.alex.mfppro.widget.Widgets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where a home-screen widget tap lands in the app. */
object WidgetLink {
    /** A position to manage; with [close] its close confirmation comes up first. */
    data class Req(val positionId: String, val close: Boolean)

    var request by mutableStateOf<Req?>(null)
    /** A market whose chart to open. */
    var market by mutableStateOf<String?>(null)

    fun handle(intent: Intent?) {
        if (intent?.action != Widgets.ACTION_LINK) return
        // Reopened from Recents: the old tap must not bring its screen back.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val uri = intent.data ?: return
        val id = uri.lastPathSegment
        when (uri.host) {
            "position" -> if (id != null) request = Req(id, uri.getQueryParameter("do") == "close")
            "market" -> market = id
        }
    }
}

/**
 * The widget's manage screen: the position with every Dashboard action underneath, each of which
 * asks before it sends. Arriving from the widget's Close button, the close confirmation shows first;
 * it always asks, whatever the confirm settings say, because the tap came from the home screen.
 */
@Composable
fun WidgetManage(repo: Repo, onOpenMarket: (String) -> Unit) {
    val req = WidgetLink.request ?: return
    val accounts by repo.account.accounts.collectAsStateWithLifecycle()
    val marks by repo.market.marks.collectAsStateWithLifecycle()
    val state = accounts.values.firstOrNull { req.positionId in it.positions }
    val p = state?.positions?.get(req.positionId)
    val dismiss = { WidgetLink.request = null }
    val shape = RoundedCornerShape(16.dp)

    if (state == null || p == null) {
        // A cold start has no positions for a moment; after that the position is simply gone.
        var waited by remember(req) { mutableStateOf(false) }
        LaunchedEffect(req) { delay(6_000); waited = true }
        Dialog(dismiss) {
            Column(Modifier.fillMaxWidth().clip(shape).background(C.panel).border(1.dp, C.line, shape).padding(18.dp)) {
                Text(if (waited) "That position is no longer open." else "Loading position…", color = C.text, fontSize = 15.sp)
                Act("Close", modifier = Modifier.padding(top = 14.dp).fillMaxWidth(), onClick = dismiss)
            }
        }
        return
    }

    val mark = marks[p.feedKey]
    val name = ticker(p.symbol, p.provider)
    if (req.close) {
        val pnl = net(mark?.let(p::pnlAt))
        ConfirmDialog(
            "Confirm close", "Close $name", C.down,
            listOf(
                ConfirmRow("Account", accountName(state)),
                ConfirmRow("Position", "${if (p.isLong) "Long" else "Short"} ${p.leverage.toInt()}x", if (p.isLong) C.up else C.down),
                ConfirmRow("Value", fmtUsd(p.size * (mark ?: p.entryPrice))),
                ConfirmRow("PnL", fmtUsd(pnl, true), pnlColor(pnl)),
            ),
            note = "Market close of the whole position. Cancel to manage it instead.",
            confirm = "Close position",
            // Cancel drops back to the manage screen rather than out of the app.
            onDismiss = { WidgetLink.request = req.copy(close = false) },
        ) {
            dismiss()
            // On the app's scope: this screen is gone the moment the request clears.
            repo.scope.launch { repo.closePosition(p) }
        }
        return
    }

    Dialog(dismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(shape).background(C.panel).border(1.dp, C.line, shape).padding(vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("MANAGE POSITION", color = C.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                Text(accountName(state), color = C.muted, fontSize = 12.sp, maxLines = 1)
            }
            val breach = mark?.let { mk -> riskOf(state, marks).room?.let { breachPrice(p, mk, it) } }
            PositionRow(p, mark, "", showAccount = false, breach = breach, tp = repo.exitPrice(p, "tp"), sl = repo.exitPrice(p, "sl"), market = repo.market(p.marketId)) {
                dismiss(); repo.setTradeAccount(p.accountId); onOpenMarket(p.marketId)
            }
            PositionActions(repo, p, mark)
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Act("Open chart", modifier = Modifier.weight(1f)) { dismiss(); repo.setTradeAccount(p.accountId); onOpenMarket(p.marketId) }
                Act("Done", modifier = Modifier.weight(1f), onClick = dismiss)
            }
        }
    }
}
