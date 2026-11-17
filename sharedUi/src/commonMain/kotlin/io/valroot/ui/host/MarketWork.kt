package io.valroot.ui.host

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.market.CandleRange
import io.valroot.ui.CandleResult
import io.valroot.ui.OrderBookResult
import io.valroot.ui.loadCandles
import io.valroot.ui.loadOrderBook
import io.valroot.ui.marketRefreshMessage

/** How market work reaches a host's database without meeting a restore. */
interface MarketGate {
    /** True while a restore or the app's close keeps market work away from the database. */
    val stopped: Boolean

    /** Runs [block] holding the gate a restore takes before it swaps the database file. */
    fun <T> underGate(block: () -> T): T

    /** Runs [block] on a connection of its own, closed afterwards, so a restore never meets it open. */
    fun <T> withApplication(block: (PortfolioApplication) -> T): T
}

/**
 * Market refresh, order books and candles, shared by every host. Each runs under the [gate] on a fresh connection and
 * stops early when a restore or close begins: Kraken requests check [MarketGate.stopped] before they are sent.
 * [kraken] builds a Kraken transport that runs its argument before each request.
 */
class MarketWork(
    private val gate: MarketGate,
    private val coingecko: (url: String, apiKey: String?) -> String,
    private val kraken: (beforeRequest: () -> Unit) -> (url: String) -> String,
) {
    /** Refreshes CoinGecko market feeds older than [maxAgeMillis], then runs [afterRefresh]; returns the message to show. */
    fun refreshMarkets(
        maxAgeMillis: Long,
        afterRefresh: (PortfolioApplication) -> Unit,
    ): String =
        gated("Market refresh is paused while data is restored.", "Could not refresh market data.") { app ->
            marketRefreshMessage(app.market.refreshFeeds(coingecko, maxAgeMillis)).also { afterRefresh(app) }
        }

    fun orderBook(coinId: String): OrderBookResult =
        gated(OrderBookResult.Failed(PAUSED), OrderBookResult.Failed("Could not load Kraken's order book.")) { app ->
            loadOrderBook(app, coinId, coingecko, krakenUntilStopped())
        }

    fun candles(
        coinId: String,
        range: CandleRange,
    ): CandleResult =
        gated(CandleResult.Unavailable(PAUSED), CandleResult.Unavailable("Could not load candles.")) { app ->
            loadCandles(app, coinId, range, coingecko, krakenUntilStopped())
        }

    private fun krakenUntilStopped() = kraken { check(!gate.stopped) }

    private fun <T> gated(
        paused: T,
        failed: T,
        work: (PortfolioApplication) -> T,
    ): T =
        gate.underGate {
            if (gate.stopped) paused else runCatching { gate.withApplication(work) }.getOrElse { failed }
        }

    private companion object {
        const val PAUSED = "Market data is paused while data is restored."
    }
}
