package com.alex.mfppro.data

import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Live rule position of one account: equity from streamed balance plus
 * mark-priced open PnL, measured against the floors from the last REST
 * risk snapshot. Daily loss is measured from start-of-day equity at
 * midnight New York with an allowance of a percentage of starting balance.
 */
data class RiskView(
    val equity: Double,
    val upnl: Double,
    val marksMissing: Boolean,
    val dailyFloor: Double?,
    val ddFloor: Double?,
    val dailyAllowance: Double?,
    val ddAllowance: Double?,
    val target: Double?,
) {
    val dailyRoom get() = dailyFloor?.let { equity - it }
    val ddRoom get() = ddFloor?.let { equity - it }
    /** Room before the nearest floor; this is what a loss can eat before a breach. */
    val room get() = listOfNotNull(dailyRoom, ddRoom).minOrNull()
    val nearestFloor get() = listOfNotNull(dailyFloor, ddFloor).maxOrNull()
    val bindingIsDaily get() = dailyFloor != null && (ddFloor == null || dailyFloor >= ddFloor)
    val startOfDayEquity get() = if (dailyFloor != null && dailyAllowance != null) dailyFloor + dailyAllowance else null
    val todayPnl get() = startOfDayEquity?.let { equity - it }
    val toTarget get() = target?.let { (it - equity).coerceAtLeast(0.0) }
    /** Fraction of the binding allowance still left, 0..1. */
    val roomFraction: Double?
        get() {
            val r = room ?: return null
            val full = (if (bindingIsDaily) dailyAllowance else ddAllowance) ?: return null
            return if (full > 0) (r / full).coerceIn(0.0, 1.0) else null
        }
}

/** Accounts the trading policy marks as competition entries; filled in by [Repo] as policies load. */
object Competitions {
    @Volatile var ids: Set<String> = emptySet()
}

fun isCompetition(a: Account) = a.id in Competitions.ids || a.name.contains("competition", true)

fun hasPassTarget(a: Account) = a.stage != "funded" && !isCompetition(a)

fun riskOf(s: AccountState, marks: Map<String, Double>): RiskView {
    var missing = false
    val upnl = s.positions.values.sumOf { p -> marks[p.feedKey]?.let(p::pnlAt) ?: run { missing = true; 0.0 } }
    val a = s.account
    val r = a.risk
    val req = r?.requirements
    // Only an evaluation has a pass target; funded and competition accounts have nothing to pass.
    val target = pctToFraction(req?.profitTargetPct)?.takeIf { hasPassTarget(a) }?.let { a.startingBalance * (1 + it) }
    return RiskView(
        equity = a.balance + upnl,
        upnl = upnl,
        marksMissing = missing,
        dailyFloor = r?.dailyLossFloor,
        ddFloor = r?.maxDrawdownFloor,
        dailyAllowance = pctToFraction(req?.dailyLossPct)?.times(a.startingBalance),
        ddAllowance = pctToFraction(req?.maxDrawdownPct)?.times(a.startingBalance),
        target = target,
    )
}

/**
 * Price at which this position alone would take the account to its nearest
 * floor, assuming everything else stays where it is.
 */
fun breachPrice(p: Position, mark: Double, room: Double): Double? {
    if (p.size <= 0) return null
    val move = room / p.size
    return if (p.isLong) (mark - move).takeIf { it > 0 } else mark + move
}

/**
 * Elite plans boost leverage on a few index and commodity markets; every other
 * market keeps its listed maximum. Per the Elite guide (leverage and exposure).
 */
private val ELITE_LEVERAGE = mapOf(
    "NAS100" to 40.0, "XYZ100" to 40.0, "SP500" to 40.0, "US500" to 40.0,
    "US30" to 25.0, "XAU" to 20.0, "GOLD" to 20.0, "CL" to 10.0, "BRENTOIL" to 10.0,
)

/** Elite accounts: named so, flagged in their rules, or with Elite's 40x-of-start total exposure cap. */
fun isElite(a: Account, policy: TradingPolicy?): Boolean =
    a.name.contains("elite", true) ||
        policy?.accountRules?.any { it.id.contains("elite", true) } == true ||
        (a.startingBalance > 0 && policy?.maximumTotalNotionalUsd?.let { kotlin.math.abs(it / a.startingBalance - 40) < 0.5 } == true)

/** Highest leverage this account may use on [m]. */
fun maxLeverage(m: Market, a: Account?, policy: TradingPolicy?): Int {
    val boost = if (a != null && isElite(a, policy)) ELITE_LEVERAGE[m.symbol.uppercase()] else null
    return maxOf(m.maxLeverage, boost ?: 0.0).toInt().coerceAtLeast(1)
}

val OSLO: ZoneId = ZoneId.of("Europe/Oslo")

/** True while the user's own "no new trades" window (Oslo time) is active. */
fun tradeLocked(enabled: Boolean, startHour: Int, endHour: Int, now: ZonedDateTime = ZonedDateTime.now(OSLO)): Boolean {
    if (!enabled || startHour == endHour) return false
    val h = now.hour
    return if (startHour < endHour) h in startHour until endHour else h >= startHour || h < endHour
}
