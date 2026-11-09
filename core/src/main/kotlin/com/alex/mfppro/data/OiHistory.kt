package com.alex.mfppro.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

enum class OiRange(val label: String, val binance: String, val bybit: String, val limit: Int) {
    H24("24 Hours", "5m", "15min", 288),
    D7("7 Days", "1h", "1h", 168),
    D30("30 Days", "4h", "4h", 180),
}

/** usd/coins are open interest; price is the mark implied at that time (Binance only). */
data class OiSample(val time: Long, val usd: Double, val coins: Double, val price: Double?)

data class OiHistory(val samples: List<OiSample>, val longPct: Double?)

/**
 * Historical open interest straight from the venue's public futures data,
 * which is where MFP's own Trading Data panel gets it. Hyperliquid and FX
 * venues have no history endpoint; the app samples live OI for those.
 */
class OiHistoryApi(private val http: OkHttpClient) {

    fun supported(provider: String) = provider == "binance" || provider == "bybit"

    suspend fun load(provider: String, coin: String, range: OiRange, mark: Double?): OiHistory? = when (provider) {
        "binance" -> binance(coin, range)
        "bybit" -> bybit(coin, range, mark)
        else -> null
    }

    /** OI change over the last 24 hours in percent, from hourly venue data. */
    suspend fun change24h(provider: String, coin: String): Double? {
        val list: List<Double> = when (provider) {
            "binance" -> (get("https://fapi.binance.com/futures/data/openInterestHist?symbol=$coin&period=1h&limit=25") as JsonArray)
                .mapNotNull { it.jsonObject.dbl("sumOpenInterestValue") }
            "bybit" -> ((get("https://api.bybit.com/v5/market/open-interest?category=linear&symbol=$coin&intervalTime=1h&limit=25") as JsonObject)
                .obj("result")?.arr("list").orEmpty()).mapNotNull { (it as? JsonObject)?.dbl("openInterest") }.reversed()
            else -> return null
        }
        if (list.size < 2 || list.first() <= 0) return null
        return (list.last() / list.first() - 1) * 100
    }

    private suspend fun binance(coin: String, range: OiRange): OiHistory = coroutineScope {
        val base = "https://fapi.binance.com/futures/data"
        val oi = async { get("$base/openInterestHist?symbol=$coin&period=${range.binance}&limit=${minOf(range.limit, 500)}") }
        val ls = async { runCatching { get("$base/globalLongShortAccountRatio?symbol=$coin&period=5m&limit=1") }.getOrNull() }
        val samples = (oi.await() as JsonArray).map {
            val o = it.jsonObject
            val coins = o.dbl("sumOpenInterest") ?: 0.0
            val usd = o.dbl("sumOpenInterestValue") ?: 0.0
            OiSample(o.long("timestamp") ?: 0, usd, coins, if (coins > 0) usd / coins else null)
        }.sortedBy { it.time }
        val longPct = (ls.await() as? JsonArray)?.lastOrNull()?.jsonObject?.dbl("longAccount")?.times(100)
        OiHistory(samples, longPct)
    }

    private suspend fun bybit(coin: String, range: OiRange, mark: Double?): OiHistory = coroutineScope {
        val base = "https://api.bybit.com/v5/market"
        val oi = async { get("$base/open-interest?category=linear&symbol=$coin&intervalTime=${range.bybit}&limit=${minOf(range.limit, 200)}") }
        val ls = async { runCatching { get("$base/account-ratio?category=linear&symbol=$coin&period=5min&limit=1") }.getOrNull() }
        val list = (oi.await() as JsonObject).obj("result")?.arr("list").orEmpty()
        // Bybit reports contracts only; value them at the current mark.
        val samples = list.map {
            val o = it.jsonObject
            val coins = o.dbl("openInterest") ?: 0.0
            OiSample(o.long("timestamp") ?: 0, coins * (mark ?: 0.0), coins, null)
        }.sortedBy { it.time }
        val buy = ((ls.await() as? JsonObject)?.obj("result")?.arr("list")?.firstOrNull() as? JsonObject)?.dbl("buyRatio")
        OiHistory(samples, buy?.times(100))
    }

    private suspend fun get(url: String): JsonElement = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "http_${r.code}", "HTTP ${r.code}")
            restJson.parseToJsonElement(r.body?.string().orEmpty())
        }
    }
}

/** Percentage change of OI from [ago] ms before the newest sample. */
fun List<OiSample>.changeOver(ago: Long): Double? {
    val last = lastOrNull() ?: return null
    val ref = lastOrNull { it.time <= last.time - ago } ?: return null
    return if (ref.usd > 0) (last.usd / ref.usd - 1) * 100 else null
}
