package com.alex.mfppro.widget

import com.alex.mfppro.data.Conn
import com.alex.mfppro.data.Position
import com.alex.mfppro.data.Prefs
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.riskOf
import com.alex.mfppro.ui.AppTheme
import com.alex.mfppro.ui.accountName
import com.alex.mfppro.ui.fmtPrice
import com.alex.mfppro.ui.fmtUsd
import com.alex.mfppro.ui.isComp
import com.alex.mfppro.ui.ticker
import java.util.Locale

/** One open position as the notification and the widgets show it. */
data class LivePos(val p: Position, val account: String, val mark: Double?, val pnl: Double?, val roe: Double?) {
    val name get() = ticker(p.symbol, p.provider)
    val side get() = "${if (p.isLong) "Long" else "Short"} ${p.leverage.toInt()}x"
    val value get() = p.size * (mark ?: p.entryPrice)
}

/**
 * Everything live about the open positions, read straight from the streams: the same numbers the
 * Dashboard shows (profit after the user's split, competition accounts hidden when set so).
 */
data class LiveSnap(
    val hasKey: Boolean,
    /** False until the account stream has delivered something; nothing is drawn before that. */
    val ready: Boolean,
    val authFailed: Boolean,
    val connected: Boolean,
    val positions: List<LivePos>,
    val pnl: Double,
    val equity: Double?,
    val room: Double?,
)

fun liveSnap(repo: Repo): LiveSnap {
    val prefs = repo.prefs
    val marks = repo.market.marks.value
    val conn = repo.account.conn.value
    val all = repo.account.accounts.value.values
    val split = prefs.profitSplit
    fun net(v: Double) = if (v > 0) v * split / 100.0 else v
    val accounts = all.filter { it.account.status == "active" && !(prefs.hideComp && isComp(it)) }
    val positions = accounts.flatMap { s ->
        s.positions.values.map { p ->
            // No price yet reads as zero on some feeds: show a dash rather than 0.0000000 and a made-up PnL.
            val m = marks[p.feedKey]?.takeIf { it > 0 }
            val pnl = m?.let { net(p.pnlAt(it)) }
            LivePos(p, accountName(s), m, pnl, pnl?.let { v -> p.marginAt().takeIf { it > 0 }?.let { v / it * 100 } })
        }
    }.sortedByDescending { it.value }
    // Equity and room of the accounts in play; with nothing open, of the account being traded.
    val busy = accounts.filter { it.positions.isNotEmpty() }
        .ifEmpty { accounts.filter { it.account.id == repo.tradeAccount.value } }
        .map { riskOf(it, marks) }
    return LiveSnap(
        hasKey = repo.apiKey.value != null,
        ready = repo.apiKey.value == null || all.isNotEmpty() || conn == Conn.AuthFailed,
        authFailed = conn == Conn.AuthFailed,
        connected = conn == Conn.Live,
        positions = positions,
        pnl = positions.sumOf { it.pnl ?: 0.0 },
        equity = busy.takeIf { it.isNotEmpty() }?.sumOf { it.equity },
        room = busy.mapNotNull { it.room }.minOrNull(),
    )
}

fun fmtRoe(v: Double?) = v?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "—"

/** The ongoing notification's words. Compared as a whole, so it is only re-posted when something changed. */
data class LiveText(val title: String, val text: String, val lines: List<String>, val summary: String, val pnl: Double?)

fun liveText(s: LiveSnap): LiveText {
    val account = listOfNotNull(s.equity?.let { "Equity ${fmtUsd(it)}" }, s.room?.let { "room ${fmtUsd(it)}" }).joinToString(" · ")
    return when {
        !s.hasKey -> LiveText("Delta 1kx", "Add your API key to see live PnL", emptyList(), "", null)
        s.authFailed -> LiveText("Delta 1kx", "The API key was rejected", emptyList(), "", null)
        !s.ready -> LiveText("Delta 1kx", "Connecting…", emptyList(), "", null)
        s.positions.isEmpty() -> LiveText("No open positions", account.ifEmpty { "Alerts stay live in the background" }, emptyList(), "", null)
        else -> LiveText(
            title = "Open PnL ${fmtUsd(s.pnl, true)} · ${s.positions.size} position${if (s.positions.size > 1) "s" else ""}",
            text = s.positions.joinToString(" · ") { "${it.p.symbol} ${fmtUsd(it.pnl, true)}" },
            lines = s.positions.take(6).map { "${it.name} ${it.side.lowercase()}  ${fmtUsd(it.pnl, true)} (${fmtRoe(it.roe)})" + (it.mark?.let { m -> "  @ ${fmtPrice(m)}" } ?: "") },
            summary = account,
            pnl = s.pnl,
        )
    }
}

/** Widget colours: the app theme the user picked, with the colour-blind swap. */
class WColors(prefs: Prefs) {
    private val t = AppTheme.entries.firstOrNull { it.name == prefs.theme } ?: AppTheme.STANDARD
    val panel = t.panel.toInt()
    val line = t.line.toInt()
    val text = t.text.toInt()
    val muted = t.muted.toInt()
    val up = if (prefs.colorBlind) 0xFF3B8BFF.toInt() else t.up.toInt()
    val down = if (prefs.colorBlind) 0xFFFF8C1A.toInt() else t.down.toInt()
    val accent = if (prefs.colorBlind) 0xFFB794F6.toInt() else t.accent.toInt()
    fun pnl(v: Double?) = if (v == null || v == 0.0) text else if (v > 0) up else down
}
