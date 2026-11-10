package com.alex.mfppro.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alex.mfppro.data.Candle
import com.alex.mfppro.data.Market
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.Sub
import com.alex.mfppro.data.candleKey
import com.alex.mfppro.ui.fmtPct
import com.alex.mfppro.ui.fmtPrice
import com.alex.mfppro.ui.ticker
import kotlinx.coroutines.delay

val INTERVALS = listOf("1m", "3m", "5m", "15m", "30m", "1h", "4h", "1d")

/** Grid shapes: name, then rows of column counts. */
private val PRESETS = listOf(
    "1" to listOf(1),
    "2" to listOf(2),
    "1+2" to listOf(1, 2),
    "4" to listOf(2, 2),
    "6" to listOf(3, 3),
)
private val DEFAULT_SYMBOLS = listOf(listOf("BTC"), listOf("ETH"), listOf("SOL"), listOf("NAS100", "XYZ100", "NQ100"), listOf("SP500", "SPX"), listOf("XAU", "GOLD"))

class Slot(val marketId: String, val interval: String)

/** The chart grid: its shape, what each chart shows, and which one is active. Saved in layout.json. */
class GridState(private val store: JsonFileStore) {
    var preset by mutableStateOf(store.getString("grid", "1") ?: "1")
        private set
    val slots = mutableStateListOf<Slot>().apply {
        store.getString("slots", null)?.split(';')?.mapNotNull { s -> s.split('|').takeIf { it.size == 2 }?.let { Slot(it[0], it[1]) } }?.let { addAll(it) }
        while (size < 6) add(Slot("", "15m"))
    }
    var active by mutableIntStateOf(store.getInt("activeSlot", 0).coerceIn(0, 5))
        private set
    /** One chart blown up to the whole grid (double-click a header), or null. */
    var maxed by mutableStateOf<Int?>(null)

    val count get() = (PRESETS.firstOrNull { it.first == preset }?.second ?: listOf(1)).sum()

    fun choose(p: String) { preset = p; if (active >= count) active = 0; maxed = null; save() }
    fun activate(i: Int) { if (active != i) { active = i; save() } }
    fun set(i: Int, marketId: String? = null, interval: String? = null) {
        val s = slots[i]
        slots[i] = Slot(marketId ?: s.marketId, interval ?: s.interval); save()
    }
    private fun save() = store.edit().putString("grid", preset).putInt("activeSlot", active)
        .putString("slots", slots.joinToString(";") { it.marketId + "|" + it.interval }).apply()

    /** Saved market, or the slot's default once markets have loaded. */
    fun market(i: Int, all: List<Market>): Market? =
        slots[i].marketId.takeIf { it.isNotBlank() }?.let { id -> all.firstOrNull { it.marketId == id } }
            ?: DEFAULT_SYMBOLS.getOrNull(i)?.firstNotNullOfOrNull { sym -> all.firstOrNull { it.symbol.equals(sym, true) } }
            ?: all.getOrNull(i)
}

/** Every chart in the grid, with the grid's toolbar on top. */
@Composable
fun ChartGrid(repo: Repo, grid: GridState, ind: Ind, onInd: (Ind) -> Unit, modifier: Modifier) {
    val markets by repo.markets.collectAsState()
    val venues by repo.venues.collectAsState()
    val candles by repo.market.candles.collectAsState()
    val marks by repo.market.marks.collectAsState()
    val stats by repo.market.stats.collectAsState()
    val accounts by repo.account.accounts.collectAsState()
    val all = markets + venues.filter { v -> markets.none { it.marketId == v.marketId } }
    val shape = PRESETS.firstOrNull { it.first == grid.preset }?.second ?: listOf(1)

    Column(modifier.background(D.bg)) {
        Row(
            Modifier.fillMaxWidth().height(30.dp).background(D.panel).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("CHARTS", style = label.copy(fontSize = 10.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.width(6.dp))
            PRESETS.forEach { (name, _) -> Pill(if (name == "1") "Single" else name, grid.preset == name && grid.maxed == null) { grid.choose(name) } }
            Spacer(Modifier.width(6.dp))
            IndicatorMenu(ind, onInd)
            Box(Modifier.weight(1f))
            Text("Wheel: zoom · drag: pan · drag the axis: scale · double-click: fit", style = label.copy(fontSize = 10.sp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(D.line))

        @Composable
        fun cell(i: Int, mod: Modifier) {
            val m = grid.market(i, all)
            ChartCell(repo, grid, i, m, all, candles, marks[m?.feedKey] ?: m?.let { stats[it.feedKey]?.markPx }, stats[m?.feedKey]?.change24hPct,
                if (m == null) emptyList() else accounts.values.flatMap { it.positions.values }.filter { it.inMarket(m) }.flatMap { p ->
                    val col = if (p.isLong) D.up else D.down
                    listOfNotNull(
                        ChartLine(p.entryPrice, col, (if (p.isLong) "Long " else "Short ") + com.alex.mfppro.ui.fmtUsd(p.size * p.entryPrice)),
                        p.liquidationPrice?.let { ChartLine(it, Color(0xFFFF7A00), "Liq", dotted = true) },
                    )
                }, ind, mod)
        }

        val maxed = grid.maxed
        if (maxed != null) cell(maxed, Modifier.weight(1f).fillMaxWidth())
        else {
            var i = 0
            shape.forEachIndexed { r, cols ->
                if (r > 0) Box(Modifier.fillMaxWidth().height(3.dp))
                Row(Modifier.weight(if (shape.size == 2 && shape[0] == 1 && cols == 2) 0.8f else 1f).fillMaxWidth()) {
                    repeat(cols) { c ->
                        if (c > 0) Box(Modifier.width(3.dp).fillMaxHeight())
                        cell(i, Modifier.weight(1f).fillMaxHeight())
                        i++
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ChartCell(
    repo: Repo, grid: GridState, i: Int, m: Market?, all: List<Market>, candles: Map<String, List<Candle>>,
    mark: Double?, chg24: Double?, lines: List<ChartLine>, ind: Ind, modifier: Modifier,
) {
    val active = grid.active == i
    val iv = grid.slots[i].interval.takeIf { it in INTERVALS } ?: "15m"
    val compact = grid.count > 2 && grid.maxed == null
    Column(
        modifier.background(D.panel)
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { grid.activate(i) },
    ) {
        Row(
            Modifier.fillMaxWidth().height(28.dp).background(if (active && grid.count > 1) D.panel2 else D.panel).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (grid.count > 1) Box(Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(if (active) D.accent else Color.Transparent))
            MarketPicker(all, m) { picked -> grid.set(i, marketId = picked.marketId); grid.activate(i) }
            if (m != null) {
                Text(fmtPrice(mark, m), style = num.copy(fontWeight = FontWeight.SemiBold, color = pnlColor(chg24)))
                if (!compact) Text(fmtPct(chg24) + " 24h", style = num.copy(fontSize = 11.sp, color = pnlColor(chg24)))
            }
            Box(Modifier.weight(1f))
            INTERVALS.filter { !compact || it in setOf("1m", "5m", "15m", "1h", "4h", "1d") }.forEach { x ->
                Pill(x, x == iv) { grid.set(i, interval = x) }
            }
            if (grid.count > 1) Pill(if (grid.maxed == i) "⤡" else "⤢", false) { grid.maxed = if (grid.maxed == i) null else i }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(D.line))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (m == null) Empty("Loading markets…")
            else {
                Subscribe(repo, Sub("candles", m.provider, m.coin, iv))
                Subscribe(repo, Sub("ticks", m.provider, m.coin))
                Subscribe(repo, Sub("marketStats", m.provider, m.coin))
                var days by remember(m.marketId) { mutableStateOf<List<Candle>>(emptyList()) }
                LaunchedEffect(m.marketId) {
                    while (true) {
                        val d = runCatching { repo.history(m, "1d", 40) }.getOrDefault(emptyList())
                        if (d.isNotEmpty()) days = d
                        delay(if (d.isEmpty()) 10_000 else 600_000)
                    }
                }
                val raw = candles[candleKey(m.provider, m.coin, iv)].orEmpty()
                if (raw.isEmpty()) Empty("Loading ${m.symbol} $iv…")
                else ChartView(raw, mark, m, ind, lines, days, Modifier.fillMaxSize(), compact = compact)
            }
        }
    }
}

/** The chart's market name; click for a searchable list of every market. */
@Composable
fun MarketPicker(all: List<Market>, m: Market?, onPick: (Market) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(4.dp)).clickable { open = true }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(m?.let { ticker(it.symbol, it.provider) } ?: "Pick market", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
            Text(" ▾", color = D.muted, fontSize = 10.sp)
        }
        DropdownMenu(open, { open = false; q = "" }, offset = DpOffset(0.dp, 2.dp), modifier = Modifier.background(D.panel2)) {
            Column(Modifier.width(280.dp).padding(horizontal = 8.dp)) {
                Field(q, { q = it }, Modifier.fillMaxWidth(), placeholder = "Search markets")
                val list = all.filter { q.isBlank() || it.symbol.contains(q.trim(), true) || it.provider.contains(q.trim(), true) }
                    .sortedWith(compareBy({ !it.symbol.startsWith(q.trim(), true) }, { it.symbol }))
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 6.dp)) {
                    items(list.take(80), key = { it.marketId }) { x ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable { onPick(x); open = false; q = "" }
                                .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 6.dp, vertical = 5.dp),
                        ) {
                            Text(x.symbol, Modifier.weight(1f), fontSize = 12.sp, fontWeight = if (x.marketId == m?.marketId) FontWeight.Bold else FontWeight.Normal,
                                color = if (x.marketId == m?.marketId) D.accent else D.text)
                            Text(x.provider, style = label.copy(fontSize = 10.sp))
                        }
                    }
                }
            }
        }
    }
}
