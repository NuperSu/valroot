package io.valroot.core.exchange

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.SourceRefreshOptions
import io.valroot.core.model.AccountId
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExchangeConnectorTest {
    private val account = AccountId("a")

    private class Reader : ExchangeConnector(ExchangeKind.MEXC) {
        override val manifest get() = error("unused")

        override fun sync(
            app: PortfolioApplication,
            account: AccountId,
            uid: String,
            options: SourceRefreshOptions,
        ) = false

        fun read(
            app: PortfolioApplication,
            account: AccountId,
            stream: StreamWindows,
            now: Long,
            hasBudget: () -> Boolean,
            window: (from: Long, to: Long) -> Int? = { _, _ -> 0 },
        ) = readStream(app, account, stream, now, hasBudget, window)
    }

    private val day = ExchangeConnector.DAY_MILLIS
    private val stream = StreamWindows("deposits", 7 * day, 0)

    /** A reader that refuses windows matching [refuses] with [code], and records every window it was asked for. */
    private class Exchange(
        var code: Int = 30000,
        var refuses: (from: Long) -> Boolean,
    ) {
        val asked = mutableListOf<Long>()

        fun window(
            from: Long,
            @Suppress("UNUSED_PARAMETER") to: Long,
        ): Int {
            asked += from
            if (refuses(from)) {
                throw io.valroot.core.exchange.mexc
                    .MexcException(code, "refused")
            }
            return 1
        }
    }

    private fun withApp(test: (PortfolioApplication) -> Unit) =
        JvmSqliteDatabase(":memory:").use { db -> test(PortfolioApplication(db) { 0L }) }

    private fun PortfolioApplication.readOnce(
        exchange: Exchange,
        now: Long,
    ) = Reader().read(this, account, stream, now, { true }, exchange::window)

    @Test
    fun anOldWindowRefusedWhileNewerOnesReadEndsHistoryAtRetention() =
        withApp { app ->
            app.exchanges.saveStream(account, ExchangeStreamState("deposits", 50 * day, 100 * day, false, 0))
            val exchange = Exchange { it < 40 * day }
            assertTrue(app.readOnce(exchange, 100 * day))
            val state = app.exchanges.streams(account).getValue("deposits")
            assertTrue(state.complete)
            assertEquals(43 * day, state.fromMillis)
            val end = app.exchanges.streamEnds(account).getValue("deposits")
            assertEquals(StreamEndReason.RETENTION, end.reason)
            assertEquals(130 * day, end.retryAtMillis)
            // After the day of overlap forward, the probe read the newest window again.
            assertEquals(listOf(99 * day, 43 * day, 36 * day, 93 * day), exchange.asked)
            // Until the monthly check, nothing older is requested.
            exchange.asked.clear()
            assertTrue(app.readOnce(exchange, 100 * day))
            assertEquals(listOf(99 * day), exchange.asked)
        }

    @Test
    fun aRefusalTheNewestWindowSharesKeepsHistoryOpenAndRetriesWithLongerWaits() =
        withApp { app ->
            app.exchanges.saveStream(account, ExchangeStreamState("deposits", 50 * day, 100 * day, false, 0))
            val exchange = Exchange { it < 40 * day || it in 93 * day until 99 * day }
            assertTrue(app.readOnce(exchange, 100 * day))
            assertFalse(
                app.exchanges
                    .streams(account)
                    .getValue("deposits")
                    .complete,
            )
            val first = app.exchanges.streamEnds(account).getValue("deposits")
            assertEquals(StreamEndReason.REFUSED, first.reason)
            assertEquals(101 * day, first.retryAtMillis)
            exchange.asked.clear()
            assertTrue(app.readOnce(exchange, 100 * day + 1))
            assertEquals(listOf(99 * day), exchange.asked)
            assertTrue(app.readOnce(exchange, 101 * day))
            val second = app.exchanges.streamEnds(account).getValue("deposits")
            assertEquals(2, second.attempts)
            assertEquals(108 * day, second.retryAtMillis)
            assertFalse(
                app.exchanges
                    .streams(account)
                    .getValue("deposits")
                    .complete,
            )
        }

    @Test
    fun anAnswerWithoutACodeIsNeverTakenAsRetention() =
        withApp { app ->
            app.exchanges.saveStream(account, ExchangeStreamState("deposits", 50 * day, 100 * day, false, 0))
            val exchange = Exchange(code = -1) { it < 40 * day }
            assertTrue(app.readOnce(exchange, 100 * day))
            assertEquals(
                StreamEndReason.REFUSED,
                app.exchanges
                    .streamEnds(account)
                    .getValue("deposits")
                    .reason,
            )
            assertFalse(
                app.exchanges
                    .streams(account)
                    .getValue("deposits")
                    .complete,
            )
            assertEquals(listOf(99 * day, 43 * day, 36 * day), exchange.asked)
        }

    @Test
    fun aRetriedWindowThatNowReadsContinuesToTheLimit() =
        withApp { app ->
            app.exchanges.saveStream(account, ExchangeStreamState("deposits", 50 * day, 100 * day, false, 0))
            val exchange = Exchange { it < 40 * day || it in 93 * day until 99 * day }
            app.readOnce(exchange, 100 * day)
            exchange.refuses = { false }
            assertTrue(app.readOnce(exchange, 101 * day))
            assertTrue(app.exchanges.streamEnds(account).isEmpty())
            val state = app.exchanges.streams(account).getValue("deposits")
            assertTrue(state.complete)
            assertEquals(0L, state.fromMillis)
        }

    @Test
    fun aStreamCompletedShortOfItsLimitWithoutARecordedEndIsCheckedAgain() =
        withApp { app ->
            app.exchanges.saveStream(account, ExchangeStreamState("deposits", 50 * day, 100 * day, true, 0))
            val exchange = Exchange { false }
            assertTrue(app.readOnce(exchange, 100 * day))
            val state = app.exchanges.streams(account).getValue("deposits")
            assertTrue(state.complete)
            assertEquals(0L, state.fromMillis)
        }

    @Test
    fun aStreamThatReachedItsLimitIsNotCheckedAgain() =
        withApp { app ->
            app.exchanges.saveStream(account, ExchangeStreamState("deposits", 0, 100 * day, true, 0))
            val exchange = Exchange { false }
            assertTrue(app.readOnce(exchange, 100 * day))
            assertEquals(listOf(99 * day), exchange.asked)
        }

    @Test
    fun aSpentBudgetNeverMarksHistoryCompleteWithoutReading() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 0L }
            val day = ExchangeConnector.DAY_MILLIS
            // Read back to day 10 earlier; retention now ends at day 20, after the saved start.
            val saved = ExchangeStreamState("deposits", 10 * day, 100 * day, false, 0)
            app.exchanges.saveStream(account, saved)
            val complete = Reader().read(app, account, StreamWindows("deposits", 7 * day, 20 * day), 100 * day, { false })
            assertFalse(complete)
            assertEquals(saved, app.exchanges.streams(account).getValue("deposits"))
        }
    }

    @Test
    fun coverageIsCompleteOnlyWhenEveryStreamEndedAndNothingWasRefused() =
        withApp { app ->
            assertEquals(HistoryCoverage.READING, app.exchanges.coverage(account).coverage)
            app.exchanges.saveStream(account, ExchangeStreamState("deposits", 50 * day, 100 * day, false, 0))
            app.readOnce(Exchange { it < 40 * day || it in 93 * day until 99 * day }, 100 * day)
            val refused = app.exchanges.coverage(account)
            assertEquals(HistoryCoverage.INCOMPLETE, refused.coverage)
            assertEquals(101 * day, refused.nextRetryMillis)
            app.readOnce(Exchange { false }, 101 * day)
            assertEquals(HistoryCoverage.COMPLETE, app.exchanges.coverage(account).coverage)
            app.exchanges.saveStream(account, ExchangeStreamState("withdrawals", 50 * day, 100 * day, false, 0))
            assertEquals(HistoryCoverage.READING, app.exchanges.coverage(account).coverage)
        }
}
