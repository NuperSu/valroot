package io.valroot.core.exchange.imports

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExportFileReaderTest {
    private fun zip(vararg entries: Pair<String, ByteArray?>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                content?.let(zip::write)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A minimal workbook: shared strings, a styles part with a date style (index 1) and the given sheets. */
    private fun xlsx(
        sheets: List<Pair<String, String>>,
        shared: List<String> = emptyList(),
        date1904: Boolean = false,
    ): ByteArray {
        val workbook =
            """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """ +
                """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
                """<workbookPr date1904="${if (date1904) 1 else 0}"/><sheets>""" +
                sheets.indices.joinToString("") { """<sheet name="${sheets[it].first}" sheetId="${it + 1}" r:id="rId${it + 1}"/>""" } +
                "</sheets></workbook>"
        val rels =
            """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                sheets.indices.joinToString(
                    "",
                ) { """<Relationship Id="rId${it + 1}" Type="worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } +
                "</Relationships>"
        val strings =
            """<?xml version="1.0"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
                shared.joinToString("") { "<si>$it</si>" } + "</sst>"
        val styles =
            """<?xml version="1.0"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
                """<numFmts><numFmt numFmtId="164" formatCode="yyyy\-mm\-dd hh:mm:ss"/>""" +
                """<numFmt numFmtId="165" formatCode="&quot;d&quot;0.00"/></numFmts>""" +
                """<cellXfs><xf numFmtId="0"/><xf numFmtId="164"/><xf numFmtId="165"/><xf numFmtId="14"/></cellXfs></styleSheet>"""
        return zip(
            "[Content_Types].xml" to "<Types/>".toByteArray(),
            "xl/workbook.xml" to workbook.toByteArray(),
            "xl/_rels/workbook.xml.rels" to rels.toByteArray(),
            "xl/sharedStrings.xml" to strings.toByteArray(),
            "xl/styles.xml" to styles.toByteArray(),
            *sheets.indices
                .map { index ->
                    "xl/worksheets/sheet${index + 1}.xml" to
                        (
                            """<?xml version="1.0"?>""" +
                                """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""" +
                                sheets[index].second + "</sheetData></worksheet>"
                        ).toByteArray()
                }.toTypedArray(),
        )
    }

    private fun read(
        name: String,
        bytes: ByteArray,
        budget: ImportBudget = ImportBudget(),
    ) = ExportFileReader.read(name, bytes.inputStream(), budget)

    @Test fun csvKeepsLineNumbersPreamblesAndDuplicateColumns() {
        val text = "\uFEFFUID: 1,Company Name: \r\nTime,Pair,Time,Amount\r\n\r\n2024-01-01 00:00:00,BTCUSDT,2024-01-01 00:00:01,\"1,5\"\r\n"
        val table = read("export.csv", text.toByteArray()).single()
        assertEquals(listOf(1, 2, 4), table.rows.map { it.number })
        assertEquals(listOf("Time", "Pair", "Time", "Amount"), table.rows[1].cells.map { it.text })
        assertEquals("1,5", table.rows[2].text(3))
        assertEquals(null, table.member)
        assertEquals(64, table.fileHash.length)
    }

    @Test fun archivesYieldTheirCsvAndWorkbookMembers() {
        val book = xlsx(listOf("Sheet1" to """<row r="1"><c r="A1" t="inlineStr"><is><t>UID</t></is></c></row>"""))
        val archive =
            zip(
                "History/" to null,
                "History/b.csv" to "Coin,Amount\nBTC,1\n".toByteArray(),
                "History/a.xlsx" to book,
                "readme.txt" to "ignored".toByteArray(),
            )
        val tables = read("History.zip", archive)
        assertEquals(listOf("History/a.xlsx" to "Sheet1", "History/b.csv" to null), tables.map { it.member to it.sheet })
        assertEquals("History.zip › History/a.xlsx › Sheet1", tables.first().label)
    }

    @Test fun unsafeNestedEncryptedAndOversizedArchivesFailWithAReason() {
        assertEquals(
            "\"bad.zip\" holds an unsafe path \"../x.csv\"; it was not read",
            assertFailsWith<ImportFileException> { read("bad.zip", zip("../x.csv" to "a,b".toByteArray())) }.message,
        )
        assertFailsWith<ImportFileException> { read("abs.zip", zip("/etc/x.csv" to "a,b".toByteArray())) }
        assertTrue(
            assertFailsWith<ImportFileException> { read("outer.zip", zip("inner.zip" to zip("a.csv" to "a,b".toByteArray()))) }
                .message!!
                .contains("archive inside an archive"),
        )
        // The general-purpose flag's bit 0 marks an encrypted entry.
        val encrypted = zip("a.csv" to "a,b".toByteArray()).also { it[6] = (it[6].toInt() or 1).toByte() }
        assertTrue(assertFailsWith<ImportFileException> { read("locked.zip", encrypted) }.message!!.contains("password-protected"))
        val small = ImportLimits(maxFileBytes = 1024, maxExpandedBytes = 10_000, maxArchiveEntries = 2, maxRows = 3)
        assertTrue(
            assertFailsWith<ImportFileException> { read("big.csv", ByteArray(2000) { 'a'.code.toByte() }, ImportBudget(small)) }
                .message!!
                .contains("larger than"),
        )
        val bomb = zip("a.csv" to ByteArray(20_000) { 'a'.code.toByte() })
        assertTrue(assertFailsWith<ImportFileException> { read("bomb.zip", bomb, ImportBudget(small)) }.message!!.contains("expand"))
        val many = zip("a.csv" to "a".toByteArray(), "b.csv" to "b".toByteArray(), "c.csv" to "c".toByteArray())
        assertTrue(assertFailsWith<ImportFileException> { read("many.zip", many, ImportBudget(small)) }.message!!.contains("more than 2"))
        assertTrue(
            assertFailsWith<ImportFileException> { read("rows.csv", "a\nb\nc\nd\n".toByteArray(), ImportBudget(small)) }
                .message!!
                .contains("more than 3 rows"),
        )
    }

    @Test fun workbooksKeepExactTextAndMarkFormulasAndDates() {
        val book =
            xlsx(
                listOf(
                    "Fills" to
                        """<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="D1" t="s"><v>0</v></c></row>""" +
                        """<row r="3"><c r="A3" s="1"><v>45658.5</v></c><c r="B3"><v>0.10000000</v></c>""" +
                        """<c r="C3"><f>B3*2</f><v>0.2</v></c><c r="D3" s="2"><v>12</v></c></row>""" +
                        """<row r="4"><c r="A4" s="3"><v>1</v></c><c r="B4" t="inlineStr"><is><t>1.5e-05</t></is></c></row>""",
                    "Empty" to "",
                ),
                shared = listOf("<t>Time</t>", "<r><t>Am</t></r><r><t>ount</t></r><rPh><t>x</t></rPh>"),
            )
        val (fills, empty) = read("book.xlsx", book)
        assertEquals("Fills", fills.sheet)
        assertEquals("Empty", empty.sheet)
        assertTrue(empty.rows.isEmpty())
        assertEquals(listOf(1, 3, 4), fills.rows.map { it.number })
        // Duplicate column names and gaps keep their positions.
        assertEquals(listOf("Time", "Amount", "", "Time"), fills.rows[0].cells.map { it.text })
        val values = fills.rows[1].cells
        assertEquals(ImportCell("45658.5", serialDate = true), values[0])
        assertEquals(ImportCell("0.10000000"), values[1])
        assertEquals(ImportCell("0.2", formula = true), values[2])
        // A custom format whose letters are quoted text is not a date.
        assertEquals(ImportCell("12"), values[3])
        assertEquals(ImportCell("1", serialDate = true), fills.rows[2].cells[0])
        assertEquals("1.5e-05", fills.rows[2].text(1))
        assertEquals(false, fills.date1904)
    }

    @Test fun oldWorkbooksAndDocumentTypesAreRejected() {
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        assertTrue(assertFailsWith<ImportFileException> { read("old.xlsx", ole) }.message!!.contains("save it as .xlsx or CSV"))
        val hostile =
            xlsx(listOf("S" to "")).let { bytes ->
                val parts =
                    java.util.zip
                        .ZipInputStream(bytes.inputStream())
                        .use { zip ->
                            generateSequence { zip.nextEntry }.associate { it.name to zip.readBytes() }
                        }.toMutableMap()
                parts["xl/workbook.xml"] =
                    """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><workbook/>""".toByteArray()
                zip(*parts.map { it.key to it.value }.toTypedArray())
            }
        assertTrue(assertFailsWith<ImportFileException> { read("x.xlsx", hostile) }.message!!.contains("document type"))
        assertTrue(
            assertFailsWith<ImportFileException> { read("latin.csv", byteArrayOf(0x41, 0xE9.toByte(), 0x0A)) }.message!!.contains("UTF-8"),
        )
    }

    @Test fun timesUseTheColumnOrFileOffsetAndExcelDaysAndNeverTheDevice() {
        assertEquals(ImportOffset.UTC, ImportTime.columnOffset("Date & Time(UTC)"))
        assertEquals(ImportOffset(240), ImportTime.columnOffset("Creation Time(UTC+04:00)"))
        assertEquals(ImportOffset(480), ImportTime.columnOffset("Date(UTC+8)"))
        assertEquals(ImportOffset.UTC, ImportTime.columnOffset("Timestamp (UTC+0)"))
        assertEquals(null, ImportTime.columnOffset("Time"))
        assertEquals(ImportOffset(240), ImportTime.nameOffset("Binance-Spot-Trade-History-202610201952(UTC+4)_412182df.zip"))
        assertEquals("UTC+4", ImportOffset(240).label)
        assertEquals("UTC-3:30", ImportOffset(-210).label)
        // 2024-03-02 01:09:57 at UTC+4 is 2024-03-01 21:09:57 UTC.
        assertEquals(1_709_327_397_000L, ImportTime.millis(ImportCell("2024-03-02 01:09:57"), ImportOffset(240)))
        assertEquals(1_709_341_797_000L, ImportTime.millis(ImportCell("2024-03-02T01:09:57.000Z"), ImportOffset(240)))
        // Excel day 45658.5 is 2025-01-01 12:00 wall time; 1904 workbooks count 1462 days fewer.
        assertEquals(1_735_732_800_000L, ImportTime.millis(ImportCell("45658.5", serialDate = true), ImportOffset.UTC))
        assertEquals(1_735_732_800_000L - 8 * 3_600_000L, ImportTime.millis(ImportCell("45658.5", serialDate = true), ImportOffset(480)))
        assertEquals(1_735_732_800_000L, ImportTime.millis(ImportCell("44196.5", serialDate = true), ImportOffset.UTC, date1904 = true))
        assertFailsWith<ImportFileException> { ImportTime.millis(ImportCell("13/01/2025"), ImportOffset.UTC) }
    }
}
