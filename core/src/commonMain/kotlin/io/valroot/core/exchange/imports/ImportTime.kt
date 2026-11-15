package io.valroot.core.exchange.imports

import io.valroot.core.application.CsvImport
import kotlin.math.roundToLong

/** A fixed offset from UTC, as exports state it (`UTC+4`, `UTC+04:00`, `UTC`). */
data class ImportOffset(
    val minutes: Int,
) {
    init {
        require(minutes in -14 * 60..14 * 60) { "Not a time zone offset" }
    }

    val label: String
        get() =
            if (minutes == 0) {
                "UTC"
            } else {
                val sign = if (minutes < 0) "-" else "+"
                val whole = kotlin.math.abs(minutes)
                "UTC$sign${whole / 60}" + if (whole % 60 != 0) ":${(whole % 60).toString().padStart(2, '0')}" else ""
            }

    companion object {
        val UTC = ImportOffset(0)
    }
}

/**
 * Time zones and timestamps of export cells. A column's own offset comes first (`Time(UTC)`, `Date(UTC+8)`,
 * `Creation Time(UTC+04:00)`), then the report's file name or metadata (`…(UTC+4)…`), then the user's choice; the
 * device's time zone is never used.
 */
object ImportTime {
    private val OFFSET = Regex("""UTC\s*(?:([+-])\s*(\d{1,2})(?::?(\d{2}))?)?""", RegexOption.IGNORE_CASE)
    private val BRACKETED = Regex("""\(\s*(UTC\s*(?:[+-]\s*\d{1,2}(?::?\d{2})?)?)\s*\)""", RegexOption.IGNORE_CASE)

    /** The offset a column header states, such as `Time(UTC+04:00)`; null when it states none. */
    fun columnOffset(header: String): ImportOffset? =
        BRACKETED
            .find(header)
            ?.groupValues
            ?.get(1)
            ?.let(::parse)

    /** The offset a file name states, such as `…History-202610201950(UTC+4)_….zip`; null when it states none. */
    fun nameOffset(name: String): ImportOffset? =
        BRACKETED
            .find(name)
            ?.groupValues
            ?.get(1)
            ?.let(::parse)

    private fun parse(text: String): ImportOffset? {
        val match = OFFSET.matchEntire(text.trim()) ?: return null
        val (sign, hours, minutes) = match.destructured
        if (sign.isEmpty()) return ImportOffset.UTC
        val total = hours.toInt() * 60 + (minutes.toIntOrNull() ?: 0)
        return ImportOffset(if (sign == "-") -total else total).takeIf { total <= 14 * 60 }
    }

    /**
     * Epoch milliseconds of a time cell read at [offset]. Text such as `2024-03-02 01:09:57` or `2024-03-02T01:09:57Z` is
     * a wall-clock time unless it states its own offset; an Excel serial date counts days from 1899-12-30, or from
     * 1904-01-01 in a [date1904] workbook.
     */
    fun millis(
        cell: ImportCell,
        offset: ImportOffset,
        date1904: Boolean = false,
    ): Long {
        val shift = offset.minutes * 60_000L
        if (cell.serialDate) {
            val days = cell.text.trim().toDoubleOrNull() ?: throw ImportFileException("\"${cell.text}\" is not a date")
            val epoch = if (date1904) EPOCH_1904 else EPOCH_1900
            return epoch + (days * DAY_MILLIS).roundToLong() - shift
        }
        return try {
            val raw = cell.text.trim()
            val date =
                if (raw.matches(Regex("[0-9]{8}"))) {
                    "${raw.take(4)}-${raw.substring(4, 6)}-${raw.takeLast(2)}"
                } else {
                    raw
                }
            CsvImport.date(date) { CsvImport.utcMillis(it) - shift }
        } catch (failure: IllegalArgumentException) {
            throw ImportFileException(failure.message ?: "\"${cell.text}\" is not a date")
        }
    }

    private const val DAY_MILLIS = 86_400_000.0

    /** 1899-12-30, Excel's day zero; it absorbs Excel's nonexistent 1900-02-29 for dates after it. */
    private const val EPOCH_1900 = -2_209_161_600_000L

    /** 1904-01-01. */
    private const val EPOCH_1904 = -2_082_844_800_000L
}
