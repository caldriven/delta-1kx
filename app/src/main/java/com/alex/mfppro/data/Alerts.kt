package com.alex.mfppro.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.alex.mfppro.MainActivity
import com.alex.mfppro.MfpApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

private const val CH_ALERTS = "risk_alerts"
private const val CH_MONITOR = "monitor"
private const val CH_TRADES = "trade_events"

/**
 * Drawdown alerts: notifies once when an account's room to the nearest floor
 * drops below the user's dollar or percentage threshold, and re-arms when it
 * recovers 20 % above it.
 */
class AlertMonitor(private val ctx: Context, private val repo: Repo) {
    private val fired = HashSet<String>()

    init {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Drawdown alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true); vibrationPattern = longArrayOf(0, 400, 200, 400)
        })
        nm.createNotificationChannel(NotificationChannel(CH_MONITOR, "Background monitor", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_TRADES, "Fills and PnL", NotificationManager.IMPORTANCE_HIGH))
        repo.scope.launch {
            while (isActive) {
                delay(2_000)
                if (repo.prefs.alertsEnabled) check()
                runCatching { checkAccounts() }
            }
        }
        // Fills come straight off the account stream.
        repo.scope.launch {
            repo.account.fills.collect { o ->
                val f = runCatching { restJson.decodeFromJsonElement(Fill.serializer(), o) }.getOrNull() ?: return@collect
                runCatching { onFill(f) }
            }
        }
    }

    private val status = HashMap<String, String>()
    private val breached = HashSet<String>()
    private val marginHigh = HashSet<String>()
    // Last PnL step seen per position, in dollars and in percent of value.
    private val usdStep = HashMap<String, Long>()
    private val pctStep = HashMap<String, Long>()

    private fun onFill(f: Fill) {
        val p = repo.prefs
        val name = repo.account.accounts.value[f.accountId]?.let { it.account.name.ifBlank { "#" + it.account.accountNumber } } ?: ""
        val sym = f.symbol + "USDT"
        when {
            f.type == "liquidation" || f.type == "close_out" -> if (p.notifyBreach)
                trade(f.id.hashCode(), "${if (f.type == "liquidation") "Liquidated" else "Closed out"}: $sym", "${fmtSigned(f.realizedPnl)} · $name", alarm = true)
            f.type.startsWith("stop") && f.reduceOnly -> if (p.notifyStops)
                trade(f.id.hashCode(), "Stop loss hit: $sym", "${fmtSigned(f.realizedPnl - f.fee)} at ${px(f.fillPrice)} · $name", alarm = true)
            f.realizedPnl != 0.0 -> if (p.notifyFills)
                trade(f.id.hashCode(), "Closed $sym ${fmtSigned(f.realizedPnl - f.fee)}", "${fmt(f.notional)} at ${px(f.fillPrice)} · $name")
            else -> if (p.notifyFills)
                trade(f.id.hashCode(), "Filled: ${if (f.side == "buy") "Long" else "Short"} $sym ${fmt(f.notional)}", "at ${px(f.fillPrice)} · $name")
        }
    }

    /** Breach, margin and PnL-step checks, every couple of seconds. */
    private fun checkAccounts() {
        val p = repo.prefs
        val marks = repo.market.marks.value
        for (s in repo.account.accounts.value.values) {
            val id = s.account.id
            val name = s.account.name.ifBlank { "#" + s.account.accountNumber }
            // Breach: the account flips to failed, or live room goes to zero.
            val prev = status.put(id, s.account.status)
            val r = riskOf(s, marks)
            val gone = (prev == "active" && s.account.status == "failed") || (s.account.status == "active" && (r.room ?: 1.0) <= 0)
            if (gone && id !in breached) {
                breached += id
                if (p.notifyBreach) trade(id.hashCode() + 1, "Account breached: $name", "Equity ${fmt(r.equity)}", alarm = true)
            } else if (!gone && s.account.status == "active" && (r.room ?: 1.0) > 0) breached -= id
            if (s.account.status != "active") continue
            // Margin: share of equity tied up as position margin.
            val margin = s.positions.values.sumOf { it.marginAt() }
            val used = if (r.equity > 0) margin / r.equity * 100 else 0.0
            if (used >= p.marginAlertPct && id !in marginHigh) {
                marginHigh += id
                if (p.notifyMargin) trade(id.hashCode() + 2, "Margin ${"%.0f".format(used)}% used: $name", "${fmt(margin)} of ${fmt(r.equity)} equity", alarm = true)
            } else if (used < p.marginAlertPct * 0.9) marginHigh -= id
            // PnL steps per position.
            for (pos in s.positions.values) {
                val m = marks[pos.feedKey] ?: continue
                val pnl = pos.pnlAt(m)
                val value = pos.size * m
                val pct = if (value > 0) pnl / (pos.size * pos.entryPrice) * 100 else 0.0
                val su = p.pnlStepUsd.toDouble()
                val sp = p.pnlStepPct.toDouble()
                val bu = if (su > 0) kotlin.math.floor(pnl / su).toLong() else 0L
                val bp = if (sp > 0) kotlin.math.floor(pct / sp).toLong() else 0L
                // First sight records the step. Afterwards notify only on a step at least one beyond the last
                // notified one (in either direction), so PnL wobbling across a boundary doesn't spam.
                val lastU = usdStep[pos.id]; val lastP = pctStep[pos.id]
                if (lastU == null) usdStep[pos.id] = bu
                if (lastP == null) pctStep[pos.id] = bp
                val newU = lastU != null && su > 0 && (bu > lastU || bu < lastU - 1)
                val newP = lastP != null && sp > 0 && (bp > lastP || bp < lastP - 1)
                if (newU) usdStep[pos.id] = bu
                if (newP) pctStep[pos.id] = bp
                if (p.notifyPnl && (newU || newP)) {
                    trade(pos.id.hashCode(), "${pos.symbol} ${if (pos.isLong) "long" else "short"} ${fmtSigned(pnl)}",
                        String.format(Locale.US, "%+.2f%% · value %s · %s", pct, fmt(value), name))
                }
            }
        }
        val open = repo.account.accounts.value.values.flatMap { it.positions.keys }.toSet()
        usdStep.keys.retainAll(open); pctStep.keys.retainAll(open)
    }

    private fun trade(id: Int, title: String, text: String, alarm: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(ctx).notify(
            id,
            NotificationCompat.Builder(ctx, if (alarm) CH_ALERTS else CH_TRADES)
                .setSmallIcon(if (alarm) android.R.drawable.stat_sys_warning else android.R.drawable.stat_notify_more)
                .setContentTitle(title).setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_EVENT)
                .setContentIntent(open).setAutoCancel(true).build(),
        )
    }

    private fun fmtSigned(v: Double) = (if (v >= 0) "+" else "-") + fmt(kotlin.math.abs(v))
    private fun px(v: Double) = if (v >= 1000) String.format(Locale.US, "%,.2f", v) else String.format(Locale.US, "%.4f", v)

    private fun check() {
        val usd = repo.prefs.alertRoomUsd.toDouble()
        val pct = repo.prefs.alertRoomPct / 100.0
        val marks = repo.market.marks.value
        for (s in repo.account.accounts.value.values) {
            if (s.account.status != "active") continue
            val r = riskOf(s, marks)
            val room = r.room ?: continue
            val frac = r.roomFraction
            val low = (usd > 0 && room < usd) || (pct > 0 && frac != null && frac < pct)
            val id = s.account.id
            if (low && id !in fired) {
                fired += id
                val which = if (r.bindingIsDaily) "daily loss" else "max drawdown"
                notify(
                    id.hashCode(),
                    "${s.account.name}: ${fmt(room)} drawdown left",
                    "Equity ${fmt(r.equity)} · $which floor ${fmt(r.nearestFloor)} · ${s.positions.size} open",
                )
            } else if (!low && id in fired) {
                val clearUsd = usd <= 0 || room > usd * 1.2
                val clearPct = pct <= 0 || frac == null || frac > pct * 1.2
                if (clearUsd && clearPct) fired -= id
            }
        }
    }

    private fun notify(id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(ctx).notify(
            id,
            NotificationCompat.Builder(ctx, CH_ALERTS)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title).setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(open).setAutoCancel(true).build(),
        )
    }

    private fun fmt(v: Double?) = if (v == null) "—" else String.format(Locale.US, "$%,.2f", v)

    companion object {
        fun monitorNotification(ctx: Context) = NotificationCompat.Builder(ctx, CH_MONITOR)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Delta 1kx is watching your accounts")
            .setContentText("Alerts stay live in the background")
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }
}

/** Keeps the process and sockets alive in the background so alerts keep firing. */
class MonitorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, 42, AlertMonitor.monitorNotification(this),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        (application as MfpApp).repo.setBackgroundMonitoring(true)
        return START_STICKY
    }

    override fun onDestroy() {
        (application as MfpApp).repo.setBackgroundMonitoring(false)
        super.onDestroy()
    }

    companion object {
        fun setEnabled(ctx: Context, on: Boolean) {
            val i = Intent(ctx, MonitorService::class.java)
            if (on) ContextCompat.startForegroundService(ctx, i) else ctx.stopService(i)
        }
    }
}
