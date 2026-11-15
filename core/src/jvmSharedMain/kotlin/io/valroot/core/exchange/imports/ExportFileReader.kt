package io.valroot.core.exchange.imports

import io.valroot.core.storage.sha256Hex
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Reads selected export files into tables of exact cell text: CSV files, XLSX workbooks (one table per sheet), and ZIP
 * archives of them. Everything a batch reads counts against one [ImportBudget]. Nothing in a file is executed:
 * formulas, macros and external links are never evaluated.
 */
object ExportFileReader {
    /** Reads one selected file named [name] from [input], which must hold at most the budget's per-file size. */
    fun read(
        name: String,
        input: InputStream,
        budget: ImportBudget,
    ): List<ImportTable> {
        val bytes = readBounded(input, budget.limits.maxFileBytes) { "\"$name\" is larger than ${it / (1024 * 1024)} MB" }
        val hash = sha256Hex(bytes)
        return read(name, hash, null, bytes, budget, topLevel = true)
    }

    private fun read(
        name: String,
        hash: String,
        member: String?,
        bytes: ByteArray,
        budget: ImportBudget,
        topLevel: Boolean,
    ): List<ImportTable> {
        val label = member ?: name
        return when {
            bytes.startsWith(OLE_MAGIC) ->
                throw ImportFileException("\"$label\" is an old Excel .xls workbook; open it and save it as .xlsx or CSV")
            bytes.startsWith(ZIP_MAGIC) -> {
                val entries = unzip(label, bytes, budget)
                when {
                    "[Content_Types].xml" in entries && "xl/workbook.xml" in entries ->
                        XlsxReader.read(label, entries, budget).map { (sheet, rows, date1904) ->
                            ImportTable(name, hash, member, sheet, rows, date1904)
                        }
                    !topLevel -> throw ImportFileException("\"$label\" is an archive inside an archive; unpack it and select its files")
                    else ->
                        entries.entries.sortedBy { it.key }.flatMap { (path, content) ->
                            val lower = path.lowercase()
                            when {
                                lower.endsWith(".zip") || (content.startsWith(ZIP_MAGIC) && !lower.endsWith(".xlsx")) ->
                                    throw ImportFileException(
                                        "\"$path\" in \"$name\" is an archive inside an archive; unpack it and select its files",
                                    )
                                lower.endsWith(".csv") || lower.endsWith(".xlsx") || lower.endsWith(".xls") ->
                                    read(name, hash, path, content, budget, topLevel = false)
                                else -> emptyList()
                            }
                        }
                }
            }
            else -> listOf(ImportTable(name, hash, member, null, ExportCsv.read(text(label, bytes), budget)))
        }
    }

    /** Every file entry of a ZIP archive by path; directories are skipped and unsafe or encrypted entries rejected. */
    private fun unzip(
        label: String,
        bytes: ByteArray,
        budget: ImportBudget,
    ): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        try {
            ZipDirectory.entries(label, bytes).forEach { entry ->
                val path = entry.name
                if (unsafe(path)) throw ImportFileException("\"$label\" holds an unsafe path \"$path\"; it was not read")
                if (entry.directory) return@forEach
                budget.entry()
                if (path in entries) throw ImportFileException("\"$label\" holds \"$path\" twice")
                val remaining = budget.limits.maxExpandedBytes - budget.expandedBytes
                val content =
                    ZipDirectory.content(label, bytes, entry, remaining) {
                        "The selected files expand to more than ${budget.limits.maxExpandedBytes / (1024 * 1024)} MB; import fewer files at once"
                    }
                budget.expand(content.size.toLong())
                entries[path] = content
            }
        } catch (_: IndexOutOfBoundsException) {
            throw ImportFileException("\"$label\" is a damaged archive")
        }
        return entries
    }

    private fun unsafe(path: String): Boolean =
        path.startsWith("/") ||
            path.startsWith("\\") ||
            path.replace('\\', '/').split('/').any { it == ".." } ||
            (path.length > 1 && path[1] == ':')

    /** UTF-8 text, with or without a byte-order mark. */
    private fun text(
        label: String,
        bytes: ByteArray,
    ): String =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw ImportFileException("\"$label\" is not a UTF-8 CSV file")
        }

    /** Reads at most [limit] bytes; more fails with [message] of the limit. */
    internal fun readBounded(
        input: InputStream,
        limit: Long,
        message: (Long) -> String,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw ImportFileException(message(limit))
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val OLE_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())
}
