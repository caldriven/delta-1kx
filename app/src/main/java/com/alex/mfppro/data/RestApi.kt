package com.alex.mfppro.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

const val LIVE_REST = "https://developers.myfundedperpetuals.com"
const val SANDBOX_REST = "https://sandbox.myfundedperpetuals.com"

fun restBase(key: String) = if (key.startsWith("fp_test_")) SANDBOX_REST else LIVE_REST

class ApiException(val status: Int, val code: String, message: String) : IOException(message)

class RestApi(private val http: OkHttpClient) {

    suspend fun accounts(key: String): List<Account> =
        get<DataList<Account>>(key, "/v1/accounts").data

    suspend fun account(key: String, id: String): Account =
        get<DataOne<Account>>(key, "/v1/accounts/$id").data

    suspend fun positions(key: String, accountId: String): List<Position> =
        get<DataList<Position>>(key, "/v1/positions?account_id=" + java.net.URLEncoder.encode(accountId, "UTF-8")).data

    suspend fun fills(key: String, accountId: String, cursor: String?): FillPage =
        get(key, "/v1/fills?limit=100&account_id=" + java.net.URLEncoder.encode(accountId, "UTF-8") +
            (cursor?.let { "&cursor=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""))

    suspend fun tradingPolicy(key: String, accountId: String): TradingPolicy =
        get<DataOne<TradingPolicy>>(key, "/v1/accounts/" + java.net.URLEncoder.encode(accountId, "UTF-8") + "/trading-policy").data

    /** Public catalog; served on the live host without a key. */
    suspend fun markets(): List<Market> =
        get<DataList<Market>>(null, "/v1/markets").data

    /** Every venue that accepts new positions, default and alternates. */
    suspend fun allVenues(): List<Market> =
        get<DataList<Market>>(null, "/v1/markets?venues=all").data

    private suspend inline fun <reified T> get(key: String?, path: String): T = withContext(Dispatchers.IO) {
        val base = if (key == null) LIVE_REST else restBase(key)
        val req = Request.Builder().url(base + path).apply {
            if (key != null) header("Authorization", "Bearer $key")
        }.build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw parseError(res.code, body)
            restJson.decodeFromString<T>(body)
        }
    }

    private fun parseError(status: Int, body: String): ApiException = runCatching {
        val err = restJson.parseToJsonElement(body).jsonObject["error"]!!.jsonObject
        ApiException(status, err["code"]!!.jsonPrimitive.content, err["message"]!!.jsonPrimitive.content)
    }.getOrElse { ApiException(status, "http_$status", "HTTP $status") }
}
