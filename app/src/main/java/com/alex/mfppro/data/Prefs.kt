package com.alex.mfppro.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class Prefs(ctx: Context) {
    private val secure = EncryptedSharedPreferences.create(
        ctx,
        "secure",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val plain = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)

    var apiKey: String?
        get() = secure.getString("api_key", null)
        set(v) = secure.edit().putString("api_key", v).apply()

    /** Alpha terms version accepted with the key, and when; kept through a settings reset. */
    val termsAccepted: Int get() = secure.getInt("terms_version", 0)
    val termsAcceptedAt: Long get() = secure.getLong("terms_at", 0L)
    fun acceptTerms(version: Int) =
        secure.edit().putInt("terms_version", version).putLong("terms_at", System.currentTimeMillis()).apply()

    /** Empty means "all accounts". */
    var selectedAccounts: Set<String>
        get() = plain.getStringSet("selected_accounts", emptySet())!!.toSet()
        set(v) = plain.edit().putStringSet("selected_accounts", v).apply()

    var lastMarketId: String?
        get() = plain.getString("last_market", null)
        set(v) = plain.edit().putString("last_market", v).apply()

    var tradeAccountId: String?
        get() = plain.getString("trade_account", null)
        set(v) = plain.edit().putString("trade_account", v).apply()

    var confirmOrders: Boolean
        get() = plain.getBoolean("confirm_orders", true)
        set(v) = plain.edit().putBoolean("confirm_orders", v).apply()

    var slippageBps: Int
        get() = plain.getInt("slippage_bps", 20)
        set(v) = plain.edit().putInt("slippage_bps", v).apply()

    var quickSizes: List<Int>
        get() = plain.getString("quick_sizes", "250,500,1000")!!.split(",").mapNotNull { it.trim().toIntOrNull() }
        set(v) = plain.edit().putString("quick_sizes", v.joinToString(",")).apply()

    fun leverage(marketId: String, default: Int) = plain.getInt("lev_$marketId", default)
    fun setLeverage(marketId: String, v: Int) = plain.edit().putInt("lev_$marketId", v).apply()

    var marginMode: String
        get() = plain.getString("margin_mode", "cross")!!
        set(v) = plain.edit().putString("margin_mode", v).apply()

    var lockEnabled: Boolean
        get() = plain.getBoolean("lock_enabled", true)
        set(v) = plain.edit().putBoolean("lock_enabled", v).apply()

    /** No new trades from lockStart:00 until lockEnd:00, Oslo time. */
    var lockStart: Int
        get() = plain.getInt("lock_start", 0)
        set(v) = plain.edit().putInt("lock_start", v).apply()

    var lockEnd: Int
        get() = plain.getInt("lock_end", 7)
        set(v) = plain.edit().putInt("lock_end", v).apply()

    fun locked() = tradeLocked(lockEnabled, lockStart, lockEnd)

    var assetClasses: String
        get() = plain.getString("asset_classes", "")!!
        set(v) = plain.edit().putString("asset_classes", v).apply()

    var overviewSort: String
        get() = plain.getString("overview_sort", "vol:desc")!!
        set(v) = plain.edit().putString("overview_sort", v).apply()

    var overviewClass: String
        get() = plain.getString("overview_class", "All")!!
        set(v) = plain.edit().putString("overview_class", v).apply()

    /** Display-only payout share applied to positive PnL: 100, 90 or 80. */
    var profitSplit: Int
        get() = plain.getInt("profit_split", 100)
        set(v) = plain.edit().putInt("profit_split", v).apply()

    var alertsEnabled: Boolean
        get() = plain.getBoolean("alerts_enabled", true)
        set(v) = plain.edit().putBoolean("alerts_enabled", v).apply()

    /** Alert when drawdown left drops below this many dollars (0 = off). */
    var alertRoomUsd: Int
        get() = plain.getInt("alert_room_usd", 300)
        set(v) = plain.edit().putInt("alert_room_usd", v).apply()

    /** Alert when drawdown left drops below this share of the allowance (0 = off). */
    var alertRoomPct: Int
        get() = plain.getInt("alert_room_pct", 25)
        set(v) = plain.edit().putInt("alert_room_pct", v).apply()

    var monitorBackground: Boolean
        get() = plain.getBoolean("monitor_background", false)
        set(v) = plain.edit().putBoolean("monitor_background", v).apply()

    var colorBlind: Boolean
        get() = plain.getBoolean("color_blind", false)
        set(v) = plain.edit().putBoolean("color_blind", v).apply()

    var lastInterval: String
        get() = plain.getString("last_interval", "15m")!!
        set(v) = plain.edit().putString("last_interval", v).apply()

    var showOpens: Boolean
        get() = plain.getBoolean("show_opens", true)
        set(v) = plain.edit().putBoolean("show_opens", v).apply()

    var showVp: Boolean
        get() = plain.getBoolean("show_vp", false)
        set(v) = plain.edit().putBoolean("show_vp", v).apply()

    /** Trade screen: show positions from every active account instead of just the trading one. */
    var tradeShowAll: Boolean
        get() = plain.getBoolean("trade_show_all", false)
        set(v) = plain.edit().putBoolean("trade_show_all", v).apply()

    var realtimePnl: Boolean
        get() = plain.getBoolean("realtime_pnl", true)
        set(v) = plain.edit().putBoolean("realtime_pnl", v).apply()

    var showPoc: Boolean
        get() = plain.getBoolean("show_poc", true)
        set(v) = plain.edit().putBoolean("show_poc", v).apply()

    var hideComp: Boolean
        get() = plain.getBoolean("hide_comp", false)
        set(v) = plain.edit().putBoolean("hide_comp", v).apply()
    var confirmCloseAll: Boolean
        get() = plain.getBoolean("confirm_close_all", true)
        set(v) = plain.edit().putBoolean("confirm_close_all", v).apply()

    var showSessions: Boolean
        get() = plain.getBoolean("show_sessions", true)
        set(v) = plain.edit().putBoolean("show_sessions", v).apply()
    var sessionEu: Boolean
        get() = plain.getBoolean("session_eu", false)
        set(v) = plain.edit().putBoolean("session_eu", v).apply()
    var killzones: Boolean
        get() = plain.getBoolean("killzones", true)
        set(v) = plain.edit().putBoolean("killzones", v).apply()

    var largeOnly: Boolean
        get() = plain.getBoolean("large_only", false)
        set(v) = plain.edit().putBoolean("large_only", v).apply()
    var largeTradeMin: Int
        get() = plain.getInt("large_trade_min", 250_000)
        set(v) = plain.edit().putInt("large_trade_min", v).apply()

    var ordersAllMarkets: Boolean
        get() = plain.getBoolean("orders_all_markets", true)
        set(v) = plain.edit().putBoolean("orders_all_markets", v).apply()

    var dashAll: Boolean
        get() = plain.getBoolean("dash_all", false)
        set(v) = plain.edit().putBoolean("dash_all", v).apply()

    // Which indicator toggles appear above the chart, and whether that bar is open.
    var chipOpens: Boolean
        get() = plain.getBoolean("chip_opens", true)
        set(v) = plain.edit().putBoolean("chip_opens", v).apply()
    var chipPivots: Boolean
        get() = plain.getBoolean("chip_pivots", true)
        set(v) = plain.edit().putBoolean("chip_pivots", v).apply()
    var showPivots: Boolean
        get() = plain.getBoolean("show_pivots", false)
        set(v) = plain.edit().putBoolean("show_pivots", v).apply()
    var chipVp: Boolean
        get() = plain.getBoolean("chip_vp", true)
        set(v) = plain.edit().putBoolean("chip_vp", v).apply()
    var chipPoc: Boolean
        get() = plain.getBoolean("chip_poc", true)
        set(v) = plain.edit().putBoolean("chip_poc", v).apply()
    var chipSess: Boolean
        get() = plain.getBoolean("chip_sess", true)
        set(v) = plain.edit().putBoolean("chip_sess", v).apply()
    var indBarOpen: Boolean
        get() = plain.getBoolean("ind_bar_open", true)
        set(v) = plain.edit().putBoolean("ind_bar_open", v).apply()

    // Trade notifications.
    var notifyFills: Boolean
        get() = plain.getBoolean("notify_fills", true)
        set(v) = plain.edit().putBoolean("notify_fills", v).apply()
    var notifyStops: Boolean
        get() = plain.getBoolean("notify_stops", true)
        set(v) = plain.edit().putBoolean("notify_stops", v).apply()
    var notifyBreach: Boolean
        get() = plain.getBoolean("notify_breach", true)
        set(v) = plain.edit().putBoolean("notify_breach", v).apply()
    var notifyMargin: Boolean
        get() = plain.getBoolean("notify_margin", true)
        set(v) = plain.edit().putBoolean("notify_margin", v).apply()
    var marginAlertPct: Int
        get() = plain.getInt("margin_alert_pct", 90)
        set(v) = plain.edit().putInt("margin_alert_pct", v).apply()
    var notifyPnl: Boolean
        get() = plain.getBoolean("notify_pnl", false)
        set(v) = plain.edit().putBoolean("notify_pnl", v).apply()
    var pnlStepUsd: Int
        get() = plain.getInt("pnl_step_usd", 100)
        set(v) = plain.edit().putInt("pnl_step_usd", v).apply()
    var pnlStepPct: Float
        get() = plain.getFloat("pnl_step_pct", 1f)
        set(v) = plain.edit().putFloat("pnl_step_pct", v).apply()
    /** PnL step as % of account equity (0 = off). */
    var pnlStepEq: Float
        get() = plain.getFloat("pnl_step_eq", 0f)
        set(v) = plain.edit().putFloat("pnl_step_eq", v).apply()
    /** PnL step as % of the binding loss allowance, daily or max DD (0 = off). */
    var pnlStepRisk: Float
        get() = plain.getFloat("pnl_step_risk", 0f)
        set(v) = plain.edit().putFloat("pnl_step_risk", v).apply()
    /** "both", "loss" or "profit". */
    var pnlSide: String
        get() = plain.getString("pnl_side", "both") ?: "both"
        set(v) = plain.edit().putString("pnl_side", v).apply()
    /** Count PnL after fees and swap instead of price PnL. */
    var pnlNet: Boolean
        get() = plain.getBoolean("pnl_net", false)
        set(v) = plain.edit().putBoolean("pnl_net", v).apply()
    var pnlPreset: String
        get() = plain.getString("pnl_preset", "custom") ?: "custom"
        set(v) = plain.edit().putString("pnl_preset", v).apply()

    /** Notification rules every account uses unless it has its own. The first read carries the old single settings over. */
    var notifyDefaults: NotifyRules
        get() = plain.getString("notify_rules", null)?.let { runCatching { notifyJson.decodeFromString(NotifyRules.serializer(), it) }.getOrNull() }
            ?: NotifyRules(
                fills = notifyFills, stops = notifyStops, breach = notifyBreach,
                room = alertsEnabled, roomUsd = alertRoomUsd, roomPct = alertRoomPct,
                margin = notifyMargin, marginPct = marginAlertPct,
                pnl = notifyPnl, pnlUsd = if (plain.contains("pnl_step_usd")) pnlStepUsd else 0,
                pnlPct = if (plain.contains("pnl_step_pct")) pnlStepPct else 0f,
                pnlEq = if (plain.contains("pnl_step_eq")) pnlStepEq else 1f,
                pnlRisk = if (plain.contains("pnl_step_risk")) pnlStepRisk else 25f,
                pnlSide = pnlSide, pnlNet = pnlNet,
                pnlPreset = if (plain.contains("pnl_preset")) pnlPreset else "Calm",
            )
        set(v) = plain.edit().putString("notify_rules", notifyJson.encodeToString(NotifyRules.serializer(), v)).apply()

    /** An account's own rules, or null when it follows the defaults. */
    fun notifyOverride(accountId: String): NotifyRules? =
        plain.getString("notify_rules_$accountId", null)?.let { runCatching { notifyJson.decodeFromString(NotifyRules.serializer(), it) }.getOrNull() }

    fun setNotifyOverride(accountId: String, v: NotifyRules?) = plain.edit().apply {
        if (v == null) remove("notify_rules_$accountId") else putString("notify_rules_$accountId", notifyJson.encodeToString(NotifyRules.serializer(), v))
    }.apply()

    fun notifyFor(accountId: String): NotifyRules = notifyOverride(accountId) ?: notifyDefaults

    var pickerSort: String
        get() = plain.getString("picker_sort", "24h") ?: "24h"
        set(v) = plain.edit().putString("picker_sort", v).apply()

    var marketsAnalysis: Boolean
        get() = plain.getBoolean("markets_analysis", false)
        set(v) = plain.edit().putBoolean("markets_analysis", v).apply()
    var corrSet: String
        get() = plain.getString("corr_set", "POS") ?: "POS"
        set(v) = plain.edit().putString("corr_set", v).apply()
    var corrWindow: String
        get() = plain.getString("corr_window", "H1") ?: "H1"
        set(v) = plain.edit().putString("corr_window", v).apply()

    /** Price decimals for limit prices in one market (fewer than its tick allows); null = the tick. */
    /** How new TP and SL exits execute: "market" (trigger, then market) or "limit" (resting reduce-only limit). */
    var tpExecution: String
        get() = plain.getString("tp_exec", "market") ?: "market"
        set(v) = plain.edit().putString("tp_exec", v).apply()
    var slExecution: String
        get() = plain.getString("sl_exec", "market") ?: "market"
        set(v) = plain.edit().putString("sl_exec", v).apply()

    fun priceDecimals(marketId: String): Int? = plain.getInt("dec_$marketId", -1).takeIf { it >= 0 }
    fun setPriceDecimals(marketId: String, v: Int?) = plain.edit().apply { if (v == null) remove("dec_$marketId") else putInt("dec_$marketId", v) }.apply()

    /** Chart layers turned off: pnl (position box), exits (TP/SL), floors (DLL/DD), target, orders, fills. */
    var chartHidden: Set<String>
        get() = plain.getString("chart_hidden", "")!!.split(',').filter { it.isNotBlank() }.toSet()
        set(v) = plain.edit().putString("chart_hidden", v.joinToString(",")).apply()

    /** Trade view account card unfolded (rule lines shown). */
    var accountOpen: Boolean
        get() = plain.getBoolean("account_open", false)
        set(v) = plain.edit().putBoolean("account_open", v).apply()

    /** Analysis view: correlation or special trades. */
    var analysisView: String
        get() = plain.getString("analysis_view", "CORR") ?: "CORR"
        set(v) = plain.edit().putString("analysis_view", v).apply()
    var pairScan: String
        get() = plain.getString("pair_scan", "T20") ?: "T20"
        set(v) = plain.edit().putString("pair_scan", v).apply()
    var pairWindow: String
        get() = plain.getString("pair_window", "H4") ?: "H4"
        set(v) = plain.edit().putString("pair_window", v).apply()

    var fillsAllMarkets: Boolean
        get() = plain.getBoolean("fills_all_markets", true)
        set(v) = plain.edit().putBoolean("fills_all_markets", v).apply()

    var theme: String
        get() = plain.getString("theme", "STANDARD") ?: "STANDARD"
        set(v) = plain.edit().putString("theme", v).apply()

    /** Back to default settings. The API key lives in the encrypted store and is kept. */
    fun resetUi() {
        // Keep what you trade on: account, selection, market and per-market leverage.
        val keep = setOf("trade_account", "selected_accounts", "last_market")
        val e = plain.edit()
        plain.all.keys.filter { it !in keep && !it.startsWith("lev_") }.forEach { e.remove(it) }
        e.commit()
    }

    /** Extra markets added to every correlation preset. */
    /** Multi-chart slots, as market ids; blank slots fall back to the defaults. */
    var multiCharts: List<String>
        get() = plain.getString("multi_charts", "")!!.split(',')
        set(v) = plain.edit().putString("multi_charts", v.joinToString(",")).apply()
    var multiInterval: String
        get() = plain.getString("multi_interval", "15m") ?: "15m"
        set(v) = plain.edit().putString("multi_interval", v).apply()

    var corrExtra: List<String>
        get() = plain.getString("corr_extra", "")!!.split(',').filter { it.isNotBlank() }
        set(v) = plain.edit().putString("corr_extra", v.joinToString(",")).apply()

    var showPosLines: Boolean
        get() = plain.getBoolean("show_pos_lines", true)
        set(v) = plain.edit().putBoolean("show_pos_lines", v).apply()
    var chipPos: Boolean
        get() = plain.getBoolean("chip_pos", true)
        set(v) = plain.edit().putBoolean("chip_pos", v).apply()
}
