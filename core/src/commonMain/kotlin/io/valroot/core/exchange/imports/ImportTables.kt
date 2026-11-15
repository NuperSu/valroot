package io.valroot.core.exchange.imports

/**
 * One cell of an export as written: its exact text. A [formula] cell's text is the value the spreadsheet last saved,
 * which Valroot never recalculates, so a report that needs the cell rejects it. A [serialDate] cell holds an Excel day
 * number ([ImportTable.date1904] tells which day zero), not text.
 */
data class ImportCell(
    val text: String,
    val formula: Boolean = false,
    val serialDate: Boolean = false,
)

/** A row of a table with its position in the file (1-based line or sheet row), kept for provenance. */
data class ImportRow(
    val number: Int,
    val cells: List<ImportCell>,
) {
    /** The text of the cell at [index], empty past the end of the row. */
    fun text(index: Int): String = cells.getOrNull(index)?.text.orEmpty()
}

/**
 * One table of a selected export file: a CSV file or archive member, or one sheet of a workbook. [file] is the name the
 * user chose and [fileHash] the SHA-256 of its bytes; [member] is the path inside an archive and [sheet] the sheet name.
 */
data class ImportTable(
    val file: String,
    val fileHash: String,
    val member: String?,
    val sheet: String?,
    val rows: List<ImportRow>,
    val date1904: Boolean = false,
) {
    /** Where the table came from, as shown to the user: `file › member › sheet`. */
    val label: String get() = listOfNotNull(file, member, sheet).joinToString(" › ")
}

/** An export that cannot be read, with a message saying what to do. */
class ImportFileException(
    message: String,
) : IllegalArgumentException(message)

/** Bounds on what one import batch reads, so a malformed or hostile file cannot exhaust the device. */
data class ImportLimits(
    val maxFileBytes: Long = 64L * 1024 * 1024,
    val maxExpandedBytes: Long = 256L * 1024 * 1024,
    val maxArchiveEntries: Int = 512,
    val maxRows: Int = 200_000,
)

/** What a batch has read so far against its [limits]; shared by every file of the batch. */
class ImportBudget(
    val limits: ImportLimits = ImportLimits(),
) {
    var expandedBytes: Long = 0
        private set
    var archiveEntries: Int = 0
        private set
    var rows: Int = 0
        private set

    fun expand(bytes: Long) {
        expandedBytes += bytes
        val limit = limits.maxExpandedBytes
        if (expandedBytes > limit) {
            throw ImportFileException("The selected files expand to more than ${limit / MIB} MB; import fewer files at once")
        }
    }

    fun entry() {
        archiveEntries++
        val limit = limits.maxArchiveEntries
        if (archiveEntries > limit) throw ImportFileException("The archives hold more than $limit files; import fewer at once")
    }

    fun row() {
        rows++
        if (rows >
            limits.maxRows
        ) {
            throw ImportFileException("The selected files have more than ${limits.maxRows} rows; import fewer at once")
        }
    }

    private companion object {
        const val MIB = 1024 * 1024
    }
}

/**
 * Reads CSV text into rows with their line numbers. The delimiter is the one of `,` `;` and tab occurring most in the
 * first line outside quotes; quoted cells may hold delimiters, quotes (doubled) and line breaks. Blank lines are skipped.
 */
object ExportCsv {
    fun read(
        text: String,
        budget: ImportBudget,
    ): List<ImportRow> {
        val body = text.removePrefix("\uFEFF")
        val delimiter = delimiter(body.substringBefore('\n'))
        val rows = mutableListOf<ImportRow>()
        var cells = mutableListOf<ImportCell>()
        val cell = StringBuilder()
        var inQuotes = false
        var line = 1
        var rowStart = 1
        var i = 0

        fun endRow() {
            cells += ImportCell(cell.toString())
            cell.clear()
            if (cells.any { it.text.isNotBlank() }) {
                budget.row()
                rows += ImportRow(rowStart, cells)
            }
            cells = mutableListOf()
        }
        while (i < body.length) {
            val c = body[i]
            if (inQuotes) {
                when {
                    c == '"' && body.getOrNull(i + 1) == '"' -> {
                        cell.append('"')
                        i++
                    }
                    c == '"' -> inQuotes = false
                    else -> {
                        if (c == '\n') line++
                        cell.append(c)
                    }
                }
            } else {
                when (c) {
                    '"' -> if (cell.isEmpty()) inQuotes = true else cell.append(c)
                    delimiter -> {
                        cells += ImportCell(cell.toString())
                        cell.clear()
                    }
                    '\r' ->
                        if (body.getOrNull(i + 1) != '\n') {
                            endRow()
                            line++
                            rowStart = line
                        }
                    '\n' -> {
                        endRow()
                        line++
                        rowStart = line
                    }
                    else -> cell.append(c)
                }
            }
            i++
        }
        if (inQuotes) throw ImportFileException("A quoted cell is not closed (line $rowStart)")
        if (cell.isNotEmpty() || cells.isNotEmpty()) endRow()
        return rows
    }

    private fun delimiter(firstLine: String): Char {
        var quoted = false
        val counts = mutableMapOf(',' to 0, ';' to 0, '\t' to 0)
        firstLine.forEach { c ->
            if (c == '"') {
                quoted = !quoted
            } else if (!quoted && c in counts) {
                counts[c] = counts.getValue(c) + 1
            }
        }
        return counts.maxByOrNull { it.value }!!.takeIf { it.value > 0 }?.key ?: ','
    }
}
