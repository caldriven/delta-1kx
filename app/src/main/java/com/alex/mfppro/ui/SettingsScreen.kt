package com.alex.mfppro.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alex.mfppro.data.Repo
import kotlinx.coroutines.launch

// One colour per section so the page scans at a glance.
private val API = Color(0xFF3B82F6)
private val DISPLAY = Color(0xFFA78BFA)
private val DATA = Color(0xFF22D3EE)
private val ORDERS = Color(0xFF10B981)
private val RULES = Color(0xFFF43F5E)
private val ALERTS = Color(0xFFF59E0B)
private val PNL = Color(0xFF14B8A6)

@Composable
fun SettingsScreen(repo: Repo) {
    val key by repo.apiKey.collectAsStateWithLifecycle()
    val conn by repo.account.conn.collectAsStateWithLifecycle()
    val realtime by repo.realtime.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}

    Column(
        Modifier.fillMaxSize().background(C.bg).imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp).padding(bottom = 24.dp),
    ) {
        Text("Settings", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = C.text, modifier = Modifier.padding(4.dp, 8.dp, 4.dp, 4.dp))

        Section("API key", API, androidx.compose.material.icons.Icons.Filled.Key) {
            if (key != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (key!!.startsWith("fp_test_")) "Sandbox" else "Live") + " key …" + key!!.takeLast(4), color = C.text, fontSize = 14.sp)
                        Spacer(Modifier.height(2.dp))
                        ConnBadge(conn)
                    }
                    Act("Remove key", color = C.down, modifier = Modifier.width(100.dp)) { repo.setApiKey(null); status = null }
                }
            } else {
                AppInput("", input, { input = it.trim() }, Modifier.fillMaxWidth(), secret = true, placeholder = "fp_live_… or fp_test_…")
                Spacer(Modifier.height(8.dp))
                SolidBtn(
                    if (busy) "Checking…" else "Save and test", API, Modifier.fillMaxWidth(),
                    enabled = !busy && (input.startsWith("fp_live_") || input.startsWith("fp_test_")),
                ) {
                    busy = true; status = null
                    scope.launch {
                        runCatching { repo.rest.accounts(input) }
                            .onSuccess { repo.setApiKey(input); input = ""; status = "Connected: ${it.size} account(s)" }
                            .onFailure { status = "Rejected: ${it.message}" }
                        busy = false
                    }
                }
                Note("Stored encrypted on this phone. Needs a Read And Trade key.")
            }
            status?.let { Text(it, color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        }

        Section("Orders", ORDERS, androidx.compose.material.icons.Icons.Filled.TouchApp) {
            var confirm by remember { mutableStateOf(repo.prefs.confirmOrders) }
            Toggle("Confirm orders", "Off = one tap", confirm, ORDERS) { confirm = it; repo.prefs.confirmOrders = it }
            var confirmAll by remember { mutableStateOf(repo.prefs.confirmCloseAll) }
            Toggle("Confirm full closes", "Close, close all, hold to close", confirmAll, ORDERS) { confirmAll = it; repo.prefs.confirmCloseAll = it }
            var slip by remember { mutableStateOf((repo.prefs.slippageBps / 100.0).toString()) }
            var quick by remember { mutableStateOf(repo.prefs.quickSizes.joinToString(", ")) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppInput("Max slippage %", slip, {
                    slip = it
                    it.toDoubleOrNull()?.takeIf { v -> v > 0 && v <= 5 }?.let { v -> repo.prefs.slippageBps = (v * 100).toInt().coerceAtLeast(1) }
                }, Modifier.weight(1f), numeric = true)
                AppInput("Quick sizes $", quick, { quick = it; repo.prefs.quickSizes = it.split(",").mapNotNull { s -> s.trim().toIntOrNull() }.take(4) }, Modifier.weight(1f))
            }
        }

        Section("Risk", RULES, androidx.compose.material.icons.Icons.Filled.Bedtime) {
            var lock by remember { mutableStateOf(repo.prefs.lockEnabled) }
            val locked = repo.prefs.locked()
            // Can't switch the rule off (or move its hours) while it's in force.
            Toggle("Night lock", if (locked) "Active now; locked until it ends" else "No new trades in these Oslo hours; closing works", lock, RULES, enabled = !locked) {
                lock = it; repo.prefs.lockEnabled = it
            }
            if (lock && !locked) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var from by remember { mutableStateOf(repo.prefs.lockStart.toString()) }
                var to by remember { mutableStateOf(repo.prefs.lockEnd.toString()) }
                AppInput("From", from, { from = it; it.toIntOrNull()?.takeIf { h -> h in 0..23 }?.let { h -> repo.prefs.lockStart = h } }, Modifier.weight(1f), numeric = true)
                AppInput("Until", to, { to = it; it.toIntOrNull()?.takeIf { h -> h in 0..23 }?.let { h -> repo.prefs.lockEnd = h } }, Modifier.weight(1f), numeric = true)
            }
            Divider()
            var alerts by remember { mutableStateOf(repo.prefs.alertsEnabled) }
            Toggle("Drawdown alerts", "Notify + vibrate when room runs low", alerts, RULES) {
                alerts = it; repo.prefs.alertsEnabled = it
                if (it && android.os.Build.VERSION.SDK_INT >= 33) askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            if (alerts) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var usd by remember { mutableStateOf(repo.prefs.alertRoomUsd.toString()) }
                var pct by remember { mutableStateOf(repo.prefs.alertRoomPct.toString()) }
                AppInput("Below $ left", usd, { usd = it; repo.prefs.alertRoomUsd = it.toDoubleOrNull()?.toInt() ?: 0 }, Modifier.weight(1f), numeric = true)
                AppInput("Or below % left", pct, { pct = it; repo.prefs.alertRoomPct = it.toDoubleOrNull()?.toInt()?.coerceIn(0, 100) ?: 0 }, Modifier.weight(1f), numeric = true)
            }
            var bg by remember { mutableStateOf(repo.prefs.monitorBackground) }
            Toggle("Watch in background", "Keeps alerts live with the app closed", bg, RULES) {
                bg = it; repo.prefs.monitorBackground = it
                if (it && android.os.Build.VERSION.SDK_INT >= 33) askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                com.alex.mfppro.data.MonitorService.setEnabled(ctx, it)
            }
        }

        Section("Notifications", ALERTS, androidx.compose.material.icons.Icons.Filled.NotificationsActive) {
            fun ask() { if (android.os.Build.VERSION.SDK_INT >= 33) askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
            var nf by remember { mutableStateOf(repo.prefs.notifyFills) }
            Toggle("Fills", "Every open and close, with PnL", nf, ALERTS) { nf = it; repo.prefs.notifyFills = it; if (it) ask() }
            var ns by remember { mutableStateOf(repo.prefs.notifyStops) }
            Toggle("Stop loss hit", "Loud alert when a stop fills", ns, ALERTS) { ns = it; repo.prefs.notifyStops = it; if (it) ask() }
            var nb by remember { mutableStateOf(repo.prefs.notifyBreach) }
            Toggle("Breach / liquidation", "Account failed, no room left, liquidated", nb, ALERTS) { nb = it; repo.prefs.notifyBreach = it; if (it) ask() }
            var nm by remember { mutableStateOf(repo.prefs.notifyMargin) }
            Toggle("Margin call", "Margin used above the % below", nm, ALERTS) { nm = it; repo.prefs.notifyMargin = it; if (it) ask() }
            if (nm) {
                var mp by remember { mutableStateOf(repo.prefs.marginAlertPct.toString()) }
                AppInput("Margin used %", mp, { mp = it; it.toIntOrNull()?.takeIf { v -> v in 10..100 }?.let { v -> repo.prefs.marginAlertPct = v } }, Modifier.fillMaxWidth(), numeric = true)
            }
            var np by remember { mutableStateOf(repo.prefs.notifyPnl) }
            Toggle("PnL steps", "Each position, every $ step or % step", np, ALERTS) { np = it; repo.prefs.notifyPnl = it; if (it) ask() }
            if (np) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var su by remember { mutableStateOf(repo.prefs.pnlStepUsd.toString()) }
                var sp by remember { mutableStateOf(repo.prefs.pnlStepPct.toString()) }
                AppInput("Every $ (0 = off)", su, { su = it; repo.prefs.pnlStepUsd = it.toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 0 }, Modifier.weight(1f), numeric = true)
                AppInput("Every % (0 = off)", sp, { sp = it; repo.prefs.pnlStepPct = it.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f }, Modifier.weight(1f), numeric = true)
            }
        }

        Section("Chart", DATA, androidx.compose.material.icons.Icons.Filled.Bolt) {
            Toggle("Real-time position value", "Live order book instead of slower mark", realtime, DATA) { repo.setRealtime(it) }
            var sess by remember { mutableStateOf(repo.prefs.showSessions) }
            Toggle("Session shading", "Asia and US hours, open/close lines", sess, DATA) { sess = it; repo.prefs.showSessions = it }
            var eu by remember { mutableStateOf(repo.prefs.sessionEu) }
            Toggle("EU session", "Shade London hours too", eu, DATA) { eu = it; repo.prefs.sessionEu = it }
            var kz by remember { mutableStateOf(repo.prefs.killzones) }
            Toggle("Killzones", "Asia, London, NY AM/PM strip on top", kz, DATA) { kz = it; repo.prefs.killzones = it }
        }

        Section("Chart indicators", DATA, androidx.compose.material.icons.Icons.Filled.Build) {
            Note("Which toggles show in the bar above the chart.")
            var o by remember { mutableStateOf(repo.prefs.chipOpens) }
            Toggle("D/W/M opens", "Daily, weekly, monthly open lines", o, DATA) { o = it; repo.prefs.chipOpens = it }
            var v by remember { mutableStateOf(repo.prefs.chipVp) }
            Toggle("Volume profile", "Session VP with value area", v, DATA) { v = it; repo.prefs.chipVp = it }
            var pc by remember { mutableStateOf(repo.prefs.chipPoc) }
            Toggle("Session POC", "Busiest price of the session", pc, DATA) { pc = it; repo.prefs.chipPoc = it }
            var se by remember { mutableStateOf(repo.prefs.chipSess) }
            Toggle("Sessions", "Asia/US shading, killzones, opens/closes", se, DATA) { se = it; repo.prefs.chipSess = it }
        }

        Section("Display", DISPLAY, androidx.compose.material.icons.Icons.Filled.Palette) {
            Toggle("Colour-blind mode", "Blue up, orange down", Palette.colorBlind, DISPLAY) {
                Palette.colorBlind = it; repo.prefs.colorBlind = it
            }
            var hideComp by remember { mutableStateOf(repo.prefs.hideComp) }
            Toggle("Hide competition accounts", "Out of Positions, Accounts and totals", hideComp, DISPLAY) {
                hideComp = it; repo.prefs.hideComp = it; Comp.hide = it
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Profit split", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Your share of positive PnL", color = C.muted, fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (v in listOf(100, 90, 80)) Seg("$v%", Split.pct == v, PNL) { Split.pct = v; repo.prefs.profitSplit = v }
                }
            }
        }
    }
}

/** Card with a tinted icon + title. */
@Composable
private fun Section(title: String, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
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
