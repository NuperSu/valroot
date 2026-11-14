package io.valroot.ui

import io.valroot.core.exchange.ExchangeCoverage
import io.valroot.core.exchange.ExchangeKind
import io.valroot.core.exchange.HistoryCoverage
import kotlin.test.Test
import kotlin.test.assertEquals

class ExchangeCoverageTextTest {
    private val from = 1_700_000_000_000L
    private val retry = 1_700_086_400_000L

    @Test
    fun eachCoverageStateSaysWhetherOlderHistoryMayStillArrive() {
        assertEquals(
            "History from 2023-11-14 22:13 UTC, as far back as Bybit provides. Earlier balances appear as one opening amount per coin.",
            historyCoverageText(ExchangeKind.BYBIT, ExchangeCoverage(from, HistoryCoverage.COMPLETE, null), from),
        )
        assertEquals(
            "History from 2023-11-14 22:13 UTC so far; older history is still being read. Earlier balances appear as one opening amount per coin.",
            historyCoverageText(ExchangeKind.BYBIT, ExchangeCoverage(from, HistoryCoverage.READING, null), from),
        )
        assertEquals(
            "History from 2023-11-14 22:13 UTC so far. Some older history could not be read; it is tried again after " +
                "2023-11-15 22:13 UTC. Earlier balances appear as one opening amount per coin.",
            historyCoverageText(ExchangeKind.BYBIT, ExchangeCoverage(from, HistoryCoverage.INCOMPLETE, retry), from),
        )
    }
}
