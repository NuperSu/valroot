package io.valroot.core.market

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.bitcoin.BITCOIN_ASSET
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.Price
import io.valroot.core.model.ProviderId
import io.valroot.core.storage.BundledSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BitcoinPriceSourceTest {
    private val usd = AssetId("USD")
    private val kraken = ProviderId(KrakenPrices.PROVIDER_ID)

    private fun chosen(app: PortfolioApplication) =
        app
            .snapshot()
            .valuations
            .single { it.asset == BITCOIN_ASSET }
            .price
            ?.price
            ?.value

    @Test fun walletBitcoinIsPricedByCoingeckoFirstAndKrakenWhenCoingeckoHasNothingFresh() {
        BundledSqliteDatabase(":memory:").use { db ->
            var at = 1_800_000_000_000L
            val app = PortfolioApplication(db) { at }
            app.demo.seed()
            app.market.setConsent(true)
            // Later than the example data's manual price, which a newer provider price replaces.
            at += 600_000L
            val market = MarketRepository(db)
            market.recordPrice(PriceObservation("kraken-now", Price(BITCOIN_ASSET, usd, ExactDecimal.parse("90000")), kraken, at, at))
            val requests = mutableListOf<String>()
            app.linkedCoins.refreshPrices({ url, _ ->
                requests += url
                """{"bitcoin":{"usd":91000,"last_updated_at":${(at - 60_000) / 1000}}}"""
            })
            assertTrue(requests.single().contains("ids=bitcoin&vs_currencies=usd&"))
            // Both are fresh: CoinGecko's, though Kraken's is newer.
            assertEquals(ExactDecimal.parse("91000"), chosen(app))
            // Three hours later only Kraken has a fresh price.
            at += 3 * 3_600_000L
            market.recordPrice(PriceObservation("kraken-later", Price(BITCOIN_ASSET, usd, ExactDecimal.parse("92000")), kraken, at, at))
            assertEquals(ExactDecimal.parse("92000"), chosen(app))
        }
    }
}
