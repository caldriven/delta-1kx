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
                // Every 2 s on screen; every 5 s in the background, where it only feeds notifications.
                delay(if (repo.visible.value) 2_000 else 5_000)
                runCatching { check() }
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
    // Last PnL step seen, keyed "kind|positionId" (u = $, p = % of position, e = % of equity, r = % of loss limit).
    private val steps = HashMap<String, Long>()

    private fun onFill(f: Fill) {
        val p = repo.prefs.notifyFor(f.accountId)
        if (p.muted) return
        val name = repo.account.accounts.value[f.accountId]?.let { it.account.name.ifBlank { "#" + it.account.accountNumber } } ?: ""
        val sym = com.alex.mfppro.ui.ticker(f.symbol, f.provider)
        when {
            f.type == "liquidation" || f.type == "close_out" -> if (p.breach)
                trade(f.id.hashCode(), "${if (f.type == "liquidation") "Liquidated" else "Closed out"}: $sym", "${fmtSigned(f.realizedPnl)} · $name", alarm = true)
            f.type.startsWith("stop") && f.reduceOnly -> if (p.stops)
                trade(f.id.hashCode(), "Stop loss hit: $sym", "${fmtSigned(f.realizedPnl - f.fee)} at ${px(f.fillPrice)} · $name", alarm = true)
            f.realizedPnl != 0.0 -> if (p.fills)
                trade(f.id.hashCode(), "Closed $sym ${fmtSigned(f.realizedPnl - f.fee)}", "${fmt(f.notional)} at ${px(f.fillPrice)} · $name")
            else -> if (p.fills)
                trade(f.id.hashCode(), "Filled: ${if (f.side == "buy") "Long" else "Short"} $sym ${fmt(f.notional)}", "at ${px(f.fillPrice)} · $name")
        }
    }

    /** Breach, margin and PnL-step checks, every couple of seconds. */
    private fun checkAccounts() {
        val marks = repo.market.marks.value
        for (s in repo.account.accounts.value.values) {
            val id = s.account.id
            val p = repo.prefs.notifyFor(id)
            val name = s.account.name.ifBlank { "#" + s.account.accountNumber }
            // Breach: the account flips to failed, or live room goes to zero.
            val prev = status.put(id, s.account.status)
            val r = riskOf(s, marks)
            val gone = (prev == "active" && s.account.status == "failed") || (s.account.status == "active" && (r.room ?: 1.0) <= 0)
            if (gone && id !in breached) {
                breached += id
                if (p.breach && !p.muted) trade(id.hashCode() + 1, "Account breached: $name", "Equity ${fmt(r.equity)}", alarm = true)
            } else if (!gone && s.account.status == "active" && (r.room ?: 1.0) > 0) breached -= id
            if (s.account.status != "active" || p.muted) continue
            // Margin: share of equity tied up as position margin.
            val margin = s.positions.values.sumOf { it.marginAt() }
            val used = if (r.equity > 0) margin / r.equity * 100 else 0.0
            if (used >= p.marginPct && id !in marginHigh) {
                marginHigh += id
                if (p.margin) trade(id.hashCode() + 2, "Margin ${"%.0f".format(used)}% used: $name", "${fmt(margin)} of ${fmt(r.equity)} equity", alarm = true)
            } else if (used < p.marginPct * 0.9) marginHigh -= id
            // PnL steps per position, each measured against its own base: dollars, position cost,
            // account equity, and the binding loss allowance.
            val allowance = (if (r.bindingIsDaily) r.dailyAllowance else r.ddAllowance)?.takeIf { it > 0 }
            for (pos in s.positions.values) {
                val m = marks[pos.feedKey] ?: continue
                val pnl = if (p.pnlNet) pos.netAt(m) else pos.pnlAt(m)
                val value = pos.size * m
                val cost = pos.size * pos.entryPrice
                val pct = if (cost > 0) pnl / cost * 100 else 0.0
                val eqPct = if (r.equity > 0) pnl / r.equity * 100 else 0.0
                val riskPct = allowance?.let { pnl / it * 100 }
                val hits = listOfNotNull(
                    step("u", pos.id, pnl, p.pnlUsd.toDouble())?.let { "${fmtSigned(it)} step" },
                    step("p", pos.id, pct, p.pnlPct.toDouble())?.let { String.format(Locale.US, "%+.2f%% of position", it) },
                    step("e", pos.id, eqPct, p.pnlEq.toDouble())?.let { String.format(Locale.US, "%+.2f%% of equity", it) },
                    // Loss limit: only losses use it up, so this step only speaks when the position is red.
                    riskPct?.let { step("r", pos.id, it, p.pnlRisk.toDouble()) }?.takeIf { it < 0 && allowance != null }?.let {
                        // Measured against the limit's size, which says nothing about a breach on its own
                        // (today's gains add room), so the real room left goes with it.
                        val used = -it
                        String.format(Locale.US, "Loss is %.0f%% of the %s %s limit · %s room left", used, fmt(allowance!!),
                            if (r.bindingIsDaily) "daily" else "max DD", fmt(r.room))
                    },
                )
                val sideOk = when (p.pnlSide) { "loss" -> pnl < 0; "profit" -> pnl > 0; else -> true }
                if (p.pnl && hits.isNotEmpty() && sideOk) {
                    trade(pos.id.hashCode(), "${pos.symbol} ${if (pos.isLong) "long" else "short"} ${fmtSigned(pnl)}${if (p.pnlNet) " net" else ""}",
                        String.format(Locale.US, "%s · %+.2f%% · equity %+.2f%% · value %s · %s", hits.first(), pct, eqPct, fmt(value), name))
                }
            }
        }
        val open = repo.account.accounts.value.values.flatMap { it.positions.keys }.toSet()
        steps.keys.retainAll { it.substringAfter('|') in open }
    }

    /**
     * Crossed step boundary for [v] in units of [size], or null. First sight only records the step.
     * Afterwards a step at least one beyond the last notified one (either direction) fires, so PnL
     * wobbling across a boundary doesn't spam. Returns the boundary value that was crossed.
     */
    private fun step(kind: String, id: String, v: Double, size: Double): Double? {
        if (size <= 0) return null
        val key = "$kind|$id"
        val b = kotlin.math.floor(v / size).toLong()
        val last = steps[key] ?: run { steps[key] = b; return null }
        if (b > last || b < last - 1) { steps[key] = b; return (if (b > last) b else b + 1) * size }
        return null
    }

    private fun trade(id: Int, title: String, text: String, alarm: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(ctx).notify(
            id,
            NotificationCompat.Builder(ctx, if (alarm) CH_ALERTS else CH_TRADES)
                .setSmallIcon(com.alex.mfppro.R.drawable.ic_stat_delta1)
                // A label and colour per kind, so a risk alarm never reads like a routine fill.
                .setSubText(if (alarm) "Risk alert" else "Trade").setColor(if (alarm) 0xFFEF4444.toInt() else 0xFFF59E0B.toInt())
                .setContentTitle(title).setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_EVENT)
                .setContentIntent(open).setAutoCancel(true).build(),
        )
    }

    private fun fmtSigned(v: Double) = (if (v >= 0) "+" else "-") + fmt(kotlin.math.abs(v))
    private fun px(v: Double) = com.alex.mfppro.ui.fmtPrice(v)

    private fun check() {
        val marks = repo.market.marks.value
        for (s in repo.account.accounts.value.values) {
            if (s.account.status != "active") continue
            val rules = repo.prefs.notifyFor(s.account.id)
            if (rules.muted || !rules.room) { fired -= s.account.id; continue }
            val usd = rules.roomUsd.toDouble()
            val pct = rules.roomPct / 100.0
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
                .setSmallIcon(com.alex.mfppro.R.drawable.ic_stat_delta1)
                .setSubText("Drawdown alert").setColor(0xFFEF4444.toInt())
                .setContentTitle(title).setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(open).setAutoCancel(true).build(),
        )
    }

    private fun fmt(v: Double?) = com.alex.mfppro.ui.fmtUsd(v)

    companion object {
        /**
         * The ongoing monitor notification, carrying live numbers: open PnL in the title, one line per
         * position when expanded, equity and drawdown room underneath. Silent on every update.
         */
        fun monitorNotification(ctx: Context, t: com.alex.mfppro.widget.LiveText) = NotificationCompat.Builder(ctx, CH_MONITOR)
            .setSmallIcon(com.alex.mfppro.R.drawable.ic_stat_delta1)
            .setContentTitle(t.title)
            .setContentText(t.text)
            .setSubText("Live")
            .apply {
                // Green or red with the PnL (the user's theme colours aside: the shade has its own look).
                t.pnl?.let { setColor(if (it >= 0) 0xFF20B26C.toInt() else 0xFFEF454A.toInt()) }
                if (t.lines.isNotEmpty()) setStyle(NotificationCompat.InboxStyle().also { st ->
                    t.lines.forEach(st::addLine)
                    if (t.summary.isNotEmpty()) st.setSummaryText(t.summary)
                })
            }
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }
}

/** Keeps the process and sockets alive in the background so alerts keep firing. */
class MonitorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    private var liveJob: kotlinx.coroutines.Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = (application as MfpApp).repo
        var shown = com.alex.mfppro.widget.liveText(com.alex.mfppro.widget.liveSnap(repo))
        ServiceCompat.startForeground(
            this, 42, AlertMonitor.monitorNotification(this, shown),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        repo.setBackgroundMonitoring(true)
        // Live numbers in the notification: re-posted (silently) only when its words changed.
        if (liveJob == null) liveJob = repo.scope.launch {
            while (isActive) {
                delay(3_000)
                val now = runCatching { com.alex.mfppro.widget.liveText(com.alex.mfppro.widget.liveSnap(repo)) }.getOrNull() ?: continue
                if (now == shown) continue
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@MonitorService, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) continue
                shown = now
                runCatching { NotificationManagerCompat.from(this@MonitorService).notify(42, AlertMonitor.monitorNotification(this@MonitorService, now)) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        liveJob?.cancel(); liveJob = null
        (application as MfpApp).repo.setBackgroundMonitoring(false)
        super.onDestroy()
    }

    companion object {
        fun setEnabled(ctx: Context, on: Boolean) {
            val i = Intent(ctx, MonitorService::class.java)
            // Android blocks foreground-service starts while the app is in the background (e.g. a process
            // started by an update); the activity starts it again when the app comes to the front.
            if (on) runCatching { ContextCompat.startForegroundService(ctx, i) }.onFailure { android.util.Log.w("Monitor", "start deferred: ${it.message}") }
            else ctx.stopService(i)
        }
    }
}
