package com.alex.mfppro.data

import java.util.Locale
import kotlin.math.abs

/**
 * Plain-language text for the liquidation levels: the card a tapped level opens, and the legend.
 * Shared by both apps so they explain the same thing the same way.
 */
object LiqGuide {

    enum class Tone { TITLE, TEXT, MUTED, UP, DOWN, ACCENT }
    data class Line(val text: String, val tone: Tone = Tone.TEXT)

    private fun ago(t: Long, now: Long): String {
        val m = (now - t) / 60_000
        return when { m < 60 -> "${m}m"; m < 1440 -> "${m / 60}h ${m % 60}m"; else -> "${m / 1440}d ${m % 1440 / 60}h" }
    }

    /**
     * The card for [l] from [src], with the price now at [price] and the 14-bar ATR at [atrPct] percent.
     * [fmt] prints a price the way its market quotes it.
     */
    fun explain(l: Liquidity.PivotLevel, src: Liquidity.Pivots, price: Double, atrPct: Double, now: Long, fmt: (Double) -> String): List<Line> {
        val out = ArrayList<Line>()
        val side = if (l.longs) "longs" else "shorts"
        out += Line("${l.lev}x $side liquidation level", Tone.TITLE)
        val dist = (l.price / price - 1) * 100
        val atrs = if (atrPct > 0) abs(dist) / atrPct else null
        out += Line(String.format(Locale.US, "%s · %+.2f%% from here", fmt(l.price), dist) +
            (atrs?.let { String.format(Locale.US, " · %.1f× ATR", it) } ?: ""), if (l.price > price) Tone.UP else Tone.DOWN)

        out += Line(when (src.origin) {
            Liquidity.Origin.RANGE -> "From a compressed range that ended ${ago(src.end, now)} ago. Its volume-weighted average ${fmt(src.anchor)} is where those positions were opened on average."
            Liquidity.Origin.FLUSH -> "From a long flush ${ago(src.end, now)} ago: longs were forced out on heavy volume and new positions opened around ${fmt(src.anchor)}."
            Liquidity.Origin.SQUEEZE -> "From a pump ${ago(src.end, now)} ago: shorts were squeezed on heavy volume and new positions opened around ${fmt(src.anchor)}."
        })
        out += Line("${l.lev}x $side opened there are liquidated at this price: ${String.format(Locale.US, "%.2f", Liquidity.distance(l.lev, 0.005) * 100)}% from their entry.", Tone.MUTED)

        val seg = l.segments.lastOrNull()
        when {
            l.active && l.hits >= 2 -> out += Line("Holding, hit ${l.hits}×: price wicked into it and closed back each time. A defended level: a clean close through it tends to run.", Tone.ACCENT)
            l.active && l.hits == 1 -> out += Line("Holding, hit once: price tested it and closed back.", Tone.ACCENT)
            l.active && l.segments.size > 1 -> out += Line("Back in play: it broke earlier, and price returned to retest it.", Tone.ACCENT)
            l.active -> out += Line("Untouched since it formed: the liquidations there are still waiting.", Tone.ACCENT)
            seg?.end != null -> out += Line("Broken ${ago(seg.end, now)} ago: a candle closed clearly through it, so most of those liquidations have likely fired.", Tone.MUTED)
        }
        if (l.stacked > 1) out += Line("Stacked: ${l.stacked} sources put a level within 0.1% of this price, so more positions sit here. A stronger area than a single line.", Tone.ACCENT)

        out += Line(when {
            !l.active -> "Swept: little fuel left. On a retest it can act as support or resistance instead."
            !l.longs -> "Shorts liquidating = forced buying. Price is often drawn up to it and can spike through, then stall. " +
                "Long: a natural take-profit just under it. Short: don't put your stop just below it, the spike will take it."
            else -> "Longs liquidating = forced selling. Price is often drawn down to it, flushes through, then bounces. " +
                "Short: a natural take-profit just above it. Long: keep your stop clear of it, or wait for the flush to buy."
        })
        out += Line("Estimated from candles and volume, not from venue liquidation data.", Tone.MUTED)
        return out
    }

    /** The legend: each mark on the chart and what it means. */
    val LEGEND: List<Pair<String, String>> = listOf(
        "Solid line" to "A liquidation level still holding now: price has not closed clearly through it.",
        "Dots on a line" to "Hits: a candle wicked into the level and closed back. ×3 in the label = hit 3 times.",
        "Thick line with a band" to "An active area: hit twice or more, or stacked.",
        "3 stacked" to "Levels from 3 different sources (ranges or flushes) sit within 0.1% of each other. More positions liquidate there.",
        "--------|" to "A stretch that ended: a candle closed clearly through the level (beyond 0.3× ATR).",
        "|--------" to "Back in play: price returned to retest a broken level from the other side.",
        "Box" to "A compressed range (ATR well under its normal). Positions built up inside it; its levels start at its end.",
        "range avg / flush avg" to "The newest source's volume-weighted average: where its positions were opened.",
        "▼ flush / ▲ pump" to "A forced move: a bar 2× the ATR on 3× normal volume. Liquidations fired there and new positions opened.",
        "· range 8h" to "The level comes from an older source that ended 8 hours ago. Older sources are fainter.",
        "▲ / ▼ tag at the edge" to "The nearest holding level above or below that is off the screen, with its distance.",
        "Colour" to "Leverage tier: yellow 100x, orange 50x, deep orange 25x, red 10x.",
        "Compressing…" to "A range is building now. Its levels appear when it breaks.",
    )
}
