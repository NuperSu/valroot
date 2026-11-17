package io.valroot.android

import io.valroot.core.market.DefiLlamaTransport
import io.valroot.core.market.HttpCoingeckoTransport
import io.valroot.core.market.KrakenPriceTransport
import android.content.Context
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.storage.BundledSqliteDatabase
import io.valroot.ui.OrderBookResult
import io.valroot.ui.host.MarketGate
import io.valroot.ui.host.MarketWork
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

/** On-demand CoinGecko market refreshes on their own connection, so network waits never hold the UI database thread. */
internal object MarketFeedRefresher {
    private val executor = Executors.newSingleThreadExecutor()
    val transport = HttpCoingeckoTransport()
    private val defiLlama = DefiLlamaTransport()

    fun refresh(
        context: Context,
        maxAgeMillis: Long,
        onResult: (String) -> Unit,
    ) {
        val market = market(context) { false }
        executor.execute {
            val message = market.refreshMarkets(maxAgeMillis, ::refreshEvmCatalogAndPrices)
            RefreshWorker.dataRevision.update { it + 1 }
            onResult(message)
        }
    }

    /** Refreshes the CoinGecko catalog when it is due, so exchange symbols can be matched to coins. Never fails the caller. */
    fun refreshCatalog(app: PortfolioApplication) {
        if (app.market.consent()) runCatching { app.evm.refreshCatalog(transport::feed) }
    }

    /**
     * Refreshes the CoinGecko chain catalog, then the prices of Bitcoin and linked coins (CoinGecko first, DefiLlama's
     * US dollar prices as the fallback), logos and missing price history. Each step is isolated, so a failure never fails the caller. Does
     * nothing without market data consent.
     */
    fun refreshEvmCatalogAndPrices(app: PortfolioApplication) {
        if (!app.market.consent()) return
        runCatching { app.evm.refreshCatalog(transport::feed) }
        runCatching { app.linkedCoins.refreshUsdPrices(defiLlama::get) }
        runCatching { app.linkedCoins.refreshPrices(transport::feed) }
        runCatching { app.linkedCoins.refreshLogos(transport::feed) }
        runCatching { app.linkedCoins.backfillUsdHistory(defiLlama::get, pause = Thread::sleep) }
        runCatching { app.linkedCoins.backfillHistory({ url, key -> transport.get(url, key.orEmpty()) }, pause = Thread::sleep) }
    }

    /**
     * Runs a price alert and notification check on this executor, posts what it finds and reports how many were posted.
     * Nothing runs while a restore is in progress or [closing] is true.
     */
    fun checkNotifications(
        context: Context,
        closing: () -> Boolean,
        onResult: (Int) -> Unit,
    ) {
        val app = context.applicationContext
        executor.execute {
            val posted =
                synchronized(AutomaticPriceGate.lock) {
                    if (AutomaticPriceGate.paused || closing()) {
                        0
                    } else {
                        runCatching { PriceAlertNotifications.post(app, checkPriceAlerts(app)) }.getOrDefault(0)
                    }
                }
            RefreshWorker.dataRevision.update { it + 1 }
            onResult(posted)
        }
    }

    /** Loads Kraken's order book for [coinId] on this executor, under the same pause gate as market refreshes. */
    fun orderBook(
        context: Context,
        coinId: String,
        closing: () -> Boolean,
        onResult: (OrderBookResult) -> Unit,
    ) {
        val market = market(context, closing)
        executor.execute {
            val result = market.orderBook(coinId)
            onResult(result)
        }
    }

    fun candles(
        context: Context,
        coinId: String,
        range: io.valroot.core.market.CandleRange,
        closing: () -> Boolean,
        onResult: (io.valroot.ui.CandleResult) -> Unit,
    ) {
        val market = market(context, closing)
        executor.execute {
            val result = market.candles(coinId, range)
            onResult(result)
        }
    }

    /** Shared market work on a fresh connection to the app's database, stopped by a restore or by [closing]. */
    private fun market(
        context: Context,
        closing: () -> Boolean,
    ): MarketWork {
        val path = context.applicationContext.getDatabasePath("portfolio.sqlite").path
        return MarketWork(
            object : MarketGate {
                override val stopped: Boolean get() = AutomaticPriceGate.paused || closing()

                override fun <T> underGate(block: () -> T): T = synchronized(AutomaticPriceGate.lock) { block() }

                override fun <T> withApplication(block: (PortfolioApplication) -> T): T =
                    BundledSqliteDatabase(path).use { block(PortfolioApplication(it, System::currentTimeMillis)) }
            },
            transport::feed,
        ) { beforeRequest -> KrakenPriceTransport(beforeRequest = beforeRequest)::get }
    }
}
