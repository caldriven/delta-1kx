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

    // ---- liquidation pivots ---------------------------------------------------------------------

    /**
     * A stretch of time in which a level held. [end]: open time of the bar that closed through it, or null
     * while it still holds. [touches]: open times of the bars that wicked into it and closed back.
     */
    data class Segment(val start: Long, val end: Long?, val touches: List<Long>)

    /**
     * One leverage tier's liquidation price off a source's average, and the stretches in which it held.
     * A close through the level ends a segment; a later retest from the other side starts a new one.
     */
    data class PivotLevel(
        val lev: Int, val longs: Boolean, val price: Double, val segments: List<Segment>,
        /** Holding levels from this many sources (this one included) sit within 0.1% of it. */
        val stacked: Int = 1,
        /** Folded into a newer source's level at about the same price; not drawn on its own. */
        val hidden: Boolean = false,
    ) {
        /** Holding right now: the price is still respecting it. */
        val active get() = segments.lastOrNull()?.end == null
        /** Hits in the segment running now. */
        val hits get() = if (active) segments.last().touches.size else 0
        /** Closed through at least once. */
        val swept get() = segments.any { it.end != null }
    }

    /** Where a set of levels comes from. */
    enum class Origin(val label: String, val short: String) {
        /** A compressed range: positions built up quietly around its average. */
        RANGE("range", "range"),
        /** A long liquidation cascade: a big red candle on a volume spike. Dip buyers and late shorts open there. */
        FLUSH("long flush", "flush"),
        /** A short squeeze or pump: a big green candle on a volume spike. Breakout longs and faders open there. */
        SQUEEZE("short squeeze", "pump"),
    }

    /**
     * A source of positions and the liquidation levels it leaves behind. [anchor] is its volume-weighted
     * average price: where those positions were opened on average. [forming]: a range still building, so
     * its levels are not drawn yet. [volume]: its traded volume, for weighing sources against each other.
     */
    data class Pivots(
        val origin: Origin, val start: Long, val end: Long, val hi: Double, val lo: Double, val anchor: Double,
        val levels: List<PivotLevel>, val forming: Boolean, val volume: Double,
    )

    /**
     * Liquidation pivots: fixed levels that stay until price trades through them, from two kinds of source.
     *
     * Ranges: a bar is compressed when its 14-bar ATR is under a threshold x its 100-bar ATR. The threshold
     * is the quietest quarter of this chart's own history, kept between [minRatio] and [maxRatio]. A run of
     * at least [minBars] such bars (gaps of up to 3 allowed) is a range; it starts 13 bars before its first
     * compressed bar, since those bars built the low ATR.
     *
     * Events: a bar whose volume is [volMult] x the 20-bar average and whose true range is [rangeMult] x the
     * 14-bar ATR, with the close in the outer third of the bar, is a forced move. Red is a long flush, green a
     * squeeze; neighbouring such bars merge into one cascade.
     *
     * Each source's levels run from its end until the first bar that trades through them, so older levels
     * stay visible with where they were taken. The newest [keepRanges] ranges and [keepEvents] events are
     * returned, oldest first, plus a range still building now.
     */
    fun pivots(
        cs: List<Candle>, s: Settings, keepRanges: Int = 3, keepEvents: Int = 4, events: Boolean = true,
        minRatio: Double = 0.7, maxRatio: Double = 0.85, minBars: Int = 12, volMult: Double = 3.0, rangeMult: Double = 2.0,
    ): List<Pivots> {
        val n = cs.size
        if (n < 120 || s.tiers.isEmpty()) return emptyList()
        val tr = DoubleArray(n) { i -> if (i == 0) cs[0].h - cs[0].l else max(cs[i].h, cs[i - 1].c) - min(cs[i].l, cs[i - 1].c) }
        val pre = DoubleArray(n + 1).also { for (i in 0 until n) it[i + 1] = it[i] + tr[i] }
        val vpre = DoubleArray(n + 1).also { for (i in 0 until n) it[i + 1] = it[i] + cs[i].v }
        fun atr(i: Int, len: Int) = (pre[i + 1] - pre[i + 1 - len]) / len
        // How far a close must go through a level to break it, and price must move off before a retest counts.
        fun buffer(i: Int) = 0.3 * atr(max(i, 14), 14)

        // Levels and how they held after the source ends.
        fun build(origin: Origin, r: IntRange, forming: Boolean): Pivots {
            var pv = 0.0; var v = 0.0; var hi = -Double.MAX_VALUE; var lo = Double.MAX_VALUE
            for (k in r) { val c = cs[k]; pv += typical(c) * c.v; v += c.v; hi = max(hi, c.h); lo = min(lo, c.l) }
            val anchor = if (v > 1e-12) pv / v else (hi + lo) / 2
            val levels = s.tiers.flatMap { lev ->
                val lp = longLiq(anchor, lev, s.mmr); val sp = shortLiq(anchor, lev, s.mmr)
                listOf(PivotLevel(lev, true, lp, segments(cs, r.last + 1, lp, ::buffer)), PivotLevel(lev, false, sp, segments(cs, r.last + 1, sp, ::buffer)))
            }
            return Pivots(origin, cs[r.first].openTime, cs[r.last].openTime, hi, lo, anchor, levels, forming, v)
        }

        val out = ArrayList<Pivots>()

        // Ranges.
        val ratios = DoubleArray(n) { i -> if (i >= 100) atr(i, 14) / atr(i, 100).coerceAtLeast(1e-12) else Double.NaN }
        val quiet = ratios.filter { !it.isNaN() }.sorted().let { it[it.size / 4] }
        val ratio = quiet.coerceIn(minRatio, maxRatio)
        val tight = BooleanArray(n) { i -> i >= 100 && ratios[i] < ratio }
        val runs = ArrayList<IntRange>()
        var i = 0
        while (i < n) {
            if (!tight[i]) { i++; continue }
            val a = i
            var b = i
            var j = i + 1
            while (j < n) {
                if (tight[j]) { b = j; j++ } else if (j - b <= 3) j++ else break
            }
            if (b - a + 1 >= minBars) runs += max(0, a - 13)..b
            i = b + 1
        }
        val building = runs.lastOrNull()?.takeIf { it.last >= n - 1 }
        runs.filter { it !== building }.takeLast(keepRanges).forEach { out += build(Origin.RANGE, it, false) }
        building?.let { out += build(Origin.RANGE, it, true) }

        // Forced moves: volume spikes with a wide bar closing near its extreme.
        if (events) {
            val found = ArrayList<Pair<IntRange, Boolean>>() // bars, down?
            var k = 20
            while (k < n) {
                val c = cs[k]
                val avgV = (vpre[k] - vpre[k - 20]) / 20
                val prevAtr = atr(k - 1, 14)
                val span = c.h - c.l
                val down = c.c < c.o
                val nearExtreme = span > 0 && (if (down) (c.c - c.l) / span <= 0.34 else (c.h - c.c) / span <= 0.34)
                if (avgV > 0 && c.v >= volMult * avgV && tr[k] >= rangeMult * prevAtr && nearExtreme) {
                    // A cascade: following bars in the same direction that are still heavy belong to it.
                    var e = k
                    while (e + 1 < n && (cs[e + 1].c < cs[e + 1].o) == down && cs[e + 1].v >= 1.5 * avgV) e++
                    found += (k..e) to down
                    k = e + 1
                } else k++
            }
            found.takeLast(keepEvents).forEach { (r, down) -> out += build(if (down) Origin.FLUSH else Origin.SQUEEZE, r, false) }
        }
        return stack(out.sortedBy { it.end })
    }

    /**
     * Holding levels from different sources within 0.1% of each other are one area: the newest source
     * keeps the line and counts the others as stacked; the older ones are hidden.
     */
    private fun stack(sources: List<Pivots>): List<Pivots> {
        data class Ref(val src: Int, val lvl: Int, val l: PivotLevel)
        val holding = sources.flatMapIndexed { si, pv -> if (pv.forming) emptyList() else pv.levels.mapIndexedNotNull { li, l -> if (l.active) Ref(si, li, l) else null } }
        val stacked = HashMap<Pair<Int, Int>, Int>()
        val hidden = HashSet<Pair<Int, Int>>()
        for (side in listOf(true, false)) {
            val list = holding.filter { it.l.longs == side }.sortedBy { it.l.price }
            var a = 0
            while (a < list.size) {
                var b = a
                while (b + 1 < list.size && list[b + 1].l.price / list[a].l.price - 1 <= 0.001) b++
                val group = list.subList(a, b + 1)
                val keep = group.maxBy { it.src }
                stacked[keep.src to keep.lvl] = group.size
                group.filter { it !== keep }.forEach { hidden += it.src to it.lvl }
                a = b + 1
            }
        }
        return sources.mapIndexed { si, pv ->
            pv.copy(levels = pv.levels.mapIndexed { li, l ->
                l.copy(stacked = stacked[si to li] ?: 1, hidden = (si to li) in hidden)
            })
        }
    }

    /**
     * How a level at [px] held from bar [from] on. It holds while bars that reach it close back on their
     * side (each such bar after a clean one is a hit). A close through it ends the segment. Once price
     * comes back to it from the other side, a new segment starts there.
     */
    private fun segments(cs: List<Candle>, from: Int, px: Double, buffer: (Int) -> Double): List<Segment> {
        if (from >= cs.size) return listOf(Segment(cs.last().openTime, null, emptyList()))
        val out = ArrayList<Segment>()
        // Which side the price is on: the level holds price above it (true) or below it.
        var above = cs[from - 1].c >= px
        var active = true
        var start = cs[from].openTime
        var touches = ArrayList<Long>()
        var prevTouched = false
        // After a break, price has to move clear of the level before coming back counts as a retest.
        var clear = false
        for (k in from until cs.size) {
            val c = cs[k]
            val reached = c.l <= px && c.h >= px
            val buf = buffer(k)
            if (active) {
                // A break is a close clearly through, not a close a tick past it.
                val through = if (above) c.c < px - buf else c.c > px + buf
                if (through) {
                    out += Segment(start, c.openTime, touches)
                    active = false
                    above = !above
                    prevTouched = false
                    clear = false
                } else {
                    if (reached && !prevTouched) touches.add(c.openTime)
                    prevTouched = reached
                }
            } else if (!clear) {
                clear = if (above) c.l > px + 2 * buf else c.h < px - 2 * buf
            } else if (reached) {
                // Retest from the other side: the level is back in play.
                active = true
                start = c.openTime
                touches = arrayListOf(c.openTime)
                prevTouched = true
                above = c.c >= px
            }
        }
        if (active) out += Segment(start, null, touches)
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
