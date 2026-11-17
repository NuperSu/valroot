package io.valroot.core.exchange.imports

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.exchange.ExchangeBalance
import io.valroot.core.exchange.ExchangeKind
import io.valroot.core.exchange.ExchangeRow
import io.valroot.core.exchange.ExchangeSnapshot
import io.valroot.core.exchange.binance.BinanceDecoder
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.model.AccountId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.OwnerId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.ownership.TargetIdentity
import io.valroot.core.ownership.TargetRole
import io.valroot.core.ownership.TrackingTarget
import io.valroot.core.storage.BundledSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BinanceImportTest {
    private val uid = "123456"
    private val account = AccountId("binance")
    private val now = 1_800_000_000_000L

    /** 2026-01-01 12:00:00 at UTC+4, the time zone in Binance's file names. */
    private val noonUtc4 = 1_767_254_400_000L

    private fun setUp(db: BundledSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { now }
        app.snapshot()
        app.accounts.saveOwner(Owner(OwnerId("owner"), "Owner"))
        app.accounts.saveAccountAndTarget(
            Account(account, OwnerId("owner"), "Binance", AccountKind.ACCOUNT),
            TrackingTarget(
                TrackingTargetId("binance"),
                account,
                TargetIdentity(ExchangeKind.BINANCE.network, "uid", uid),
                TargetRole.LEAF,
                "Binance",
            ),
        )
        return app
    }

    private fun apiTrade(
        id: Int,
        at: Long,
    ) = ExchangeRow(
        BinanceDecoder.TRADES,
        "BTCUSDT:$id",
        at,
        """{"symbol":"BTCUSDT","id":$id,"orderId":"9","qty":"0.1","quoteQty":"1000","commission":"0.0001","commissionAsset":"BTC",""" +
            """"isBuyer":true,"time":$at}""",
        "",
    )

    /** Saves API trades and refreshes the account's history as a refresh would, with BTC and USDT balances. */
    private fun api(
        app: PortfolioApplication,
        vararg trades: ExchangeRow,
        usdt: String? = null,
    ) {
        app.exchanges.saveRows(ExchangeKind.BINANCE, account, uid, trades.toList(), BinanceDecoder.VERSION)
        val btc =
            trades.size
                .toBigDecimal()
                .multiply("0.0999".toBigDecimal())
                .toPlainString()
        app.exchanges.applyHistory(
            ExchangeKind.BINANCE,
            account,
            uid,
            ExchangeSnapshot(
                now,
                listOf(ExchangeBalance("", "BTC", ExactDecimal.parse(btc))) +
                    listOfNotNull(usdt?.let { ExchangeBalance("", "USDT", ExactDecimal.parse(it)) }),
                pairs = mapOf("BTCUSDT" to ("BTC" to "USDT")),
            ),
        )
    }

    /** Files without a user ID go to the account the user chooses. */
    private val chosen = ImportChoices(accounts = mapOf("binance:?" to AccountChoice.Existing(account)))

    private val tradeFile = "Binance-Spot-Trade-History-202610201952(UTC+4)_412182df.zip"

    private fun table(
        name: String,
        csv: String,
    ) = ImportTable(name, "hash-$name", null, null, ExportCsv.read(csv, ImportBudget()))

    private fun fills(vararg rows: String) =
        table(
            tradeFile,
            "Order No,Time,Pair,Side,Price,Executed,Amount,Fee,AOR Conversion Pair,AOR Conversion Rate\n" + rows.joinToString("\n") + "\n",
        )

    private val fill = "9,2026-01-01 12:00:00,BTCUSDT,BUY,10000,0.1BTC,1000USDT,0.0001BTC,,"
    private val earlierFill = "8,2025-12-01 12:00:00,BTCUSDT,BUY,10000,0.1BTC,1000USDT,0.0001BTC,,"

    /** Trades and imported events; the decoder's gap and balance fills, which follow the balances, are left out. */
    private fun activities(app: PortfolioApplication) =
        app
            .snapshot(
                io.valroot.core.application
                    .ActivityFilter(account = account, limit = 500),
            ).activities
            .map { it.activity }
            .filter { it.status == ActivityStatus.CONFIRMED && ":gap:" !in it.id.value && ":balance:" !in it.id.value }

    private fun holdings(app: PortfolioApplication) =
        app.snapshot().valuations.associate {
            it.asset.value to
                it.amount.atomicUnits.toString()
        }

    @Test fun apiThenFileAndFileThenApiGiveTheSameHistory() {
        val first =
            BundledSqliteDatabase(":memory:").use { db ->
                val app = setUp(db)
                api(app, apiTrade(1, noonUtc4), apiTrade(2, noonUtc4 + 300))
                val preview = app.exchangeImports.preview(listOf(fills(fill, fill, earlierFill)), chosen)
                assertEquals(mapOf(ImportStatus.MATCHED to 2, ImportStatus.NEW to 1), preview.counts)
                assertTrue(preview.canCommit, preview.blockers.toString())
                val receipt = app.exchangeImports.commit(preview)
                assertEquals(listOf(ImportedFile(tradeFile, "hash-$tradeFile")), receipt.files)
                // Reimporting, even under another name, adds nothing.
                val again = app.exchangeImports.preview(listOf(fills(earlierFill, fill, fill).copy(file = "renamed(UTC+4).csv")), chosen)
                assertEquals(mapOf(ImportStatus.ALREADY_IMPORTED to 3), again.counts)
                activities(app).map { it.id.value }.sorted() to holdings(app)
            }
        val second =
            BundledSqliteDatabase(":memory:").use { db ->
                val app = setUp(db)
                val preview = app.exchangeImports.preview(listOf(fills(fill, fill, earlierFill)), chosen)
                assertEquals(mapOf(ImportStatus.NEW to 3), preview.counts)
                app.exchangeImports.commit(preview)
                api(app, apiTrade(1, noonUtc4), apiTrade(2, noonUtc4 + 300))
                activities(app).map { it.id.value }.sorted() to holdings(app)
            }
        assertEquals(first, second)
        // Two API trades and one imported earlier fill.
        assertEquals(3, first.first.size)
        assertEquals(2, first.first.count { it.startsWith("binance:$uid:trade:") })
    }

    @Test fun moreIdenticalFillsThanTheApiHasWaitForADecision() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            api(app, apiTrade(1, noonUtc4), apiTrade(2, noonUtc4 + 300))
            val preview = app.exchangeImports.preview(listOf(fills(fill, fill, fill)), chosen)
            assertEquals(mapOf(ImportStatus.AMBIGUOUS to 3), preview.counts)
            val group = preview.groups.single()
            assertEquals(3, group.records)
            assertEquals(2, group.candidates)
            assertTrue(!preview.canCommit)
            assertFailsWith<IllegalArgumentException> { app.exchangeImports.commit(preview) }
            val decided =
                app.exchangeImports.preview(
                    listOf(fills(fill, fill, fill)),
                    chosen.copy(
                        decisions =
                            mapOf(
                                group.key to GroupDecision.SAME,
                            ),
                    ),
                )
            assertEquals(mapOf(ImportStatus.MATCHED to 2, ImportStatus.NEW to 1), decided.counts)
            app.exchangeImports.commit(decided)
            assertEquals(3, activities(app).size)
        }
    }

    @Test fun undoKeepsWhatTheApiSupports() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            api(app, apiTrade(1, noonUtc4))
            val receipt = app.exchangeImports.commit(app.exchangeImports.preview(listOf(fills(fill, earlierFill)), chosen))
            assertEquals(2, activities(app).size)
            val undone = app.exchangeImports.undo(receipt.id)
            assertTrue(undone.undoneAtMillis != null)
            assertEquals(listOf("binance:$uid:trade:BTCUSDT:1"), activities(app).map { it.id.value })
            assertEquals(2, app.exchangeImports.sources(receipt.id).size)
            assertFailsWith<IllegalArgumentException> { app.exchangeImports.undo(receipt.id) }
        }
    }

    private val statementFile = "Binance-Transaction-History-202610201950(UTC+4)_2044b610.zip"

    @Test fun theStatementSettlesAgainstDetailedRecordsAndRecordsFuturesTransfers() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            // 2500 deposited, 1000 spent on the trade, 500 to futures and 300 back.
            api(app, apiTrade(1, noonUtc4), usdt = "1300")
            val statement =
                table(
                    statementFile,
                    """
                    User ID,Time,Account,Operation,Coin,Change,Remark
                    $uid,2026-01-01 12:00:00,Spot,Buy,BTC,0.1,
                    $uid,2026-01-01 12:00:00,Spot,Sell,USDT,-1000,
                    $uid,2026-01-01 12:00:00,Spot,Fee,BTC,-0.0001,
                    $uid,2025-11-01 10:00:00,Spot,Deposit,USDT,2500,
                    $uid,2026-01-02 10:00:00,Spot,Transfer Between Spot and Funding,USDT,-100,
                    $uid,2026-01-02 10:00:00,Funding,Transfer Between Spot and Funding,USDT,100,
                    $uid,2026-01-03 10:00:00,Spot,Transfer Between Spot and UM Futures,USDT,-500,
                    $uid,2026-01-03 10:00:00,USD-M Futures,Transfer Between Spot and UM Futures,USDT,500,
                    $uid,2026-01-04 10:00:00,USD-M Futures,Realized Profit and Loss,USDT,42,
                    $uid,2026-01-05 10:00:00,Spot,Transfer Between Spot and UM Futures,USDT,300,
                    $uid,2026-01-06 10:00:00,Spot,Asset - Transfer,USDT,1,
                    """.trimIndent() + "\n",
                )
            val deposits =
                table(
                    "Binance-Deposit-History-202610201950(UTC+4)_5be6f678.zip",
                    "Time,Coin,Network,Amount,Address,TXID,Status\n2025-11-01 09:59:30,USDT,BSC,2500,0xabc,0xdead,Completed\n",
                )
            val unread = app.exchangeImports.preview(listOf(statement, deposits), chosen)
            assertTrue("Confirm that 1 row(s) are left out" in unread.blockers, unread.blockers.toString())
            val preview = app.exchangeImports.preview(listOf(statement, deposits), chosen.copy(acknowledgeExclusions = true))
            assertTrue(preview.canCommit, preview.blockers.toString())
            assertEquals(
                mapOf(
                    // The statement's trade group and deposit settle against the API trade and the deposit history.
                    ImportStatus.MATCHED to 2,
                    // The deposit history's deposit, and the two futures transfers.
                    ImportStatus.NEW to 3,
                    ImportStatus.INTERNAL to 2,
                    ImportStatus.DEFERRED to 2,
                    ImportStatus.UNRESOLVED to 1,
                ),
                preview.counts,
            )
            val flow = preview.futuresFlows.single()
            assertEquals(ExactDecimal.parse("500") to ExactDecimal.parse("300"), flow.toFutures to flow.fromFutures)
            assertEquals(ExactDecimal.parse("200"), flow.net)
            app.exchangeImports.commit(preview)
            assertEquals(flow, app.exchangeImports.futuresFlows(account).single())
            // The API trade, the deposit and the two futures transfers; the statement adds no second trade.
            assertEquals(4, activities(app).size)
            assertEquals(1, activities(app).count { it.kind == io.valroot.core.ledger.ActivityKind.TRADE })
        }
    }

    @Test fun timesWithoutAZoneNeedAChoiceAndAccountsCanComeFromExports() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { now }
            app.snapshot()
            val noZone =
                table("trades.csv", "Order No,Time,Pair,Side,Price,Executed,Amount,Fee,AOR Conversion Pair,AOR Conversion Rate\n$fill\n")
            val preview = app.exchangeImports.preview(listOf(noZone))
            assertTrue(preview.tables.single().needsOffset)
            assertEquals(listOf("Choose the time zone of trades.csv"), preview.blockers)
            val zoned = app.exchangeImports.preview(listOf(noZone), ImportChoices(offset = ImportOffset(240)))
            val assignment = zoned.assignments.single()
            assertEquals(null, assignment.account)
            assertTrue(zoned.blockers.single().startsWith("Choose the Binance account"))
            val chosen =
                app.exchangeImports.preview(
                    listOf(noZone),
                    ImportChoices(
                        offset = ImportOffset(240),
                        accounts = mapOf(assignment.key to AccountChoice.New("Binance (exports)", uid)),
                    ),
                )
            assertTrue(chosen.canCommit, chosen.blockers.toString())
            val receipt = app.exchangeImports.commit(chosen)
            val created = receipt.accounts.single()
            assertEquals(listOf(created to uid), app.exchanges.targets(ExchangeKind.BINANCE))
            assertEquals(
                noonUtc4,
                activities(app).let { _ ->
                    app
                        .snapshot()
                        .activities
                        .single()
                        .activity.occurredAtEpochMillis
                },
            )
            // An account known only from exports gets no balance fills and no key reminder.
            assertEquals(1, app.snapshot().activities.size)
        }
    }
}
