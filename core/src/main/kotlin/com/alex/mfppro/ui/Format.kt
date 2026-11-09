package com.alex.mfppro.ui

// Number and price formatting shared by the Android and desktop apps. It stays in the ui package
// so existing screens keep calling these without imports; it lives in :core because the trading
// code in Repo snaps and prints prices with it too.

import com.alex.mfppro.data.Market
import java.util.Locale
import kotlin.math.abs

fun fmtPrice(p: Double?): String {
    if (p == null) return "—"
    return String.format(Locale.US, "%,.${priceDecimals(p)}f", p)
}

/**
 * A price as its market quotes it: never finer than the tick (oil 70.12, not 70.1200; BTC on a
 * whole-dollar tick 98,765), and a 0.25 tick keeps its two decimals instead of rounding off-tick.
 */
fun fmtPrice(p: Double?, m: com.alex.mfppro.data.Market?): String {
    if (p == null) return "—"
    if (m == null) return fmtPrice(p)
    val d = if (decimalTick(m)) naturalDecimals(m, p) else tickDecimals(m)
    return String.format(Locale.US, "%,.${d}f", p)
}

/** Decimals a price is shown with: about six significant digits. */
fun priceDecimals(p: Double): Int {
    val a = abs(p)
    return when {
        a >= 10_000 -> 1
        a >= 100 -> 2
        a >= 10 -> 4
        // Forex lives here: EUR/USD needs its fifth decimal.
        a >= 0.01 -> 5
        else -> 7
    }
}

/** Dollars with cents; from $10,000 up the cents are noise and are dropped. */
fun fmtUsd(v: Double?, signed: Boolean = false): String {
    if (v == null) return "—"
    val s = String.format(Locale.US, if (abs(v) >= 10_000) "%,.0f" else "%,.2f", abs(v))
    val sign = if (v < 0) "-" else if (signed && v > 0) "+" else ""
    return "$sign$$s"
}

fun fmtCompact(v: Double?): String {
    if (v == null) return "—"
    val a = abs(v)
    val (x, u) = when {
        a >= 1e9 -> v / 1e9 to "B"
        a >= 1e6 -> v / 1e6 to "M"
        a >= 1e3 -> v / 1e3 to "K"
        else -> v to ""
    }
    // About three significant digits, no trailing zeros: 1K, 1.25K, 12.5K, 523.
    val d = when { abs(x) >= 100 -> 0; abs(x) >= 10 -> 1; else -> 2 }
    var n = String.format(Locale.US, "%.${d}f", x)
    if ('.' in n) n = n.trimEnd('0').trimEnd('.')
    return n + u
}

/** Whole dollars, rounded (not cut): $9,999.99 → $10,000, -0.40 → $0. */
fun fmtUsdWhole(v: Double?): String {
    if (v == null) return "—"
    val r = kotlin.math.round(v)
    return (if (r < 0) "-" else "") + "$" + String.format(Locale.US, "%,.0f", abs(r))
}

fun fmtPct(v: Double?, signed: Boolean = true): String {
    if (v == null) return "—"
    return String.format(Locale.US, if (signed) "%+.2f%%" else "%.2f%%", v)
}

fun fmtSize(v: Double): String = String.format(Locale.US, "%,.4f", v).trimEnd('0').trimEnd('.')

fun fmtCountdown(ms: Long): String {
    if (ms <= 0) return "00:00:00"
    val s = ms / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
}

/** Display ticker: "BNBUSDT" for USDT perps, the plain symbol for indices, commodities and FX. */
fun ticker(symbol: String, provider: String): String =
    if (provider in setOf("binance", "bybit", "okx") && !symbol.endsWith("USDT")) symbol + "USDT" else symbol

/** Decimals of the market's tick (0.001 -> 3); 6 when the market has none. */
fun tickDecimals(m: Market): Int =
    if (m.tickSize > 0) java.math.BigDecimal(m.tickSize.toString()).stripTrailingZeros().scale().coerceAtLeast(0) else 6

/** Decimals a price needs to read well (as [fmtPrice] shows it), never finer than the market's tick. */
fun naturalDecimals(m: Market, px: Double): Int = minOf(priceDecimals(px), tickDecimals(m))

/** [px] rounded to [d] decimals, without binary noise (627.93, not 627.92999995). */
fun roundDec(px: Double, d: Int): Double = java.math.BigDecimal.valueOf(px).setScale(d.coerceAtLeast(0), java.math.RoundingMode.HALF_UP).toDouble()

/** Largest 1, 2 or 5 × 10^k at or below [v]. */
fun niceStep(v: Double): Double {
    if (v <= 0) return 0.0
    val p = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(v)))
    return p * (if (v / p >= 5) 5 else if (v / p >= 2) 2 else 1)
}

/** True for a tick of 1, 0.1, 0.01 …; false for one like 0.25 or 5, where dropping decimals leaves the tick. */
fun decimalTick(m: Market): Boolean =
    m.tickSize <= 0 || java.math.BigDecimal.valueOf(m.tickSize).stripTrailingZeros().unscaledValue() == java.math.BigInteger.ONE

/** Price snapped to the market's tick, then to the decimals that suit it (only where that stays on the tick). */
fun snapTick(px: Double, m: Market): Double {
    val t = if (m.tickSize > 0) java.math.BigDecimal.valueOf(m.tickSize).multiply(java.math.BigDecimal.valueOf(px / m.tickSize).setScale(0, java.math.RoundingMode.HALF_UP)).toDouble()
        else px
    return roundDec(t, if (decimalTick(m)) naturalDecimals(m, px) else tickDecimals(m))
}
