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

/**
 * MFP's clock, from the market stream's ping replies, so day boundaries don't depend on the
 * phone's clock. The day-open time zone, if any, is applied in [Opens].
 */
object ServerClock {
    @Volatile var offset = 0L
    fun now() = System.currentTimeMillis() + offset
    /** Milliseconds until the next 00:00 UTC. */
    fun untilUtcMidnight() = 86_400_000L - Math.floorMod(now(), 86_400_000L)
}

/**
 * Session boundaries for the daily / weekly / monthly opens. By default 00:00 UTC (what the
 * venues' daily candles use). [configure] moves the open to an hour in a time zone, e.g. 00:00
 * Europe/Oslo (UTC+1 in winter, UTC+2 in summer) or 17:00 New York, with summer time handled
 * by the zone. Every MFP venue's daily candle opens at 00:00 UTC (checked for Binance, Bybit,
 * Hyperliquid, Veranta and Ostium), so off UTC midnight the opens come from 1h candles.
 * Times follow [ServerClock], not the phone's clock.
 */
object Opens {
    private val UTC: java.time.ZoneId = java.time.ZoneOffset.UTC
    @Volatile private var zone: java.time.ZoneId = UTC
    @Volatile private var hour = 0

    /** Set from Settings; [zoneId] is an IANA zone ("UTC", "Europe/Oslo", "America/New_York"). */
    fun configure(zoneId: String, openHour: Int) {
        zone = runCatching { java.time.ZoneId.of(zoneId) }.getOrDefault(UTC)
        hour = openHour.coerceIn(0, 23)
    }
    /** True when the open isn't 00:00 UTC and needs hourly candles. */
    val custom get() = !(hour == 0 && (zone == UTC || zone.id == "UTC" || zone.id == "Z"))
    /** Changes whenever the setting does; key effects on it. */
    val key get() = "${zone.id}|$hour"

    /** An open at or after noon starts the next day's session (17:00 New York opens Monday on Sunday). */
    private fun lead() = if (hour >= 12) 1L else 0L
    private fun startOf(tradeDate: java.time.LocalDate) =
        tradeDate.minusDays(lead()).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    /** The session (trade date) running now. */
    private fun tradeDate(now: Long = ServerClock.now()): java.time.LocalDate {
        val local = java.time.Instant.ofEpochMilli(now).atZone(zone)
        val d = local.toLocalDate().plusDays(lead())
        return if (now >= startOf(d)) d else d.minusDays(1)
    }
    fun dayStart() = startOf(tradeDate())
    fun weekStart() = startOf(tradeDate().with(java.time.DayOfWeek.MONDAY))
    fun monthStart() = startOf(tradeDate().withDayOfMonth(1))
    /** Milliseconds until the next daily open (00:00 UTC unless set otherwise). */
    fun untilNextDay(): Long = startOf(tradeDate().plusDays(1)) - ServerClock.now()

    /** Open of the first candle at or after [t]; markets closed at the boundary use the next session. */
    fun openAt(days: List<Candle>, t: Long): Double? = days.firstOrNull { it.openTime >= t }?.o

    /**
     * Today's open, or the last close when today's session hasn't printed yet. With a custom
     * open time it comes from [hours] (1h candles); without them it falls back to the UTC day.
     */
    fun dayOpen(days: List<Candle>, hours: List<Candle>? = null): Double? =
        if (custom && !hours.isNullOrEmpty()) openAt(hours, dayStart()) ?: hours.lastOrNull()?.c
        else openAt(days, dayStart()) ?: days.lastOrNull()?.c

    /** Week / month open: from 1h candles when they reach back far enough, else the daily candle. */
    fun openAt(days: List<Candle>, hours: List<Candle>?, t: Long): Double? =
        if (custom && hours != null && (hours.firstOrNull()?.openTime ?: Long.MAX_VALUE) <= t) openAt(hours, t) else openAt(days, t)

    /** Classic floor pivots: P from the last full period's high, low and close, three levels each side. */
    data class Pivots(val p: Double, val r1: Double, val r2: Double, val r3: Double, val s1: Double, val s2: Double, val s3: Double)

    /** Pivots from the last completed day (in the chosen session time, from [hours] when set), or week. Null without data. */
    fun pivots(days: List<Candle>, weekly: Boolean, hours: List<Candle>? = null): Pivots? {
        val end = if (weekly) weekStart() else dayStart()
        // The previous session by the calendar: 23 or 25 hours on a summer-time change in a custom zone.
        val start = if (weekly) startOf(tradeDate().with(java.time.DayOfWeek.MONDAY).minusWeeks(1)) else startOf(tradeDate().minusDays(1))
        if (custom && !weekly && hours != null) {
            val bars = hours.filter { it.openTime in start until end }
            if (bars.isNotEmpty()) {
                val h = bars.maxOf { it.h }; val l = bars.minOf { it.l }; val c = bars.last().c
                val p = (h + l + c) / 3
                return Pivots(p, 2 * p - l, p + (h - l), h + 2 * (p - l), 2 * p - h, p - (h - l), l - 2 * (h - p))
            }
        }
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
