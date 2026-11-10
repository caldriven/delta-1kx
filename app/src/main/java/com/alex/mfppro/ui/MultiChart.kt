package com.alex.mfppro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alex.mfppro.data.AssetClasses
import com.alex.mfppro.data.Market
import com.alex.mfppro.data.Repo
import com.alex.mfppro.data.Sub
import com.alex.mfppro.data.candleKey

/** Default four: the two crypto majors next to US stock indices. Each slot tries its names in order. */
private val DEFAULT_SLOTS = listOf(listOf("BTC"), listOf("ETH"), listOf("SP500", "SPX"), listOf("NAS100", "XYZ100", "NQ100"))
private val MULTI_INTERVALS = listOf("1m", "5m", "15m", "1h", "4h", "1d")

/**
 * Four charts at once on one shared candle size. Tap a chart's name to swap the market;
 * the arrow opens it on the Trade tab. Slots are remembered.
 */
@Composable
fun MultiChart(repo: Repo, onOpenMarket: (String) -> Unit) {
    val markets by repo.markets.collectAsStateWithLifecycle()
    val venues by repo.venues.collectAsStateWithLifecycle()
    val classes by repo.classes.collectAsStateWithLifecycle()
    val stats by repo.market.stats.collectThrottled(1000)
    val marks by repo.market.marks.collectThrottled(500)
    val candles by repo.market.candles.collectThrottled(500)
    var interval by remember { mutableStateOf(repo.prefs.multiInterval) }
    var ids by remember { mutableStateOf(repo.prefs.multiCharts) }
    var picking by remember { mutableStateOf<Int?>(null) }

    val all = markets + venues.filter { v -> markets.none { it.marketId == v.marketId } }
    val slots: List<Market?> = DEFAULT_SLOTS.indices.map { i ->
        ids.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { id -> all.firstOrNull { it.marketId == id } }
            ?: DEFAULT_SLOTS[i].firstNotNullOfOrNull { sym -> markets.firstOrNull { it.symbol.equals(sym, true) } }
    }

    picking?.let { slot ->
        MarketPicker(repo, markets, classes, onDismiss = { picking = null }) { m ->
            picking = null
            val next = List(4) { i -> if (i == slot) m.marketId else ids.getOrNull(i).orEmpty() }
            ids = next; repo.prefs.multiCharts = next
        }
    }

    Column(Modifier.fillMaxSize().background(C.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Candles", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MULTI_INTERVALS.forEach { iv -> Seg(iv, iv == interval, C.accent) { interval = iv; repo.prefs.multiInterval = iv } }
            }
            if (ids.any { it.isNotBlank() }) Text("Reset", color = C.muted, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { ids = emptyList(); repo.prefs.multiCharts = emptyList() }.padding(6.dp))
        }
        for (row in 0..1) Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (col in 0..1) {
                val i = row * 2 + col
                val m = slots[i]
                Column(Modifier.weight(1f).fillMaxSize().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp)).background(C.panel)) {
                    if (m == null) {
                        Text("Tap to pick a market", color = C.muted, fontSize = 12.sp,
                            modifier = Modifier.fillMaxSize().clickable { picking = i }.padding(12.dp))
                    } else key(m.marketId, interval) {
                        DisposableEffect(m.marketId, interval) {
                            val sub = Sub("candles", m.provider, m.coin, interval)
                            repo.market.acquire(sub)
                            onDispose { repo.market.release(sub) }
                        }
                        val mark = marks[m.feedKey] ?: stats[m.feedKey]?.markPx
                        val chg = stats[m.feedKey]?.change24hPct
                        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { picking = i }, verticalAlignment = Alignment.CenterVertically) {
                                MarketLogo(m, AssetClasses.classOf(m, classes), 16.dp)
                                Spacer(Modifier.width(4.dp))
                                Text(m.symbol, color = C.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                Icon(Icons.Filled.ArrowDropDown, "Change market", tint = C.muted, modifier = Modifier.size(16.dp))
                            }
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in Trade", tint = C.muted,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onOpenMarket(m.marketId) }.padding(4.dp).size(14.dp))
                        }
                        Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.Bottom) {
                            Text(fmtPrice(mark), style = num.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pnlColor(chg)), maxLines = 1)
                            Spacer(Modifier.width(4.dp))
                            Text(fmtPct(chg) + " 24h", style = num.copy(fontSize = 10.sp, color = pnlColor(chg)), maxLines = 1)
                        }
                        CandleChart(candles[candleKey(m.provider, m.coin, interval)].orEmpty().withLive(mark), emptyList(),
                            Modifier.weight(1f).fillMaxWidth().padding(top = 2.dp), compactAxis = true,
                            sessions = if (repo.prefs.showSessions) SessionOpts(false, repo.prefs.killzones) else null)
                    }
                }
            }
        }
        Spacer(Modifier.size(4.dp))
    }
}
