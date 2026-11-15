package io.valroot.core.exchange.imports

import io.valroot.core.exchange.ExchangeKind
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.ExactDecimal

/** A record's state as the exchange reports it; only completed records move coins. */
enum class RecordStatus { COMPLETED, PENDING, CANCELLED, FAILED, UNKNOWN }

/** What an imported record is, independent of the report it came from. */
enum class RecordKind {
    TRADE,
    CONVERT,
    DEPOSIT,
    WITHDRAWAL,

    /** Between the account's own supported wallets (spot, funding); moves nothing in or out. */
    INTERNAL_TRANSFER,

    /** Into futures from a supported wallet; the coins leave the supported balance. */
    FUTURES_IN,

    /** Back from futures into a supported wallet. */
    FUTURES_OUT,
    REWARD,
    FEE,
    CARD_PAYMENT,
    CARD_REFUND,

    /** A payment out of the account other than a withdrawal, such as a P2P sale. */
    PAYMENT,
    OTHER,

    /** An order summary: supporting evidence, never a trade. */
    ORDER,

    /** A balance at a moment: a reconciliation checkpoint, never a deposit. */
    SNAPSHOT,

    /** A futures or derivative row kept for later futures accounting. */
    DERIVATIVE,

    /** A row whose operation Valroot does not know; shown as unresolved. */
    UNKNOWN,
}

/** One coin moving in a record: a signed exact amount and its role. */
data class RecordLeg(
    val coin: String,
    val amount: ExactDecimal,
    val role: MovementRole,
)

/** Where a record came from: the selected file, its hash, archive member, sheet and row. */
data class RecordSource(
    val file: String,
    val fileHash: String,
    val member: String?,
    val sheet: String?,
    val row: Int,
)

/**
 * One row (or a row's part) of an export, read into an exchange-neutral record. [nativeId] is the exchange's own
 * identifier within [namespace] (for example a withdrawal ID), when the report has one; [wallet] names the exchange
 * wallet the movement happened in. [cells] keep the row exactly as written.
 */
data class ImportedRecord(
    val exchange: ExchangeKind,
    val report: String,
    val family: ProductFamily,
    val role: EvidenceRole,
    val kind: RecordKind,
    val status: RecordStatus,
    val atMillis: Long,
    val legs: List<RecordLeg>,
    val uid: String?,
    val wallet: String? = null,
    val namespace: String? = null,
    val nativeId: String? = null,
    val details: Map<String, String> = emptyMap(),
    val source: RecordSource,
    val cells: List<String>,
) {
    /** Whether the record moves coins of the supported wallets once imported. */
    val moves: Boolean
        get() = role == EvidenceRole.PRIMARY && status == RecordStatus.COMPLETED && kind !in NON_MOVING && legs.isNotEmpty()

    private companion object {
        val NON_MOVING =
            setOf(RecordKind.ORDER, RecordKind.SNAPSHOT, RecordKind.DERIVATIVE, RecordKind.UNKNOWN, RecordKind.INTERNAL_TRANSFER)
    }
}

/**
 * An exchange's export formats: the [reports] it recognises and how a detected table's rows become records.
 * [offsets] gives each time column's offset, resolved by [DetectedTable.offsets].
 */
interface ExchangeExportAdapter {
    val kind: ExchangeKind
    val reports: List<ExportReport>

    fun interpret(
        table: DetectedTable,
        offsets: Map<String, ImportOffset>,
    ): List<ImportedRecord>
}
