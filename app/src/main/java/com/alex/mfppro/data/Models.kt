package com.alex.mfppro.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

@OptIn(ExperimentalSerializationApi::class)
val restJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}

@Serializable
data class DataList<T>(val data: List<T>)

@Serializable
data class DataOne<T>(val data: T)

@Serializable
data class Account(
    val id: String,
    val accountNumber: String = "",
    val name: String = "",
    val stage: String = "",
    val status: String = "",
    val startingBalance: Double = 0.0,
    val balance: Double = 0.0,
    val risk: AccountRisk? = null,
)

@Serializable
data class AccountRisk(
    val equity: Double? = null,
    val unrealizedPnl: Double? = null,
    val marginUsed: Double = 0.0,
    val availableBalance: Double = 0.0,
    val dailyLossFloor: Double? = null,
    val maxDrawdownFloor: Double? = null,
    val remainingProfitEquity: Double? = null,
    val requirements: Requirements? = null,
)

@Serializable
data class Requirements(
    val dailyLossPct: Double? = null,
    val maxDrawdownPct: Double? = null,
    val profitTargetPct: Double? = null,
)

/** Rule percentages may arrive as 5 or 0.05; normalise to a fraction. */
fun pctToFraction(p: Double?) = p?.let { if (it > 1) it / 100 else it }

@Serializable
data class Position(
    val id: String,
    val accountId: String = "",
    val marketId: String = "",
    val provider: String = "",
    val symbol: String = "",
    val coin: String = "",
    val side: String = "long",
    val size: Double = 0.0,
    val entryPrice: Double = 0.0,
    val leverage: Double = 1.0,
    val marginMode: String = "cross",
    val liquidationPrice: Double? = null,
    val openedAt: Double = 0.0,
) {
    val isLong get() = side == "long"

    /** Positions may carry a differently formatted market_id; venue + coin is the reliable match. */
    fun inMarket(m: Market) = marketId == m.marketId || (provider == m.provider && coin == m.coin)
    val feedKey get() = feedKey(provider, coin)

    fun pnlAt(mark: Double) = (mark - entryPrice) * size * (if (isLong) 1 else -1)
    fun marginAt() = entryPrice * size / leverage.coerceAtLeast(1.0)
}

@Serializable
data class Order(
    val id: String,
    val clientOrderId: String? = null,
    val accountId: String = "",
    val marketId: String = "",
    val symbol: String = "",
    val type: String = "",
    val mode: String? = null,
    val side: String = "",
    val size: Double = 0.0,
    val triggerPrice: Double? = null,
    val limitPrice: Double? = null,
    val reduceOnly: Boolean = false,
    val status: String = "",
    val bracketRole: String? = null,
    val leverage: Double? = null,
) {
    /** Protective exit group for TP/SL batches, or null for an ordinary order. */
    val exitGroup: String?
        get() = when {
            bracketRole == "take_profit" -> "tp"
            bracketRole == "stop_loss" -> "sl"
            !reduceOnly -> null
            type.startsWith("take") -> "tp"
            type.startsWith("stop") -> "sl"
            else -> null
        }
    val exitExecution get() = if (type.endsWith("_limit")) "limit" else "market"
    val exitPrice get() = triggerPrice ?: limitPrice
}

@Serializable
data class Market(
    val marketId: String,
    val provider: String,
    val symbol: String,
    val coin: String,
    val sizeDecimals: Int = 0,
    val maxLeverage: Double = 1.0,
    val tickSize: Double = 0.0,
    val minSize: Double = 0.0,
    val minNotional: Double = 0.0,
    val category: String? = null,
    val tradingEnabled: Boolean = true,
) {
    val feedKey get() = feedKey(provider, coin)
}

fun feedKey(provider: String, coin: String) = "$provider|$coin"

// Market stream values (parsed by hand from the public socket).
data class Level(val px: Double, val sz: Double)
data class Book(val bids: List<Level>, val asks: List<Level>, val time: Long)
data class Trade(val id: String, val buy: Boolean, val price: Double, val size: Double, val time: Long)
data class Candle(val openTime: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)
data class Stats(
    val markPx: Double? = null,
    val change24hPct: Double? = null,
    val fundingRate: Double? = null,
    val fundingIntervalHours: Double? = null,
    val nextFundingTime: Long? = null,
    val openInterestUsd: Double? = null,
    val dayNtlVlm: Double? = null,
)
data class OiPoint(val time: Long, val oi: Double)

/** One execution from GET /v1/fills; realized PnL is non-zero on fills that reduced or closed a position. */
@Serializable
data class Fill(
    val id: String,
    val accountId: String = "",
    val marketId: String = "",
    val type: String = "",
    val reduceOnly: Boolean = false,
    val liquidityRole: String? = null,
    val settledAt: Long? = null,
    val provider: String = "",
    val symbol: String = "",
    val side: String = "",
    val size: Double = 0.0,
    val fillPrice: Double = 0.0,
    val notional: Double = 0.0,
    val fee: Double = 0.0,
    val realizedPnl: Double = 0.0,
    val requestedAt: Long = 0,
)

@Serializable
data class FillPage(val data: List<Fill> = emptyList(), val hasMore: Boolean = false, val nextCursor: String? = null)

/** Exposure caps from GET /v1/accounts/{id}/trading-policy (advisory). */
@Serializable
data class PolicyLimits(val maxPositionValueUsd: Double? = null, val maxOpenPositions: Double? = null)

@Serializable
data class TradingPolicy(val limits: PolicyLimits? = null, val maximumTotalNotionalUsd: Double? = null)
