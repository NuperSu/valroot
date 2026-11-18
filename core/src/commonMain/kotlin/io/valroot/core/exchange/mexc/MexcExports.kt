package io.valroot.core.exchange.mexc

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

/** MEXC spot/funding exports. Order summaries and snapshots never create balance movements. */
object MexcExports : ExchangeExportAdapter {
    override val kind = ExchangeKind.MEXC

    private fun report(
        id: String,
        label: String,
        header: String,
        role: EvidenceRole = EvidenceRole.PRIMARY,
        family: ProductFamily = ProductFamily.SPOT,
    ): ExportReport {
        val columns = header.split(',')
        return ExportReport(
            "mexc.$id",
            kind,
            label,
            family,
            role,
            columns,
            if (role ==
                EvidenceRole.DEFERRED
            ) {
                emptySet()
            } else {
                columns
                    .filter {
                        it.contains("Time") ||
                            it.startsWith("Date") ||
                            it.startsWith("update_time") ||
                            it.startsWith("create_time")
                    }.toSet()
            },
        )
    }

    val TRADES = report("trades", "Spot fills", "UID,Pairs,Time,Side,Filled Price,Executed Amount,Total,Fee,Role")
    val ORDERS =
        report(
            "orders",
            "Spot orders",
            "UID,Pairs,Time,Type,Direction,Average Filled Price,Order Price,Filled Quantity,Order Quantity,Order Amount,Status",
            EvidenceRole.SUPPORTING,
        )
    val LEGACY_ORDERS =
        report(
            "orders.legacy",
            "Spot orders",
            "Pairs,Time,Type,Direction,Average Filled Price,Order Price,Filled Quantity,Order Quantity,Order Amount,Status",
            EvidenceRole.SUPPORTING,
        )
    val CONVERT =
        report("convert", "Convert history", "UID,Date(UTC+04:00),Trading Pair,Type,Sell,Received,Price,Completion Time(UTC+04:00),Status")
    val DEPOSITS = report("deposits", "Deposit history", "UID,Status,Time,Crypto,Network,Deposit Amount,TxID,Progress")
    val WITHDRAWALS =
        report(
            "withdrawals",
            "Withdrawal history",
            "UID,Status,Time,Crypto,Network,Request Amount,Withdrawal Address,memo,TxID,Trading Fee,Settlement Amount,Withdrawal Descriptions",
        )
    val LEGACY_WITHDRAWALS =
        report(
            "withdrawals.legacy",
            "Withdrawal history",
            "Status,Time,Crypto,Network,Request Amount,Withdrawal Address,TxID,Trading Fee,Settlement Amount,Withdrawal Descriptions",
        )
    val TRANSFERS =
        report(
            "transfers",
            "Account transfers",
            "UID,From System,To System,Currency,Amount,Status,update_time(UTC+04:00),create_time(UTC+04:00),Transfer Type",
        )
    val OTHER = report("other", "Other funding history", "UID,Time,Crypto,Type,Quantity,Status,Remark")
    val STATEMENT = report("statement", "Balance statement", "UID,Creation Time(UTC+04:00),Crypto,Transaction Type,Direction,Quantity")
    val SNAPSHOTS = report("snapshots", "Asset snapshots", "UID,Date(UTC+8),Account,Crypto,Total,USDT Valuation", EvidenceRole.CHECKPOINT)

    private val derivatives =
        listOf(
            "capital" to "UID,Time(UTC+04:00),Futures Trading Pair,Crypto,Fund Type,Fund Flow Type,Amount",
            "orders" to
                "UID,Time(UTC+04:00),Futures Trading Pair,Direction,Leverage,Order Type,Order Qty (Cont.),Filled Qty (Cont.),Order Qty (Crypto),Filled Qty (Crypto),Order Qty (Amount),Filled Qty (Amount),Order Price,Average Filled Price,Closing PNL,Trading Fee,Fee-payment Crypto,Status",
            "trades" to
                "UID,Time(UTC+04:00),Futures Trading Pair,Direction,Order Type,Filled Qty (Cont.),Filled Qty (Crypto),Filled Qty (Amount),Filled Price,Trading Fee,Fee-payment Crypto,Role,Closing PNL",
            "positions.utc3" to
                "UID,Futures,Open Time(UTC+03:00),Close Time,Margin Mode,Avg Entry Price,Avg Close Price,Direction,Closing Qty (Cont.),Fee,Realized PNL,Status",
            "positions.utc4" to
                "UID,Futures,Open Time(UTC+04:00),Close Time,Margin Mode,Avg Entry Price,Avg Close Price,Direction,Closing Qty (Cont.),Fee,Realized PNL,Status",
            "events" to
                "UID,Futures,Quantity,Payout,Settlement Amount,Entry Price,Close Price,PNL,Opening Time(UTC+04:00),Closing Time(UTC+04:00)",
            "grid" to
                "UID,Trading Pair,Leverage,Running Mode,Direction,Activation Time,Stop Time,Price Range,No. of Grids,Investment Amount,Total PNL,PNL (%),Grid Profit Count,Status",
            "copy" to
                "UID,copy_trader_uid,copy_state,futures,used_margin,leverage,open_type,vol(Cont),copy_amount(USDT),deal_avg_price,close_avg_price,Position Profit/Loss(USDT),fee,create_time(UTC+04:00),close_time(UTC+04:00)",
        ).map { (id, header) -> report("futures.$id", "Futures $id", header, EvidenceRole.DEFERRED, ProductFamily.FUTURES) }

    override val reports =
        listOf(TRADES, ORDERS, LEGACY_ORDERS, CONVERT, DEPOSITS, WITHDRAWALS, LEGACY_WITHDRAWALS, TRANSFERS, OTHER, STATEMENT, SNAPSHOTS) +
            derivatives

    private fun text(
        table: DetectedTable,
        row: ImportRow,
        column: String,
    ): String {
        val cell = row.cells.getOrNull(table.column(column)) ?: return ""
        if (cell.formula) {
            throw ImportFileException(
                "Row ${row.number} of ${table.table.label} contains a calculated value; export it again",
            )
        }
        return cell.text.trim()
    }

    private fun time(
        table: DetectedTable,
        row: ImportRow,
        offsets: Map<String, ImportOffset>,
        column: String,
    ): Long {
        text(table, row, column)
        val cell = row.cells.getOrNull(table.column(column)) ?: throw ImportFileException("Missing time in row ${row.number}")
        return ImportTime.millis(
            cell,
            offsets[column] ?: throw ImportFileException("Choose the time zone of ${table.table.label}"),
            table.table.date1904,
        )
    }

    private fun number(
        table: DetectedTable,
        row: ImportRow,
        column: String,
    ) = ImportValues.decimal(text(table, row, column), "row ${row.number} of ${table.table.label}")

    private fun neg(value: ExactDecimal) = ExactDecimal(-value.coefficient, value.scale)

    private fun abs(value: ExactDecimal) = if (value.coefficient.sign < 0) neg(value) else value

    private fun status(value: String) =
        when (value.lowercase()) {
            "success", "successful", "complete", "completed", "finished", "credited successfully", "withdrawal successful", "filled" ->
                RecordStatus.COMPLETED
            "cancelled", "canceled" -> RecordStatus.CANCELLED
            "failed", "failure", "rejected" -> RecordStatus.FAILED
            "pending", "processing", "confirming", "submitted", "under review", "partially filled", "new" -> RecordStatus.PENDING
            else -> RecordStatus.UNKNOWN
        }

    private fun record(
        table: DetectedTable,
        row: ImportRow,
        kind: RecordKind,
        at: Long,
        legs: List<RecordLeg> = emptyList(),
        status: RecordStatus = RecordStatus.COMPLETED,
        details: Map<String, String> = emptyMap(),
        role: EvidenceRole = table.report.role,
        family: ProductFamily = table.report.family,
    ): ImportedRecord =
        ImportedRecord(
            exchange = this.kind,
            report = table.report.id,
            family = family,
            role = role,
            kind = kind,
            status = status,
            atMillis = at,
            legs = legs,
            uid =
                if ("UID" in
                    table.report.header
                ) {
                    text(table, row, "UID").ifEmpty { null }
                } else {
                    table.uids().singleOrNull()
                },
            wallet = "Spot",
            details = details,
            source = RecordSource(table.table.file, table.table.fileHash, table.table.member, table.table.sheet, row.number),
            cells =
                row.cells.map {
                    it.text
                },
        )

    private fun pair(value: String): Pair<String, String> {
        val split = value.split('/', '_', '-').filter { it.isNotBlank() }
        if (split.size == 2) return split[0] to split[1]
        val quote =
            listOf("USDT", "USDC", "BTC", "ETH", "TUSD", "EUR", "MX").firstOrNull { value.endsWith(it) && value.length > it.length }
                ?: throw ImportFileException("A trading pair does not identify its two coins")
        return value.dropLast(quote.length) to quote
    }

    private fun amount(
        table: DetectedTable,
        row: ImportRow,
        column: String,
        coin: String,
    ): ExactDecimal {
        val value = text(table, row, column)
        return if (value.endsWith(
                coin,
            )
        ) {
            ImportValues.amountAndCoin(value, listOf(coin), "row ${row.number}").first
        } else {
            number(table, row, column)
        }
    }

    override fun interpret(
        table: DetectedTable,
        offsets: Map<String, ImportOffset>,
    ): List<ImportedRecord> {
        if (table.report.role == EvidenceRole.DEFERRED) return table.rows.map { record(table, it, RecordKind.DERIVATIVE, 0) }
        if (table.report.id == STATEMENT.id) return statement(table, offsets)
        return table.rows.map { row ->
            when (table.report.id) {
                TRADES.id -> {
                    val (base, quote) = pair(text(table, row, "Pairs"))
                    val buy =
                        when (text(table, row, "Side").lowercase()) {
                            "buy" -> true
                            "sell" -> false
                            else -> throw ImportFileException("Unknown fill side in row ${row.number}")
                        }
                    val qty = abs(amount(table, row, "Executed Amount", base))
                    val total = abs(amount(table, row, "Total", quote))
                    val fee =
                        text(table, row, "Fee").takeUnless { it in setOf("", "--", "-") }?.let {
                            runCatching { ImportValues.amountAndCoin(it, listOf(base, quote, "MX"), "row ${row.number}") }.getOrElse { _ ->
                                val zero = ImportValues.decimal(it, "row ${row.number}")
                                if (zero.coefficient.sign !=
                                    0
                                ) {
                                    throw ImportFileException("The fill fee needs its coin in row ${row.number}")
                                }
                                zero to base
                            }
                        }
                    record(
                        table,
                        row,
                        RecordKind.TRADE,
                        time(table, row, offsets, "Time"),
                        listOf(
                            RecordLeg(base, if (buy) qty else neg(qty), MovementRole.TRADE_LEG),
                            RecordLeg(quote, if (buy) neg(total) else total, MovementRole.TRADE_LEG),
                        ) +
                            listOfNotNull(
                                fee?.takeIf { it.first.coefficient.sign != 0 }?.let {
                                    RecordLeg(
                                        it.second,
                                        neg(it.first),
                                        MovementRole.FEE,
                                    )
                                },
                            ),
                    )
                }
                ORDERS.id, LEGACY_ORDERS.id ->
                    record(
                        table,
                        row,
                        RecordKind.ORDER,
                        time(table, row, offsets, "Time"),
                        status = status(text(table, row, "Status")),
                    )
                CONVERT.id -> {
                    val (base, quote) = pair(text(table, row, "Trading Pair"))
                    val sold = ImportValues.amountAndCoin(text(table, row, "Sell"), listOf(base, quote), "row ${row.number}")
                    val got = ImportValues.amountAndCoin(text(table, row, "Received"), listOf(base, quote), "row ${row.number}")
                    val column =
                        if (text(
                                table,
                                row,
                                "Completion Time(UTC+04:00)",
                            ).isBlank()
                        ) {
                            "Date(UTC+04:00)"
                        } else {
                            "Completion Time(UTC+04:00)"
                        }
                    record(
                        table,
                        row,
                        RecordKind.CONVERT,
                        time(table, row, offsets, column),
                        listOf(
                            RecordLeg(sold.second, neg(abs(sold.first)), MovementRole.TRADE_LEG),
                            RecordLeg(got.second, abs(got.first), MovementRole.TRADE_LEG),
                        ),
                        status(text(table, row, "Status")),
                    )
                }
                DEPOSITS.id, WITHDRAWALS.id, LEGACY_WITHDRAWALS.id -> {
                    val deposit = table.report.id == DEPOSITS.id
                    val coin = text(table, row, "Crypto")
                    val quantity = abs(number(table, row, if (deposit) "Deposit Amount" else "Settlement Amount"))
                    val fee = if (deposit) null else ImportValues.optionalDecimal(text(table, row, "Trading Fee"), "row ${row.number}")
                    record(
                        table,
                        row,
                        if (deposit) RecordKind.DEPOSIT else RecordKind.WITHDRAWAL,
                        time(table, row, offsets, "Time"),
                        listOf(RecordLeg(coin, if (deposit) quantity else neg(quantity), MovementRole.TRANSFER)) +
                            listOfNotNull(
                                fee?.takeIf { it.coefficient.sign != 0 }?.let { RecordLeg(coin, neg(abs(it)), MovementRole.FEE) },
                            ),
                        status(text(table, row, "Status")),
                        mapOf(
                            "txid" to text(table, row, "TxID"),
                            "network" to text(table, row, "Network"),
                        ),
                    )
                }
                TRANSFERS.id -> {
                    val from = text(table, row, "From System").uppercase()
                    val to = text(table, row, "To System").uppercase()
                    val quantity = abs(number(table, row, "Amount"))
                    val kind =
                        when {
                            from == "SPOT" && to == "FUTURES" -> RecordKind.FUTURES_IN
                            from == "FUTURES" && to == "SPOT" -> RecordKind.FUTURES_OUT
                            from ==
                                to -> RecordKind.INTERNAL_TRANSFER
                            else -> RecordKind.UNKNOWN
                        }
                    record(
                        table,
                        row,
                        kind,
                        time(table, row, offsets, "update_time(UTC+04:00)"),
                        listOf(
                            RecordLeg(
                                text(table, row, "Currency"),
                                if (kind ==
                                    RecordKind.FUTURES_IN
                                ) {
                                    neg(quantity)
                                } else {
                                    quantity
                                },
                                MovementRole.TRANSFER,
                            ),
                        ),
                        status(text(table, row, "Status")),
                    )
                }
                OTHER.id -> {
                    val type = text(table, row, "Type")
                    val kind = if (type.lowercase() in setOf("airdrop", "reward", "cashback")) RecordKind.REWARD else RecordKind.UNKNOWN
                    record(
                        table,
                        row,
                        kind,
                        time(table, row, offsets, "Time"),
                        listOf(RecordLeg(text(table, row, "Crypto"), number(table, row, "Quantity"), MovementRole.REWARD)),
                        status(text(table, row, "Status")),
                        mapOf(
                            "operation" to type,
                        ),
                    )
                }
                SNAPSHOTS.id ->
                    record(
                        table,
                        row,
                        RecordKind.SNAPSHOT,
                        time(table, row, offsets, "Date(UTC+8)"),
                        listOf(RecordLeg(text(table, row, "Crypto"), number(table, row, "Total"), MovementRole.TRANSFER)),
                        details =
                            mapOf(
                                "account" to text(table, row, "Account"),
                            ),
                    )
                else -> throw ImportFileException("Unsupported MEXC report")
            }
        }
    }

    private fun statement(
        table: DetectedTable,
        offsets: Map<String, ImportOffset>,
    ): List<ImportedRecord> {
        val futures = listOfNotNull(table.table.file, table.table.member, table.table.sheet).any { it.contains("futures", true) }
        val records =
            table.rows.map { row ->
                val operation = text(table, row, "Transaction Type")
                val direction = text(table, row, "Direction").lowercase()
                val quantity = abs(number(table, row, "Quantity"))
                val change =
                    when (direction) {
                        "inflow", "in", "income" -> quantity
                        "outflow", "out", "expense" -> neg(quantity)
                        else -> throw ImportFileException("Unknown statement direction in row ${row.number}")
                    }
                val kind =
                    when (operation.lowercase()) {
                        "spot trading", "spot trading fees" -> RecordKind.TRADE
                        "small asset mx conversion" -> RecordKind.CONVERT
                        "deposit" -> RecordKind.DEPOSIT
                        "withdraw", "withdrawal", "withdrawal fees", "funds sent" -> RecordKind.WITHDRAWAL
                        "from futures account" -> RecordKind.FUTURES_OUT
                        "to futures account" -> RecordKind.FUTURES_IN
                        "airdrop", "locked savings airdrop" -> RecordKind.REWARD
                        else -> RecordKind.UNKNOWN
                    }
                val derivative =
                    futures ||
                        operation.lowercase() in
                        setOf(
                            "closing pnl",
                            "funding fee",
                            "liquidation clearance",
                            "transfer from spot account",
                            "transfer to spot account",
                            "trading fee",
                        )
                val fee = operation.lowercase() in setOf("spot trading fees", "withdrawal fees")
                val role =
                    when {
                        fee -> MovementRole.FEE
                        kind in setOf(RecordKind.TRADE, RecordKind.CONVERT) -> MovementRole.TRADE_LEG
                        kind ==
                            RecordKind.REWARD -> MovementRole.REWARD
                        else -> MovementRole.TRANSFER
                    }
                record(
                    table,
                    row,
                    if (derivative) RecordKind.DERIVATIVE else kind,
                    time(table, row, offsets, "Creation Time(UTC+04:00)"),
                    listOf(RecordLeg(text(table, row, "Crypto"), change, role)),
                    details =
                        mapOf(
                            "operation" to operation,
                            "statement" to "1",
                        ),
                    role = if (derivative) EvidenceRole.DEFERRED else EvidenceRole.PRIMARY,
                    family = if (derivative) ProductFamily.FUTURES else ProductFamily.SPOT,
                )
            }
        return records
            .groupBy {
                if (it.kind in
                    setOf(RecordKind.TRADE, RecordKind.CONVERT, RecordKind.WITHDRAWAL)
                ) {
                    "${it.atMillis}:${it.kind}:${if (it.kind == RecordKind.WITHDRAWAL) it.legs.first().coin else ""}"
                } else {
                    "row:${it.source.row}"
                }
            }.values
            .map { group ->
                // Legs in a fixed order, so where the rows sit in the file does not change the record.
                group.first().copy(
                    legs =
                        group
                            .flatMap { it.legs }
                            .groupBy { it.coin to it.role }
                            .toList()
                            .sortedBy { (key, _) ->
                                "${key.first}|${key.second}"
                            }.map { (_, legs) ->
                                legs.first().copy(
                                    amount =
                                        legs.map { it.amount }.reduce { a, b ->
                                            a +
                                                b
                                        },
                                )
                            },
                    // The operations of all the rows, not the first one's, so the record does not depend on row order.
                    details =
                        group.first().details +
                            (
                                "operation" to
                                    group
                                        .mapNotNull { it.details["operation"] }
                                        .distinct()
                                        .sorted()
                                        .joinToString("+")
                            ) +
                            ("rows" to group.joinToString(",") { it.source.row.toString() }),
                )
            }
    }
}
