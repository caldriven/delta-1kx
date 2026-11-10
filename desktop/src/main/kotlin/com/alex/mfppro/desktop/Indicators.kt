package com.alex.mfppro.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alex.mfppro.data.Liquidity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What every chart draws. One set for all charts so a grid reads the same everywhere;
 * saved to indicators.json.
 */
@Serializable
data class Ind(
    val volume: Boolean = true,
    // Volume profile
    val vp: Boolean = true,
    /** "visible" = the bars on screen, "session" = today (UTC). */
    val vpRange: String = "visible",
    val vpLevels: Boolean = true,
    val nakedPocs: Boolean = true,
    // Liquidation levels
    val liqBands: Boolean = false,
    val liqBase: String = Liquidity.Base.VWMA.name,
    val liqLength: Int = 20,
    val liqTiers: List<Int> = listOf(10, 25, 50, 100),
    val liqOn: List<Int> = listOf(25, 50, 100),
    val liqMmr: Double = 0.005,
    val liqPools: Boolean = false,
    val liqPivots: Boolean = true,
    /** Also anchor levels on forced moves: long flushes and pumps (volume spikes). */
    val liqEvents: Boolean = true,
    /** How many past sources of each kind to keep on the chart. */
    val liqHistory: Int = 2,
    // Sessions and opens
    val killzones: Boolean = true,
    val markets: Boolean = false,
    val opens: Boolean = true,
    val opensDay: Boolean = true,
    val opensWeek: Boolean = true,
    val opensMonth: Boolean = true,
    val opensNyMid: Boolean = false,
    val prevDay: Boolean = true,
    /** Levels further than this (%) from the price are drawn faint. */
    val fadePct: Double = 3.0,
) {
    val liqSettings get() = Liquidity.Settings(
        tiers = liqTiers.filter { it in liqOn }.sorted(),
        base = runCatching { Liquidity.Base.valueOf(liqBase) }.getOrDefault(Liquidity.Base.VWMA),
        length = liqLength.coerceIn(2, 500),
        mmr = liqMmr,
    )
}

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun loadInd(store: JsonFileStore): Ind =
    store.getString("indicators", null)?.let { runCatching { json.decodeFromString(Ind.serializer(), it) }.getOrNull() } ?: Ind()

fun saveInd(store: JsonFileStore, ind: Ind) = store.edit().putString("indicators", json.encodeToString(Ind.serializer(), ind)).apply()

/** Colours per leverage tier: the higher the leverage, the hotter (and nearer the price). */
fun tierColor(lev: Int): Color = when {
    lev >= 100 -> Color(0xFFFFE082)
    lev >= 50 -> Color(0xFFFFB74D)
    lev >= 25 -> Color(0xFFFF7043)
    lev >= 10 -> Color(0xFFE57373)
    else -> Color(0xFFBA68C8)
}

/** The Indicators button and its menu. */
@Composable
fun IndicatorMenu(ind: Ind, onChange: (Ind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill("Indicators", open) { open = true }
        DropdownMenu(open, { open = false }, offset = DpOffset(0.dp, 4.dp), modifier = Modifier.background(D.panel2)) {
            Column(Modifier.widthIn(min = 300.dp).padding(horizontal = 12.dp, vertical = 4.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Section("Volume")
                Check("Volume bars", ind.volume) { onChange(ind.copy(volume = it)) }
                Check("Volume profile", ind.vp) { onChange(ind.copy(vp = it)) }
                if (ind.vp) Indent {
                    Choice(listOf("visible" to "Visible bars", "session" to "Today (UTC)"), ind.vpRange) { onChange(ind.copy(vpRange = it)) }
                    Check("POC, value area high/low", ind.vpLevels) { onChange(ind.copy(vpLevels = it)) }
                    Check("Naked POCs (untested earlier days)", ind.nakedPocs) { onChange(ind.copy(nakedPocs = it)) }
                }

                Section("Liquidation levels")
                Check("Liquidation pivots from the last compressed range", ind.liqPivots) { onChange(ind.copy(liqPivots = it)) }
                if (ind.liqPivots) Indent {
                    Check("Also from flushes and pumps (volume spikes)", ind.liqEvents) { onChange(ind.copy(liqEvents = it)) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("History", style = label, modifier = Modifier.width(62.dp))
                        listOf(1, 2, 3, 5).forEach { k -> Pill(if (k == 1) "latest" else "$k", ind.liqHistory == k) { onChange(ind.copy(liqHistory = k)) } }
                    }
                }
                Check("Liquidity pools heat (right edge)", ind.liqPools) { onChange(ind.copy(liqPools = it)) }
                var legend by remember { mutableStateOf(false) }
                Pill(if (legend) "Hide the legend" else "How to read it · click a level's ⓘ label for its card", legend) { legend = !legend }
                if (legend) Column(Modifier.padding(start = 4.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((mark, meaning) in com.alex.mfppro.data.LiqGuide.LEGEND) Column {
                        Text(mark, color = D.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text(meaning, style = label.copy(fontSize = 11.sp, lineHeight = 14.sp, color = D.text), modifier = Modifier.widthIn(max = 380.dp))
                    }
                }
                Check("Leverage bands around the average", ind.liqBands) { onChange(ind.copy(liqBands = it)) }
                Indent {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Leverage", style = label, modifier = Modifier.width(62.dp))
                        ind.liqTiers.sorted().forEach { lev ->
                            Pill("${lev}x", lev in ind.liqOn, tierColor(lev)) {
                                onChange(ind.copy(liqOn = if (lev in ind.liqOn) ind.liqOn - lev else ind.liqOn + lev))
                            }
                        }
                    }
                    var custom by remember { mutableStateOf("") }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Add", style = label, modifier = Modifier.width(62.dp))
                        Field(custom, { custom = it.filter(Char::isDigit).take(3) }, Modifier.width(64.dp), placeholder = "75", numeric = true, suffix = "x")
                        Pill("Add", false) {
                            custom.toIntOrNull()?.takeIf { it in 2..500 && it !in ind.liqTiers }?.let {
                                onChange(ind.copy(liqTiers = ind.liqTiers + it, liqOn = ind.liqOn + it)); custom = ""
                            }
                        }
                        if (ind.liqTiers.size > 1) Pill("Reset", false) { onChange(ind.copy(liqTiers = Ind().liqTiers, liqOn = Ind().liqOn)) }
                    }
                    if (ind.liqBands) {
                        Choice(Liquidity.Base.entries.map { it.name to it.label }, ind.liqBase) { onChange(ind.copy(liqBase = it)) }
                        if (ind.liqBase != Liquidity.Base.SESSION_VWAP.name) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Length", style = label, modifier = Modifier.width(62.dp))
                            listOf(10, 20, 50, 100).forEach { n -> Pill("$n", ind.liqLength == n) { onChange(ind.copy(liqLength = n)) } }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Maint.", style = label, modifier = Modifier.width(62.dp))
                        listOf(0.004, 0.005, 0.01).forEach { m -> Pill(String.format(java.util.Locale.US, "%.1f%%", m * 100), ind.liqMmr == m) { onChange(ind.copy(liqMmr = m)) } }
                    }
                    Text(
                        "Pivots: a coiled range (14-bar ATR well under the 100-bar ATR for 12+ bars) or a forced move " +
                            "(volume 3× normal on a bar 2× the ATR: a flush or a pump) is where positions were opened. Each tier's " +
                            "long and short liquidation price from its volume-weighted average is a level. Solid while it holds, " +
                            "dots are hits (a wick in, a close back), |---| caps where a close went through and where a retest " +
                            "brought it back. Thicker with a band: hit twice or more, or levels from several sources stacked within 0.1%. " +
                            "Older sources fade and show only what still holds.",
                        style = label.copy(fontSize = 10.sp, lineHeight = 13.sp), modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                    )
                }

                Section("Sessions and opens")
                Check("Killzones (strip along the bottom)", ind.killzones) { onChange(ind.copy(killzones = it)) }
                Check("Market hours (strip along the top)", ind.markets) { onChange(ind.copy(markets = it)) }
                Check("Opens and previous day", ind.opens) { onChange(ind.copy(opens = it)) }
                if (ind.opens) Indent {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Pill("Daily", ind.opensDay, Color(0xFFFFCA28)) { onChange(ind.copy(opensDay = !ind.opensDay)) }
                        Pill("Weekly", ind.opensWeek, Color(0xFFFF8A65)) { onChange(ind.copy(opensWeek = !ind.opensWeek)) }
                        Pill("Monthly", ind.opensMonth, Color(0xFFF06292)) { onChange(ind.copy(opensMonth = !ind.opensMonth)) }
                        Pill("NY mid", ind.opensNyMid, Color(0xFFBA68C8)) { onChange(ind.copy(opensNyMid = !ind.opensNyMid)) }
                        Pill("PDH/L", ind.prevDay, Color(0xFF9E9E9E)) { onChange(ind.copy(prevDay = !ind.prevDay)) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Faint past", style = label, modifier = Modifier.width(62.dp))
                        listOf(1.0, 3.0, 5.0, 100.0).forEach { p -> Pill(if (p >= 100) "never" else "${p.toInt()}%", ind.fadePct == p) { onChange(ind.copy(fadePct = p)) } }
                    }
                }
                Spacer(Modifier.size(6.dp))
                Pill("Reset all to defaults", false) { onChange(Ind()) }
            }
        }
    }
}

@Composable
private fun Section(t: String) {
    Text(t.uppercase(), style = label.copy(fontSize = 10.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold, color = D.accent),
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
}

@Composable
private fun Indent(content: @Composable () -> Unit) {
    Column(Modifier.padding(start = 22.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
fun Check(text: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(4.dp)).clickable { onChange(!on) }.pointerHoverIcon(PointerIcon.Hand).padding(vertical = 3.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(13.dp).clip(RoundedCornerShape(3.dp)).background(if (on) D.accent else D.line),
            contentAlignment = Alignment.Center,
        ) { if (on) Text("✓", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, color = D.text)
    }
}

@Composable
private fun Choice(options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { options.forEach { (k, t) -> Pill(t, k == value) { onPick(k) } } }
}
