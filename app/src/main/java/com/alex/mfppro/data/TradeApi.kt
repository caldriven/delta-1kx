package com.alex.mfppro.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID

@Serializable
data class Quote(
    val bid: Double? = null,
    val ask: Double? = null,
    val mid: Double? = null,
    val fillable: Boolean? = null,
    val unfillableReason: String? = null,
    val estimatedFillPrice: Double? = null,
    val worstPrice: Double? = null,
    val estimatedNotional: Double? = null,
    val estimatedFee: Double? = null,
    val feeBps: Double? = null,
)

/** Result of a write: the order the server returned, or a readable rejection. */
sealed class TradeResult {
    data class Ok(val order: Order?, val queued: Boolean = false) : TradeResult()
    data class Rejected(val code: String, val message: String) : TradeResult()
}

/**
 * Trading writes. Every create/close carries an Idempotency-Key generated once
 * per user action; network failures retry with the same key so a lost
 * response can never place a duplicate order.
 */
class TradeApi(private val http: OkHttpClient) {
    private val jsonType = "application/json".toMediaType()

    suspend fun quote(key: String, marketId: String, side: String, size: Double): Quote =
        restJson.decodeFromJsonElement(
            DataOne.serializer(Quote.serializer()),
            call(key, "GET", "/v1/markets/${enc(marketId)}/quote?side=$side&size=$size"),
        ).data

    suspend fun placeOrder(key: String, body: JsonObject) = write(key, "POST", "/v1/orders", body, idempotent = true)

    /** Limit ladder from start to end price (2–10 rungs); each rung is an ordinary limit order. */
    suspend fun placeScaled(key: String, body: JsonObject) = write(key, "POST", "/v1/scaled-orders", body, idempotent = true)

    suspend fun closePosition(key: String, positionId: String, body: JsonObject) =
        write(key, "POST", "/v1/positions/${enc(positionId)}/close", body, idempotent = true)

    suspend fun cancelOrder(key: String, orderId: String) =
        write(key, "DELETE", "/v1/orders/${enc(orderId)}", null, idempotent = false)

    /** Atomic TP/SL batch, guarded by the position size and exit orders we observed. */
    suspend fun replaceExits(key: String, position: Position, current: List<Order>, ops: List<JsonObject>) =
        write(key, "PUT", "/v1/positions/${enc(position.id)}/exit-orders", buildJsonObject {
            put("expected_position_size", position.size)
            put("expected_orders", buildJsonArray {
                current.forEach { o ->
                    add(buildJsonObject {
                        put("order_id", o.id); put("execution_type", o.exitExecution)
                        put("price", o.exitPrice ?: 0.0); put("size", o.size)
                    })
                }
            })
            put("operations", buildJsonArray { ops.forEach { add(it) } })
        }, idempotent = false)

    /** Account-wide close. The server advances it in steps; repeat with the same key until done. */
    suspend fun closeAll(key: String, accountId: String): TradeResult =
        resumable(key, "/v1/accounts/${enc(accountId)}/close-all-positions")

    suspend fun cancelAll(key: String, accountId: String): TradeResult =
        resumable(key, "/v1/accounts/${enc(accountId)}/cancel-all-orders")

    private suspend fun resumable(key: String, path: String): TradeResult {
        val idem = UUID.randomUUID().toString()
        repeat(120) {
            val res = try {
                raw(key, "POST", path, buildJsonObject { }, idem)
            } catch (e: IOException) {
                delay(1000); return@repeat
            }
            when (res.status) {
                200, 201 -> return TradeResult.Ok(null)
                202, 409 -> delay((res.retryAfter ?: 1) * 1000L)
                else -> return rejection(res)
            }
        }
        return TradeResult.Rejected("timeout", "Still in progress; check positions")
    }

    private suspend fun write(key: String, method: String, path: String, body: JsonObject?, idempotent: Boolean): TradeResult {
        val idem = if (idempotent) UUID.randomUUID().toString() else null
        var lastError: IOException? = null
        repeat(if (idempotent) 3 else 1) { attempt ->
            try {
                val res = raw(key, method, path, body, idem)
                if (res.status in 200..299) {
                    val data = (res.json as? JsonObject)?.get("data") as? JsonObject
                    val order = data?.let { d -> (d["order"] as? JsonObject ?: d).let { runCatching { restJson.decodeFromJsonElement(Order.serializer(), it) }.getOrNull() } }
                    return TradeResult.Ok(order, queued = data?.str("queued") == "true")
                }
                if (res.status == 429 && attempt < 2) { delay((res.retryAfter ?: 1) * 1000L); return@repeat }
                return rejection(res)
            } catch (e: IOException) {
                lastError = e
                delay(400L * (attempt + 1))
            }
        }
        return TradeResult.Rejected("network", lastError?.message ?: "Network error; check open orders before retrying")
    }

    private fun rejection(res: Raw): TradeResult.Rejected {
        val err = (res.json as? JsonObject)?.get("error") as? JsonObject
        val code = err?.str("code") ?: "http_${res.status}"
        val detail = err?.arr("details")?.joinToString("; ") {
            val o = it.jsonObject; "${o.str("field")}: ${o.str("issue")}"
        }.orEmpty()
        return TradeResult.Rejected(code, listOfNotNull(err?.str("message"), detail.ifBlank { null }).joinToString(" — ").ifBlank { code })
    }

    private class Raw(val status: Int, val json: JsonElement?, val retryAfter: Long?)

    private suspend fun call(key: String, method: String, path: String): JsonElement {
        val res = raw(key, method, path, null, null)
        if (res.status !in 200..299) {
            val r = rejection(res); throw ApiException(res.status, r.code, r.message)
        }
        return res.json ?: throw IOException("empty response")
    }

    private suspend fun raw(key: String, method: String, path: String, body: JsonObject?, idem: String?): Raw = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(restBase(key) + path)
            .header("Authorization", "Bearer $key")
            .apply { if (idem != null) header("Idempotency-Key", idem) }
            .method(method, body?.toString()?.toRequestBody(jsonType))
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            Raw(r.code, runCatching { restJson.parseToJsonElement(text) }.getOrNull(), r.header("Retry-After")?.toLongOrNull())
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
