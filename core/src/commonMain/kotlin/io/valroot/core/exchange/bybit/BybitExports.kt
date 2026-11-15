package io.valroot.core.exchange.bybit

import io.valroot.core.exchange.ExchangeKind
import io.valroot.core.exchange.imports.DetectedTable
import io.valroot.core.exchange.imports.EvidenceRole
import io.valroot.core.exchange.imports.ExchangeExportAdapter
import io.valroot.core.exchange.imports.ExportReport
import io.valroot.core.exchange.imports.ImportFileException
import io.valroot.core.exchange.imports.ImportOffset
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

/** Bybit balance logs are primary; transaction histories enrich them without adding balance changes. */
object BybitExports : ExchangeExportAdapter {
    override val kind = ExchangeKind.BYBIT

    private fun report(
        id: String,
        family: ProductFamily,
        role: EvidenceRole,
        header: String,
    ): ExportReport {
        val columns = header.split(',')
        return ExportReport(
            "bybit.$id",
            kind,
            id.replace('.', ' '),
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
                        it.startsWith("Date") ||
                            it.startsWith("Time") ||
                            it == "Transaction Date & Time" ||
                            it == "Settlement Time"
                    }.toSet()
            },
        )
    }

    private const val FUND_HEADER = "Date & Time(UTC),Coin,QTY,Type,Account Balance,Description"
    private const val UTA_HEADER =
        "Currency,Contract,Type,Direction,Quantity,Position,Filled Price,Funding,Fee Paid," +
            "Cash Flow,Change,Wallet Balance,Action,Time(UTC)"
    private const val TRADES_HEADER =
        "Spot Pairs,Order Type,Direction,Filled Value,Filled Price,Filled Quantity," +
            "Fees,Transaction ID,Order No.,Timestamp (UTC+0)"
    val FUNDING = report("funding", ProductFamily.FUNDING, EvidenceRole.PRIMARY, "Uid,$FUND_HEADER")
    val FUNDING_LEGACY = report("funding.legacy", ProductFamily.FUNDING, EvidenceRole.PRIMARY, FUND_HEADER)
    val UNIFIED = report("unified", ProductFamily.SPOT, EvidenceRole.PRIMARY, "Uid,$UTA_HEADER")
    val UNIFIED_LEGACY = report("unified.legacy", ProductFamily.SPOT, EvidenceRole.PRIMARY, UTA_HEADER)
    val SPOT = report("spot", ProductFamily.SPOT, EvidenceRole.PRIMARY, "Type,Coin,Amount,Wallet Balance,Time(UTC)")
    val TRANSFERS =
        report(
            "transfers",
            ProductFamily.FUNDING,
            EvidenceRole.SUPPORTING,
            "Uid,Date,Type,Asset,Chain,Amount,Tx ID,Status,Received Address",
        )
    val TRADES = report("trades", ProductFamily.SPOT, EvidenceRole.SUPPORTING, "Uid,$TRADES_HEADER")
    val TRADES_LEGACY = report("trades.legacy", ProductFamily.SPOT, EvidenceRole.SUPPORTING, TRADES_HEADER)
    val CARD =
        report(
            "card",
            ProductFamily.CARD,
            EvidenceRole.SUPPORTING,
            "Uid,Last 4 Digits,Type,Order Type,Status,Transaction Amount,Total Fee,Currency,Merchant Name,Transaction Date & Time,Transaction ID,Payment ID,Category,Location,Settlement Time,Payment Amount,Payment Currency,Total Mark-up over ECB Rate,Points Earned",
        )
    override val reports =
        listOf(FUNDING, FUNDING_LEGACY, UNIFIED, UNIFIED_LEGACY, SPOT, TRANSFERS, TRADES, TRADES_LEGACY, CARD) +
            listOf(
                "Time,Currency,Contract,Type,Direction,Quantity,Position,Filled Price,Funding,Fee Paid,Cash Flow,Change,Wallet Balance,Fee Rate,Trade ID,Order ID",
                "Master Trader,Positions,Qty,Opened On,Entry Price,Closed On,Closing Price,Close By,Closed P&L,Fees,Order No.",
                "Contracts,Closing Direction,Qty,Entry Price,Exit Price,Closed P&L,Exit Type,Trade Time(UTC+0),Order No,Create Time,Symbol Type",
                "Contracts,Order No,Transaction Time(UTC+0),Leverage,Direction,Order Type,Filled Type,Filled Qty,Filled Price,Order Price,Trading Fee Rate,Fees Paid,Transaction ID,Symbol Type",
                "Contracts,Trade Type,Qty,Entry Price,Realized P&L,Filled Price,Exit Type,Filled/Settlement Time(UTC+0),Create Time",
                "contracts,Order No.,Direction,Order Type,Filled Qty,Filled Price,Order Price,Filled Type,Trading Fee Rate,Fees Paid,Transaction ID,Transaction Time(UTC+0)",
            ).mapIndexed { index, header -> report("derivatives.$index", ProductFamily.FUTURES, EvidenceRole.DEFERRED, header) }

    override fun interpret(
        table: DetectedTable,
        offsets: Map<String, ImportOffset>,
    ): List<ImportedRecord> =
        table.rows.map { row ->
            fun text(name: String): String {
                val cell = row.cells.getOrNull(table.column(name)) ?: throw ImportFileException("Missing cell in row ${row.number}")
                if (cell.formula) throw ImportFileException("Calculated cell in row ${row.number}; export it again")
                return cell.text.trim()
            }

            fun number(name: String) = ImportValues.decimal(text(name), "row ${row.number}")

            fun optional(name: String) = ImportValues.optionalDecimal(text(name), "row ${row.number}") ?: ZERO

            fun time(name: String): Long {
                val offset = offsets[name] ?: throw ImportFileException("Choose the time zone of ${table.table.label}")
                text(name)
                return ImportTime.millis(row.cells[table.column(name)], offset, table.table.date1904)
            }
            val uid = if ("Uid" in table.report.header) text("Uid").ifEmpty { null } else table.uids().singleOrNull()
            var role = table.report.role
            var family = table.report.family
            var kind = RecordKind.ORDER
            var status = RecordStatus.COMPLETED
            var at = 0L
            var wallet: String? = null
            var legs = emptyList<RecordLeg>()
            var details = emptyMap<String, String>()
            when (table.report.id) {
                FUNDING.id, FUNDING_LEGACY.id, SPOT.id -> {
                    val spot = table.report.id == SPOT.id
                    wallet = if (spot) "SPOT" else BybitDecoder.FUND
                    at = time(if (spot) "Time(UTC)" else "Date & Time(UTC)")
                    val operation = text("Type")
                    val description = if (spot) "" else text("Description")
                    val change = number(if (spot) "Amount" else "QTY")
                    val coin = text("Coin")
                    val incoming = change.coefficient.sign > 0
                    val words = "$operation $description".lowercase()
                    kind =
                        when {
                            spot && (operation == "trade" || operation == "tradingFee") -> RecordKind.TRADE
                            spot && "smallbalance" in operation.lowercase() -> RecordKind.CONVERT
                            "futures" in words || "derivatives" in words -> if (incoming) RecordKind.FUTURES_OUT else RecordKind.FUTURES_IN
                            spot && operation.startsWith("internalAccountTransfer") -> RecordKind.INTERNAL_TRANSFER
                            "transfer to spot account" in words ||
                                "transfer from spot account" in words ||
                                "unified trading account" in words -> RecordKind.INTERNAL_TRANSFER
                            "deposit" in words -> RecordKind.DEPOSIT
                            "withdraw" in words -> RecordKind.WITHDRAWAL
                            listOf(
                                "interest",
                                "yield",
                                "reward",
                                "airdrop",
                                "bonus",
                                "cashback",
                            ).any { it in words } &&
                                incoming -> RecordKind.REWARD
                            "card" in words -> if (incoming) RecordKind.CARD_REFUND else RecordKind.CARD_PAYMENT
                            spot && operation in setOf("Flexible Savings Principal Redemption", "transfer") -> RecordKind.OTHER
                            !spot &&
                                operation.lowercase() in
                                setOf(
                                    "earn",
                                    "transfer in",
                                    "transfer out",
                                    "fiat",
                                    "bot",
                                    "copy trading",
                                    "nft",
                                )
                            -> RecordKind.OTHER
                            else -> RecordKind.UNKNOWN
                        }
                    legs =
                        listOf(
                            RecordLeg(
                                coin,
                                change,
                                if (operation ==
                                    "tradingFee"
                                ) {
                                    MovementRole.FEE
                                } else if (kind in
                                    setOf(RecordKind.TRADE, RecordKind.CONVERT)
                                ) {
                                    MovementRole.TRADE_LEG
                                } else {
                                    MovementRole.TRANSFER
                                },
                            ),
                        )
                    details =
                        mapOf(
                            "balanceAfter" to number(if (spot) "Wallet Balance" else "Account Balance").toString(),
                            "operation" to operation,
                            "description" to description,
                        ) + if (kind == RecordKind.UNKNOWN) emptyMap() else mapOf("bybitWalletLog" to "1")
                }
                UNIFIED.id, UNIFIED_LEGACY.id -> {
                    wallet = BybitDecoder.UNIFIED
                    at = time("Time(UTC)")
                    val operation = text("Type")
                    val derivative = text("Action") !in setOf("--", "") || operation in setOf("SETTLEMENT", "LIQUIDATION", "FUNDING")
                    kind =
                        when {
                            derivative -> RecordKind.DERIVATIVE
                            operation == "TRADE" -> RecordKind.TRADE
                            operation in setOf("TRANSFER_IN", "TRANSFER_OUT") -> RecordKind.INTERNAL_TRANSFER
                            operation in setOf("AIRDROP", "BONUS", "FEE_REFUND", "INTEREST") -> RecordKind.REWARD
                            operation in
                                setOf(
                                    "CURRENCY_BUY",
                                    "CURRENCY_SELL",
                                    "CONVERT",
                                    "SPOT_REPAYMENT_BUY",
                                    "SPOT_REPAYMENT_SELL",
                                )
                            -> RecordKind.CONVERT
                            operation in
                                setOf(
                                    "BONUS_RECOLLECT",
                                    "BONUS_TRANSFER_IN",
                                    "BONUS_TRANSFER_OUT",
                                    "FLEXIBLE_STAKING_SUBSCRIPTION",
                                    "FLEXIBLE_STAKING_REDEMPTION",
                                ) -> RecordKind.OTHER
                            else -> RecordKind.UNKNOWN
                        }
                    if (derivative) {
                        role = EvidenceRole.DEFERRED
                        family = ProductFamily.FUTURES
                    }
                    val fee = optional("Fee Paid")
                    val change = number("Change")
                    val coin = text("Currency")
                    legs =
                        listOf(
                            RecordLeg(
                                coin,
                                change + fee,
                                if (kind ==
                                    RecordKind.TRADE
                                ) {
                                    MovementRole.TRADE_LEG
                                } else {
                                    MovementRole.TRANSFER
                                },
                            ),
                        ) +
                        listOfNotNull(fee.takeIf { it.coefficient.sign != 0 }?.let { RecordLeg(coin, negate(it), MovementRole.FEE) })
                    details =
                        mapOf(
                            "balanceAfter" to number("Wallet Balance").toString(),
                            "change" to change.toString(),
                            "fee" to fee.toString(),
                            "operation" to operation,
                            "contract" to text("Contract"),
                            "cashFlow" to optional("Cash Flow").toString(),
                            "funding" to optional("Funding").toString(),
                        ) +
                        if (derivative || kind == RecordKind.UNKNOWN) emptyMap() else mapOf("bybitWalletLog" to "1")
                }
                TRANSFERS.id -> {
                    at = time("Date")
                    kind =
                        when (text("Type").lowercase()) {
                            "deposit" -> RecordKind.DEPOSIT
                            "withdraw", "withdrawal" -> RecordKind.WITHDRAWAL
                            else -> RecordKind.UNKNOWN
                        }
                    status =
                        when (text("Status").lowercase()) {
                            "completed", "transferred successfully", "success" -> RecordStatus.COMPLETED
                            "failed", "rejected" -> RecordStatus.FAILED
                            "cancelled", "canceled" -> RecordStatus.CANCELLED
                            else -> RecordStatus.UNKNOWN
                        }
                    val amount = number("Amount")
                    legs =
                        listOf(RecordLeg(text("Asset"), if (kind == RecordKind.DEPOSIT) amount else negate(amount), MovementRole.TRANSFER))
                    details =
                        mapOf("txid" to text("Tx ID"), "network" to text("Chain")) +
                        if (kind == RecordKind.UNKNOWN) emptyMap() else mapOf("bybitTransferEvidence" to "1")
                }
                TRADES.id, TRADES_LEGACY.id -> {
                    at = time("Timestamp (UTC+0)")
                    details = mapOf("tradeId" to text("Transaction ID"))
                }
                CARD.id -> {
                    at = time("Transaction Date & Time")
                    details = mapOf("transactionId" to text("Transaction ID"))
                }
                else -> kind = RecordKind.DERIVATIVE
            }
            ImportedRecord(
                kind = kind,
                exchange = ExchangeKind.BYBIT,
                report = table.report.id,
                family = family,
                role = role,
                status = status,
                atMillis = at,
                legs = legs,
                uid = uid,
                wallet = wallet,
                details = details,
                source = RecordSource(table.table.file, table.table.fileHash, table.table.member, table.table.sheet, row.number),
                cells =
                    row.cells.map {
                        it.text
                    },
            )
        }

    private fun negate(value: ExactDecimal) = ExactDecimal(-value.coefficient, value.scale)

    private val ZERO = ExactDecimal.parse("0")
}
