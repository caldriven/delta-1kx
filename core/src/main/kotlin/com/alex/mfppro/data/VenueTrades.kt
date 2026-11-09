package com.alex.mfppro.data

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicReference

/**
 * Public trade prints straight from the big perp venues (no keys), for an
 * aggr-style tape across exchanges. Each venue reconnects on its own; a venue
 * that doesn't list the coin simply stays quiet.
 */
class VenueTrades(private val http: OkHttpClient) {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** Trades for [base] (e.g. "HYPE") from every venue, tagged with the venue name. */
    fun stream(base: String): Flow<Pair<String, Trade>> = callbackFlow {
        val b = base.uppercase()
        val sockets = mutableListOf<AtomicReference<WebSocket?>>()

        fun venue(name: String, url: String, subscribe: String?, parse: (JsonObject) -> List<Trade>) {
            val ref = AtomicReference<WebSocket?>(null)
            sockets += ref
            launch {
                var backoff = 1_000L
                while (isActive) {
                    val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
                    val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            backoff = 1_000L
                            subscribe?.let { webSocket.send(it) }
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (text == "pong") return
                            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                            runCatching { parse(o) }.getOrNull()?.forEach { trySend(name to it) }
                        }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
                    })
                    ref.set(ws)
                    closed.await()
                    delay(backoff)
                    backoff = minOf(backoff * 2, 30_000L)
                }
            }
        }

        // Binance USD-M futures: aggTrade already merges fills of one taker order. m = buyer is maker -> a sell.
        venue("binance", "wss://fstream.binance.com/ws/${b.lowercase()}usdt@aggTrade", null) { o ->
            listOf(Trade("b" + o.str("a"), o.str("m") != "true", o.num("p"), o.num("q"), o.str("T").toLong()))
        }
        // Bybit linear perps.
        venue("bybit", "wss://stream.bybit.com/v5/public/linear", """{"op":"subscribe","args":["publicTrade.${b}USDT"]}""") { o ->
            (o["data"] as? JsonArray ?: return@venue emptyList()).map { e ->
                val t = e.jsonObject
                Trade("y" + t.str("i"), t.str("S") == "Buy", t.num("p"), t.num("v"), t.str("T").toLong())
            }
        }
        // Hyperliquid: side B = taker bought.
        venue("hyperliquid", "wss://api.hyperliquid.xyz/ws", """{"method":"subscribe","subscription":{"type":"trades","coin":"$b"}}""") { o ->
            if (o.str("channel") != "trades") return@venue emptyList()
            o["data"]!!.jsonArray.map { e ->
                val t = e.jsonObject
                Trade("h" + t.str("tid"), t.str("side") == "B", t.num("px"), t.num("sz"), t.str("time").toLong())
            }
        }
        // OKX swaps quote size in contracts; convert with the instrument's contract value.
        launch {
            val inst = "$b-USDT-SWAP"
            val ctVal = withContext(Dispatchers.IO) {
                runCatching {
                    http.newCall(Request.Builder().url("https://www.okx.com/api/v5/public/instruments?instType=SWAP&instId=$inst").build()).execute().use { r ->
                        json.parseToJsonElement(r.body!!.string()).jsonObject["data"]!!.jsonArray.first().jsonObject.num("ctVal")
                    }
                }.getOrNull()
            } ?: return@launch
            venue("okx", "wss://ws.okx.com:8443/ws/v5/public", """{"op":"subscribe","args":[{"channel":"trades","instId":"$inst"}]}""") { o ->
                (o["data"] as? JsonArray ?: return@venue emptyList()).map { e ->
                    val t = e.jsonObject
                    Trade("o" + t.str("tradeId"), t.str("side") == "buy", t.num("px"), t.num("sz") * ctVal, t.str("ts").toLong())
                }
            }
        }

        awaitClose { sockets.forEach { it.get()?.close(1000, null) } }
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content ?: ""
    private fun JsonObject.num(k: String) = this[k]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
}
