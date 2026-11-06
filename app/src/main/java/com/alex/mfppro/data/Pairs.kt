package com.alex.mfppro.data

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Statistics of a pair A/B over shared candle closes.
 * Spread = ln A − α − β·ln B (OLS of log prices); z is the latest spread in standard deviations.
 * Holding $1 of A against $β of B is flat to the common move, so β is also the dollar hedge ratio.
 */
class PairStat(
    val corr: Double,
    val beta: Double,
    val z: Double,
    val sd: Double,
    /** Bars for the spread to halve its distance to the mean (AR(1)), null when it doesn't revert. */
    val halfLife: Double?,
    val zs: List<Double>,
    val times: List<Long>,
    /** A's closes, and the A price B implies through the fit (exp(α + β·ln B)): the gap between them is the spread. */
    val pricesA: List<Double>,
    val fairA: List<Double>,
    val lastB: Double,
) {
    /** Above the mean A is rich: sell A, buy B. */
    val longA get() = z < 0
    val lastA get() = pricesA.last()

    /** A's price at spread z [to] with B unchanged. */
    fun priceA(to: Double) = lastA * kotlin.math.exp((to - z) * sd)

    /** B's price at spread z [to] with A unchanged. */
    fun priceB(to: Double) = lastB * kotlin.math.exp(-(to - z) * sd / beta)
}

fun pairStat(a: List<Candle>, b: List<Candle>): PairStat? {
    val pb = HashMap<Long, Double>()
    b.forEach { if (it.c > 0) pb[it.openTime] = it.c }
    val times = ArrayList<Long>(); val y = ArrayList<Double>(); val x = ArrayList<Double>()
    a.sortedBy { it.openTime }.forEach { c -> val q = pb[c.openTime]; if (c.c > 0 && q != null) { times += c.openTime; y += ln(c.c); x += ln(q) } }
    val n = times.size
    if (n < 30) return null
    // Correlation of bar returns: does the pair actually move together?
    val ra = (1 until n).map { y[it] - y[it - 1] }; val rb = (1 until n).map { x[it] - x[it - 1] }
    val corr = corr(ra, rb) ?: return null
    val mx = x.average(); val my = y.average()
    var sxy = 0.0; var sxx = 0.0
    for (i in 0 until n) { sxy += (x[i] - mx) * (y[i] - my); sxx += (x[i] - mx) * (x[i] - mx) }
    if (sxx <= 0) return null
    val beta = sxy / sxx
    val alpha = my - beta * mx
    val s = (0 until n).map { y[it] - alpha - beta * x[it] }
    val sd = sqrt(s.sumOf { it * it } / (n - 1))
    if (sd <= 0) return null
    // Mean reversion speed: Δs = λ·s(t−1) + e; half-life = −ln 2 / λ.
    val lag = s.subList(0, n - 1); val ds = (1 until n).map { s[it] - s[it - 1] }
    val ml = lag.average(); val md = ds.average()
    var c = 0.0; var v = 0.0
    for (i in lag.indices) { c += (lag[i] - ml) * (ds[i] - md); v += (lag[i] - ml) * (lag[i] - ml) }
    val lambda = if (v > 0) c / v else 0.0
    val hl = if (lambda < 0) -ln(2.0) / lambda else null
    return PairStat(corr, beta, s.last() / sd, sd, hl, s.map { it / sd }, times,
        y.map { kotlin.math.exp(it) }, x.map { kotlin.math.exp(alpha + beta * it) }, kotlin.math.exp(x.last()))
}

private fun corr(a: List<Double>, b: List<Double>): Double? {
    val ma = a.average(); val mb = b.average()
    var c = 0.0; var va = 0.0; var vb = 0.0
    for (i in a.indices) { val da = a[i] - ma; val db = b[i] - mb; c += da * db; va += da * da; vb += db * db }
    return if (va > 0 && vb > 0) c / sqrt(va * vb) else null
}

/** Worth trading: moves together, a sane hedge ratio and a spread that reverts within a third of the window. */
fun PairStat.tradable(bars: Int) = corr >= 0.6 && beta in 0.2..5.0 && halfLife != null && halfLife <= bars / 3.0

/** Gross USD split into the A and B legs for a hedge ratio (1 = dollar neutral). */
fun legSplit(gross: Double, hedge: Double): Pair<Double, Double> {
    val h = abs(hedge).coerceAtLeast(0.01)
    return gross / (1 + h) to gross * h / (1 + h)
}
