package io.valroot.ui

import io.valroot.core.market.CandleSeries
import io.valroot.core.market.MarketCandle
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class CoinCandlesTest {
    private fun candle(
        close: Long,
        open: String,
        high: String,
        low: String,
        last: String,
    ) = MarketCandle(
        close - 1_800_000L,
        close,
        ExactDecimal.parse(open),
        ExactDecimal.parse(high),
        ExactDecimal.parse(low),
        ExactDecimal.parse(last),
    )

    @Test fun candlesBecomeChartCandlesWithTheirClosesAsTheLine() {
        val series =
            CandleSeries(
                "CoinGecko",
                1_800_000L,
                listOf(candle(1_800_000L, "1", "2", "0.5", "1.5"), candle(3_600_000L, "1.5", "3", "1.2", "2.5")),
            )
        val chart = candleChartData(series, AssetId("USD"))
        assertEquals(listOf(1.5, 2.5), chart.values)
        assertEquals(listOf(1_800_000L, 3_600_000L), chart.times)
        assertEquals(3.0, chart.candles!![1].high)
        assertEquals(0L, chart.candles!![0].start)
    }

    @Test fun theNoteNamesTheSourceAndCandleLength() {
        val one = listOf(candle(1_800_000L, "1", "2", "0.5", "1.5"))
        assertEquals("CoinGecko · 30-minute candles", candleNote(CoinCandlesState(CandleSeries("CoinGecko", 1_800_000L, one), null, false)))
        assertEquals("CoinGecko · 4-hour candles", candleNote(CoinCandlesState(CandleSeries("CoinGecko", 14_400_000L, one), null, false)))
        assertEquals("CoinGecko · 4-day candles", candleNote(CoinCandlesState(CandleSeries("CoinGecko", 345_600_000L, one), null, false)))
        assertEquals(
            "Kraken XBT/USD · weekly candles",
            candleNote(CoinCandlesState(CandleSeries("Kraken XBT/USD", 604_800_000L, one), null, false)),
        )
        assertEquals(
            "Kraken XBT/USD · daily candles",
            candleNote(CoinCandlesState(CandleSeries("Kraken XBT/USD", 86_400_000L, one), null, false)),
        )
        assertEquals("Loading candles…", candleNote(CoinCandlesState(null, null, true)))
        assertEquals("No candles", candleNote(CoinCandlesState(null, "No candles", false)))
    }
}
