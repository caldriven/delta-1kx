package com.alex.mfppro.ui

import com.alex.mfppro.data.Candle

/**
 * Fold the live price into the forming candle so the chart never trails the market.
 *
 * Only the bar that is still open takes the price. Right after a candle closes, before the feed
 * prints the next one, a fresh bar is started from the last close. Bars older than that are left
 * alone: folding today's price into a candle from hours ago (bars kept from before the app was in
 * the background) drew one long wick across the gap until the next restart.
 */
fun List<Candle>.withLive(p: Double?, now: Long = System.currentTimeMillis()): List<Candle> {
    if (p == null || p <= 0 || isEmpty()) return this
    val l = last()
    // A price far from the last bar means the bars are stale (or from another feed), not that the
    // market gapped in one candle: drawing it would make one huge wick. Leave the bars to the reload.
    if (l.c > 0 && kotlin.math.abs(p / l.c - 1) > 0.006) return this
    fun fold() = dropLast(1) + l.copy(c = p, h = maxOf(l.h, p), l = minOf(l.l, p))
    if (size < 2) return fold()
    val step = l.openTime - this[size - 2].openTime
    if (step <= 0) return fold()
    return when {
        now < l.openTime + step -> fold()
        now < l.openTime + 2 * step -> this + Candle(l.openTime + step, l.c, maxOf(l.c, p), minOf(l.c, p), p, 0.0)
        else -> this
    }
}
