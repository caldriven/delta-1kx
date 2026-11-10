package com.alex.mfppro.data

import java.util.Locale
import kotlin.math.abs

/**
 * Short labels for the liquidation levels: the card a tapped level opens, and the legend.
 * Fragments, not sentences. Shared by both apps.
 */
object LiqGuide {

    enum class Tone { TITLE, TEXT, MUTED, UP, DOWN, ACCENT }
    data class Line(val text: String, val tone: Tone = Tone.TEXT)

    private fun ago(t: Long, now: Long): String {
        val m = (now - t) / 60_000
        return when { m < 60 -> "${m}m"; m < 1440 -> "${m / 60}h"; else -> "${m / 1440}d" }
    }

    /** The card for [l] from [src], price now [price], 14-bar ATR [atrPct] percent. */
    fun explain(l: Liquidity.PivotLevel, src: Liquidity.Pivots, price: Double, atrPct: Double, now: Long, fmt: (Double) -> String): List<Line> {
        val out = ArrayList<Line>()
        out += Line("${l.lev}x ${if (l.longs) "long" else "short"} liq · ${fmt(l.price)}", Tone.TITLE)
        val dist = (l.price / price - 1) * 100
        out += Line(String.format(Locale.US, "%+.2f%%", dist) + (if (atrPct > 0) String.format(Locale.US, " · %.1f ATR", abs(dist) / atrPct) else ""),
            if (l.price > price) Tone.UP else Tone.DOWN)
        out += Line("${src.origin.label} ${ago(src.end, now)} ago · avg ${fmt(src.anchor)}", Tone.MUTED)
        val seg = l.segments.lastOrNull()
        out += when {
            !l.active && seg?.end != null -> Line("Broken ${ago(seg.end, now)} ago", Tone.MUTED)
            l.hits >= 2 -> Line("Holding · hit ${l.hits}× · defended", Tone.ACCENT)
            l.hits == 1 -> Line("Holding · hit once", Tone.ACCENT)
            l.segments.size > 1 -> Line("Holding · retest", Tone.ACCENT)
            else -> Line("Untouched", Tone.ACCENT)
        }
        if (l.stacked > 1) out += Line("${l.stacked} sources stacked · strong", Tone.ACCENT)
        out += Line(when {
            !l.active -> "Swept · may flip to S/R"
            l.longs -> "Forced selling · magnet below · short TP / long stop zone"
            else -> "Forced buying · magnet above · long TP / short stop zone"
        })
        return out
    }

    /** Legend: mark → meaning. */
    val LEGEND: List<Pair<String, String>> = listOf(
        "Solid line" to "Holding",
        "Dots · ×3" to "Hits (wick in, close back)",
        "Thick + band" to "Active area",
        "3 stacked" to "3 sources within 0.1%",
        "---|" to "Broken (close through)",
        "|---" to "Retest after break",
        "Box" to "Compressed range",
        "▼ flush / ▲ pump" to "Volume-spike forced move",
        "avg" to "Where positions opened",
        "· range 8h" to "Older source, age",
        "▲ / ▼ edge tag" to "Nearest level off screen",
        "Colour" to "100x yellow · 50x orange · 25x deep orange · 10x red",
    )
}
