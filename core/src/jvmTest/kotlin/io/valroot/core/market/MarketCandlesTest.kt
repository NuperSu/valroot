package io.valroot.core.market

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.model.ExactDecimal
import io.valroot.core.storage.BundledSqliteDatabase
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarketCandlesTest {
    @BeforeTest fun forgetCachedCandles() = CoingeckoCandleCache.clear()

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
        BundledSqliteDatabase(":memory:").use { db ->
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

            // A failed CoinGecko request falls back to Kraken (the earlier response is no longer kept).
            CoingeckoCandleCache.clear()
            requests.clear()
            val failing = { _: String, _: String? -> throw CoingeckoProviderException(CoingeckoProviderFailure.OFFLINE) }
            assertEquals("Kraken XBT/USD", app.market.candles("bitcoin", CandleRange.DAY, failing, fromKraken)!!.source)

            // Without Kraken, the CoinGecko failure is reported.
            app.market.setOrderBookConsent(false)
            assertFailsWith<CoingeckoProviderException> { app.market.candles("bitcoin", CandleRange.DAY, failing, fromKraken) }
        }
    }

    @Test fun theCandleStillFormingIsBuiltFromShorterClosedCandles() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { now }
            app.market.setConsent(true)
            val hour = 3_600_000L
            val fourDays = 96 * hour
            val lastClose = now - 95 * hour
            val requests = mutableListOf<String>()
            val transport = { url: String, _: String? ->
                requests += url
                when (url.substringAfter("days=")) {
                    // Closed 4-day candles only; the last closed 95 hours ago.
                    "90" -> """[[${lastClose - fourDays},90,100,80,95],[$lastClose,95,99,90,96]]"""
                    // 4-hour candles: one before the last close, then two after it.
                    "7" -> """[[$lastClose,95,96,94,95],[${lastClose + 4 * hour},96,120,93,97],[${lastClose + 8 * hour},97,98,70,72]]"""
                    // 30-minute candles: older ones are covered by the 4-hour candles already.
                    else -> """[[${lastClose + 8 * hour},97,98,96,97],[${lastClose + 8 * hour + halfHour},72,75,71,74]]"""
                }
            }
            val series = app.market.candles("bitcoin", CandleRange.QUARTER, transport, { error("Kraken is not needed") })!!
            assertEquals(listOf("days=90", "days=7", "days=1"), requests.map { "days=" + it.substringAfter("days=") })
            assertEquals(3, series.candles.size)
            val forming = series.candles.last()
            assertTrue(forming.forming)
            assertEquals(lastClose, forming.openMillis)
            assertEquals(lastClose + 8 * hour + halfHour, forming.closeMillis)
            assertEquals(ExactDecimal.parse("96"), forming.open)
            assertEquals(ExactDecimal.parse("120"), forming.high)
            assertEquals(ExactDecimal.parse("70"), forming.low)
            assertEquals(ExactDecimal.parse("74"), forming.close)

            // A failed shorter request leaves the closed candles.
            CoingeckoCandleCache.clear()
            val failing = { url: String, key: String? ->
                if (url.endsWith(
                        "days=90",
                    )
                ) {
                    transport(url, key)
                } else {
                    throw CoingeckoProviderException(CoingeckoProviderFailure.RATE_LIMITED)
                }
            }
            val closed = app.market.candles("bitcoin", CandleRange.QUARTER, failing, { error("Kraken is not needed") })!!
            assertEquals(2, closed.candles.size)
            assertTrue(closed.candles.none { it.forming })
        }
    }

    @Test fun krakensLastRowIsTheCandleStillForming() {
        val candles = KrakenCandles.parse(kraken("XXBTZUSD"), 1)
        assertEquals(listOf(false, true), candles.map { it.forming })
    }

    @Test fun rangesShareRecentResponsesAndARateLimitPausesCandleRequests() {
        BundledSqliteDatabase(":memory:").use { db ->
            var at = now
            val app = PortfolioApplication(db) { at }
            app.market.setConsent(true)
            val requests = mutableListOf<String>()
            val transport = { url: String, _: String? -> coingecko.also { requests += url.substringAfter("days=") } }
            app.market.candles("bitcoin", CandleRange.QUARTER, transport, { error("Kraken is not needed") })
            assertEquals(listOf("90", "7", "1"), requests)
            // 1W and 1D reuse the 7-day and 1-day responses 3M fetched.
            app.market.candles("bitcoin", CandleRange.WEEK, transport, { error("Kraken is not needed") })
            app.market.candles("bitcoin", CandleRange.DAY, transport, { error("Kraken is not needed") })
            assertEquals(listOf("90", "7", "1"), requests)

            // A refused request pauses candle requests for a minute.
            val limited = { _: String, _: String? ->
                requests += "limited"
                throw CoingeckoProviderException(CoingeckoProviderFailure.RATE_LIMITED)
            }
            assertFailsWith<CoingeckoProviderException> {
                app.market.candles(
                    "bitcoin",
                    CandleRange.MONTH,
                    limited,
                    { error("no Kraken") },
                )
            }
            assertFailsWith<CoingeckoProviderException> {
                app.market.candles(
                    "bitcoin",
                    CandleRange.MONTH,
                    transport,
                    { error("no Kraken") },
                )
            }
            assertEquals(listOf("90", "7", "1", "limited"), requests)
            at += 61_000L
            app.market.candles("bitcoin", CandleRange.MONTH, transport, { error("no Kraken") })
            assertEquals("30", requests[4])
        }
    }
}
