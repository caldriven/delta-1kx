package com.alex.mfppro.data

import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Estimated liquidation levels: where positions opened near an average price get liquidated.
 *
 * Two views, both from candles alone (no venue publishes where its users' liquidations sit):
 *
 * - **Bands**: a smoothed base price (VWMA, session VWAP or rolling VWAP) with each leverage tier's
 *   long and short liquidation price around it, like the classic rolling "liq levels" study.
 *
 * - **Pools**: every candle's volume is treated as positions opened at its typical price, spread over
 *   the leverage tiers. Each tier leaves a long pool below and a short pool above. A pool lives until
 *   price trades through it (the stops and liquidations there have fired) and fades with age. What is
 *   left is a map of untouched liquidity: the pockets a stop hunt would run to. In a compressed range
 *   the pockets bunch up close to the price on both sides, which is when a sweep is most likely.
 */
object Liquidity {

    enum class Base(val label: String) { VWMA("VWMA"), SESSION_VWAP("Session VWAP"), ROLLING_VWAP("Rolling VWAP") }

    data class Settings(
        /** Leverage tiers shown; any positive whole numbers. */
        val tiers: List<Int> = listOf(10, 25, 50, 100),
        val base: Base = Base.VWMA,
        /** Bars for VWMA / rolling VWAP. */
        val length: Int = 20,
        /** Maintenance margin rate: a position is liquidated with this much margin left. */
        val mmr: Double = 0.005,
        /** Bars over which a pool fades to half weight. */
        val halfLife: Int = 288,
    )

    /** Distance from entry to liquidation for an isolated linear position at [lev]x. */
    fun distance(lev: Int, mmr: Double): Double = (1.0 / lev - mmr).coerceAtLeast(0.0005)

    fun longLiq(entry: Double, lev: Int, mmr: Double) = entry * (1 - distance(lev, mmr))
    fun shortLiq(entry: Double, lev: Int, mmr: Double) = entry * (1 + distance(lev, mmr))

    private fun typical(c: Candle) = (c.h + c.l + c.c) / 3

    /** Base price per bar (NaN until there are enough bars). */
    fun base(cs: List<Candle>, s: Settings): DoubleArray {
        val out = DoubleArray(cs.size) { Double.NaN }
        when (s.base) {
            Base.VWMA, Base.ROLLING_VWAP -> {
                // Both are volume-weighted means of the typical price over the window; VWMA is the
                // textbook name, rolling VWAP the trader's, and on candles they are the same number.
                val n = s.length.coerceAtLeast(1)
                var pv = 0.0; var v = 0.0
                for (i in cs.indices) {
                    pv += typical(cs[i]) * cs[i].v; v += cs[i].v
                    if (i >= n) { pv -= typical(cs[i - n]) * cs[i - n].v; v -= cs[i - n].v }
                    if (i >= n - 1) out[i] = if (v > 1e-12) pv / v else typical(cs[i])
                }
            }
            Base.SESSION_VWAP -> {
                var day = Long.MIN_VALUE; var pv = 0.0; var v = 0.0
                for (i in cs.indices) {
                    val d = Math.floorDiv(cs[i].openTime, 86_400_000L)
                    if (d != day) { day = d; pv = 0.0; v = 0.0 }
                    pv += typical(cs[i]) * cs[i].v; v += cs[i].v
                    out[i] = if (v > 1e-12) pv / v else typical(cs[i])
                }
            }
        }
        return out
    }

    class Band(val lev: Int, val longs: DoubleArray, val shorts: DoubleArray)

    /** Long and short liquidation lines per tier around [base]. */
    fun bands(base: DoubleArray, s: Settings): List<Band> = s.tiers.map { lev ->
        val d = distance(lev, s.mmr)
        Band(lev, DoubleArray(base.size) { base[it] * (1 - d) }, DoubleArray(base.size) { base[it] * (1 + d) })
    }

    /** Untouched liquidity at a price. [longs]: long liquidations (below the price at the time). */
    data class Pool(val price: Double, val weight: Double, val longs: Boolean)

    /**
     * Pools still standing after the last bar, with their age fade applied.
     * Tier weights lean towards the middle tiers, where most retail leverage sits.
     */
    fun pools(cs: List<Candle>, s: Settings): List<Pool> {
        if (cs.size < 2 || s.tiers.isEmpty()) return emptyList()
        val longs = TreeMap<Double, Double>()
        val shorts = TreeMap<Double, Double>()
        val n = cs.size
        val tierW = s.tiers.associateWith { lev -> when { lev <= 5 -> 0.6; lev <= 25 -> 1.0; lev <= 50 -> 0.8; else -> 0.5 } }
        val wSum = tierW.values.sum()
        for (i in cs.indices) {
            val c = cs[i]
            // Price trading through a level sets those liquidations off: the pool is gone.
            longs.tailMap(c.l, true).clear()
            shorts.headMap(c.h, true).clear()
            val p = typical(c)
            val fade = 0.5.pow((n - 1 - i).toDouble() / s.halfLife.coerceAtLeast(1))
            val w = c.v * p * fade
            if (w <= 0) continue
            for (lev in s.tiers) {
                val share = w * tierW.getValue(lev) / wSum
                longs.merge(longLiq(p, lev, s.mmr), share, Double::plus)
                shorts.merge(shortLiq(p, lev, s.mmr), share, Double::plus)
            }
        }
        return longs.map { Pool(it.key, it.value, true) } + shorts.map { Pool(it.key, it.value, false) }
    }

    /** Pools summed into price bins [step] wide, from [lo]. */
    class Heat(val lo: Double, val step: Double, val longs: DoubleArray, val shorts: DoubleArray) {
        val max = maxOf(longs.maxOrNull() ?: 0.0, shorts.maxOrNull() ?: 0.0)
        fun price(i: Int) = lo + step * (i + 0.5)
    }

    fun heat(pools: List<Pool>, lo: Double, hi: Double, bins: Int): Heat? {
        if (hi <= lo || bins <= 0) return null
        val step = (hi - lo) / bins
        val l = DoubleArray(bins); val sh = DoubleArray(bins)
        for (p in pools) {
            if (p.price < lo || p.price >= hi) continue
            val i = ((p.price - lo) / step).toInt().coerceIn(0, bins - 1)
            if (p.longs) l[i] += p.weight else sh[i] += p.weight
        }
        return Heat(lo, step, l, sh)
    }

    /** The pocket a sweep would most likely run to on one side of the price. */
    data class Magnet(val price: Double, val longs: Boolean, val distPct: Double, val share: Double)

    /**
     * Strongest pocket above and below [price], grouped into bins [binPct] wide and scored by size over
     * distance in ATRs (a big pool 5 ATR away is less of a target than a fair one just overhead).
     * [share] is its weight as a part of everything on that side within reach.
     */
    fun magnets(pools: List<Pool>, price: Double, atrPct: Double, binPct: Double = 0.15, reachPct: Double = 8.0): List<Magnet> {
        if (price <= 0) return emptyList()
        val out = ArrayList<Magnet>()
        for (above in listOf(true, false)) {
            val side = pools.filter { if (above) it.price > price else it.price < price }
                .filter { abs(it.price / price - 1) * 100 <= reachPct }
            if (side.isEmpty()) continue
            val bins = HashMap<Long, Pair<Double, Double>>() // bin -> (weight, weight × price)
            for (p in side) {
                val b = kotlin.math.floor((p.price / price - 1) * 100 / binPct).toLong()
                val (w, wp) = bins[b] ?: (0.0 to 0.0)
                bins[b] = (w + p.weight) to (wp + p.weight * p.price)
            }
            val total = bins.values.sumOf { it.first }
            val atr = atrPct.coerceAtLeast(0.05)
            val best = bins.values.maxByOrNull { (w, wp) -> w / (1 + abs(wp / w / price - 1) * 100 / atr) } ?: continue
            val px = best.second / best.first
            out += Magnet(px, longs = !above, distPct = (px / price - 1) * 100, share = best.first / total)
        }
        return out
    }

    /** Average true range over the last [n] bars, as a percent of the last close. */
    fun atrPct(cs: List<Candle>, n: Int = 14): Double {
        if (cs.size < 2) return 0.0
        val from = max(1, cs.size - n)
        var sum = 0.0
        for (i in from until cs.size) {
            val c = cs[i]; val pc = cs[i - 1].c
            sum += max(c.h, pc) - min(c.l, pc)
        }
        val last = cs.last().c
        return if (last > 0) sum / (cs.size - from) / last * 100 else 0.0
    }

    /**
     * How tight the market is now against its own recent normal: short ATR over long ATR.
     * Under ~0.65 the range is compressed; ranges that coil like this tend to break with a sweep.
     */
    fun compression(cs: List<Candle>): Double? {
        if (cs.size < 60) return null
        val slow = atrPct(cs, 100)
        return if (slow > 0) atrPct(cs, 14) / slow else null
    }
}
