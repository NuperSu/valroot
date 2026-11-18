package io.valroot.core.exchange.imports

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.exchange.ExchangeRow
import io.valroot.core.exchange.ImportOutcome
import io.valroot.core.exchange.ImportedEvents
import io.valroot.core.exchange.bybit.BybitDecoder
import io.valroot.core.exchange.bybit.BybitExports
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.AccountId
import io.valroot.core.model.AssetId
import io.valroot.core.storage.BundledSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BybitImportTest {
    private fun records(
        header: String,
        vararg lines: String,
    ): List<ImportedRecord> {
        val table =
            ImportTable("synthetic.csv", "synthetic", null, null, ExportCsv.read(header + "\n" + lines.joinToString("\n"), ImportBudget()))
        val detected = requireNotNull(ExportReports.detect(table, BybitExports.reports))
        return BybitExports.interpret(detected, detected.offsets(ImportOffset(240)).mapValues { requireNotNull(it.value) })
    }

    private fun rows(records: List<ImportedRecord>) =
        records.mapIndexed { index, r ->
            ExchangeRow(
                ImportedEvents.STREAM,
                "test-$index",
                r.atMillis,
                ImportedEvents.payload(r),
                "file-$index",
            )
        }

    private val assets = mapOf("USDT" to AssetId("usdt"), "BTC" to AssetId("btc"))

    private fun decode(rows: List<ExchangeRow>) = BybitDecoder.decode(AccountId("bybit"), "123", rows, assets, null)

    private val funding = BybitExports.FUNDING.header.joinToString(",")
    private val unified = BybitExports.UNIFIED.header.joinToString(",")
    private val spot = BybitExports.SPOT.header.joinToString(",")

    @Test fun fundingImportKeepsOpeningAndUsesHeaderUtc() {
        val records = records(funding, "123,2026-01-01 12:00:00,USDT,2,Earn,12,Interest distribution")
        assertEquals(ImportTime.millis(ImportCell("2026-01-01 12:00:00"), ImportOffset(0)), records.single().atMillis)
        val result = decode(rows(records))
        assertEquals(2, result.activities.size)
        assertEquals(ActivityKind.REWARD, result.activities.last().kind)
        assertTrue(result.imports["test-0"] is ImportOutcome.New)
        assertEquals(
            "10000000000000000000000000000000",
            result.activities
                .first()
                .movements
                .single()
                .amount.atomicUnits
                .toString(),
        )
    }

    @Test fun fundingApiOverlapAddsEvidenceWithoutChangingOpening() {
        val records = records(funding, "123,2026-01-01 12:00:00,USDT,2,Earn,12,Interest distribution")
        val at = records.single().atMillis
        val api =
            ExchangeRow(
                BybitDecoder.FUNDING,
                "api",
                at + 500,
                """{"currcCursor":"api","currency":"USDT","txnAmt":"2","ioDirection":"I","afterAmt":"12","createTime":"${at / 1000}","showBusiTypeEn":"Earn","descriptionEn":"Interest distribution"}""",
                "api-source",
            )
        val result = decode(listOf(api) + rows(records))
        assertEquals(2, result.activities.size)
        assertTrue(result.imports["test-0"] is ImportOutcome.Same)
        assertEquals(
            2,
            result.activities
                .last()
                .sources.size,
        )
    }

    @Test fun unifiedSpotTradeCombinesCoinsAndFeesAndDefersDerivatives() {
        val records =
            records(
                unified,
                "123,BTC,BTCUSDT,TRADE,BUY,1,0,10,0,0.01,1,0.99,0.99,--,2026-01-01 12:00:00",
                "123,USDT,BTCUSDT,TRADE,BUY,1,0,10,0,0,-10,-10,0,--,2026-01-01 12:00:00",
                "123,USDT,BTCUSDT,TRADE,BUY,1,1,10,0,0,20,20,20,OPEN,2026-01-02 12:00:00",
            )
        assertEquals(EvidenceRole.DEFERRED, records.last().role)
        assertTrue("bybitWalletLog" !in records.last().details)
        val result = decode(rows(records))
        val trade = result.activities.single { it.kind == ActivityKind.TRADE }
        assertEquals(3, trade.movements.size)
        assertEquals(1, trade.movements.count { it.role == MovementRole.FEE })
        assertEquals(2, result.imports.size)
    }

    @Test fun sameMillisecondUnifiedFillsKeepDistinctEconomics() {
        val imported =
            records(
                unified,
                "123,BTC,BTCUSDT,TRADE,BUY,1,0,10,0,0,1,1,1,--,2026-01-01 12:00:00",
                "123,USDT,BTCUSDT,TRADE,BUY,1,0,10,0,0,-10,-10,90,--,2026-01-01 12:00:00",
                "123,BTC,BTCUSDT,TRADE,BUY,2,0,11,0,0,2,2,3,--,2026-01-01 12:00:00",
                "123,USDT,BTCUSDT,TRADE,BUY,2,0,11,0,0,-22,-22,68,--,2026-01-01 12:00:00",
            )
        val result = decode(rows(imported))
        assertEquals(2, result.activities.count { it.kind == ActivityKind.TRADE })
        assertEquals(
            4,
            result.imports.values
                .filterIsInstance<ImportOutcome.New>()
                .size,
        )
    }

    @Test fun savedUnifiedRowsKeepTheirKeysAndRecoverFillEvidenceFromTheReceipt() {
        val lines =
            listOf(
                "123,BTC,BTCUSDT,TRADE,BUY,1,0,10,0,0,1,1,1,--,2026-01-01 12:00:00",
                "123,USDT,BTCUSDT,TRADE,BUY,1,0,10,0,0,-10,-10,90,--,2026-01-01 12:00:00",
                "123,BTC,BTCUSDT,TRADE,SELL,1,0,11,0,0,-1,-1,0,--,2026-01-01 12:00:00",
                "123,USDT,BTCUSDT,TRADE,SELL,1,0,11,0,0,11,11,101,--,2026-01-01 12:00:00",
            )
        val table = ImportTable("unified.csv", "test", null, null, ExportCsv.read("$unified\n" + lines.joinToString("\n"), ImportBudget()))
        BundledSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1_800_000_000_000L }
            val choices = ImportChoices(accounts = mapOf("bybit:123" to AccountChoice.New("Bybit", "123")))
            val receipt = app.exchangeImports.commit(app.exchangeImports.preview(listOf(table), choices))
            val account = receipt.accounts.single()
            db.execute(
                "UPDATE exchange_rows SET payload=json_remove(payload, '$.details.direction', " +
                    "'$.details.quantity', '$.details.filledPrice') WHERE account=? AND stream='file'",
                listOf(account.value),
            )
            val saved = app.exchanges.savedRows(account)
            assertEquals(2, decode(saved).activities.count { it.kind == ActivityKind.TRADE })
            val preview = app.exchangeImports.preview(listOf(table))
            assertEquals(mapOf(ImportStatus.ALREADY_IMPORTED to 4), preview.counts)
            app.exchangeImports.commit(preview)
            assertEquals(saved.map { it.key }, app.exchanges.savedRows(account).map { it.key })
        }
    }

    @Test fun indistinguishableUnifiedFillsKeepMovementsWithoutInventingPairings() {
        val imported =
            records(
                unified,
                "123,BTC,BTCUSDT,TRADE,BUY,1,0,10,0,0,1,1,1,--,2026-01-01 12:00:00",
                "123,USDT,BTCUSDT,TRADE,BUY,1,0,10,0,0,-10,-10,90,--,2026-01-01 12:00:00",
                "123,BTC,BTCUSDT,TRADE,BUY,1,0,10,0,0,1,1,2,--,2026-01-01 12:00:00",
                "123,USDT,BTCUSDT,TRADE,BUY,1,0,10,0,0,-10,-10,80,--,2026-01-01 12:00:00",
            )
        val result = decode(rows(imported))
        val trades = result.activities.filter { it.kind == ActivityKind.TRADE }
        assertEquals(4, trades.size)
        assertTrue(trades.all { it.movements.size == 1 })
        assertEquals(
            4,
            result.imports.values
                .filterIsInstance<ImportOutcome.New>()
                .size,
        )
        assertTrue(result.failures.any { it.contains("lack pairing evidence") })
    }

    @Test fun sameMillisecondApiFillsKeepProviderIdentityWhenImportsOverlap() {
        val imported =
            records(
                unified,
                "123,BTC,BTCUSDT,TRADE,BUY,1,0,10,0,0,1,1,1,--,2026-01-01 12:00:00",
                "123,USDT,BTCUSDT,TRADE,BUY,1,0,10,0,0,-10,-10,90,--,2026-01-01 12:00:00",
                "123,BTC,BTCUSDT,TRADE,BUY,1,0,10,0,0,1,1,2,--,2026-01-01 12:00:00",
                "123,USDT,BTCUSDT,TRADE,BUY,1,0,10,0,0,-10,-10,80,--,2026-01-01 12:00:00",
            )
        val api =
            imported.mapIndexed { index, record ->
                val coin = record.legs.first().coin
                val change = record.details.getValue("change")
                val balance = record.details.getValue("balanceAfter")
                ExchangeRow(
                    BybitDecoder.LOG,
                    "api-$index",
                    record.atMillis,
                    """{"id":"log-${index / 2}","type":"TRADE","category":"spot","symbol":"BTCUSDT","currency":"$coin","change":"$change","cashFlow":"$change","fee":"0","cashBalance":"$balance","tradeId":"fill-${index / 2}","transactionTime":"${record.atMillis}"}""",
                    "api-source-$index",
                )
            }
        val result = decode(api + rows(imported))
        val trades = result.activities.filter { it.kind == ActivityKind.TRADE }
        assertEquals(2, trades.size)
        assertTrue(trades.all { it.sources.size == 4 })
        assertEquals(
            4,
            result.imports.values
                .filterIsInstance<ImportOutcome.Same>()
                .size,
        )
    }

    @Test fun legacySpotGroupsTradeAndFeeAndPairsFundingTransfer() {
        val records =
            records(funding, "123,2026-01-01 11:00:00,USDT,-10,Transfer out,0,Transfer to Spot Account") +
                records(
                    spot,
                    "internalAccountTransferDeposit,USDT,10,10,2026-01-01 11:00:00",
                    "trade,USDT,-10,0,2026-01-01 12:00:00",
                    "trade,BTC,1,1,2026-01-01 12:00:00",
                    "tradingFee,BTC,-0.01,0.99,2026-01-01 12:00:00",
                )
        val result = decode(rows(records))
        assertEquals(1, result.activities.count { it.kind == ActivityKind.TRADE })
        assertEquals(2, result.activities.size)
        assertEquals(
            "10000000000000000000000000000000",
            result.activities
                .first()
                .movements
                .single()
                .amount.atomicUnits
                .toString(),
        )
    }

    @Test fun fundingFuturesTransferIsBoundaryAndCardHistoryOnlySupports() {
        val r = records(funding, "123,2026-01-01 12:00:00,USDT,-3,Transfer out,7,Transfer to Futures Account").single()
        assertEquals(RecordKind.FUTURES_IN, r.kind)
        assertTrue(r.moves)
        assertEquals(EvidenceRole.SUPPORTING, BybitExports.CARD.role)
    }

    @Test fun transferHistoryOnlyEnrichesMatchingFundingLog() {
        val primary = records(funding, "123,2026-01-01 12:00:00,BTC,1,Deposit,1,Deposit")
        val supporting =
            records(
                BybitExports.TRANSFERS.header.joinToString(","),
                "123,2026-01-01 16:00:00,Deposit,BTC,BTC,1,synthetic-tx,Completed,address",
            )
        assertTrue(!supporting.single().moves)
        val result = decode(rows(primary + supporting))
        assertEquals(1, result.activities.size)
        assertEquals(
            "synthetic-tx",
            result.transfers.values
                .single()
                .txId,
        )
        assertEquals(
            2,
            result.activities
                .single()
                .sources.size,
        )
        assertTrue(decode(rows(supporting)).activities.isEmpty())
    }

    @Test fun surplusIdenticalLogsWaitForDecision() {
        val records =
            records(
                funding,
                "123,2026-01-01 12:00:00,USDT,2,Earn,12,Interest distribution",
                "123,2026-01-01 12:00:00,USDT,2,Earn,12,Interest distribution",
            )
        val at = records.first().atMillis
        val api =
            ExchangeRow(
                BybitDecoder.FUNDING,
                "api",
                at,
                """{"currcCursor":"api","currency":"USDT","txnAmt":"2","ioDirection":"I","afterAmt":"12","createTime":"${at / 1000}","showBusiTypeEn":"Earn","descriptionEn":"Interest distribution"}""",
                "api-source",
            )
        val imported = rows(records)
        val result = decode(listOf(api) + imported)
        val ambiguity =
            result.imports.values
                .filterIsInstance<ImportOutcome.Ambiguous>()
                .first()
        assertEquals(2, ambiguity.records)
        assertEquals(1, ambiguity.candidates)
        assertEquals(
            2,
            result.imports.values
                .filterIsInstance<ImportOutcome.Ambiguous>()
                .size,
        )
        val decision = ExchangeRow(ImportedEvents.DECISIONS, ambiguity.group, 0, GroupDecision.DISTINCT.name, "decision")
        assertEquals(3, decode(listOf(api, decision) + imported).activities.count { it.kind == ActivityKind.REWARD })
        val excluded = decision.copy(payload = GroupDecision.EXCLUDED.name)
        assertEquals(
            2,
            decode(listOf(api, excluded) + imported)
                .imports.values
                .filterIsInstance<ImportOutcome.Excluded>()
                .size,
        )
    }

    @Test fun legacyUnifiedRowsWithoutAmountsAreEvidenceThatMovesNothing() {
        val legacy = BybitExports.UNIFIED_LEGACY.header.joinToString(",")
        val records = records(legacy, "USDT,,INTEREST,--,,,,,,,,,--,2024-03-01 00:00:00")
        val record = records.single()
        assertEquals(EvidenceRole.SUPPORTING, record.role)
        assertTrue(record.legs.isEmpty())
        assertTrue(!record.moves)
    }

    @Test fun unknownWalletOperationsRemainUnresolved() {
        val records =
            records(funding, "123,2026-01-01 12:00:00,USDT,2,Unrecognized business,12,Unknown operation") +
                records(spot, "newUnknownType,BTC,1,1,2026-01-01 12:00:00")
        assertTrue(records.all { it.kind == RecordKind.UNKNOWN && !it.moves && "bybitWalletLog" !in it.details })
        assertTrue(decode(rows(records)).activities.isEmpty())
    }

    @Test fun deferredReportsRequireNoTimeZone() {
        val report = BybitExports.reports.first { it.role == EvidenceRole.DEFERRED }
        assertTrue(report.timeColumns.isEmpty())
        val records = records(report.header.joinToString(","), List(report.header.size) { "--" }.joinToString(","))
        assertEquals(RecordKind.DERIVATIVE, records.single().kind)
        assertTrue(!records.single().moves)
    }
}
