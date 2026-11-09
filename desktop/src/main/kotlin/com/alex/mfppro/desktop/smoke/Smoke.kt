package com.alex.mfppro.desktop.smoke

import com.alex.mfppro.desktop.desktopRepo

import com.alex.mfppro.data.Sub
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
        withTimeout(30_000) {
            val markets = repo.markets.first { it.isNotEmpty() }
            println("markets: ${markets.size}")
            val btc = markets.first { it.symbol == "BTC" }
            repo.market.acquire(Sub("ticks", btc.provider, btc.coin))
            val px = repo.market.marks.first { btc.feedKey in it }[btc.feedKey]
            println("live ${btc.symbol} on ${btc.provider}: $px")
            val bars = repo.history(btc, "1h", 24)
            println("history: ${bars.size} hourly candles")
        }
    }.onFailure { println("FAILED: $it") }.isSuccess
    delay(200)
    exitProcess(if (ok) 0 else 1)
}
