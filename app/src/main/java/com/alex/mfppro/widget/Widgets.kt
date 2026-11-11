package com.alex.mfppro.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import com.alex.mfppro.MainActivity
import com.alex.mfppro.MfpApp
import com.alex.mfppro.R
import com.alex.mfppro.data.Candle
import com.alex.mfppro.data.Market
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.Sub
import com.alex.mfppro.data.feedKey
import com.alex.mfppro.ui.fmtPrice
import com.alex.mfppro.ui.fmtUsd
import com.alex.mfppro.ui.ticker
import com.alex.mfppro.ui.withLive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/** Home-screen widget: open PnL up top, one row per position. A row opens its manage screen, Close its confirm screen. */
class PositionWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = Widgets.refresh(ctx, goAsync())
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, o: Bundle) = Widgets.refresh(ctx, goAsync())
}

/** Home-screen widget: 5-minute candles of the largest position with its entry, TP/SL and live PnL. */
class ChartWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = Widgets.refresh(ctx, goAsync())
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, o: Bundle) = Widgets.refresh(ctx, goAsync())
}

/**
 * Draws both widgets from the live streams. While the process is alive (app open, or the background
 * monitor running) a loop pushes new numbers every few seconds, only when they changed. Otherwise
 * Android wakes the widgets every half hour and they redraw from a fresh connection.
 *
 * Nothing here sends an order: every tap opens the app, where the action is confirmed.
 */
object Widgets {
    const val ACTION_LINK = "com.alex.mfppro.LINK"
    private const val SCHEME = "delta1kx"
    private const val CHART_BARS = 72
    private const val CHART_EVERY_MS = 8_000L

    private val lock = Mutex()
    private var posSig = ""
    private var chartSig = ""
    private var chartPushedAt = 0L
    private var sub: Sub? = null
    private var candles: List<Candle> = emptyList()
    private var candlesKey = ""
    private var candlesAt = 0L

    fun start(ctx: Context, repo: Repo) {
        repo.scope.launch {
            while (isActive) {
                delay(4_000)
                runCatching { pass(ctx, repo, force = false) }
            }
        }
    }

    /** Redraw now (widget added, resized, or Android's periodic update). */
    fun refresh(ctx: Context, pending: BroadcastReceiver.PendingResult?) {
        val app = ctx.applicationContext as MfpApp
        val repo = app.repo
        repo.scope.launch {
            try {
                // A process started just for this broadcast has no data yet: give the streams a moment.
                withTimeoutOrNull(8_000) {
                    while (!liveSnap(repo).let { s -> s.ready && s.positions.all { it.mark != null } }) delay(250)
                }
                runCatching { pass(app, repo, force = true) }
            } finally {
                pending?.finish()
            }
        }
    }

    private suspend fun pass(ctx: Context, repo: Repo, force: Boolean) = lock.withLock {
        val mgr = AppWidgetManager.getInstance(ctx)
        val posIds = mgr.getAppWidgetIds(ComponentName(ctx, PositionWidget::class.java))
        val chartIds = mgr.getAppWidgetIds(ComponentName(ctx, ChartWidget::class.java))
        if (chartIds.isEmpty()) hold(repo, null)
        if (posIds.isEmpty() && chartIds.isEmpty()) return@withLock
        val snap = liveSnap(repo)
        // Not connected yet: keep whatever the widget shows rather than blanking it.
        if (!snap.ready) return@withLock
        val col = WColors(repo.prefs)
        val look = repo.prefs.theme + repo.prefs.colorBlind
        if (posIds.isNotEmpty()) {
            val sig = snap.positions.joinToString("|") { "${it.p.id},${fmtUsd(it.pnl)},${fmtRoe(it.roe)},${fmtUsd(it.value)}" } +
                "/${fmtUsd(snap.equity)}/${fmtUsd(snap.room)}/${snap.hasKey}${snap.authFailed}${snap.connected}/$look"
            if (force || sig != posSig) {
                posSig = sig
                for (id in posIds) mgr.updateAppWidget(id, positionsViews(ctx, snap, col, mgr.getAppWidgetOptions(id)))
            }
        }
        if (chartIds.isNotEmpty()) chartPass(ctx, repo, mgr, chartIds, snap, col, look, force)
    }

    /** One tick subscription for the charted market, so its price is live even without a position. */
    private fun hold(repo: Repo, want: Sub?) {
        if (want == sub) return
        sub?.let { repo.market.release(it) }
        want?.let { repo.market.acquire(it) }
        sub = want
    }

    private suspend fun chartPass(
        ctx: Context, repo: Repo, mgr: AppWidgetManager, ids: IntArray, snap: LiveSnap, col: WColors, look: String, force: Boolean,
    ) {
        // The largest position; with nothing open, the market last looked at in the app.
        val lp = snap.positions.firstOrNull()
        val market: Market? = if (lp != null) repo.market(lp.p.marketId) else
            repo.prefs.lastMarketId?.let { repo.market(it) } ?: repo.markets.value.firstOrNull { it.symbol == "BTC" } ?: repo.markets.value.firstOrNull()
        val provider = lp?.p?.provider ?: market?.provider
        val coin = lp?.p?.coin ?: market?.coin
        if (provider.isNullOrEmpty() || coin.isNullOrEmpty()) return
        hold(repo, Sub("ticks", provider, coin))
        val key = feedKey(provider, coin)
        val now = System.currentTimeMillis()
        if (key != candlesKey) { candles = emptyList(); candlesKey = key; candlesAt = 0 }
        // Bars once a minute through the shared history limiter; the live price moves the last one in between.
        if (now - candlesAt > 60_000 && repo.market.live.value) {
            candlesAt = now
            val c = runCatching { repo.market.candleHistory(provider, coin, "5m", CHART_BARS) }.getOrNull().orEmpty()
            if (c.isNotEmpty()) candles = c else candlesAt = now - 45_000
        }
        val mark = repo.market.marks.value[key]
        val bars = candles.withLive(mark)
        val price = mark ?: bars.lastOrNull()?.c
        val exits = lp?.let { repo.exitsOf(it.p) }.orEmpty()
        val tp = exits.firstOrNull { it.exitGroup == "tp" }?.exitPrice
        val sl = exits.firstOrNull { it.exitGroup == "sl" }?.exitPrice
        val sig = "$key/${lp?.p?.id}/${fmtUsd(lp?.pnl)}/${fmtPrice(price, market)}/${bars.size}/${bars.lastOrNull()?.openTime}/$tp/$sl/$look"
        if (!force && (sig == chartSig || now - chartPushedAt < CHART_EVERY_MS)) return
        chartSig = sig; chartPushedAt = now

        val name = lp?.name ?: market?.let { ticker(it.symbol, it.provider) } ?: coin
        val open = when {
            lp != null -> link(ctx, "position", lp.p.id)
            market != null -> link(ctx, "market", market.marketId)
            else -> link(ctx, "home")
        }
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_chart)
            v.setInt(R.id.bg, "setColorFilter", col.panel)
            v.setTextViewText(R.id.sym, name); v.setTextColor(R.id.sym, col.text)
            v.setTextViewText(R.id.sub, listOfNotNull(lp?.side, lp?.let { fmtUsd(it.value) }, fmtPrice(price, market), "5m").joinToString(" · "))
            v.setTextColor(R.id.sub, col.muted)
            v.setTextViewText(R.id.pnl, lp?.let { fmtUsd(it.pnl, true) } ?: "")
            v.setTextColor(R.id.pnl, col.pnl(lp?.pnl))
            v.setTextViewText(R.id.roe, if (lp != null) fmtRoe(lp.roe) else "No position")
            v.setTextColor(R.id.roe, if (lp != null) col.pnl(lp.pnl) else col.muted)
            val o = mgr.getAppWidgetOptions(id)
            val wDp = (o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 320) - 24
            val hDp = (o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 190) - 62
            v.setImageViewBitmap(R.id.chart, chartBitmap(ctx, wDp, hDp, bars, lp, tp, sl, market, col))
            v.setOnClickPendingIntent(R.id.root, open)
            mgr.updateAppWidget(id, v)
        }
    }

    private fun positionsViews(ctx: Context, s: LiveSnap, col: WColors, opts: Bundle): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_positions)
        v.setInt(R.id.bg, "setColorFilter", col.panel)
        v.setTextColor(R.id.label, col.muted)
        v.setTextViewText(R.id.pnl, when {
            !s.hasKey -> "Delta 1kx"
            s.authFailed -> "Key rejected"
            else -> fmtUsd(s.pnl, true)
        })
        v.setTextColor(R.id.pnl, if (s.positions.isEmpty()) col.text else col.pnl(s.pnl))
        v.setTextViewText(R.id.equity, s.equity?.let { "Equity ${fmtUsd(it)}" } ?: ""); v.setTextColor(R.id.equity, col.text)
        v.setTextViewText(R.id.room, s.room?.let { "Room ${fmtUsd(it)}" } ?: ""); v.setTextColor(R.id.room, col.muted)
        v.setOnClickPendingIntent(R.id.header, link(ctx, "home"))

        v.removeAllViews(R.id.rows)
        // Rows that fit under the header at the widget's current height.
        val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        val fit = if (hDp <= 0) 2 else ((hDp - 82) / 42).coerceIn(1, 8)
        val severalAccounts = s.positions.map { it.p.accountId }.distinct().size > 1
        for (lp in s.positions.take(fit)) {
            val r = RemoteViews(ctx.packageName, R.layout.widget_pos_row)
            r.setTextViewText(R.id.row_sym, lp.name); r.setTextColor(R.id.row_sym, col.text)
            r.setTextViewText(R.id.row_sub, listOfNotNull(lp.side, fmtUsd(lp.value), lp.account.takeIf { severalAccounts }).joinToString(" · "))
            r.setTextColor(R.id.row_sub, if (lp.p.isLong) col.up else col.down)
            r.setTextViewText(R.id.row_pnl, fmtUsd(lp.pnl, true)); r.setTextColor(R.id.row_pnl, col.pnl(lp.pnl))
            r.setTextViewText(R.id.row_roe, fmtRoe(lp.roe)); r.setTextColor(R.id.row_roe, col.pnl(lp.pnl))
            r.setTextColor(R.id.row_close, col.down)
            r.setOnClickPendingIntent(R.id.row_main, link(ctx, "position", lp.p.id))
            r.setOnClickPendingIntent(R.id.row_close, link(ctx, "position", lp.p.id, close = true))
            v.addView(R.id.rows, r)
        }
        if (s.positions.isEmpty()) {
            val n = RemoteViews(ctx.packageName, R.layout.widget_note)
            n.setTextViewText(R.id.note, when {
                !s.hasKey -> "Add your API key in the app."
                s.authFailed -> "Check the API key in Settings."
                else -> "No open positions"
            })
            n.setTextColor(R.id.note, col.muted)
            n.setOnClickPendingIntent(R.id.note, link(ctx, "home"))
            v.addView(R.id.rows, n)
        }
        val more = s.positions.size - fit
        v.setTextViewText(R.id.footer, listOfNotNull(
            "+$more more".takeIf { more > 0 },
            LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            "offline".takeIf { s.hasKey && !s.connected },
        ).joinToString(" · "))
        v.setTextColor(R.id.footer, col.muted)
        return v
    }

    /** Opens the app at a position's manage screen, its close confirmation, a market's chart, or the Dashboard. */
    private fun link(ctx: Context, host: String, id: String? = null, close: Boolean = false): PendingIntent {
        val uri = Uri.Builder().scheme(SCHEME).authority(host).apply {
            id?.let { appendPath(it) }
            if (close) appendQueryParameter("do", "close")
        }.build()
        return PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).setAction(ACTION_LINK).setData(uri),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * Candles with the position drawn in: the stretch between entry and the price shaded green or red,
     * a dashed entry line, TP and SL when they are in view, and the live price tagged on the right.
     */
    private fun chartBitmap(
        ctx: Context, wDp: Int, hDp: Int, bars: List<Candle>, lp: LivePos?, tp: Double?, sl: Double?, market: Market?, col: WColors,
    ): Bitmap {
        // Capped so the bitmap stays small enough to hand to the launcher.
        val d = min(ctx.resources.displayMetrics.density, 900f / wDp.coerceAtLeast(1))
        val w = (wDp * d).toInt().coerceAtLeast(64)
        val h = (hDp * d).toInt().coerceAtLeast(48)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        if (bars.isEmpty()) {
            text.color = col.muted; text.typeface = Typeface.DEFAULT; text.textSize = 12f * d
            c.drawText("Chart loads once the app is connected", w / 2f, h / 2f, text)
            return bmp
        }
        val axisW = 58f * d
        val plotW = w - axisW
        var lo = bars.minOf { it.l }
        var hi = bars.maxOf { it.h }
        lp?.p?.entryPrice?.let { lo = min(lo, it); hi = max(hi, it) }
        val pad = (hi - lo).takeIf { it > 0 }?.times(0.08) ?: (hi * 0.001).coerceAtLeast(1e-9)
        lo -= pad; hi += pad
        val top = 8f * d
        val bot = h - 8f * d
        fun y(v: Double) = (top + (hi - v) / (hi - lo) * (bot - top)).toFloat()
        val last = bars.last().c
        val pnlCol = col.pnl(lp?.let { it.pnl ?: it.p.pnlAt(last) })

        lp?.let {
            val ye = y(it.p.entryPrice); val yl = y(last)
            paint.style = Paint.Style.FILL
            paint.color = (pnlCol and 0x00FFFFFF) or 0x26000000
            c.drawRect(0f, min(ye, yl), plotW, max(ye, yl), paint)
        }

        val step = plotW / bars.size
        val half = max(1f, step * 0.32f)
        paint.strokeWidth = max(1f, d)
        bars.forEachIndexed { i, b ->
            val x = step * (i + 0.5f)
            paint.color = if (b.c >= b.o) col.up else col.down
            c.drawLine(x, y(b.h), x, y(b.l), paint)
            val yo = y(b.o); val yc = y(b.c)
            c.drawRect(x - half, min(yo, yc), x + half, max(max(yo, yc), min(yo, yc) + d), paint)
        }

        val tagH = 7.5f * d
        fun level(v: Double?, color: Int, label: String, dashed: Boolean, shift: Float = 0f) {
            if (v == null || v < lo || v > hi) return
            val yy = y(v)
            paint.color = color; paint.strokeWidth = max(1f, d)
            paint.pathEffect = if (dashed) DashPathEffect(floatArrayOf(5f * d, 4f * d), 0f) else null
            c.drawLine(0f, yy, plotW, yy, paint)
            paint.pathEffect = null
            val cy = (yy + shift).coerceIn(tagH, h - tagH)
            c.drawRoundRect(RectF(plotW + 2f * d, cy - tagH, w.toFloat(), cy + tagH), 4f * d, 4f * d, paint)
            // Black on light tags, white on dark ones.
            val lum = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
            text.color = if (lum > 150) Color.BLACK else Color.WHITE
            c.drawText(label, plotW + 2f * d + (axisW - 2f * d) / 2, cy - (text.descent() + text.ascent()) / 2, text)
        }
        level(tp, col.up, "TP", dashed = true)
        level(sl, col.down, "SL", dashed = true)
        lp?.let {
            // The entry tag steps aside when it would sit under the price tag.
            val gap = y(it.p.entryPrice) - y(last)
            val shift = if (kotlin.math.abs(gap) < tagH * 2) (if (gap >= 0) 1 else -1) * (tagH * 2 - kotlin.math.abs(gap)) else 0f
            level(it.p.entryPrice, col.muted, "Entry", dashed = true, shift = shift)
        }
        level(last, if (lp != null) pnlCol.takeIf { it != col.text } ?: col.muted else col.accent, fmtPrice(last, market), dashed = false)
        return bmp
    }
}
