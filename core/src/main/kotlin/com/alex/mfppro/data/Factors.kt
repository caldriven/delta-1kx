package com.alex.mfppro.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Macro factors MFP doesn't list (volatility, rates, the dollar), read-only from Yahoo's public
 * chart API for the correlation view. They look like markets with provider "factor" but can't be traded.
 */
class FactorFeed(private val http: OkHttpClient) {
    companion object {
        const val PROVIDER = "factor"
        val ALL = listOf(
            factor("VIX", "^VIX"), factor("US10Y", "^TNX"), factor("DXY", "DX-Y.NYB"),
        )
        private fun factor(symbol: String, ticker: String) =
            Market("factor:$symbol", PROVIDER, symbol, ticker, sizeDecimals = 0, tradingEnabled = false)
    }

    private val cache = ConcurrentHashMap<String, Pair<Long, List<Candle>>>()

    /**
     * Closes bucketed onto the same UTC candle grid as the crypto feeds (1h, 4h, 1d), so returns
     * line up by open time. Yahoo has no 4h bars; those are built from 1h.
     */
    suspend fun candles(m: Market, interval: String, bars: Int): List<Candle> {
        val key = "${m.coin}|$interval|$bars"
        cache[key]?.let { (at, c) -> if (System.currentTimeMillis() - at < 10 * 60_000) return c }
        val (yInterval, range) = when (interval) {
            "1h" -> "1h" to "${bars / 24 + 3}d"
            "4h" -> "1h" to "${bars / 6 + 5}d"
            else -> "1d" to "${bars + 10}d"
        }
        val step = when (interval) { "1h" -> 3_600_000L; "4h" -> 4 * 3_600_000L; else -> 86_400_000L }
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/${java.net.URLEncoder.encode(m.coin, "UTF-8")}?interval=$yInterval&range=$range"
        val text = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()).execute().use { it.body?.string().orEmpty() }
        }
        val res = (restJson.parseToJsonElement(text).jsonObject["chart"]?.jsonObject?.get("result") as? JsonArray)?.firstOrNull()?.jsonObject ?: return emptyList()
        val ts = res["timestamp"]?.jsonArray ?: return emptyList()
        val close = ((res["indicators"] as? JsonObject)?.get("quote") as? JsonArray)?.firstOrNull()?.jsonObject?.get("close")?.jsonArray ?: return emptyList()
        // Last close in each bucket.
        val byBucket = java.util.TreeMap<Long, Double>()
        for (i in ts.indices) {
            val t = ts[i].jsonPrimitive.longOrNull ?: continue
            val c = close.getOrNull(i)?.jsonPrimitive?.doubleOrNull ?: continue
            byBucket[t * 1000 / step * step] = c
        }
        val out = byBucket.entries.map { (t, c) -> Candle(t, c, c, c, c, 0.0) }.takeLast(bars)
        cache[key] = System.currentTimeMillis() to out
        return out
    }
}
