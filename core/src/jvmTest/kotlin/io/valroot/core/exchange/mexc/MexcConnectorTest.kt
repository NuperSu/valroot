package io.valroot.core.exchange.mexc

import io.valroot.core.application.ActivityFilter
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.SourceRefreshOptions
import io.valroot.core.exchange.ExchangeAssets
import io.valroot.core.exchange.ExchangeKind
import io.valroot.core.exchange.ExchangeTransfer
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.market.PriceObservation
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.OwnerId
import io.valroot.core.model.Price
import io.valroot.core.model.Rational
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.ownership.TargetIdentity
import io.valroot.core.ownership.TargetRole
import io.valroot.core.ownership.TrackingTarget
import io.valroot.core.storage.BundledSqliteDatabase
import io.valroot.core.sync.JobState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A MEXC account replayed by a fake API with MEXC's short retention: a month of trades, 90 days of transfers. */
class MexcConnectorTest {
    private val now = 1_800_000_000_000L
    private val day = Mexc.DAY_MILLIS
    private val account = AccountId("mexc")
    private val uid = "12345678"

    private inner class FakeMexc {
        val requests = mutableListOf<MexcRequest>()
        val spot = mutableMapOf("BTC" to "0.5", "USDT" to "1000", "MX" to "0.0084217282955947436887649")
        val trades = mutableListOf<String>()
        val withdrawals = mutableListOf<String>()

        init {
            // 150 trades in ten days: more than a page, so the window is split.
            repeat(150) { i -> trades += trade("t$i", "0.001", "50", "0.05", "USDT", true, now - 10 * day + i * 60_000L) }
            withdrawals +=
                """{"id":"w1","coin":"USDT","network":"ERC20","amount":"100","transferType":0,"status":7,"transactionFee":"1",
                |"applyTime":${now - 2 * day},"transHash":"0xabc"}
                """.trimMargin()
        }

        fun trade(
            id: String,
            qty: String,
            quote: String,
            commission: String,
            asset: String,
            buyer: Boolean,
            time: Long,
        ) = """{"symbol":"BTCUSDT","id":"$id","orderId":"o$id","price":"50000","qty":"$qty","quoteQty":"$quote","commission":"$commission",
            |"commissionAsset":"$asset","time":$time,"isBuyer":$buyer}
            """.trimMargin()

        private fun time(
            row: String,
            field: String,
        ) = Regex("\"$field\":(\\d+)").find(row)!!.groupValues[1].toLong()

        fun get(request: MexcRequest): String {
            requests += request
            val q = request.query.toMap()
            return when (request.path) {
                "/api/v3/uid" -> """{"uid":"$uid"}"""
                "/api/v3/account" -> """{"balances":[${spot.entries.joinToString(
                    ",",
                ) { """{"asset":"${it.key}","free":"${it.value}","locked":"0"}""" }}]}"""
                "/api/v3/ticker/price" ->
                    """[{"symbol":"BTCUSDT","price":"1"},{"symbol":"MXUSDT","price":"1"},{"symbol":"MXBTC","price":"1"}]"""
                "/api/v3/myTrades" -> {
                    val from = q.getValue("startTime").toLong()
                    val to = q.getValue("endTime").toLong()
                    // MEXC keeps a month and answers at most 100.
                    val kept =
                        if (q["symbol"] ==
                            "BTCUSDT"
                        ) {
                            trades.filter { time(it, "time") in maxOf(from, now - 30 * day)..to }
                        } else {
                            emptyList()
                        }
                    "[" + kept.take(100).joinToString(",") + "]"
                }
                "/api/v3/capital/deposit/hisrec" -> "[]"
                "/api/v3/capital/withdraw/history" ->
                    "[" +
                        withdrawals
                            .filter {
                                time(
                                    it,
                                    "applyTime",
                                ) in q.getValue("startTime").toLong()..q.getValue("endTime").toLong()
                            }.joinToString(",") +
                        "]"
                "/api/v3/capital/transfer/internal" -> """{"page":1,"totalRecords":0,"totalPageNum":0,"data":[]}"""
                "/api/v3/capital/transfer" ->
                    if (q.getValue("startTime").toLong() < now - 60 * day) {
                        """{"code":33333,"msg":"date is incorrect"}"""
                    } else {
                        """{"rows":[],"total":0}"""
                    }
                "/api/v3/capital/convert" ->
                    """{"data":[{"totalConvert":"0.01","totalFee":"0.0002","convertTime":${now - 400 * day},"convertDetails":[{"id":"c1","convert":"0.01",
                    "fee":"0.0002","amount":"3","time":${now - 400 * day},"asset":"DOGE"}]}],"totalRecords":1,"page":1,"totalPageNum":1}"""
                "/api/v3/capital/config/getall" -> "[]"
                else -> error("Unexpected ${request.path}")
            }
        }
    }

    private fun setUp(
        db: BundledSqliteDatabase,
        clock: () -> Long = { now },
    ): PortfolioApplication {
        val app = PortfolioApplication(db, clock)
        app.snapshot()
        app.accounts.saveOwner(Owner(OwnerId("owner"), "Owner"))
        app.accounts.saveAccountAndTarget(
            Account(account, OwnerId("owner"), "MEXC", AccountKind.ACCOUNT),
            TrackingTarget(
                TrackingTargetId("mexc"),
                account,
                TargetIdentity(ExchangeKind.MEXC.network, "uid", uid),
                TargetRole.LEAF,
                "MEXC",
            ),
        )
        app.exchanges.setKey(ExchangeKind.MEXC, account, "KEYKEYKEYKEY", "SECRETSECRETSECRET")
        return app
    }

    private fun units(decimal: String) =
        ExactInteger
            .parse(decimal.replace(".", "").trimStart('0').ifEmpty { "0" })
            .timesPowerOfTen(ExchangeAssets.DECIMALS - decimal.substringAfter('.', "").length)

    private fun balances(app: PortfolioApplication) =
        app
            .snapshot()
            .balances.quantities
            .filterKeys { it.account == account }
            .filterValues { it.sign != 0 }
            .mapKeys { it.key.asset.value }

    @Test
    fun keepsRecentRecordsAndOpensWhatMexcNoLongerKeeps() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val mexc = FakeMexc()
            val outcome = MexcConnector { _, request -> mexc.get(request) }.refresh(app, SourceRefreshOptions())
            assertEquals(listOf(JobState.COMPLETED), outcome.jobs)
            assertNull(app.exchanges.status(account).error)
            // MEXC's balances exactly, including MX to 25 decimals.
            assertEquals(
                mapOf(
                    "coin:bitcoin" to units("0.5"),
                    "coin:tether" to units("1000"),
                    "coin:mx-token" to units("0.0084217282955947436887649"),
                ),
                balances(app),
            )
            val activities = app.snapshot(ActivityFilter(account = account, limit = 500)).activities.map { it.activity }
            assertEquals(150, activities.count { it.kind == ActivityKind.TRADE && it.id.value.contains(":trade:BTCUSDT:") })
            assertTrue(activities.any { it.id.value == "mexc:$uid:dust:${now - 400 * day}:c1" })
            assertEquals(mapOf(ActivityId("mexc:$uid:withdrawal:w1") to ExchangeTransfer("ERC20", "0xabc")), app.exchanges.transfers())
            // BTC bought before the records: an opening where the records start, not after the latest one.
            val opening = activities.single { it.id.value == "mexc:$uid:balance:BTC" }
            assertTrue(opening.occurredAtEpochMillis < now - 10 * day)
            // A full page of trades is split; spot↔futures history ends where MEXC refuses older windows.
            assertTrue(mexc.requests.count { it.path == "/api/v3/myTrades" && it.query.toMap()["symbol"] == "BTCUSDT" } > 1)
            assertTrue(
                app.exchanges
                    .streams(account)
                    .values
                    .all { it.complete },
            )
        }
    }

    @Test
    fun unclassifiedExchangeActivityCountsWithDefaultsAndASavedClassificationWins() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            MexcConnector { _, request -> FakeMexc().get(request) }.refresh(app, SourceRefreshOptions())
            val view = app.accounting.report()
            assertTrue(view.missingTreatments.isEmpty())
            val trade = view.automaticTreatments.first { it.value.contains(":trade:BTCUSDT:") }
            // 150 bought lots plus the opening, all held: BTC is in the FIFO results.
            assertTrue(view.report.lots.count { it.acquisitionActivityId?.value?.contains(":trade:BTCUSDT:") == true } == 150)
            val movements =
                app
                    .snapshot(ActivityFilter(account = account, limit = 500))
                    .activities
                    .single { it.activity.id == trade }
                    .activity.movements
            app.accounting.saveTreatments(
                trade,
                movements.map { m ->
                    if (m.amount.atomicUnits.sign > 0) {
                        io.valroot.core.accounting.Treatment
                            .Acquire(m.id, null, opening = true)
                    } else {
                        io.valroot.core.accounting.Treatment
                            .Dispose(m.id, null, fee = m.role == io.valroot.core.ledger.MovementRole.FEE)
                    }
                },
            )
            assertTrue(trade !in app.accounting.report().automaticTreatments)
        }
    }

    @Test
    fun anUnclassifiedOpeningCountsAtItsMarketValue() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            // Without trades the opening is BTC's only lot.
            val mexc = FakeMexc().apply { trades.clear() }
            MexcConnector { _, request -> mexc.get(request) }.refresh(app, SourceRefreshOptions())
            val opening =
                app
                    .snapshot(ActivityFilter(account = account, limit = 500))
                    .activities
                    .single { it.activity.id.value == "mexc:$uid:balance:BTC" }
                    .activity
            val btc =
                opening.movements
                    .single()
                    .amount.assetId
            val usd = app.assets.portfolioCurrency(null)
            // A historical price saved after the opening, as a later price download would.
            assertTrue(opening.occurredAtEpochMillis < now - 1)
            app.prices.recordManual(
                PriceObservation(
                    "opening",
                    Price(btc, usd, ExactDecimal.parse("20000")),
                    null,
                    opening.occurredAtEpochMillis,
                    now - 1,
                    true,
                ),
            )
            // An unclassified opening counts at its market value when the history begins.
            val report = app.accounting.report()
            assertTrue(opening.id in report.automaticTreatments)
            assertTrue(opening.id in report.estimatedBasis)
            assertTrue(opening.id !in report.missingTreatments)
            val lot = report.report.lots.single { it.acquisitionActivityId == opening.id }
            // 0.5 BTC held at 20,000 USD.
            assertEquals(Rational.of("10000"), lot.basis)
            assertTrue(btc in report.estimatedAssets)
        }
    }

    @Test
    fun laterRefreshesKeepTradesMexcHasDropped() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val mexc = FakeMexc()
            val connector = MexcConnector { _, request -> mexc.get(request) }
            connector.refresh(app, SourceRefreshOptions())
            // MEXC drops the trades; Valroot keeps them, and the balance still matches.
            mexc.trades.clear()
            mexc.requests.clear()
            connector.refresh(app, SourceRefreshOptions())
            val activities = app.snapshot(ActivityFilter(account = account, limit = 500)).activities.map { it.activity }
            assertEquals(150, activities.count { it.id.value.contains(":trade:BTCUSDT:") })
            assertEquals(units("1000"), balances(app)["coin:tether"])
            assertTrue(mexc.requests.none { it.path == "/api/v3/myTrades" })
        }
    }

    @Test
    fun fullWithdrawalPagesSplitAndRecoverEveryRecord() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val mexc = FakeMexc()
            val outcome =
                MexcConnector { _, request ->
                    if (request.path != "/api/v3/capital/withdraw/history") {
                        mexc.get(request)
                    } else {
                        val query = request.query.toMap()
                        val from = query.getValue("startTime").toLong()
                        val to = query.getValue("endTime").toLong()
                        val rows =
                            (0 until 1200).mapNotNull { i ->
                                val at = now - 3 * day + i * 60_000L
                                if (at !in from..to) {
                                    null
                                } else {
                                    """{"id":"w$i","coin":"BTC","network":"BTC","amount":"1","transHash":"tx$i","status":7,"transactionFee":"0","applyTime":$at}"""
                                }
                            }
                        "[${rows.take(Mexc.RECORD_PAGE).joinToString(",")}]"
                    }
                }.refresh(app, SourceRefreshOptions())
            assertEquals(listOf(JobState.COMPLETED), outcome.jobs)
            assertEquals(1200, app.exchanges.rowKeys(account, MexcDecoder.WITHDRAWALS).size)
            assertTrue(
                app.exchanges
                    .streams(account)
                    .getValue(MexcDecoder.WITHDRAWALS)
                    .complete,
            )
        }
    }

    @Test
    fun fullDepositAndWithdrawalPagesLeaveTheirWindowUnread() {
        listOf("deposits", "withdrawals").forEach { stream ->
            BundledSqliteDatabase(":memory:").use { db ->
                val app = setUp(db)
                val mexc = FakeMexc()
                val path = if (stream == "deposits") "/api/v3/capital/deposit/hisrec" else "/api/v3/capital/withdraw/history"
                val outcome =
                    MexcConnector { _, request ->
                        if (request.path != path) {
                            mexc.get(request)
                        } else {
                            val rows =
                                (0 until Mexc.RECORD_PAGE).map { i ->
                                    if (stream == "deposits") {
                                        """{"id":"d$i","coin":"BTC","network":"BTC","amount":"1","txId":"tx$i","status":5,"insertTime":${now - day}}"""
                                    } else {
                                        """{"id":"w$i","coin":"BTC","network":"BTC","amount":"1","transHash":"tx$i","status":7,"transactionFee":"0","applyTime":${now - day}}"""
                                    }
                                }
                            "[${rows.joinToString(",")}]"
                        }
                    }.refresh(app, SourceRefreshOptions())
                assertEquals(listOf(JobState.FAILED), outcome.jobs)
                val state = app.exchanges.streams(account).getValue(stream)
                assertEquals(now, state.fromMillis)
                assertEquals(now, state.toMillis)
                assertTrue(!state.complete)
                assertTrue(
                    app.exchanges
                        .status(account)
                        .error
                        .orEmpty()
                        .contains("incomplete"),
                )
            }
        }
    }

    @Test
    fun aSplitForwardWithdrawalWindowKeepsItsBoundsAsTheClockMoves() {
        BundledSqliteDatabase(":memory:").use { db ->
            var clock = now
            val app = setUp(db) { clock }
            val mexc = FakeMexc()
            MexcConnector { _, request -> mexc.get(request) }.refresh(app, SourceRefreshOptions())
            val path = "/api/v3/capital/withdraw/history"
            val connector =
                MexcConnector(requestLimit = 5) { _, request ->
                    if (request.path != path) {
                        mexc.get(request)
                    } else {
                        mexc.requests += request
                        val query = request.query.toMap()
                        val from = query.getValue("startTime").toLong()
                        val to = query.getValue("endTime").toLong()
                        val rows =
                            (0 until 1_200)
                                .asSequence()
                                .map { i -> i to (now - day / 4 + i * 1_000L) }
                                .filter { (_, time) -> time in from..to }
                                .take(Mexc.RECORD_PAGE)
                                .joinToString(",") { (i, time) ->
                                    """{"id":"future-$i","coin":"BTC","network":"BTC","amount":"1","status":7,"applyTime":$time,"transHash":"tx$i","transactionFee":"0"}"""
                                }
                        "[$rows]"
                    }
                }
            mexc.requests.clear()
            connector.refresh(app, SourceRefreshOptions())
            val initial = mexc.requests.first { it.path == path }.query
            repeat(25) {
                if (app.exchanges.rowKeys(account, MexcDecoder.WITHDRAWALS).size < 1_201) {
                    clock += 1_000
                    assertEquals(listOf(JobState.COMPLETED), connector.refresh(app, SourceRefreshOptions()).jobs)
                }
            }
            assertEquals(1_201, app.exchanges.rowKeys(account, MexcDecoder.WITHDRAWALS).size)
            assertEquals(1, mexc.requests.count { it.path == path && it.query == initial })
        }
    }

    @Test
    fun splitTradeWindowsResumeAcrossBudgetsWithLegacyCompletionCursor() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val mexc = FakeMexc()
            val expectedTrades = mexc.trades.toList()
            mexc.trades.clear()
            // Complete the window streams first, leaving a due legacy symbol timestamp.
            MexcConnector { _, request -> mexc.get(request) }.refresh(app, SourceRefreshOptions())
            mexc.trades += expectedTrades
            app.exchanges.saveCursor(account, MexcConnector.TRADE_CURSOR + "BTCUSDT", "${now - 29 * day}")
            mexc.requests.clear()
            // Five stream overlap requests, UID, balance, dust and tickers leave two trade requests.
            val connector = MexcConnector(requestLimit = 11) { _, request -> mexc.get(request) }
            val first = connector.refresh(app, SourceRefreshOptions())
            assertEquals(listOf(JobState.COMPLETED), first.jobs)
            val pending = app.exchanges.cursors(account).getValue(MexcConnector.TRADE_PENDING + "BTCUSDT")
            assertTrue(pending.contains('|'))
            val firstTradeRequests = mexc.requests.filter { it.path == "/api/v3/myTrades" }.toList()
            assertEquals(2, firstTradeRequests.size)
            repeat(20) {
                if (app.exchanges
                        .cursors(account)[MexcConnector.TRADE_PENDING + "BTCUSDT"]
                        .orEmpty()
                        .isNotEmpty()
                ) {
                    assertEquals(listOf(JobState.COMPLETED), connector.refresh(app, SourceRefreshOptions()).jobs)
                }
            }
            assertNull(app.exchanges.cursors(account)[MexcConnector.TRADE_PENDING + "BTCUSDT"])
            assertEquals("$now", app.exchanges.cursors(account)[MexcConnector.TRADE_CURSOR + "BTCUSDT"])
            val rootQuery = firstTradeRequests.first().query
            assertEquals(1, mexc.requests.count { it.path == "/api/v3/myTrades" && it.query == rootQuery })
            assertEquals(
                150,
                app.snapshot(ActivityFilter(account = account, limit = 500)).activities.count {
                    it.activity.id.value
                        .contains(":trade:BTCUSDT:")
                },
            )
        }
    }

    @Test
    fun anUnsplittableWindowOfTradesFailsInsteadOfSkippingTrades() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val mexc = FakeMexc()
            repeat(120) { i -> mexc.trades += mexc.trade("burst$i", "0.001", "50", "0.05", "USDT", true, now - 5 * day) }
            val outcome = MexcConnector { _, request -> mexc.get(request) }.refresh(app, SourceRefreshOptions())
            assertEquals(listOf(JobState.FAILED), outcome.jobs)
            assertTrue(
                app.exchanges
                    .status(account)
                    .error
                    .orEmpty()
                    .contains("full trades page"),
            )
            assertTrue(
                app.exchanges
                    .cursors(account)
                    .keys
                    .none { it.startsWith(MexcConnector.TRADE_CURSOR) },
            )
        }
    }
}
