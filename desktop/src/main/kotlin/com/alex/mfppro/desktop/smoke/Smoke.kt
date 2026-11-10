package com.alex.mfppro.desktop.smoke

import com.alex.mfppro.desktop.desktopRepo

import com.alex.mfppro.data.Killzones
import com.alex.mfppro.data.Liquidity
import com.alex.mfppro.data.Sub
import com.alex.mfppro.data.VolumeProfile
import com.alex.mfppro.data.candleKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * Headless check of the shared core on desktop JVM: loads the public MFP market list and waits
 * for a live BTC price on the market socket. No key, no window, settings in a temp folder.
 * Run with ./gradlew :desktop:smoke
 */
fun main(): Unit = runBlocking {
    val repo = desktopRepo(Files.createTempDirectory("delta1kx-smoke").toFile())
    repo.setVisible(true)
    val ok = runCatching {
        withTimeout(60_000) {
            val markets = repo.markets.first { it.isNotEmpty() }
            println("markets: ${markets.size}")
            val btc = markets.first { it.symbol == "BTC" }
            repo.market.acquire(Sub("ticks", btc.provider, btc.coin))
            val px = repo.market.marks.first { btc.feedKey in it }[btc.feedKey]
            println("live ${btc.symbol} on ${btc.provider}: $px")
            // 24h volume as the stats feed reports it, for the market list's volume column.
            val sample = markets.filter { it.symbol in setOf("BTC", "ETH", "SOL") || it.coin.startsWith("FARTCOIN") }
            sample.forEach { repo.market.acquire(Sub("marketStats", it.provider, it.coin)) }
            // The market list holds stats for every market at once, on top of the charts' own.
            markets.forEach { repo.market.acquire(Sub("marketStats", it.provider, it.coin)) }
            delay(6_000)
            val st = repo.market.stats.value
            println("stats for all: ${markets.count { st[it.feedKey]?.dayNtlVlm != null }} of ${markets.size} have volume; missing: " +
                markets.filter { st[it.feedKey]?.dayNtlVlm == null }.joinToString { it.symbol })
            sample.forEach { m -> repo.market.stats.value[m.feedKey].let { println("stats ${m.symbol}/${m.coin}@${m.provider}: 24h ${it?.change24hPct}%, vol ${it?.dayNtlVlm}, oi ${it?.openInterestUsd}") } }
            val bars = repo.history(btc, "1h", 24)
            println("history: ${bars.size} hourly candles")

            // A chart's candle feed: history lands under the live bars through the shared limiter.
            val sub = Sub("candles", btc.provider, btc.coin, "15m")
            val key = candleKey(btc.provider, btc.coin, "15m")
            repo.market.acquire(sub)
            val chart = repo.market.candles.first { (it[key]?.size ?: 0) > 300 }.getValue(key)
            val gaps = chart.zipWithNext().count { (a, b) -> b.openTime - a.openTime != 900_000L }
            println("chart feed: ${chart.size} bars of 15m, gaps: $gaps, last close ${chart.last().c}, mark $px")
            // Released feeds drop their bars, so a later chart never starts from stale ones.
            repo.market.release(sub)
            repo.market.candles.first { key !in it }
            println("released feed dropped its bars")

            val s = Liquidity.Settings(tiers = listOf(10, 25, 50, 100))
            val pools = Liquidity.pools(chart, s)
            val last = chart.last().c
            println("liquidity pools: ${pools.size} untouched (${pools.count { it.longs }} long, ${pools.count { !it.longs }} short); " +
                "all longs below price: ${pools.filter { it.longs }.all { it.price < last }}, shorts above: ${pools.filter { !it.longs }.all { it.price > last }}")
            Liquidity.magnets(pools, last, Liquidity.atrPct(chart)).forEach {
                println("  sweep target: ${if (it.longs) "long liqs" else "short liqs"} at ${"%.1f".format(it.price)} (${"%+.2f".format(it.distPct)}%, ${"%.0f".format(it.share * 100)}% of that side)")
            }
            println("compression (ATR14/ATR100): ${Liquidity.compression(chart)?.let { "%.2f".format(it) }}")
            val vp = VolumeProfile.of(chart)!!
            println("profile: POC ${"%.1f".format(vp.price(vp.poc))}, VA ${"%.1f".format(vp.vaLow)}..${"%.1f".format(vp.vah)}, LVNs ${vp.lvns.size}, naked POCs ${VolumeProfile.nakedPocs(chart).size}")
            val days = repo.history(btc, "1d", 40)
            val levels = Killzones.levels(chart, days)
            Killzones.nearest(levels, last).forEach { println("  level ${it.kind.short}: ${"%.1f".format(it.price)}") }
            val now = System.currentTimeMillis()
            (Killzones.KILLZONES + Killzones.MARKETS).map { Killzones.next(it, now) }.forEach {
                println("  ${it.window.short}: ${if (it.running) "running" else "next"} ${java.time.Instant.ofEpochMilli(it.start)} -> ${java.time.Instant.ofEpochMilli(it.end)}")
            }
        }
    }.onFailure { println("FAILED: $it") }.isSuccess
    delay(200)
    exitProcess(if (ok) 0 else 1)
}
