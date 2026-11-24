package com.alex.mfppro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alex.mfppro.data.NotifyRules
import com.alex.mfppro.data.PNL_PRESETS
import com.alex.mfppro.data.Repo
import kotlinx.coroutines.launch
import java.util.Locale

// One colour per section so the pages scan at a glance.
private val API = Color(0xFF3B82F6)
private val DISPLAY = Color(0xFFA78BFA)
private val DATA = Color(0xFF22D3EE)
private val ORDERS = Color(0xFF10B981)
private val RULES = Color(0xFFF43F5E)
private val ALERTS = Color(0xFFF59E0B)
private val PNL = Color(0xFF14B8A6)
private val ABOUT = Color(0xFF94A3B8)

/**
 * Settings: a short list of categories, each opening its own page. Notification
 * rules live under Notifications, per account, with PnL steps a page of their own.
 * Pages: home, api, accounts, acct:<id>, about, license, oss, oss:<i>, trading, risk, notify, rules:<id|default>,
 * pnl:<id|default>, chart, display.
 */
@Composable
fun SettingsScreen(repo: Repo, onBack: () -> Unit = {}) {
    var page by rememberSaveable { mutableStateOf("home") }
    var resetAsk by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    fun ask() { if (android.os.Build.VERSION.SDK_INT >= 33) askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS) }

    // Back walks up one level: PnL steps -> its rules page -> Notifications -> home.
    fun up() {
        page = when {
            page.startsWith("pnl:") -> "rules:" + page.removePrefix("pnl:")
            page.startsWith("rules:") -> "notify"
            page.startsWith("oss:") -> "oss"
            page.startsWith("acct:") -> "accounts"
            page == "license" || page == "oss" -> "about"
            else -> "home"
        }
    }
    androidx.activity.compose.BackHandler(enabled = page != "home") { up() }

    // Each page starts at its top.
    androidx.compose.runtime.key(page) { Column(
        Modifier.fillMaxSize().background(C.bg).imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp).padding(bottom = 24.dp),
    ) {
        when {
            page == "home" -> Home(repo, onBack) { page = it }
            page == "api" -> Page("Account & API key", ::up) { ApiPage(repo) { page = it } }
            page == "about" -> Page("About Delta 1kx", ::up) { AboutPage(repo) { page = it } }
            page == "accounts" -> Page("Accounts", ::up) { AccountsPage(repo) { page = it } }
            page.startsWith("acct:") -> page.removePrefix("acct:").let { id -> Page(targetName(repo, id), ::up) { AccountPage(repo, id) { page = it } } }
            page == "license" -> Page("License & terms", ::up) { LicensePage() }
            page == "oss" -> Page("Open source licenses", ::up) { OssPage { page = it } }
            page.startsWith("oss:") -> OSS.getOrNull(page.removePrefix("oss:").toInt())?.let { o -> Page(o.name, ::up) { OssLicensePage(o) } }
            page == "trading" -> Page("Trading", ::up) { TradingPage(repo) }
            page == "risk" -> Page("Risk guard", ::up) { RiskPage(repo) }
            page == "notify" -> Page("Notifications", ::up) { NotifyPage(repo, ::ask) { page = it } }
            page.startsWith("rules:") -> page.removePrefix("rules:").let { t ->
                Page(targetName(repo, t), ::up) { RulesPage(repo, t.takeIf { it != "default" }, ::ask) { page = "pnl:$t" } }
            }
            page.startsWith("pnl:") -> page.removePrefix("pnl:").let { t ->
                Page("PnL steps · " + targetName(repo, t), ::up) { PnlPage(repo, t.takeIf { it != "default" }, ::ask) }
            }
            page == "chart" -> Page("Chart", ::up) { ChartPage(repo) }
            page == "display" -> Page("Display", ::up) { DisplayPage(repo) }
        }
        if (page == "home") Section("Reset", RULES, Icons.Filled.Refresh) {
            Note("Puts every setting back to default. Your API key stays.")
            Act("Reset settings", color = C.down, modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) { resetAsk = true }
        }
    } }

    if (resetAsk) ConfirmDialog("Reset settings", "Back to defaults", C.down, note = "Theme, alerts, chart and order settings reset. The API key is kept.",
        confirm = "Reset", onDismiss = { resetAsk = false }) {
        resetAsk = false
        repo.prefs.resetUi()
        // Live state that isn't re-read on restart.
        repo.setRealtime(repo.prefs.realtimePnl)
        com.alex.mfppro.data.MonitorService.setEnabled(ctx, repo.prefs.monitorBackground)
        // Restart so every screen picks up the defaults.
        (ctx as? android.app.Activity)?.recreate()
    }
}

private fun targetName(repo: Repo, t: String) =
    if (t == "default") "All accounts" else repo.account.accounts.value[t]?.let(::accountName) ?: "Account"

@Composable
private fun Home(repo: Repo, onBack: () -> Unit, open: (String) -> Unit) {
    val key by repo.apiKey.collectAsStateWithLifecycle()
    val p = repo.prefs
    // Same back arrow as every sub-page, so Settings has a visible way out.
    Page("Settings", onBack) {}
    Column(Modifier.padding(vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel)) {
        NavRow("Account & API key", key?.let { (if (it.startsWith("fp_test_")) "Sandbox" else "Live") + " key …" + it.takeLast(4) } ?: "No key yet", API, Icons.Filled.Key) { open("api") }
        NavRow("About Delta 1kx", "Alpha ${appVersion(androidx.compose.ui.platform.LocalContext.current)} · by $CREATOR · terms and licenses", ABOUT, Icons.Filled.Info) { open("about") }
        NavRow("Accounts", "Names, cross or isolated, MFP rules", API, Icons.Filled.AccountBox) { open("accounts") }
        NavRow("Trading", "Confirmations, slippage, quick sizes, TP/SL type", ORDERS, Icons.Filled.TouchApp) { open("trading") }
        NavRow("Risk guard", if (p.lockEnabled) "Night lock ${p.lockStart}:00–${p.lockEnd}:00 Oslo" else "Night lock off", RULES, Icons.Filled.Bedtime) { open("risk") }
        NavRow("Notifications", notifySummary(repo), ALERTS, Icons.Filled.NotificationsActive) { open("notify") }
        NavRow("Chart", "Sessions, killzones, indicator bar", DATA, Icons.Filled.Bolt) { open("chart") }
        NavRow("Display", "${Palette.theme.label} theme · profit split ${Split.pct}%", DISPLAY, Icons.Filled.Palette, last = true) { open("display") }
    }
}

private fun notifySummary(repo: Repo): String {
    val ids = activeAccounts(repo.account.accounts.value, includeComp = true).map { it.account.id }
    val own = ids.count { repo.prefs.notifyOverride(it) != null }
    val muted = ids.count { repo.prefs.notifyFor(it).muted }
    return listOfNotNull(
        if (repo.prefs.monitorBackground) "Background on" else "In app only",
        own.takeIf { it > 0 }?.let { "$it with own rules" },
        muted.takeIf { it > 0 }?.let { "$it muted" },
    ).joinToString(" · ")
}

// ---- pages ------------------------------------------------------------

@Composable
private fun ApiPage(repo: Repo, open: (String) -> Unit) {
    val key by repo.apiKey.collectAsStateWithLifecycle()
    val conn by repo.account.conn.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var removeAsk by remember { mutableStateOf(false) }
    var termsAsk by remember { mutableStateOf(false) }
    var license by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun test() {
        busy = true; status = null
        scope.launch {
            runCatching { repo.rest.accounts(input) }
                .onSuccess { repo.setApiKey(input); input = ""; status = "Connected: ${it.size} account(s)" }
                .onFailure { status = "Rejected: ${it.message}" }
            busy = false
        }
    }
    Section("API key", API, Icons.Filled.Key) {
        if (key != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((if (key!!.startsWith("fp_test_")) "Sandbox" else "Live") + " key …" + key!!.takeLast(4), color = C.text, fontSize = 14.sp)
                    Spacer(Modifier.height(2.dp))
                    ConnBadge(conn)
                }
                Act("Remove key", color = C.down, modifier = Modifier.width(100.dp)) { removeAsk = true }
            }
        } else {
            AppInput("", input, { input = it.trim() }, Modifier.fillMaxWidth(), secret = true, placeholder = "fp_live_… or fp_test_…")
            Spacer(Modifier.height(8.dp))
            SolidBtn(
                if (busy) "Checking…" else "Save and test", API, Modifier.fillMaxWidth(),
                enabled = !busy && (input.startsWith("fp_live_") || input.startsWith("fp_test_")),
            ) { termsAsk = true }
            Note("Stored encrypted on this phone and only sent to the MFP API. Needs a Read And Trade key.")
            Note("Adding a key means you accept the alpha tester terms: Delta 1kx is an alpha app, not related to MFP, and used at your own risk.")
        }
        status?.let { Text(it, color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        if (key != null) repo.prefs.termsAcceptedAt.takeIf { it > 0 }?.let {
            Note("Alpha tester terms accepted " + java.text.SimpleDateFormat("d MMM yyyy", Locale.US).format(java.util.Date(it)) + ".")
        }
    }
    Column(Modifier.padding(vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel)) {
        NavRow("About Delta 1kx", "Alpha app by $CREATOR · x.com/caldriven", ABOUT, Icons.Filled.Info) { open("about") }
        NavRow("License & terms", "Private alpha tester license, as is, own risk", ABOUT, Icons.Filled.Gavel, last = true) { open("license") }
    }

    if (termsAsk) TermsDialog(
        title = "Add your MFP API key", confirm = "Agree and connect", dismiss = "Cancel",
        onLicense = { license = true }, onDismiss = { termsAsk = false },
    ) { termsAsk = false; repo.prefs.acceptTerms(TERMS_VERSION); test() }
    if (license) LicenseDialog { license = false }

    // Removing the key needs "ok" typed: it logs the app out of every account.
    if (removeAsk) {
        var typed by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removeAsk = false }, containerColor = C.panel,
            title = { Text("Remove API key?", color = C.down) },
            text = {
                Column {
                    Text("The app disconnects from every account until you add a key again. Type ok to confirm.", color = C.text, fontSize = 13.sp)
                    AppInput("", typed, { typed = it }, Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = "ok")
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(enabled = typed.trim().equals("ok", true), onClick = { removeAsk = false; repo.setApiKey(null); status = null }) {
                    Text("Remove", color = if (typed.trim().equals("ok", true)) C.down else C.muted)
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { removeAsk = false }) { Text("Cancel", color = C.muted) } },
        )
    }
}

@Composable
private fun TradingPage(repo: Repo) {
    val p = repo.prefs
    Section("Confirmations", ORDERS, Icons.Filled.TouchApp) {
        var confirm by remember { mutableStateOf(p.confirmOrders) }
        Toggle("Confirm orders", "Off = one tap", confirm, ORDERS) { confirm = it; p.confirmOrders = it }
        var confirmAll by remember { mutableStateOf(p.confirmCloseAll) }
        Toggle("Confirm full closes", "Close, close all, hold to close", confirmAll, ORDERS) { confirmAll = it; p.confirmCloseAll = it }
    }
    Section("Market orders", ORDERS, Icons.Filled.TouchApp) {
        var slip by remember { mutableStateOf(p.slippageBps) }
        ChipPick("Max slippage", listOf(5, 10, 20, 50, 100), slip, ORDERS, { String.format(Locale.US, "%.2f%%", it / 100.0).replace(".00%", "%") }) {
            slip = it; p.slippageBps = it
        }
        var quick by remember { mutableStateOf(p.quickSizes.joinToString(", ")) }
        AppInput("Quick sizes $ (up to 4, comma separated)", quick, { quick = it; p.quickSizes = it.split(",").mapNotNull { s -> s.trim().toIntOrNull() }.take(4) },
            Modifier.fillMaxWidth().padding(top = 6.dp))
    }
    Section("Take profit / stop loss", ORDERS, Icons.Filled.TouchApp) {
        Note("Default for new exits. The TP/SL dialogs let you switch per order.")
        var tp by remember { mutableStateOf(p.tpExecution) }
        Label("Take profit")
        Box(Modifier.padding(top = 4.dp)) { ExitExecPicker(true, tp, "binance") { tp = it; p.tpExecution = it } }
        var sl by remember { mutableStateOf(p.slExecution) }
        Label("Stop loss")
        Box(Modifier.padding(top = 4.dp)) { ExitExecPicker(false, sl, "binance") { sl = it; p.slExecution = it } }
        Note("Triggers watch the last traded price on Binance, Bybit and Hyperliquid, and the venue mid price on Veranta (forex) and Ostium.")
        var cm by remember { mutableStateOf(p.closeMarginBps) }
        Label("Post-only close margin")
        androidx.compose.foundation.layout.Row(Modifier.padding(top = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
            for (b in listOf(0f, 1f, 2f, 5f, 10f)) Seg(if (b == 0f) "at touch" else "${b.toInt()} bp", cm == b, ORDERS) { cm = b; p.closeMarginBps = b }
        }
        Note("How far behind the best bid/ask the Trade screen's post-only close rests. More margin: less chance MFP refuses it as crossing, but it fills a little later.")
        var beLimit by remember { mutableStateOf(p.breakevenLimit) }
        Toggle("Breakeven limit when underwater", "Breakeven on a losing position places a reduce-only limit at entry (a sell for a long, a buy for a short). In profit it stays a stop at entry.",
            beLimit, ORDERS) { beLimit = it; p.breakevenLimit = it }
    }
}

@Composable
private fun RiskPage(repo: Repo) {
    val p = repo.prefs
    Section("Night lock", RULES, Icons.Filled.Bedtime) {
        var lock by remember { mutableStateOf(p.lockEnabled) }
        val locked = p.locked()
        // Can't switch the rule off (or move its hours) while it's in force.
        Toggle("Night lock", if (locked) "Active now; locked until it ends" else "No new trades in these Oslo hours; closing works", lock, RULES, enabled = !locked) {
            lock = it; p.lockEnabled = it
        }
        if (lock && !locked) {
            var from by remember { mutableStateOf(p.lockStart) }
            var to by remember { mutableStateOf(p.lockEnd) }
            ChipPick("From", listOf(20, 21, 22, 23, 0, 1, 2), from, RULES, { String.format(Locale.US, "%02d:00", it) }) { from = it; p.lockStart = it }
            ChipPick("Until", listOf(5, 6, 7, 8, 9, 10), to, RULES, { String.format(Locale.US, "%02d:00", it) }) { to = it; p.lockEnd = it }
        }
    }
}

@Composable
private fun NotifyPage(repo: Repo, ask: () -> Unit, open: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val accounts by repo.account.accounts.collectAsStateWithLifecycle()
    Section("Delivery", ALERTS, Icons.Filled.NotificationsActive) {
        var bg by remember { mutableStateOf(repo.prefs.monitorBackground) }
        Toggle("Watch in background", "Keeps alerts live with the app closed", bg, ALERTS) {
            bg = it; repo.prefs.monitorBackground = it
            if (it) ask()
            com.alex.mfppro.data.MonitorService.setEnabled(ctx, it)
        }
    }
    Text("RULES", color = C.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, modifier = Modifier.padding(6.dp, 10.dp, 6.dp, 4.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel)) {
        NavRow("All accounts (default)", rulesSummary(repo.prefs.notifyDefaults), ALERTS, Icons.Filled.NotificationsActive) { open("rules:default") }
        val list = activeAccounts(accounts, includeComp = true)
        list.forEachIndexed { i, s ->
            val own = repo.prefs.notifyOverride(s.account.id)
            val sub = when {
                own == null -> "Uses the default"
                own.muted -> "Muted"
                else -> "Own rules · " + rulesSummary(own)
            }
            NavRow(accountName(s), sub, if (own?.muted == true) C.muted else if (own != null) ALERTS else C.text, null, last = i == list.lastIndex) { open("rules:" + s.account.id) }
        }
    }
    Note("Each account follows the default until you give it its own rules or mute it.")
}

private fun rulesSummary(r: NotifyRules): String = if (r.muted) "Muted" else listOfNotNull(
    "fills".takeIf { r.fills }, "stops".takeIf { r.stops }, "breach".takeIf { r.breach },
    "room < ${if (r.roomUsd > 0) "$" + r.roomUsd else "${r.roomPct}%"}".takeIf { r.room },
    "margin ${r.marginPct}%".takeIf { r.margin },
    "PnL ${if (r.pnlPreset == "custom") "custom" else r.pnlPreset.lowercase()}".takeIf { r.pnl },
).joinToString(", ").ifEmpty { "Nothing on" }

/** Rules editor for one account, or the default set when [accountId] is null. */
@Composable
private fun RulesPage(repo: Repo, accountId: String?, ask: () -> Unit, openPnl: () -> Unit) {
    val p = repo.prefs
    var own by remember(accountId) { mutableStateOf(accountId?.let { p.notifyOverride(it) }) }
    var r by remember(accountId) { mutableStateOf(accountId?.let { p.notifyFor(it) } ?: p.notifyDefaults) }
    fun save(v: NotifyRules) {
        r = v
        if (accountId == null) p.notifyDefaults = v else { own = v; p.setNotifyOverride(accountId, v) }
    }
    if (accountId != null) Section("This account", ALERTS, Icons.Filled.NotificationsActive) {
        Toggle("Use the default rules", "Off = tune this account on its own", own == null, ALERTS) { useDefault ->
            if (useDefault) { own = null; p.setNotifyOverride(accountId, null); r = p.notifyDefaults }
            else save(p.notifyDefaults)
        }
        Toggle("Mute this account", "No notifications from it at all", r.muted, C.down) { save(r.copy(muted = it)) }
    }
    // Following the default: show it, but edit it on the default page.
    if (accountId != null && own == null) {
        Note("Following the default: ${rulesSummary(r)}.")
        return
    }
    if (r.muted) return
    Section("Trades", ALERTS, Icons.Filled.NotificationsActive) {
        Toggle("Fills", "Every open and close, with PnL", r.fills, ALERTS) { save(r.copy(fills = it)); if (it) ask() }
        Toggle("Stop loss hit", "Loud alert when a stop fills", r.stops, ALERTS) { save(r.copy(stops = it)); if (it) ask() }
    }
    Section("Risk", RULES, Icons.Filled.NotificationsActive) {
        Toggle("Breach / liquidation", "Account failed, no room left, liquidated", r.breach, RULES) { save(r.copy(breach = it)); if (it) ask() }
        Divider()
        Toggle("Drawdown running low", "Notify + vibrate once; re-arms when room recovers", r.room, RULES) { save(r.copy(room = it)); if (it) ask() }
        if (r.room) {
            ChipPick("When room left is below", listOf(0, 100, 200, 300, 500, 1000), r.roomUsd, RULES, { if (it == 0) "Off" else "$$it" }) { save(r.copy(roomUsd = it)) }
            ChipPick("Or below this share of the allowance", listOf(0, 10, 25, 50), r.roomPct, RULES, { if (it == 0) "Off" else "$it%" }) { save(r.copy(roomPct = it)) }
        }
        Divider()
        Toggle("Margin call", "Margin used as a share of equity", r.margin, RULES) { save(r.copy(margin = it)); if (it) ask() }
        if (r.margin) ChipPick("Alert at", listOf(50, 70, 80, 90, 95), r.marginPct, RULES, { "$it%" }) { save(r.copy(marginPct = it)) }
    }
    Column(Modifier.padding(vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel)) {
        NavRow("PnL steps", if (!r.pnl) "Off" else pnlSummary(r), PNL, Icons.Filled.Bolt, last = true, onClick = openPnl)
    }
}

private fun pnlSummary(r: NotifyRules): String {
    val parts = listOfNotNull(
        r.pnlUsd.takeIf { it > 0 }?.let { "every $$it" },
        r.pnlPct.takeIf { it > 0 }?.let { "${fmtStep(it)}% of position" },
        r.pnlEq.takeIf { it > 0 }?.let { "${fmtStep(it)}% of equity" },
        r.pnlRisk.takeIf { it > 0 }?.let { "${fmtStep(it)}% of loss limit" },
    )
    val name = if (r.pnlPreset == "custom") "Custom" else r.pnlPreset
    return "$name · " + parts.joinToString(", ").ifEmpty { "no steps" }
}

private fun fmtStep(v: Float) = if (v % 1f == 0f) v.toInt().toString() else v.toString()

/** PnL step alerts: a preset, then optional fine-tuning by tapping values. */
@Composable
private fun PnlPage(repo: Repo, accountId: String?, ask: () -> Unit) {
    val p = repo.prefs
    var r by remember(accountId) { mutableStateOf(accountId?.let { p.notifyFor(it) } ?: p.notifyDefaults) }
    fun save(v: NotifyRules) {
        r = v
        if (accountId == null) p.notifyDefaults = v else p.setNotifyOverride(accountId, v)
    }
    Section("PnL steps", PNL, Icons.Filled.Bolt) {
        Toggle("Notify on PnL steps", "Each open position, every time its PnL moves a step", r.pnl, PNL) { save(r.copy(pnl = it)); if (it) ask() }
    }
    if (!r.pnl) return

    // What each step is worth right now: this account's largest position, or for the default the trading
    // account's, then any non-competition account's (a competition's huge book makes a poor example).
    val marks = repo.market.marks.value
    val all = activeAccounts(repo.account.accounts.value, includeComp = true)
    val pools = if (accountId != null) listOf(all.filter { it.account.id == accountId })
        else listOf(all.filter { it.account.id == repo.prefs.tradeAccountId }, all.filter { !isComp(it) }, all)
    fun largest(pool: List<com.alex.mfppro.data.AccountState>) =
        pool.flatMap { s -> s.positions.values.map { s to it } }.maxByOrNull { (_, pos) -> pos.size * (marks[pos.feedKey] ?: pos.entryPrice) }
    val big = pools.firstNotNullOfOrNull { largest(it) }
    val acct = big?.first ?: pools.firstNotNullOfOrNull { it.firstOrNull() }
    val risk = acct?.let { com.alex.mfppro.data.riskOf(it, marks) }
    val cost = big?.second?.let { it.size * it.entryPrice }
    val allowance = risk?.let { (if (it.bindingIsDaily) it.dailyAllowance else it.ddAllowance)?.takeIf { a -> a > 0 } }

    Section("How often", PNL, Icons.Filled.Bolt) {
        for (preset in PNL_PRESETS) {
            val on = r.pnlPreset == preset.name
            Column(
                Modifier.padding(vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (on) PNL.copy(alpha = 0.14f) else C.bg)
                    .border(1.dp, if (on) PNL else C.line, RoundedCornerShape(10.dp))
                    .clickable { save(r.withPreset(preset)) }.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(preset.name, color = if (on) PNL else C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(preset.about, color = C.muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        if (r.pnlPreset == "custom") Note("Custom: your own mix below.")
    }
    Section("Fine-tune", PNL, Icons.Filled.Bolt) {
        Note("Tap a value; Off drops that measure. Any measure that is on can fire.")
        fun worth(base: Double?, pct: Float) = if (pct > 0 && base != null) "≈ " + fmtUsd(base * pct / 100) else null
        ChipPick("Every fixed dollar amount", listOf(0, 25, 50, 100, 250, 500), r.pnlUsd, PNL, { if (it == 0) "Off" else "$$it" }) {
            save(r.copy(pnlUsd = it, pnlPreset = "custom"))
        }
        ChipPick("Every % of the position", listOf(0f, 0.5f, 1f, 2f, 5f), r.pnlPct, PNL, { if (it == 0f) "Off" else fmtStep(it) + "%" },
            hint = worth(cost, r.pnlPct)) { save(r.copy(pnlPct = it, pnlPreset = "custom")) }
        ChipPick("Every % of account equity", listOf(0f, 0.1f, 0.25f, 0.5f, 1f, 2f), r.pnlEq, PNL, { if (it == 0f) "Off" else fmtStep(it) + "%" },
            hint = worth(risk?.equity, r.pnlEq)) { save(r.copy(pnlEq = it, pnlPreset = "custom")) }
        ChipPick("Every % of the loss limit used (losses only)", listOf(0f, 2.5f, 5f, 10f, 25f, 50f), r.pnlRisk, PNL, { if (it == 0f) "Off" else fmtStep(it) + "%" },
            hint = if (r.pnlRisk > 0 && allowance != null) "${fmtUsd(allowance * r.pnlRisk / 100)} of ${fmtUsd(allowance)} ${if (risk?.bindingIsDaily == true) "daily" else "max DD"}" else null,
        ) { save(r.copy(pnlRisk = it, pnlPreset = "custom")) }
        if (big != null) Note("≈ values use ${big.second.symbol} (${fmtUsd(cost ?: 0.0)}) on ${accountName(big.first)}, equity ${fmtUsd(risk?.equity ?: 0.0)}.")
    }
    Section("Which moves", PNL, Icons.Filled.Bolt) {
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Seg("Both", r.pnlSide == "both", PNL) { save(r.copy(pnlSide = "both")) }
            Seg("Losses only", r.pnlSide == "loss", C.down) { save(r.copy(pnlSide = "loss")) }
            Seg("Profits only", r.pnlSide == "profit", C.up) { save(r.copy(pnlSide = "profit")) }
        }
        Toggle("Net PnL", "Count after fees and swap, not price PnL", r.pnlNet, PNL) { save(r.copy(pnlNet = it)) }
    }
}

@Composable
private fun ChartPage(repo: Repo) {
    val p = repo.prefs
    val realtime by repo.realtime.collectAsStateWithLifecycle()
    Section("Chart", DATA, Icons.Filled.Bolt) {
        Toggle("Real-time position value", "Live order book instead of slower mark", realtime, DATA) { repo.setRealtime(it) }
        var kz by remember { mutableStateOf(p.killzones) }
        Toggle("Killzones", "Asia, London, NY AM and London close as a strip along the bottom", kz, DATA) { kz = it; p.killzones = it }
        var sess by remember { mutableStateOf(p.showSessions) }
        Toggle("Market hours", "Tokyo, London and New York along the top, weekend band", sess, DATA) { sess = it; p.showSessions = it }

        // Daily open: when "today" starts for the D/W/M opens, today's % change and pivots.
        Label("Daily open")
        var openZone by remember { mutableStateOf(p.dayOpenZone) }
        var openHour by remember { mutableStateOf(p.dayOpenHour) }
        fun setOpen(z: String, h: Int) { openZone = z; openHour = h; repo.setDayOpen(z, h) }
        val presets = listOf(
            Triple("UTC 00:00", "UTC", 0),
            Triple("Europe 00:00 (UTC+1/+2)", "Europe/Oslo", 0),
            Triple("New York 17:00", "America/New_York", 17),
            Triple("New York 00:00", "America/New_York", 0),
        )
        Row(Modifier.padding(top = 6.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.forEach { (label, z, h) -> Seg(label, openZone == z && openHour == h, DATA) { setOpen(z, h) } }
        }
        // Any hour in the chosen zone.
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Hour in ${openZone.substringAfter('/').replace('_', ' ')}", color = C.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Seg("−", false, DATA) { setOpen(openZone, (openHour + 23) % 24) }
            Text(String.format(java.util.Locale.US, "%02d:00", openHour), style = num.copy(fontSize = 14.sp, color = C.text), modifier = Modifier.padding(horizontal = 10.dp))
            Seg("+", false, DATA) { setOpen(openZone, (openHour + 1) % 24) }
        }
        val nowOffset = runCatching { java.time.ZonedDateTime.now(java.time.ZoneId.of(openZone)).offset.id.replace("Z", "+00:00") }.getOrDefault("")
        Note("Summer and winter time follow the zone (now UTC$nowOffset). Venues and MFP use 00:00 UTC for their daily candles; any other time is priced from hourly candles.")
    }
    Section("Liquidation levels", DATA, Icons.Filled.Bolt) {
        Note("Ranges and volume-spike moves → fixed liq levels per leverage. Tap a label ⓘ on the chart for details.")
        var tiers by remember { mutableStateOf(p.liqTiers) }
        androidx.compose.foundation.layout.Row(Modifier.padding(top = 6.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
            for (lev in listOf(5, 10, 20, 25, 50, 75, 100)) Seg("${lev}x", lev in tiers, liqTierColor(lev)) {
                val next = if (lev in tiers) tiers - lev else tiers + lev
                if (next.isNotEmpty()) { tiers = next.sorted(); p.liqTiers = tiers }
            }
        }
        var ev by remember { mutableStateOf(p.liqEvents) }
        Toggle("From flushes and pumps", "Volume spikes (3\u00D7 normal on a bar 2\u00D7 the ATR) are sources too, marked on their candle", ev, DATA) { ev = it; p.liqEvents = it }
        var hist by remember { mutableStateOf(p.liqHistory) }
        androidx.compose.foundation.layout.Row(Modifier.padding(top = 6.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("History", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
            for (k in listOf(1, 2, 3, 5)) Seg(if (k == 1) "latest" else "$k", hist == k, DATA) { hist = k; p.liqHistory = k }
        }
        var heat by remember { mutableStateOf(p.liqHeat) }
        Toggle("Liquidity pools heat", "Untouched liquidations from every candle, along the right edge", heat, DATA) { heat = it; p.liqHeat = it }
        var bands by remember { mutableStateOf(p.liqBands) }
        Toggle("Leverage bands", "Long and short liquidation lines around the average price", bands, DATA) { bands = it; p.liqBands = it }
        if (bands) {
            var base by remember { mutableStateOf(p.liqBase) }
            androidx.compose.foundation.layout.Row(Modifier.padding(top = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                for (b in com.alex.mfppro.data.Liquidity.Base.entries) Seg(b.label, base == b.name, DATA) { base = b.name; p.liqBase = b.name }
            }
        }
    }
    Section("Indicator bar", DATA, Icons.Filled.Bolt) {
        Note("Which toggles show in the bar above the chart.")
        var ps by remember { mutableStateOf(p.chipPos) }
        Toggle("Position lines", "Net PnL box, TP/SL, DLL, Max DD and pass target", ps, DATA) { ps = it; p.chipPos = it; if (!it) p.showPosLines = true }
        var o by remember { mutableStateOf(p.chipOpens) }
        Toggle("D/W/M opens", "Daily, weekly, monthly open lines", o, DATA) { o = it; p.chipOpens = it }
        var pv by remember { mutableStateOf(p.chipPivots) }
        Toggle("Pivot points", "Classic P, R1–R3, S1–S3 from yesterday (last week on 4h+)", pv, DATA) { pv = it; p.chipPivots = it }
        var v by remember { mutableStateOf(p.chipVp) }
        Toggle("Volume profile", "Session VP with value area", v, DATA) { v = it; p.chipVp = it }
        var pc by remember { mutableStateOf(p.chipPoc) }
        Toggle("Session POC", "Busiest price, value area edges and naked POCs", pc, DATA) { pc = it; p.chipPoc = it }
        var se by remember { mutableStateOf(p.chipSess) }
        Toggle("Market hours", "Tokyo, London, New York strip and weekend band", se, DATA) { se = it; p.chipSess = it }
        var kzc by remember { mutableStateOf(p.chipKz) }
        Toggle("Killzones", "Killzone strip along the bottom", kzc, DATA) { kzc = it; p.chipKz = it }
        var lq by remember { mutableStateOf(p.chipLiq) }
        Toggle("Liquidation levels", "Liquidation pivots from compressed ranges", lq, DATA) { lq = it; p.chipLiq = it }
    }
}

@Composable
private fun DisplayPage(repo: Repo) {
    Section("Theme", DISPLAY, Icons.Filled.Palette) {
        // Each chip shows the theme's own background, panel and accent.
        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppTheme.entries.forEach { t ->
                val on = Palette.theme == t
                Column(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Color(t.bg))
                        .border(if (on) 2.dp else 1.dp, if (on) Color(t.accent) else Color(t.line), RoundedCornerShape(10.dp))
                        .clickable { Palette.theme = t; repo.prefs.theme = t.name }.padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (c in listOf(t.panel2, t.up, t.down, t.accent)) Box(Modifier.size(10.dp).clip(CircleShape).background(Color(c)))
                    }
                    Text(t.label, color = Color(t.text), fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Toggle("Colour-blind mode", "Blue up, orange down", Palette.colorBlind, DISPLAY) {
            Palette.colorBlind = it; repo.prefs.colorBlind = it
        }
    }
    Section("Accounts", DISPLAY, Icons.Filled.Palette) {
        var hideComp by remember { mutableStateOf(repo.prefs.hideComp) }
        Toggle("Hide competition accounts", "Out of positions, account pickers and totals", hideComp, DISPLAY) {
            hideComp = it; repo.prefs.hideComp = it; Comp.hide = it
        }
        ChipPick("Profit split (your share of positive PnL)", listOf(100, 90, 80), Split.pct, PNL, { "$it%" }) { Split.pct = it; repo.prefs.profitSplit = it }
    }
}

@Composable
private fun AccountsPage(repo: Repo, open: (String) -> Unit) {
    val accounts by repo.account.accounts.collectAsStateWithLifecycle()
    val list = activeAccounts(accounts, includeComp = true)
    Note("Per-account settings in Delta 1kx, and each account's rules as MFP reports them.")
    if (list.isEmpty()) Note("No active accounts yet.")
    else Column(Modifier.padding(vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel)) {
        list.forEachIndexed { i, s ->
            val own = com.alex.mfppro.data.Nicknames.map[s.account.id]
            val sub = listOfNotNull(
                own?.let { "MFP: " + s.account.name.ifBlank { "#" + s.account.accountNumber } },
                s.account.stage.ifBlank { null }, fmtUsd(s.account.balance),
                repo.prefs.marginModeFor(s.account.id).replaceFirstChar { it.uppercase() },
            ).joinToString(" · ")
            NavRow(accountName(s), sub, API, null, last = i == list.lastIndex) { open("acct:" + s.account.id) }
        }
    }
}

@Composable
private fun AccountPage(repo: Repo, id: String, open: (String) -> Unit) {
    val accounts by repo.account.accounts.collectAsStateWithLifecycle()
    val policies by repo.policies.collectAsStateWithLifecycle()
    val s = accounts[id] ?: run { Note("This account is no longer listed."); return }
    val a = s.account
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Section("Name", API, Icons.Filled.AccountBox) {
        var nick by remember(id) { mutableStateOf(com.alex.mfppro.data.Nicknames.map[id] ?: "") }
        AppInput("", nick, { nick = it.take(32); repo.prefs.setNickname(id, nick) }, Modifier.fillMaxWidth(),
            placeholder = a.name.ifBlank { "#" + a.accountNumber })
        Note("Only changes how Delta 1kx shows this account. Leave it empty to use MFP's name (${a.name.ifBlank { "#" + a.accountNumber }}). " +
            "MFP's API can't rename accounts; to rename it everywhere, use MFP's website.")
        Text("Open MFP", color = API, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(6.dp)).clickable { openUrl(ctx, "https://myfundedperpetuals.com") }.padding(vertical = 4.dp))
    }
    Section("New positions", ORDERS, Icons.Filled.TouchApp) {
        var mode by remember(id) { mutableStateOf(repo.prefs.marginModeFor(id)) }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Seg("Cross", mode == "cross", ORDERS) { mode = "cross"; repo.prefs.setMarginModeFor(id, "cross") }
            Seg("Isolated", mode == "isolated", ORDERS) { mode = "isolated"; repo.prefs.setMarginModeFor(id, "isolated") }
        }
        Note("Cross shares the whole balance as margin; isolated limits each position to its own margin. " +
            "This is the default for new positions on this account; the order ticket can still switch it. " +
            "A position you already hold keeps its mode: MFP fixes it when the position opens.")
    }
    Section("MFP rules", RULES, Icons.Filled.Bedtime) {
        val rq = a.risk?.requirements
        val pol = policies[id]
        fun pct(v: Double?) = com.alex.mfppro.data.pctToFraction(v)?.let { String.format(Locale.US, "%.1f%%", it * 100).replace(".0%", "%") }
        listOfNotNull(
            "Account" to "#${a.accountNumber}",
            "Stage" to (a.stage.ifBlank { "—" } + if (com.alex.mfppro.data.isCompetition(a)) " · competition" else ""),
            "Status" to a.status.ifBlank { "—" },
            "Starting balance" to fmtUsd(a.startingBalance),
            "Balance" to fmtUsd(a.balance),
            a.risk?.equity?.let { "Equity" to fmtUsd(it) },
            pct(rq?.dailyLossPct)?.let { "Daily loss limit" to it },
            pct(rq?.maxDrawdownPct)?.let { "Max drawdown" to it },
            pct(rq?.profitTargetPct)?.takeIf { com.alex.mfppro.data.hasPassTarget(a) }?.let { "Profit target" to it },
            a.risk?.dailyLossFloor?.let { "Daily loss floor" to fmtUsd(it) },
            a.risk?.maxDrawdownFloor?.let { "Drawdown floor" to fmtUsd(it) },
            pol?.maximumTotalNotionalUsd?.let { "Max total exposure" to fmtUsd(it) },
            pol?.limits?.maxPositionValueUsd?.let { "Max position value" to fmtUsd(it) },
            pol?.limits?.maxOpenPositions?.let { "Max open positions" to it.toInt().toString() },
            "Elite leverage" to (if (com.alex.mfppro.data.isElite(a, pol)) "yes" else "no"),
        ).forEach { (k, v) -> Info(k, v) }
        Note("As MFP reports them; the app can't change them.")
    }
    Column(Modifier.padding(vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel)) {
        NavRow("Notifications", rulesSummary(repo.prefs.notifyFor(id)), ALERTS, Icons.Filled.NotificationsActive, last = true) { open("rules:$id") }
    }
}

@Composable
private fun AboutPage(repo: Repo, open: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.padding(4.dp, 6.dp, 4.dp, 8.dp)) {
        Text("Delta 1kx", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Mobile trading terminal · alpha ${appVersion(ctx)} · by $CREATOR", color = C.muted, fontSize = 12.sp)
    }
    Section("Alpha testing", ALERTS, Icons.Filled.Info) {
        Text(ALPHA_ABOUT, color = C.text, fontSize = 13.sp, lineHeight = 18.sp)
        Note("Report bugs and wrong numbers to $CREATOR on X.")
        Text("Build " + com.alex.mfppro.BuildConfig.GIT_SHA + " · " + com.alex.mfppro.BuildConfig.BUILD_TIME + (if (com.alex.mfppro.BuildConfig.DEBUG) " · DEBUG (slow)" else ""),
            color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
    Section("Alpha features", ALERTS, Icons.Filled.Info) {
        var flow by remember { mutableStateOf(repo.prefs.showFlowChart) }
        Toggle("Flow chart", "Order-flow bubbles tab on the Trade screen; rough", flow, ALERTS) { flow = it; repo.prefs.showFlowChart = it }
    }
    Section("Made by", ABOUT, Icons.Filled.AlternateEmail) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { openUrl(ctx, CREATOR_X) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(CREATOR, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("x.com/caldriven", color = API, fontSize = 12.sp)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = C.muted, modifier = Modifier.size(20.dp))
        }
    }
    Section("Not affiliated with MFP", RULES, Icons.Filled.Gavel) {
        Text(NOT_AFFILIATED, color = C.text, fontSize = 13.sp, lineHeight = 18.sp)
        Note("Nothing in the app is financial advice. Orders placed here are real orders on your account.")
    }
    Section("Data sources", DATA, Icons.Filled.Public) { Note(DATA_SOURCES) }
    Column(Modifier.padding(vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel)) {
        val at = repo.prefs.termsAcceptedAt
        NavRow("License & terms", if (at > 0) "Private alpha tester license · accepted " +
            java.text.SimpleDateFormat("d MMM yyyy", Locale.US).format(java.util.Date(at)) else "Private alpha tester license", ABOUT, Icons.Filled.Gavel) { open("license") }
        NavRow("Open source licenses", "${OSS.size} libraries and fonts", ABOUT, Icons.Filled.Code, last = true) { open("oss") }
    }
}

@Composable
private fun LicensePage() {
    Section("Alpha tester license", ABOUT, Icons.Filled.Gavel) {
        Text(LICENSE_TEXT, color = C.text, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun OssPage(open: (String) -> Unit) {
    Note("Delta 1kx is built with these open source components. Tap one for its license text.")
    Column(Modifier.padding(vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel)) {
        OSS.forEachIndexed { i, o -> NavRow(o.name, "${o.by} · ${o.license}", ABOUT, null, last = i == OSS.lastIndex) { open("oss:$i") } }
    }
}

@Composable
private fun OssLicensePage(o: OssItem) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val text = remember(o.file) { runCatching { rawText(ctx, o.file) }.getOrDefault("") }
    Section(o.license, ABOUT, Icons.Filled.Code) {
        Text("Copyright ${o.by}", color = C.text, fontSize = 13.sp)
        Text(o.url, color = API, fontSize = 12.sp, modifier = Modifier.clickable { openUrl(ctx, o.url) }.padding(vertical = 4.dp))
        Divider()
        Text(text, color = C.muted, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    }
}

// ---- building blocks --------------------------------------------------

/** Sub-page frame: back arrow and title, then the page. */
@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(8.dp).size(20.dp))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = C.text, maxLines = 1)
    }
    Column(content = content)
}

/** A row that opens a sub-page: icon, title, one-line summary and a chevron. */
@Composable
private fun NavRow(title: String, sub: String, color: Color, icon: ImageVector?, last: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(sub, color = if (icon == null && color != C.text) color else C.muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = C.muted, modifier = Modifier.size(20.dp))
    }
    if (!last) Box(Modifier.padding(start = if (icon != null) 52.dp else 12.dp).fillMaxWidth().height(1.dp).background(C.line))
}

/** Pick one value from a short row of chips, with an optional line under it. */
@Composable
private fun <T : Comparable<T>> ChipPick(label: String, options: List<T>, value: T, color: Color, text: (T) -> String, hint: String? = null, onPick: (T) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(label, color = C.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.padding(top = 5.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // A value set elsewhere that isn't one of the chips still shows, selected.
            val all = if (value in options) options else (options + value).sorted()
            all.forEach { o -> Seg(text(o), o == value, color) { onPick(o) } }
        }
        hint?.let { Text(it, color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp, top = 3.dp)) }
    }
}

/** Card with a tinted icon + title. */
@Composable
private fun Section(title: String, color: Color, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(title.uppercase(), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        }
        content()
    }
}

@Composable
private fun Toggle(title: String, sub: String, checked: Boolean, color: Color, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = C.muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
        AppSwitch(checked, onChange, enabled = enabled, color = color)
    }
}

@Composable
private fun Label(text: String) = Text(text, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Note(text: String) = Text(text, color = C.muted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun Divider() = Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(1.dp).background(C.line))
