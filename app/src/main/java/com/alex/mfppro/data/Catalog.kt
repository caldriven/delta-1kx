package com.alex.mfppro.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val ASSETS_URL = "https://docs.myfundedperpetuals.com/guides/tradable-assets.md"

/**
 * Asset classes for each symbol. The API has no category field, so this
 * parses MFP's own "Tradable Assets" page, which groups every market under
 * "## Crypto", "## Stocks", "## Indices" and so on.
 */
object AssetClasses {
    suspend fun load(http: OkHttpClient): Map<String, String> = withContext(Dispatchers.IO) {
        val text = http.newCall(Request.Builder().url(ASSETS_URL).build()).execute().use { it.body?.string().orEmpty() }
        parse(text)
    }

    fun parse(md: String): Map<String, String> {
        val out = HashMap<String, String>()
        var current: String? = null
        for (line in md.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("## ")) { current = t.removePrefix("## ").trim(); continue }
            val cls = current ?: continue
            if (!t.startsWith("|") || t.startsWith("| -") || t.startsWith("| Market")) continue
            val cells = t.trim('|').split('|').map { it.trim() }
            if (cells.size >= 2 && cells[1].isNotEmpty()) out[cells[1].uppercase()] = cls
        }
        return out
    }

    fun encode(m: Map<String, String>) = m.entries.joinToString(";") { "${it.key}=${it.value}" }
    fun decode(s: String) = s.split(';').mapNotNull { it.split('=').takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }.toMap()

    // Known indices and commodities, whatever section MFP's page lists them under.
    private val fixed = mapOf(
        "NAS100" to "Indices", "XYZ100" to "Indices", "SP500" to "Indices", "US500" to "Indices", "US30" to "Indices",
        "JP225" to "Indices", "GER40" to "Indices", "DE40" to "Indices", "UK100" to "Indices", "HK50" to "Indices", "US2000" to "Indices",
        "XAU" to "Commodities", "XAG" to "Commodities", "XPT" to "Commodities", "XPD" to "Commodities", "PLATINUM" to "Commodities",
        "PALLADIUM" to "Commodities", "COPPER" to "Commodities", "CL" to "Commodities", "BRENTOIL" to "Commodities", "NATGAS" to "Commodities",
    )

    fun classOf(m: Market, classes: Map<String, String>) =
        fixed[m.symbol.uppercase()] ?: classes[m.symbol.uppercase()] ?: if (m.provider == "veranta") "Forex" else "Other"
}

object Logos {
    /** A drawn badge for markets with no logo image: commodities, indices. */
    data class Tile(val text: String, val bg: Long, val fg: Long = 0xFFFFFFFF)

    private val tiles = mapOf(
        "XAU" to Tile("Au", 0xFFD4AF37, 0xFF1A1300), "XAG" to Tile("Ag", 0xFFC9CED6, 0xFF15191E),
        "XPT" to Tile("Pt", 0xFF8FA3B8), "PLATINUM" to Tile("Pt", 0xFF8FA3B8),
        "XPD" to Tile("Pd", 0xFF7E8A99), "PALLADIUM" to Tile("Pd", 0xFF7E8A99),
        "COPPER" to Tile("Cu", 0xFFB87333), "CL" to Tile("WTI", 0xFF2A2F36), "BRENTOIL" to Tile("BRN", 0xFF2A2F36),
        "NATGAS" to Tile("Gas", 0xFF2563EB),
        "NAS100" to Tile("NDX", 0xFF0EA5E9), "XYZ100" to Tile("NDX", 0xFF0EA5E9), "SP500" to Tile("SPX", 0xFFDC2626),
        "US30" to Tile("DJI", 0xFF1D4ED8), "JP225" to Tile("225", 0xFFBE123C),
    )
    private val flags = mapOf("AUD" to "au", "EUR" to "eu", "GBP" to "gb", "JPY" to "jp", "USDCAD" to "ca", "USDCHF" to "ch")
    private val stockAlias = mapOf("SAMSUNG" to "005930.KS", "SKHYNIX" to "000660.KS")

    fun tile(symbol: String): Tile? = tiles[symbol.uppercase()]

    /** Ticker logo: Binance's asset icons for crypto, FMP's for stocks and ETFs, flags for FX; null means draw a letter. */
    fun ticker(symbol: String, cls: String): String? {
        val s = symbol.uppercase().removePrefix("1000").removePrefix("1M")
        flags[s]?.let { return "https://flagcdn.com/w80/$it.png" }
        return when (cls) {
            "Crypto" -> "https://bin.bnbstatic.com/static/assets/logos/$s.png"
            "Stocks", "Indices" -> "https://financialmodelingprep.com/image-stock/${stockAlias[s] ?: s}.png"
            else -> null
        }
    }

    private val domains = mapOf(
        "binance" to "https://www.binance.com",
        "bybit" to "https://www.bybit.com",
        "hyperliquid" to "https://app.hyperliquid.xyz",
        "ostium" to "https://www.ostium.com",
        "okx" to "https://www.okx.com",
    )

    fun exchange(provider: String): String? = domains[provider]?.let {
        "https://t1.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=$it&size=64"
    }
}

/** Slower-moving overview numbers loaded on demand: 7-day price change and 24h OI change. */
data class MarketExtra(val week: Double? = null, val oi24h: Double? = null, val dayOpen: Double? = null)

/** UTC session boundaries and the daily candle opens at each of them. */
object Opens {
    private val UTC = java.time.ZoneOffset.UTC
    private fun today() = java.time.LocalDate.now(UTC)
    fun dayStart() = today().atStartOfDay(UTC).toInstant().toEpochMilli()
    fun weekStart() = today().with(java.time.DayOfWeek.MONDAY).atStartOfDay(UTC).toInstant().toEpochMilli()
    fun monthStart() = today().withDayOfMonth(1).atStartOfDay(UTC).toInstant().toEpochMilli()

    /** Open of the first daily candle at or after [t]; markets closed at the boundary use the next session. */
    fun openAt(days: List<Candle>, t: Long): Double? = days.firstOrNull { it.openTime >= t }?.o

    /** Today's UTC open, or the last close when today's candle hasn't printed yet. */
    fun dayOpen(days: List<Candle>): Double? = openAt(days, dayStart()) ?: days.lastOrNull()?.c

    /** Classic floor pivots: P from the last full period's high, low and close, three levels each side. */
    data class Pivots(val p: Double, val r1: Double, val r2: Double, val r3: Double, val s1: Double, val s2: Double, val s3: Double)

    /** Pivots from the last completed UTC day, or the last completed week when [weekly]. Null without data. */
    fun pivots(days: List<Candle>, weekly: Boolean): Pivots? {
        val end = if (weekly) weekStart() else dayStart()
        val start = if (weekly) end - 7 * 86_400_000L else end - 86_400_000L
        // Markets shut at the boundary (weekends for stocks): fall back to the latest full period seen.
        val bars = days.filter { it.openTime in start until end }.ifEmpty {
            days.filter { it.openTime < end }.let { if (weekly) it.takeLast(5) else it.takeLast(1) }
        }
        if (bars.isEmpty()) return null
        val h = bars.maxOf { it.h }; val l = bars.minOf { it.l }; val c = bars.last().c
        val p = (h + l + c) / 3
        return Pivots(p, 2 * p - l, p + (h - l), h + 2 * (p - l), 2 * p - h, p - (h - l), l - 2 * (h - p))
    }
}
