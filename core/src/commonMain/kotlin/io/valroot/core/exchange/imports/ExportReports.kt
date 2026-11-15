package io.valroot.core.exchange.imports

import io.valroot.core.exchange.ExchangeKind

/** What part of an exchange account a report covers. */
enum class ProductFamily { SPOT, FUNDING, FUTURES, CARD }

/** How a report's rows count: they create events, support them, check balances, or wait for futures accounting. */
enum class EvidenceRole { PRIMARY, SUPPORTING, CHECKPOINT, DEFERRED }

/**
 * An exchange report format, identified by its exact [header] cells (in order; duplicate names keep their positions).
 * [timeColumns] are the header names of columns holding times.
 */
data class ExportReport(
    val id: String,
    val exchange: ExchangeKind,
    val label: String,
    val family: ProductFamily,
    val role: EvidenceRole,
    val header: List<String>,
    val timeColumns: Set<String> = emptySet(),
    val version: Int = 1,
)

/** A table recognised as [report]: its metadata lines, the header row and the data rows that follow it. */
class DetectedTable(
    val table: ImportTable,
    val report: ExportReport,
    /** `key: value` lines before the header, such as Bybit's `UID: 123`, by lowercased key. */
    val metadata: Map<String, String>,
    val headerRow: Int,
    val rows: List<ImportRow>,
) {
    private val positions: Map<String, List<Int>> = report.header.withIndex().groupBy({ it.value }, { it.index })

    /** The column index of the [occurrence]-th (0-based) column named [name]. */
    fun column(
        name: String,
        occurrence: Int = 0,
    ): Int = positions[name]?.getOrNull(occurrence) ?: error("${report.id} has no column $name")

    /** The account's user ID the report states: a metadata `UID` line or a UID column, when every row agrees. */
    fun uids(): Set<String> {
        val column = report.header.indexOfFirst { it.equals("uid", ignoreCase = true) || it == "User ID" }
        val fromRows = if (column >= 0) rows.map { it.text(column).trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()
        return fromRows.ifEmpty { setOfNotNull(metadata["uid"]?.takeIf { it.isNotBlank() }) }
    }

    /**
     * How each time column's times are read: the column's own offset, else the file name's, else [chosen] (the user's
     * choice in the preview); null when none of them states one.
     */
    fun offsets(chosen: ImportOffset?): Map<String, ImportOffset?> =
        report.timeColumns.associateWith { name ->
            ImportTime.columnOffset(name) ?: ImportTime.nameOffset(table.file) ?: table.member?.let(ImportTime::nameOffset) ?: chosen
        }

    /** Time columns whose offset the file does not state, so the user must choose one. */
    fun unstatedOffsets(): Set<String> = offsets(null).filterValues { it == null }.keys
}

/** Every supported export report, and detection by header. */
object ExportReports {
    /** The reports of every exchange's export adapter. */
    val all: List<ExportReport>
        get() =
            io.valroot.core.exchange.ExchangeProviders.all
                .mapNotNull { it.exports }
                .flatMap { it.reports }

    /** How many leading rows may precede the header (metadata preambles). */
    private const val PREAMBLE_ROWS = 5

    /**
     * The report [table] holds, found by its header row among the first rows; null when no report has that header. The
     * file name is never used to decide the format.
     */
    fun detect(
        table: ImportTable,
        candidates: List<ExportReport> = all,
    ): DetectedTable? {
        table.rows.take(PREAMBLE_ROWS + 1).forEachIndexed { index, row ->
            val cells = row.cells.map { it.text.trim().removePrefix("\uFEFF") }.dropLastWhile { it.isEmpty() }
            val accountColumn = cells.indexOf("Account ID")
            val header = if (accountColumn >= 0 && accountColumn == cells.size - 2) cells.take(accountColumn) else cells
            val report = candidates.firstOrNull { it.header == header } ?: return@forEachIndexed
            val metadata =
                table.rows
                    .take(index)
                    .flatMap { it.cells }
                    .mapNotNull { cell ->
                        cell.text.split(':', limit = 2).takeIf { it.size == 2 }?.let { (key, value) ->
                            key.trim().lowercase() to
                                value.trim()
                        }
                    }.toMap() +
                    if (accountColumn >= 0 && accountColumn == cells.size - 2) mapOf("uid" to cells.last()) else emptyMap()
            return DetectedTable(table, report, metadata, row.number, table.rows.drop(index + 1))
        }
        return null
    }
}
