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
import io.valroot.core.reconcile.ActivityOverlay
import io.valroot.core.storage.BundledSqliteDatabase
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
        BundledSqliteDatabase(":memory:").use { db ->
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

    @Test fun statementRowsOnOtherLinesAreTheSameTrade() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { at + 100_000 }
            app.snapshot()
            val buy = "$uid,2026-01-01 12:00:00,BTC,Spot Trading,Inflow,0.1"
            val pay = "$uid,2026-01-01 12:00:00,USDT,Spot Trading,Outflow,1000"
            val deposit = "$uid,2026-01-02 09:00:00,USDT,Deposit,Inflow,50"
            val first = table(MexcExports.STATEMENT, buy, pay, file = "first.xlsx")
            // The same trade rows below another row and in the other order: other line numbers, same record.
            val second = table(MexcExports.STATEMENT, deposit, pay, buy, file = "second.xlsx")
            val choices = ImportChoices(offset = ImportOffset(240), accounts = mapOf("mexc:$uid" to AccountChoice.New("MEXC export", uid)))
            val together = app.exchangeImports.preview(listOf(first, second), choices)
            assertEquals(mapOf(ImportStatus.NEW to 2), together.counts)
            app.exchangeImports.commit(app.exchangeImports.preview(listOf(first), choices))
            val again = app.exchangeImports.preview(listOf(second), choices.copy(accounts = emptyMap()))
            assertEquals(1, again.counts[ImportStatus.ALREADY_IMPORTED], again.counts.toString())
            app.exchangeImports.commit(again)
            assertEquals(
                1,
                app
                    .snapshot()
                    .activities
                    .count { it.activity.kind == ActivityKind.TRADE },
            )
        }
    }

    @Test fun aFeeRowBeforeTheTradeRowsIsTheSameRecord() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { at + 100_000 }
            app.snapshot()
            val buy = "$uid,2026-01-01 12:00:00,BTC,Spot Trading,Inflow,0.1"
            val pay = "$uid,2026-01-01 12:00:00,USDT,Spot Trading,Outflow,1000"
            val fee = "$uid,2026-01-01 12:00:00,BTC,Spot Trading Fees,Outflow,0.0001"
            val choices = ImportChoices(offset = ImportOffset(240), accounts = mapOf("mexc:$uid" to AccountChoice.New("MEXC export", uid)))
            app.exchangeImports.commit(
                app.exchangeImports.preview(listOf(table(MexcExports.STATEMENT, buy, pay, fee, file = "a.xlsx")), choices),
            )
            val again =
                app.exchangeImports.preview(
                    listOf(table(MexcExports.STATEMENT, fee, buy, pay, file = "b.xlsx")),
                    choices.copy(accounts = emptyMap()),
                )
            assertEquals(mapOf(ImportStatus.ALREADY_IMPORTED to 1), again.counts)
        }
    }

    @Test fun aMovedLegacyStatementKeepsItsActivityAndSurvivesUndoOfTheOriginalBatch() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { at + 100_000 }
            app.snapshot()
            val buy = "$uid,2026-01-01 12:00:00,BTC,Spot Trading,Inflow,0.1"
            val pay = "$uid,2026-01-01 12:00:00,USDT,Spot Trading,Outflow,1000"
            val deposit = "$uid,2026-01-02 09:00:00,USDT,Deposit,Inflow,50"
            val file = table(MexcExports.STATEMENT, buy, pay, deposit)
            val choices = ImportChoices(offset = ImportOffset(240), accounts = mapOf("mexc:$uid" to AccountChoice.New("MEXC export", uid)))
            val first = app.exchangeImports.commit(app.exchangeImports.preview(listOf(file), choices))
            val account = first.accounts.single()
            val sources = app.exchangeImports.sources(first.id)
            val records = interpret(MexcExports.STATEMENT, buy, pay, deposit)
            val legacyKeys = ImportValues.keys(records.first().report, records.map(::legacyStatementContent))
            val assets = app.exchanges.historyAssets(ExchangeKind.MEXC, account)
            db.transaction {
                records.zip(legacyKeys).forEach { (record, legacyKey) ->
                    val originalKey = sources.single { it.rows.first() == record.source.row }.key!!
                    db.execute(
                        "UPDATE exchange_rows SET key=?, payload=? WHERE account=? AND stream='file' AND key=?",
                        listOf(legacyKey, ImportedEvents.payload(record), account.value, originalKey),
                    )
                    db.execute(
                        "UPDATE exchange_import_events SET record_key=? WHERE account=? AND record_key=?",
                        listOf(legacyKey, account.value, originalKey),
                    )
                    db.execute(
                        "UPDATE exchange_import_sources SET record_key=? WHERE account=? AND record_key=?",
                        listOf(legacyKey, account.value, originalKey),
                    )
                }
                app.exchanges.rederiveInTransaction(ExchangeKind.MEXC, account, uid, assets)
            }

            fun active() =
                app
                    .snapshot()
                    .activities
                    .map { it.activity }
                    .filter { it.status == io.valroot.core.ledger.ActivityStatus.CONFIRMED }

            app.reload()
            val originalIds = active().map { it.id to it.movements.map { movement -> movement.id } }.toSet()
            assertEquals(2, originalIds.size)
            val tradeId = active().single { it.kind == ActivityKind.TRADE }.id
            app.transactions.saveOverlay(ActivityOverlay(tradeId, note = "Original trade note"))
            val shifted = table(MexcExports.STATEMENT, deposit, pay, buy, file = "shifted.xlsx")
            val preview = app.exchangeImports.preview(listOf(shifted), choices.copy(accounts = emptyMap()))
            assertEquals(mapOf(ImportStatus.ALREADY_IMPORTED to 2), preview.counts)
            val second = app.exchangeImports.commit(preview)
            assertEquals(originalIds, active().map { it.id to it.movements.map { movement -> movement.id } }.toSet())

            // Model an older import that saved its moved statement trade under a distinct legacy key.
            val moved = interpret(MexcExports.STATEMENT, deposit, pay, buy).single { it.kind == RecordKind.TRADE }
            val movedKey = ImportValues.keys(moved.report, listOf(legacyStatementContent(moved))).single()
            val tradeKey = legacyKeys[records.indexOfFirst { it.kind == RecordKind.TRADE }]
            db.transaction {
                val payload = ImportedEvents.payload(moved)
                val source =
                    app.exchanges.ingestRecord(
                        ExchangeKind.MEXC,
                        account,
                        uid,
                        "file:$movedKey",
                        payload,
                        moved.report,
                        origin = "file",
                    )
                app.exchanges.saveRowInTransaction(
                    account,
                    ExchangeRow(ImportedEvents.STREAM, movedKey, moved.atMillis, payload, source.value),
                )
                db.execute(
                    "UPDATE exchange_import_events SET record_key=? WHERE batch=? AND account=? AND record_key=?",
                    listOf(movedKey, second.id, account.value, tradeKey),
                )
                db.execute(
                    "UPDATE exchange_import_sources SET record_key=? WHERE batch=? AND account=? AND record_key=?",
                    listOf(movedKey, second.id, account.value, tradeKey),
                )
                app.exchanges.rederiveInTransaction(ExchangeKind.MEXC, account, uid, assets)
            }
            app.reload()
            assertEquals(originalIds, active().map { it.id to it.movements.map { movement -> movement.id } }.toSet())
            app.exchangeImports.undo(first.id)
            assertEquals(originalIds, active().map { it.id to it.movements.map { movement -> movement.id } }.toSet())
            assertEquals(
                "Original trade note",
                app
                    .snapshot()
                    .activities
                    .single { it.activity.id == tradeId }
                    .note,
            )
            app.exchangeImports.undo(second.id)
            assertTrue(active().isEmpty())
        }
    }

    private fun legacyStatementContent(record: ImportedRecord) =
        listOf(
            record.uid.orEmpty(),
            record.kind.name,
            record.status.name,
            record.atMillis.toString(),
            record.legs.joinToString(";") { "${it.coin}=${it.amount}/${it.role}" },
            record.wallet.orEmpty(),
            record.namespace.orEmpty(),
            record.nativeId.orEmpty(),
            record.details.entries
                .sortedBy { it.key }
                .joinToString(";") { "${it.key}=${it.value}" },
        ).joinToString("|")

    private fun saved(
        record: ImportedRecord,
        key: String,
    ) = ExchangeRow(ImportedEvents.STREAM, key, record.atMillis, ImportedEvents.payload(record), "source-$key")

    @Test fun equalRowsOfOneFileStayDistinctAndTheirCopiesDoNot() {
        val deposit = "$uid,2026-01-02 09:00:00,USDT,Deposit,Inflow,50"
        // Two equal deposits in one export, on different rows.
        val records = interpret(MexcExports.STATEMENT, deposit, deposit)
        assertTrue(records[0].details["rows"] != records[1].details["rows"])
        val rows =
            listOf(
                // An earlier version keyed each record with its row numbers: two different hashes for the two deposits.
                saved(records[0], "statement:aaaa:0"),
                saved(records[1], "statement:cccc:0"),
                // The same file imported again under the current keys: one hash, ordinals 0 and 1.
                saved(
                    records[0].copy(details = records[0].details + (StatementIdentity.CANONICAL_KEY to "statement:aaaa:0")),
                    "statement:bbbb:0",
                ),
                saved(
                    records[1].copy(details = records[1].details + (StatementIdentity.CANONICAL_KEY to "statement:cccc:0")),
                    "statement:bbbb:1",
                ),
            )
        val decoded = MexcDecoder.decode(account, uid, rows, assets, emptyMap(), null)
        assertEquals(2, decoded.activities.size)
        assertEquals(2, decoded.imports.values.count { it is ImportOutcome.Same })
        // Only the legacy rows, without the second import: still two deposits.
        assertEquals(2, MexcDecoder.decode(account, uid, rows.take(2), assets, emptyMap(), null).activities.size)
    }

    @Test fun historicalStatementCopiesWithoutProvenanceRequireReview() {
        BundledSqliteDatabase(":memory:").use { db ->
            val repository = ImportRepository(db)
            val record = interpret(MexcExports.STATEMENT, "$uid,2026-01-02 09:00:00,USDT,Deposit,Inflow,50").single()
            val rows = listOf(saved(record, "legacy:a"), saved(record, "legacy:b"))
            assertTrue(repository.hasUnprovenStatementCopies(account, rows))
            assertFalse(repository.hasUnprovenStatementCopies(account, rows.take(1)))
            val aliased =
                rows[1].copy(
                    payload =
                        ImportedEvents.payload(
                            record.copy(
                                details =
                                    record.details +
                                        (StatementIdentity.CANONICAL_KEY to rows[0].key),
                            ),
                        ),
                )
            assertFalse(repository.hasUnprovenStatementCopies(account, listOf(rows[0], aliased)))
        }
    }

    @Test fun equalAmountsOfDifferentOperationsAreDifferentRecords() {
        val records =
            interpret(
                MexcExports.STATEMENT,
                "$uid,2026-01-02 09:00:00,USDT,Airdrop,Inflow,5",
                "$uid,2026-01-02 09:00:00,USDT,Locked Savings Airdrop,Inflow,5",
            )
        val rows = records.mapIndexed { index, record -> saved(record, "statement:h$index:0") }
        assertEquals(2, MexcDecoder.decode(account, uid, rows, assets, emptyMap(), null).activities.size)
    }

    @Test fun aFileDepositKeepsItsTransactionIdForLaterReports() {
        val deposits =
            interpret(
                MexcExports.DEPOSITS,
                "$uid,Completed,2026-01-02 09:00:00,USDT,TRC20,50,0xaaa,1/1",
                "$uid,Completed,2026-01-02 09:00:00,USDT,TRC20,50,0xbbb,1/1",
            )
        // The second deposit reaches the account through another report: equal amount and time, but its own transaction.
        val rows = listOf(saved(deposits[0], "deposits:a:0"), saved(deposits[1].copy(report = "deposits-other"), "other:b:0"))
        val decoded = MexcDecoder.decode(account, uid, rows, assets, emptyMap(), null)
        assertEquals(2, decoded.activities.size)
        assertEquals(
            setOf("0xaaa", "0xbbb"),
            decoded.transfers.values
                .map { it.txId }
                .toSet(),
        )
    }

    @Test fun aMatchedRecordEnrichesTheTransferBeforeTheNextReportIsMatched() {
        val records =
            interpret(
                MexcExports.DEPOSITS,
                "$uid,Completed,2026-01-02 09:00:00,USDT,TRC20,50,0xaaa,1/1",
                "$uid,Completed,2026-01-02 09:00:00,USDT,TRC20,50,0xbbb,1/1",
            )
        val withoutId = records[0].copy(report = "a", details = records[0].details - "txid")
        val rows = listOf(saved(withoutId, "a"), saved(records[0].copy(report = "b"), "b"), saved(records[1].copy(report = "c"), "c"))
        val decoded = MexcDecoder.decode(account, uid, rows, assets, emptyMap(), null)
        assertEquals(2, decoded.activities.size)
        assertEquals(
            setOf("0xaaa", "0xbbb"),
            decoded.transfers.values
                .map { it.txId }
                .toSet(),
        )
        assertTrue(decoded.imports["b"] is ImportOutcome.Same)
    }
}
