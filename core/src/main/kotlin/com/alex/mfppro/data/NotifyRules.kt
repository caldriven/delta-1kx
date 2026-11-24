package com.alex.mfppro.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What one account notifies about. Every account uses the default set unless
 * it has its own; [muted] silences an account completely.
 */
@Serializable
data class NotifyRules(
    val muted: Boolean = false,
    // Trades.
    val fills: Boolean = true,
    val stops: Boolean = true,
    /** Price alerts set on MFP (they belong to the user, so only the default set's value counts). */
    val priceAlerts: Boolean = true,
    // Risk.
    val breach: Boolean = true,
    val room: Boolean = true,
    /** Drawdown left below this many dollars (0 = off). */
    val roomUsd: Int = 300,
    /** Or below this share of the allowance (0 = off). */
    val roomPct: Int = 25,
    val margin: Boolean = true,
    val marginPct: Int = 90,
    // PnL steps per position; 0 turns a measure off.
    val pnl: Boolean = false,
    val pnlUsd: Int = 0,
    /** % of position cost. */
    val pnlPct: Float = 0f,
    /** % of account equity. */
    val pnlEq: Float = 1f,
    /** % of the binding loss allowance; losses only. */
    val pnlRisk: Float = 25f,
    /** "both", "loss" or "profit". */
    val pnlSide: String = "both",
    /** After fees and swap instead of price PnL. */
    val pnlNet: Boolean = false,
    /** Preset name, or "custom". */
    val pnlPreset: String = "Calm",
) {
    /** Applies a PnL preset's step values. */
    fun withPreset(p: PnlPreset) = copy(pnlUsd = p.usd, pnlPct = p.pct, pnlEq = p.eq, pnlRisk = p.risk, pnlPreset = p.name)
}

/** One-tap starting points for the default rules; anything can be tuned afterwards. */
data class NotifyProfile(val name: String, val about: String, val apply: (NotifyRules) -> NotifyRules)

val NOTIFY_PROFILES = listOf(
    NotifyProfile("Essentials", "Stops, breaches, low drawdown, margin calls") {
        it.copy(muted = false, fills = false, stops = true, priceAlerts = true, breach = true, room = true, margin = true, pnl = false)
    },
    NotifyProfile("Standard", "Essentials plus every fill and calm PnL steps") {
        it.copy(muted = false, fills = true, stops = true, priceAlerts = true, breach = true, room = true, margin = true, pnl = true)
            .withPreset(PNL_PRESETS.first { p -> p.name == "Calm" })
    },
    NotifyProfile("Everything", "All of it, with frequent PnL steps") {
        it.copy(muted = false, fills = true, stops = true, priceAlerts = true, breach = true, room = true, margin = true, pnl = true)
            .withPreset(PNL_PRESETS.first { p -> p.name == "Active" })
    },
)

/** The profile [r] matches exactly, if any. */
fun NotifyRules.profile(): String? = NOTIFY_PROFILES.firstOrNull { it.apply(this) == this }?.name

/** Ready-made PnL step sets, from few alerts to many. */
data class PnlPreset(val name: String, val about: String, val usd: Int, val pct: Float, val eq: Float, val risk: Float)

val PNL_PRESETS = listOf(
    PnlPreset("Calm", "Every 1% of equity, and each quarter of your loss limit", 0, 0f, 1f, 25f),
    PnlPreset("Normal", "Every 0.5% of equity, 2% of position, 10% of loss limit", 0, 2f, 0.5f, 10f),
    PnlPreset("Active", "Every 0.25% of equity, 1% of position, 5% of loss limit", 0, 1f, 0.25f, 5f),
    PnlPreset("Scalp", "Every 0.1% of equity, 0.5% of position, 2.5% of loss limit", 0, 0.5f, 0.1f, 2.5f),
)

internal val notifyJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }
