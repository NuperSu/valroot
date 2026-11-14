package io.valroot.core.market

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.model.ExactDecimal
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarketCandlesTest {
    private val now = 1_800_000_000_000L
    private val halfHour = 30 * 60_000L

    /** CoinGecko rows: close time, then open, high, low and close as JSON numbers. */
    private val coingecko = """[[${now - halfHour},100,110,95,105],[$now,105,106,104,104.5]]"""

    private fun kraken(pairKey: String) =
        """{"error":[],"result":{"$pairKey":[[${(now - 120_000) / 1000},"100.5","101","99.5","100.9","100.4","3.2",12],""" +
            """[${(now - 60_000) / 1000},"100.9","102","100.8","101.7","101.2","1.1",7]],"last":${(now - 60_000) / 1000}}}"""

    @Test fun coingeckoRowsAreCandlesEndingAtTheirTimestamp() {
        val candles = CoingeckoCandles.parse(coingecko, halfHour)
        assertEquals(2, candles.size)
        val first = candles.first()
        assertEquals(now - 2 * halfHour, first.openMillis)
        assertEquals(now - halfHour, first.closeMillis)
        assertEquals(ExactDecimal.parse("110"), first.high)
        assertEquals(ExactDecimal.parse("95"), first.low)
        assertEquals(ExactDecimal.parse("104.5"), candles.last().close)
        // Small prices arrive with an exponent.
        val tiny = CoingeckoCandles.parse("""[[$now,4.47e-06,4.5e-06,4.46e-06,4.48e-06]]""", halfHour).single()
        assertEquals(ExactDecimal.parse("0.00000448"), tiny.close)
    }

    @Test fun inconsistentOrUnorderedCandlesAreRejected() {
        assertFailsWith<IllegalArgumentException> { CoingeckoCandles.parse("""[[$now,100,99,95,98]]""", halfHour) }
        assertFailsWith<IllegalArgumentException> {
            CoingeckoCandles.parse("""[[$now,100,110,95,105],[${now - 1},100,110,95,105]]""", halfHour)
        }
        assertFailsWith<IllegalArgumentException> { CoingeckoCandles.parse("""[[$now,0,110,0,105]]""", halfHour) }
    }

    @Test fun krakenRowsStartAtTheirTimestamp() {
        val candles = KrakenCandles.parse(kraken("XXBTZUSD"), 1)
        assertEquals(2, candles.size)
        assertEquals(now - 120_000, candles.first().openMillis)
        assertEquals(now - 60_000, candles.first().closeMillis)
        assertEquals(ExactDecimal.parse("102"), candles.last().high)
    }

    @Test fun coingeckoComesFirstAndKrakenOnlyWhenItCannotAnswerAndIsAllowed() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { now }
            app.market.setConsent(true)
            val requests = mutableListOf<String>()
            val fromCoingecko = { url: String, _: String? -> coingecko.also { requests += url } }
            val fromKraken = { url: String -> kraken("XXBTZUSD").also { requests += url } }

            val day = app.market.candles("bitcoin", CandleRange.DAY, fromCoingecko, fromKraken)!!
            assertEquals(CoingeckoCandles.SOURCE, day.source)
            assertEquals(halfHour, day.intervalMillis)
            assertEquals(listOf("https://api.coingecko.com/api/v3/coins/bitcoin/ohlc?vs_currency=usd&days=1"), requests)

            // CoinGecko has no candles shorter than 30 minutes; Kraken needs its own consent.
            requests.clear()
            assertNull(app.market.candles("bitcoin", CandleRange.HOUR, fromCoingecko, fromKraken))
            assertTrue(requests.isEmpty())
            app.market.setOrderBookConsent(true)
            val hour = app.market.candles("bitcoin", CandleRange.HOUR, fromCoingecko, fromKraken)!!
            assertEquals("Kraken XBT/USD", hour.source)
            assertEquals(60_000L, hour.intervalMillis)
            assertTrue(requests.single().startsWith("https://api.kraken.com/0/public/OHLC?pair=XBTUSD&interval=1&since="))

            // A failed CoinGecko request falls back to Kraken.
            requests.clear()
            val failing = { _: String, _: String? -> throw CoingeckoProviderException(CoingeckoProviderFailure.OFFLINE) }
            assertEquals("Kraken XBT/USD", app.market.candles("bitcoin", CandleRange.DAY, failing, fromKraken)!!.source)

            // Without Kraken, the CoinGecko failure is reported.
            app.market.setOrderBookConsent(false)
            assertFailsWith<CoingeckoProviderException> { app.market.candles("bitcoin", CandleRange.DAY, failing, fromKraken) }
        }
    }
}
