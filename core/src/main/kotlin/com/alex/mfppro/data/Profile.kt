package com.alex.mfppro.data

import kotlin.math.max
import kotlin.math.min

/**
 * Volume at price from candles. Each candle's volume is spread over its high-low range; the buying
 * share is where it closed in that range (a close at the high counts as all buying), which is the
 * usual estimate when the tape itself isn't stored.
 */
class VolumeProfile(val lo: Double, val step: Double, val buy: DoubleArray, val sell: DoubleArray) {
    val bins get() = buy.size
    fun total(i: Int) = buy[i] + sell[i]
    fun price(i: Int) = lo + step * (i + 0.5)
    val hi get() = lo + step * bins
    val max: Double = (0 until bins).maxOfOrNull { total(it) } ?: 0.0
    val poc: Int = (0 until bins).maxByOrNull { total(it) } ?: 0

    /** Value area: grown outward from the POC, the heavier neighbour first, until 70% of volume. */
    val valueArea: IntRange by lazy {
        var a = poc; var b = poc
        val sum = (0 until bins).sumOf { total(it) }
        var covered = total(poc)
        while (covered < sum * 0.7 && (a > 0 || b < bins - 1)) {
            val up = if (b < bins - 1) total(b + 1) else -1.0
            val dn = if (a > 0) total(a - 1) else -1.0
            if (up >= dn) { b++; covered += up } else { a--; covered += dn }
        }
        a..b
    }
    val vah get() = lo + step * (valueArea.last + 1)
    val vaLow get() = lo + step * valueArea.first

    /**
     * Low-volume nodes: bins inside the value area holding under a third of their surroundings.
     * Price tends to move fast through them, so they mark where a break would run to.
     */
    val lvns: List<Int> by lazy {
        val va = valueArea
        (va.first + 2..va.last - 2).filter { i ->
            val around = (i - 2..i + 2).filter { it != i }.map { total(it) }.average()
            around > 0 && total(i) < around / 3
        }
    }

    companion object {
        /** Bins sized to the range: [bins] across it, but never finer than a tenth of the typical candle. */
        fun of(cs: List<Candle>, bins: Int = 72): VolumeProfile? {
            if (cs.isEmpty()) return null
            val pHi = cs.maxOf { it.h }; val pLo = cs.minOf { it.l }
            if (pHi <= pLo) return null
            val avgRange = cs.sumOf { it.h - it.l } / cs.size
            val n = min(bins, max(12, ((pHi - pLo) / (avgRange / 10).coerceAtLeast(1e-12)).toInt()))
            val step = (pHi - pLo) / n
            val buy = DoubleArray(n); val sell = DoubleArray(n)
            for (c in cs) {
                if (c.v <= 0) continue
                val a = ((c.l - pLo) / step).toInt().coerceIn(0, n - 1)
                val b = ((c.h - pLo) / step).toInt().coerceIn(0, n - 1)
                val buyShare = if (c.h > c.l) (c.c - c.l) / (c.h - c.l) else 0.5
                val share = c.v / (b - a + 1)
                for (i in a..b) { buy[i] += share * buyShare; sell[i] += share * (1 - buyShare) }
            }
            return if (buy.sum() + sell.sum() > 0) VolumeProfile(pLo, step, buy, sell) else null
        }

        /** One profile's key prices per UTC day in [cs], oldest first. */
        data class Session(val start: Long, val end: Long, val poc: Double, val vah: Double, val vaLow: Double)

        fun sessions(cs: List<Candle>, lengthMs: Long = 86_400_000L): List<Session> =
            cs.groupBy { Math.floorDiv(it.openTime, lengthMs) }.toSortedMap().mapNotNull { (d, bars) ->
                val p = of(bars, 48) ?: return@mapNotNull null
                Session(d * lengthMs, (d + 1) * lengthMs, p.price(p.poc), p.vah, p.vaLow)
            }

        /**
         * Naked POCs: earlier sessions' points of control that no later bar has traded through.
         * Untested POCs are well-known magnets; the newest [keep] are returned with their session start.
         */
        fun nakedPocs(cs: List<Candle>, keep: Int = 5): List<Pair<Long, Double>> {
            val sessions = sessions(cs)
            if (sessions.size < 2) return emptyList()
            return sessions.dropLast(1).filter { s ->
                cs.none { it.openTime >= s.end && it.l <= s.poc && it.h >= s.poc }
            }.takeLast(keep).map { it.start to it.poc }
        }
    }
}
