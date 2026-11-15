package io.valroot.core.exchange.imports

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.exchange.ExchangeKind
import io.valroot.core.exchange.ExchangeRow
import io.valroot.core.exchange.ImportOutcome
import io.valroot.core.exchange.ImportedEvents
import io.valroot.core.exchange.mexc.MexcDecoder
import io.valroot.core.exchange.mexc.MexcExports
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.model.AccountId
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MexcImportTest {
    private val uid = "123456"
    private val at = 1_767_254_400_000L
    private val account = AccountId("mexc")
    private val assets = mapOf("BTC" to AssetId("btc"), "USDT" to AssetId("usdt"))

    private fun table(
        report: ExportReport,
        vararg rows: String,
        file: String = "export.xlsx",
    ) = ImportTable(
        file,
        "synthetic-$file",
        null,
        "Sheet1",
        ExportCsv.read(report.header.joinToString(",") + "\n" + rows.joinToString("\n"), ImportBudget()),
    )

    private fun interpret(
        report: ExportReport,
        vararg rows: String,
        file: String = "export.xlsx",
    ): List<ImportedRecord> {
        val detected = ExportReports.detect(table(report, *rows, file = file))!!
        return MexcExports.interpret(detected, detected.offsets(ImportOffset(240)).mapValues { it.value!! })
    }

    private fun stored(records: List<ImportedRecord>) =
        records.mapIndexed { i, record ->
            ExchangeRow(
                ImportedEvents.STREAM,
                "file-$i",
                record.atMillis,
                ImportedEvents.payload(record),
                "source-$i",
            )
        }

    @Test fun fillsSettleAgainstApiAndStatementWithoutInventingBalances() {
        val fill = interpret(MexcExports.TRADES, "$uid,BTC_USDT,2026-01-01 12:00:00,Buy,10000,0.1,1000,0.0001BTC,Maker").single()
        val statement =
            interpret(
                MexcExports.STATEMENT,
                "$uid,2026-01-01 12:00:00,BTC,Spot Trading,Inflow,0.1",
                "$uid,2026-01-01 12:00:00,USDT,Spot Trading,Outflow,1000",
                "$uid,2026-01-01 12:00:00,BTC,Spot Trading Fees,Outflow,0.0001",
            )
        assertEquals(at, fill.atMillis)
        val api =
            ExchangeRow(
                MexcDecoder.TRADES,
                "BTCUSDT:1",
                at,
                """{"symbol":"BTCUSDT","id":"1","qty":"0.1","quoteQty":"1000","commission":"0.0001","commissionAsset":"BTC","isBuyer":true,"time":$at}""",
                "api-source",
            )
        val decoded =
            MexcDecoder.decode(
                account,
                uid,
                listOf(api) + stored(listOf(fill) + statement),
                assets,
                mapOf(
                    "BTCUSDT" to ("BTC" to "USDT"),
                ),
                null,
            )
        assertEquals(1, decoded.activities.size)
        assertEquals(2, decoded.imports.values.count { it is ImportOutcome.Same })
        assertTrue(decoded.failures.isEmpty())
        val onlyFiles = MexcDecoder.decode(account, uid, stored(listOf(fill) + statement), assets, emptyMap(), null)
        assertEquals(1, onlyFiles.activities.size)
        assertEquals(ActivityKind.TRADE, onlyFiles.activities.single().kind)
        assertEquals(setOf("BTC", "USDT"), MexcDecoder.coins(stored(listOf(fill)), emptyMap()))
    }

    @Test fun withdrawalUsesSettledAmountPlusFeeAndHonoursStatus() {
        val records =
            interpret(
                MexcExports.WITHDRAWALS,
                "$uid,Withdrawal Successful,2026-01-01 12:00:00,USDT,TRX,10,address,,txid,1,9,",
                "$uid,Pending,2026-01-01 12:01:00,USDT,TRX,10,address,,txid2,1,9,",
            )
        assertEquals(listOf("-9", "-1"), records.first().legs.map { it.amount.toString() })
        assertFalse(records.last().moves)
        val statement =
            interpret(
                MexcExports.STATEMENT,
                "$uid,2026-01-01 12:00:00,USDT,Withdraw,Outflow,9",
                "$uid,2026-01-01 12:00:00,USDT,Withdrawal Fees,Outflow,1",
            )
        val decoded = MexcDecoder.decode(account, uid, stored(records + statement), assets, emptyMap(), null)
        assertEquals(1, decoded.activities.size)
        assertEquals(1, decoded.imports.values.count { it is ImportOutcome.Same })
    }

    @Test fun futuresTransfersDeduplicateApiAndDerivativesStayDeferred() {
        val records =
            interpret(
                MexcExports.TRANSFERS,
                "$uid,SPOT,FUTURES,USDT,100,SUCCESS,2026-01-01 12:00:00,2026-01-01 12:00:00,SAME_ACCOUNT_TRANSFER",
                "$uid,FUTURES,SPOT,USDT,60,SUCCESS,2026-01-02 12:00:00,2026-01-02 12:00:00,SAME_ACCOUNT_TRANSFER",
            )
        assertEquals(listOf(RecordKind.FUTURES_IN, RecordKind.FUTURES_OUT), records.map { it.kind })
        assertEquals(
            listOf("-100", "60"),
            records.map {
                it.legs
                    .single()
                    .amount
                    .toString()
            },
        )
        val api =
            ExchangeRow(
                MexcDecoder.TO_FUTURES,
                "1",
                at,
                """{"tranId":"1","asset":"USDT","amount":"100","fromAccountType":"SPOT","toAccountType":"FUTURES","status":"SUCCESS","timestamp":$at}""",
                "api-source",
            )
        val decoded = MexcDecoder.decode(account, uid, listOf(api) + stored(records), assets, emptyMap(), null)
        assertEquals(2, decoded.activities.size)
        assertTrue(decoded.imports["file-0"] is ImportOutcome.Same)
        val deferred =
            interpret(
                MexcExports.STATEMENT,
                "$uid,2026-01-01 12:00:00,USDT,Closing PNL,Inflow,5",
                file = "futures.xlsx",
            ).single()
        assertEquals(EvidenceRole.DEFERRED, deferred.role)
        assertFalse(deferred.moves)
        val derivativeReport = MexcExports.reports.first { it.role == EvidenceRole.DEFERRED }
        val derivative = interpret(derivativeReport, derivativeReport.header.joinToString(",") { "deferred" }).single()
        assertFalse(derivative.moves)
        assertTrue(derivativeReport.timeColumns.isEmpty())
    }

    @Test fun snapshotsOrdersUnknownTypesAndFailedTransfersNeverMoveCoins() {
        val snapshot = interpret(MexcExports.SNAPSHOTS, "$uid,2026-01-01,Fiat,USDT,100,100").single()
        assertEquals(EvidenceRole.CHECKPOINT, snapshot.role)
        assertFalse(snapshot.moves)
        val order = interpret(MexcExports.ORDERS, "$uid,BTC_USDT,2026-01-01 12:00:00,Limit,Buy,10000,10000,0.1,0.1,1000,Filled").single()
        assertFalse(order.moves)
        val unknown = interpret(MexcExports.STATEMENT, "$uid,2026-01-01 12:00:00,USDT,Others,Inflow,1").single()
        assertEquals(RecordKind.UNKNOWN, unknown.kind)
        assertFalse(unknown.moves)
        assertEquals(
            RecordStatus.COMPLETED,
            interpret(MexcExports.DEPOSITS, "$uid,Credited Successfully,2026-01-01 12:00:00,USDT,TRX,10,tx,100").single().status,
        )
    }

    @Test fun convertAndRewardsKeepExactCoinsAndAmounts() {
        val convert =
            interpret(
                MexcExports.CONVERT,
                "$uid,2026-01-01 12:00:00,BTC/USDT,Market,0.1BTC,1000USDT,10000,2026-01-01 12:00:00,SUCCESS",
            ).single()
        assertEquals(RecordKind.CONVERT, convert.kind)
        assertEquals(listOf("-0.1", "1000"), convert.legs.map { it.amount.toString() })
        val reward = interpret(MexcExports.OTHER, "$uid,2026-01-01 12:00:00,BTC,Airdrop,0.000000000000000000000001,complete,").single()
        assertEquals(ExactDecimal.parse("0.000000000000000000000001"), reward.legs.single().amount)
        assertTrue(reward.moves)
    }

    @Test fun importRequiresZoneThenCommitsAndUndoesAFileOnlyAccount() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { at + 100_000 }
            app.snapshot()
            val fills = table(MexcExports.TRADES, "$uid,BTC_USDT,2026-01-01 12:00:00,Buy,10000,0.1,1000,0BTC,Maker")
            assertTrue(
                app.exchangeImports
                    .preview(listOf(fills))
                    .tables
                    .single()
                    .needsOffset,
            )
            val choices = ImportChoices(offset = ImportOffset(240), accounts = mapOf("mexc:$uid" to AccountChoice.New("MEXC export", uid)))
            val preview = app.exchangeImports.preview(listOf(fills), choices)
            assertTrue(preview.canCommit, preview.blockers.toString())
            assertEquals(mapOf(ImportStatus.NEW to 1), preview.counts)
            val receipt = app.exchangeImports.commit(preview)
            assertEquals(1, app.snapshot().activities.size)
            assertEquals(listOf(receipt.accounts.single() to uid), app.exchanges.targets(ExchangeKind.MEXC))
            assertEquals(
                mapOf(ImportStatus.ALREADY_IMPORTED to 1),
                app.exchangeImports.preview(listOf(fills.copy(file = "renamed.xlsx")), choices.copy(accounts = emptyMap())).counts,
            )
            app.exchangeImports.undo(receipt.id)
            assertTrue(app.snapshot().activities.none { it.activity.status == io.valroot.core.ledger.ActivityStatus.CONFIRMED })
        }
    }

    @Test fun formulasCannotSupplyFinancialValues() {
        val original = table(MexcExports.DEPOSITS, "$uid,Credited Successfully,2026-01-01 12:00:00,USDT,TRX,10,tx,100")
        val row = original.rows.last()
        val cells = row.cells.toMutableList().also { it[6] = ImportCell("10", formula = true) }
        val detected = ExportReports.detect(original.copy(rows = listOf(original.rows.first(), row.copy(cells = cells))))!!
        assertFailsWith<ImportFileException> { MexcExports.interpret(detected, mapOf("Time" to ImportOffset(240))) }
    }
}
