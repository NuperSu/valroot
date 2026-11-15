package io.valroot.core.exchange.binance

import io.valroot.core.exchange.ExchangeKind
import io.valroot.core.exchange.imports.DetectedTable
import io.valroot.core.exchange.imports.EvidenceRole
import io.valroot.core.exchange.imports.ExchangeExportAdapter
import io.valroot.core.exchange.imports.ExportReport
import io.valroot.core.exchange.imports.ImportFileException
import io.valroot.core.exchange.imports.ImportOffset
import io.valroot.core.exchange.imports.ImportRow
import io.valroot.core.exchange.imports.ImportTime
import io.valroot.core.exchange.imports.ImportValues
import io.valroot.core.exchange.imports.ImportedRecord
import io.valroot.core.exchange.imports.ProductFamily
import io.valroot.core.exchange.imports.RecordKind
import io.valroot.core.exchange.imports.RecordLeg
import io.valroot.core.exchange.imports.RecordSource
import io.valroot.core.exchange.imports.RecordStatus
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.ExactDecimal

/**
 * Binance's export files (all CSV, usually zipped):
 *
 * - **Spot trade history** (current and the older `Date(UTC)` form): each fill, with its amounts written with their coin.
 * - **Deposit and withdrawal history:** statuses, networks and transaction IDs.
 * - **Transaction history** (statement): every balance change by wallet and operation. Its trade, convert and dust rows
 *   are grouped per second and settle against the detailed fills; deposits and withdrawals settle against the detailed
 *   histories; moves between Spot, Funding and Earn are internal; transfers to and from futures are boundary transfers;
 *   rows of the futures wallets wait for futures accounting.
 * - **Spot order history:** supporting evidence only; an order never creates a trade.
 *
 * Times without their own offset use the one in the file name (`…(UTC+4)…`).
 */
object BinanceExports : ExchangeExportAdapter {
    override val kind = ExchangeKind.BINANCE

    val LEGACY_TRADES =
        report(
            "binance.trades.legacy",
            "Spot trade history",
            ProductFamily.SPOT,
            EvidenceRole.PRIMARY,
            "Date(UTC),Pair,Side,Price,Executed,Amount,Fee",
        )
    val TRADES =
        report(
            "binance.trades",
            "Spot trade history",
            ProductFamily.SPOT,
            EvidenceRole.PRIMARY,
            "Order No,Time,Pair,Side,Price,Executed,Amount,Fee,AOR Conversion Pair,AOR Conversion Rate",
        )
    val ORDERS =
        report(
            "binance.orders",
            "Spot order history",
            ProductFamily.SPOT,
            EvidenceRole.SUPPORTING,
            "Time,OrderNo,Pair,Type¹,Side,Order Price,Order Amount,Time,Executed²,Average Price,Trading total³,Status",
        )
    val DEPOSITS =
        report(
            "binance.deposits",
            "Deposit history",
            ProductFamily.FUNDING,
            EvidenceRole.PRIMARY,
            "Time,Coin,Network,Amount,Address,TXID,Status",
        )
    val WITHDRAWALS =
        report(
            "binance.withdrawals",
            "Withdrawal history",
            ProductFamily.FUNDING,
            EvidenceRole.PRIMARY,
            "Time,Coin,Network,Amount,Fee,Address,TXID,Status",
        )
    val STATEMENT =
        report(
            "binance.statement",
            "Transaction history",
            ProductFamily.SPOT,
            EvidenceRole.PRIMARY,
            "User ID,Time,Account,Operation,Coin,Change,Remark",
        )

    override val reports = listOf(LEGACY_TRADES, TRADES, ORDERS, DEPOSITS, WITHDRAWALS, STATEMENT)

    private fun report(
        id: String,
        label: String,
        family: ProductFamily,
        role: EvidenceRole,
        header: String,
    ): ExportReport {
        val columns = header.split(',')
        return ExportReport(
            id,
            ExchangeKind.BINANCE,
            label,
            family,
            role,
            columns,
            columns
                .filter {
                    it.startsWith("Time") ||
                        it.startsWith("Date")
                }.toSet(),
        )
    }

    /** Statement operations by meaning. */
    private val TRADE_OPERATIONS =
        setOf("Buy", "Sell", "Fee", "Transaction Buy", "Transaction Spend", "Transaction Revenue", "Transaction Sold", "Transaction Fee")
    private val FEE_OPERATIONS = setOf("Fee", "Transaction Fee")
    private val CONVERT_OPERATIONS = setOf("Binance Convert", "Small Assets Exchange BNB", "Auto-Invest Transaction")
    private val INTERNAL_OPERATIONS =
        setOf(
            "Transfer Between Spot and Funding",
            "Simple Earn Flexible Subscription",
            "Simple Earn Flexible Redemption",
            "Simple Earn Locked Subscription",
            "Simple Earn Locked Redemption",
        )
    private val REWARD_OPERATIONS =
        setOf(
            "Simple Earn Flexible Interest",
            "Simple Earn Locked Rewards",
            "BNB Vault Rewards",
            "Launchpool Airdrop - System Distribution",
            "Launchpool Airdrop - User Claim Distribution",
            "Megadrop Rewards",
            "Airdrop Assets",
            "Cashback",
            "Commission Rebate",
            "Crypto Box",
            "Distribution",
        )
    private val FUTURES_TRANSFERS = setOf("Transfer Between Spot and UM Futures", "Transfer Between Spot and CM Futures")
    private val SUPPORTED_WALLETS = setOf("Spot", "Funding")

    override fun interpret(
        table: DetectedTable,
        offsets: Map<String, ImportOffset>,
    ): List<ImportedRecord> {
        val uid = table.uids().singleOrNull()
        return when (table.report.id) {
            LEGACY_TRADES.id, TRADES.id -> table.rows.map { trade(table, it, offsets, uid) }
            ORDERS.id -> table.rows.map { order(table, it, offsets, uid) }
            DEPOSITS.id, WITHDRAWALS.id -> table.rows.map { transfer(table, it, offsets, uid) }
            STATEMENT.id -> statement(table, offsets)
            else -> throw ImportFileException("${table.report.label} is not a Binance report")
        }
    }

    private fun record(
        table: DetectedTable,
        row: ImportRow,
        kind: RecordKind,
        status: RecordStatus,
        at: Long,
        legs: List<RecordLeg>,
        uid: String?,
        wallet: String? = null,
        details: Map<String, String> = emptyMap(),
        role: EvidenceRole = table.report.role,
        family: ProductFamily = table.report.family,
    ) = ImportedRecord(
        exchange = ExchangeKind.BINANCE,
        report = table.report.id,
        family = family,
        role = role,
        kind = kind,
        status = status,
        atMillis = at,
        legs = legs,
        uid = uid,
        wallet = wallet,
        details = details,
        source = RecordSource(table.table.file, table.table.fileHash, table.table.member, table.table.sheet, row.number),
        cells = row.cells.map { it.text },
    )

    private fun time(
        table: DetectedTable,
        row: ImportRow,
        offsets: Map<String, ImportOffset>,
        column: String,
    ): Long {
        val offset = offsets[column] ?: throw ImportFileException("Choose the time zone of ${table.table.label}")
        val cell =
            row.cells.getOrNull(table.column(column)) ?: throw ImportFileException("Row ${row.number} of ${table.table.label} has no time")
        if (cell.formula) throw ImportFileException("Row ${row.number} of ${table.table.label} has a calculated time; export it again")
        return ImportTime.millis(cell, offset, table.table.date1904)
    }

    private fun label(
        table: DetectedTable,
        row: ImportRow,
    ) = "row ${row.number} of ${table.table.label}"

    private fun negate(value: ExactDecimal) = ExactDecimal(-value.coefficient, value.scale)

    /** A fill: base and quote legs from its side, the fee in whatever coin it was charged. */
    private fun trade(
        table: DetectedTable,
        row: ImportRow,
        offsets: Map<String, ImportOffset>,
        uid: String?,
    ): ImportedRecord {
        val where = label(table, row)
        val pair = row.text(table.column("Pair")).trim()
        val executed = row.text(table.column("Executed"))
        val amount = row.text(table.column("Amount"))
        // The pair's split is the one whose base ends the executed amount and whose quote ends the traded amount.
        val (base, quote) =
            (1 until pair.length)
                .map { pair.take(it) to pair.drop(it) }
                .firstOrNull { (b, q) -> executed.trim().endsWith(b) && amount.trim().endsWith(q) }
                ?: throw ImportFileException("The pair $pair in $where does not match its amounts")
        val (quantity, _) = ImportValues.amountAndCoin(executed, listOf(base), where)
        val (total, _) = ImportValues.amountAndCoin(amount, listOf(quote), where)
        val fee =
            row
                .text(table.column("Fee"))
                .trim()
                .takeIf {
                    it.isNotEmpty()
                }?.let { ImportValues.amountAndCoin(it, listOf(base, quote, "BNB"), where) }
        val buy =
            when (row.text(table.column("Side")).trim().uppercase()) {
                "BUY" -> true
                "SELL" -> false
                else -> throw ImportFileException("The side in $where is neither BUY nor SELL")
            }
        val legs =
            listOf(
                RecordLeg(base, if (buy) quantity else negate(quantity), MovementRole.TRADE_LEG),
                RecordLeg(quote, if (buy) negate(total) else total, MovementRole.TRADE_LEG),
            ) +
                listOfNotNull(
                    fee?.takeIf { it.first.coefficient.sign != 0 }?.let { RecordLeg(it.second, negate(it.first), MovementRole.FEE) },
                )
        val time = if (table.report.id == LEGACY_TRADES.id) "Date(UTC)" else "Time"
        val details =
            mapOf("pair" to pair) +
                (if (table.report.id == TRADES.id) mapOf("order" to row.text(table.column("Order No")).trim()) else emptyMap())
        return record(table, row, RecordKind.TRADE, RecordStatus.COMPLETED, time(table, row, offsets, time), legs, uid, "Spot", details)
    }

    private fun order(
        table: DetectedTable,
        row: ImportRow,
        offsets: Map<String, ImportOffset>,
        uid: String?,
    ): ImportedRecord {
        val status =
            when (row.text(table.column("Status")).trim().uppercase()) {
                "FILLED" -> RecordStatus.COMPLETED
                "CANCELED", "CANCELLED", "EXPIRED" -> RecordStatus.CANCELLED
                "PARTIALLY_FILLED", "NEW" -> RecordStatus.PENDING
                "REJECTED" -> RecordStatus.FAILED
                else -> RecordStatus.UNKNOWN
            }
        val details =
            mapOf(
                "order" to row.text(table.column("OrderNo")).trim(),
                "pair" to row.text(table.column("Pair")).trim(),
                "side" to row.text(table.column("Side")).trim(),
                "executed" to row.text(table.column("Executed²")).trim(),
            )
        return record(table, row, RecordKind.ORDER, status, time(table, row, offsets, "Time"), emptyList(), uid, "Spot", details)
    }

    private fun status(text: String): RecordStatus =
        when (text.trim().lowercase()) {
            "completed", "success", "successful" -> RecordStatus.COMPLETED
            "pending", "processing", "confirming", "awaiting approval" -> RecordStatus.PENDING
            "cancelled", "canceled" -> RecordStatus.CANCELLED
            "failed", "failure", "rejected" -> RecordStatus.FAILED
            else -> RecordStatus.UNKNOWN
        }

    /** A deposit or withdrawal: the amount, a withdrawal's network fee as its own leg, and the on-chain transaction. */
    private fun transfer(
        table: DetectedTable,
        row: ImportRow,
        offsets: Map<String, ImportOffset>,
        uid: String?,
    ): ImportedRecord {
        val where = label(table, row)
        val coin = row.text(table.column("Coin")).trim()
        val amount = ImportValues.decimal(row.text(table.column("Amount")), where)
        val deposit = table.report.id == DEPOSITS.id
        val fee = if (deposit) null else ImportValues.optionalDecimal(row.text(table.column("Fee")), where)
        val legs =
            listOf(RecordLeg(coin, if (deposit) amount else negate(amount), MovementRole.TRANSFER)) +
                listOfNotNull(fee?.takeIf { it.coefficient.sign != 0 }?.let { RecordLeg(coin, negate(it), MovementRole.FEE) })
        val details =
            mapOf(
                "network" to row.text(table.column("Network")).trim(),
                "txid" to row.text(table.column("TXID")).trim(),
            ).filterValues { it.isNotEmpty() }
        return record(
            table,
            row,
            if (deposit) RecordKind.DEPOSIT else RecordKind.WITHDRAWAL,
            status(row.text(table.column("Status"))),
            time(table, row, offsets, "Time"),
            legs,
            uid,
            "Spot",
            details,
        )
    }

    /**
     * The statement: trade, convert and dust rows of one second become one statement group per kind (its net change by
     * coin, with fees as fee legs); every other row is one record.
     */
    private fun statement(
        table: DetectedTable,
        offsets: Map<String, ImportOffset>,
    ): List<ImportedRecord> {
        val records = mutableListOf<ImportedRecord>()
        val groups = linkedMapOf<Triple<String?, Long, RecordKind>, MutableList<Pair<ImportRow, RecordLeg>>>()
        val uidColumn = table.column("User ID")
        table.rows.forEach { row ->
            val where = label(table, row)
            val uid = row.text(uidColumn).trim().ifEmpty { null }
            val wallet = row.text(table.column("Account")).trim()
            val operation = row.text(table.column("Operation")).trim()
            val coin = row.text(table.column("Coin")).trim()
            val change = ImportValues.decimal(row.text(table.column("Change")), where)
            val at = time(table, row, offsets, "Time")
            val details = mapOf("operation" to operation, "statement" to "1") + remark(table, row)
            val incoming = change.coefficient.sign > 0
            if (wallet !in SUPPORTED_WALLETS) {
                records +=
                    record(
                        table,
                        row,
                        RecordKind.DERIVATIVE,
                        RecordStatus.COMPLETED,
                        at,
                        listOf(RecordLeg(coin, change, MovementRole.TRANSFER)),
                        uid,
                        wallet,
                        details,
                        EvidenceRole.DEFERRED,
                        ProductFamily.FUTURES,
                    )
                return@forEach
            }
            val kind =
                when (operation) {
                    in TRADE_OPERATIONS -> RecordKind.TRADE
                    in CONVERT_OPERATIONS -> RecordKind.CONVERT
                    in INTERNAL_OPERATIONS -> RecordKind.INTERNAL_TRANSFER
                    in REWARD_OPERATIONS -> if (incoming) RecordKind.REWARD else RecordKind.OTHER
                    in FUTURES_TRANSFERS -> if (incoming) RecordKind.FUTURES_OUT else RecordKind.FUTURES_IN
                    "Deposit" -> RecordKind.DEPOSIT
                    "Withdraw" -> RecordKind.WITHDRAWAL
                    "Spending" -> if (incoming) RecordKind.CARD_REFUND else RecordKind.CARD_PAYMENT
                    "P2P Trading" -> if (incoming) RecordKind.DEPOSIT else RecordKind.PAYMENT
                    "Thirdparty Wallet Transfer" -> if (incoming) RecordKind.DEPOSIT else RecordKind.WITHDRAWAL
                    else -> RecordKind.UNKNOWN
                }
            if (kind == RecordKind.TRADE || kind == RecordKind.CONVERT) {
                val role = if (operation in FEE_OPERATIONS) MovementRole.FEE else MovementRole.TRADE_LEG
                groups.getOrPut(Triple(uid, at, kind)) { mutableListOf() } += row to RecordLeg(coin, change, role)
                return@forEach
            }
            val role = if (kind == RecordKind.REWARD) MovementRole.REWARD else MovementRole.TRANSFER
            records += record(table, row, kind, RecordStatus.COMPLETED, at, listOf(RecordLeg(coin, change, role)), uid, wallet, details)
        }
        groups.forEach { (key, parts) ->
            val (_, at, kind) = key
            val legs =
                parts
                    .groupBy { it.second.coin to it.second.role }
                    .map { (coinRole, list) ->
                        RecordLeg(
                            coinRole.first,
                            list.map { it.second.amount }.reduce {
                                a,
                                b,
                                ->
                                a + b
                            },
                            coinRole.second,
                        )
                    }.filter { it.amount.coefficient.sign != 0 }
            val first = parts.first().first
            records +=
                record(
                    table,
                    first,
                    kind,
                    RecordStatus.COMPLETED,
                    at,
                    legs,
                    first.text(uidColumn).trim().ifEmpty { null },
                    "Spot",
                    mapOf("statement" to "1", "rows" to parts.joinToString(",") { it.first.number.toString() }),
                )
        }
        return records.sortedBy { it.atMillis }
    }

    private fun remark(
        table: DetectedTable,
        row: ImportRow,
    ): Map<String, String> =
        row
            .text(table.column("Remark"))
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { mapOf("remark" to it) }
            .orEmpty()
}
