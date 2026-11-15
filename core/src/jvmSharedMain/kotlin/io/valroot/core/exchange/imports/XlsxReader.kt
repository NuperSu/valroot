package io.valroot.core.exchange.imports

import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory

/**
 * Reads the sheets of an XLSX workbook (Office Open XML) from its unzipped parts with a streaming parser: shared and
 * inline strings, number text exactly as stored, cell styles to tell dates from numbers, and the 1900 or 1904 date
 * system. Formulas are never evaluated: a formula cell keeps the value last saved and is marked. Parts declaring a
 * document type are rejected, so no external entity is ever resolved.
 */
internal object XlsxReader {
    data class Sheet(
        val name: String,
        val rows: List<ImportRow>,
        val date1904: Boolean,
    )

    fun read(
        label: String,
        parts: Map<String, ByteArray>,
        budget: ImportBudget,
    ): List<Sheet> {
        try {
            val workbook = Workbook().also { parse(label, part(label, parts, "xl/workbook.xml"), it) }
            val relations = Relations().also { handler -> parts["xl/_rels/workbook.xml.rels"]?.let { parse(label, it, handler) } }
            val shared = SharedStrings().also { handler -> parts["xl/sharedStrings.xml"]?.let { parse(label, it, handler) } }
            val styles = Styles().also { handler -> parts["xl/styles.xml"]?.let { parse(label, it, handler) } }
            return workbook.sheets.map { (name, relation) ->
                val target = relations.targets[relation] ?: throw ImportFileException("\"$label\" names a sheet it does not contain")
                val path = if (target.startsWith("/")) target.drop(1) else "xl/" + target.removePrefix("./")
                val sheet = SheetHandler(label, shared.strings, styles, budget)
                parse(label, part(label, parts, path), sheet)
                Sheet(name, sheet.rows, workbook.date1904)
            }
        } catch (failure: ImportFileException) {
            throw failure
        } catch (_: SAXException) {
            throw ImportFileException("\"$label\" is a damaged workbook")
        } catch (_: java.io.IOException) {
            throw ImportFileException("\"$label\" is a damaged workbook")
        }
    }

    private fun part(
        label: String,
        parts: Map<String, ByteArray>,
        path: String,
    ) = parts[path] ?: throw ImportFileException("\"$label\" is a damaged workbook (no $path)")

    private fun parse(
        label: String,
        bytes: ByteArray,
        handler: DefaultHandler,
    ) {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8)
        if (head.contains("<!DOCTYPE", ignoreCase = true)) throw ImportFileException("\"$label\" declares a document type; it was not read")
        parser().parse(ByteArrayInputStream(bytes), handler)
    }

    private fun parser(): SAXParser {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        return factory.newSAXParser()
    }

    private const val RELATIONSHIPS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private class Workbook : DefaultHandler() {
        val sheets = mutableListOf<Pair<String, String>>()
        var date1904 = false

        override fun startElement(
            uri: String,
            localName: String,
            qName: String,
            attributes: Attributes,
        ) {
            when (localName) {
                "workbookPr" -> date1904 = attributes.getValue("date1904").let { it == "1" || it.equals("true", ignoreCase = true) }
                "sheet" -> sheets += attributes.getValue("name").orEmpty() to attributes.getValue(RELATIONSHIPS, "id").orEmpty()
            }
        }
    }

    private class Relations : DefaultHandler() {
        val targets = mutableMapOf<String, String>()

        override fun startElement(
            uri: String,
            localName: String,
            qName: String,
            attributes: Attributes,
        ) {
            if (localName == "Relationship") targets[attributes.getValue("Id").orEmpty()] = attributes.getValue("Target").orEmpty()
        }
    }

    /** Each `<si>`'s text: its `<t>` runs, without phonetic hints (`<rPh>`). */
    private class SharedStrings : DefaultHandler() {
        val strings = mutableListOf<String>()
        private val text = StringBuilder()
        private var inText = false
        private var phonetic = 0

        override fun startElement(
            uri: String,
            localName: String,
            qName: String,
            attributes: Attributes,
        ) {
            when (localName) {
                "si" -> text.clear()
                "rPh" -> phonetic++
                "t" -> inText = phonetic == 0
            }
        }

        override fun endElement(
            uri: String,
            localName: String,
            qName: String,
        ) {
            when (localName) {
                "si" -> strings += text.toString()
                "rPh" -> phonetic--
                "t" -> inText = false
            }
        }

        override fun characters(
            ch: CharArray,
            start: Int,
            length: Int,
        ) {
            if (inText) text.appendRange(ch, start, start + length)
        }
    }

    /** Which cell styles format a number as a date or time. */
    private class Styles : DefaultHandler() {
        private val formats = mutableMapOf<Int, String>()
        private val styleFormats = mutableListOf<Int>()
        private var inCellStyles = false

        fun isDate(style: Int?): Boolean {
            val id = style?.let { styleFormats.getOrNull(it) } ?: return false
            return id in BUILT_IN_DATES || formats[id]?.let(::dateCode) == true
        }

        override fun startElement(
            uri: String,
            localName: String,
            qName: String,
            attributes: Attributes,
        ) {
            when (localName) {
                "numFmt" ->
                    attributes.getValue("numFmtId")?.toIntOrNull()?.let { formats[it] = attributes.getValue("formatCode").orEmpty() }
                "cellXfs" -> inCellStyles = true
                "xf" -> if (inCellStyles) styleFormats += attributes.getValue("numFmtId")?.toIntOrNull() ?: 0
            }
        }

        override fun endElement(
            uri: String,
            localName: String,
            qName: String,
        ) {
            if (localName == "cellXfs") inCellStyles = false
        }

        private fun dateCode(code: String): Boolean {
            val plain =
                code
                    .replace(Regex("\"[^\"]*\""), "")
                    .replace(Regex("""\\."""), "")
                    .replace(Regex("""\[[^\]]*]"""), "")
                    .lowercase()
            return plain != "general" && plain.any { it in "ymdhs" }
        }

        companion object {
            val BUILT_IN_DATES = (14..22) + (27..36) + (45..47) + (50..58) + (71..81)
        }
    }

    private class SheetHandler(
        private val label: String,
        private val shared: List<String>,
        private val styles: Styles,
        private val budget: ImportBudget,
    ) : DefaultHandler() {
        val rows = mutableListOf<ImportRow>()
        private var rowNumber = 0
        private var cells = sortedMapOf<Int, ImportCell>()
        private var column = -1
        private var type: String? = null
        private var style: Int? = null
        private var formula = false
        private val value = StringBuilder()
        private val inline = StringBuilder()
        private var inValue = false
        private var inInline = false

        override fun startElement(
            uri: String,
            localName: String,
            qName: String,
            attributes: Attributes,
        ) {
            when (localName) {
                "row" -> {
                    rowNumber = attributes.getValue("r")?.toIntOrNull() ?: (rowNumber + 1)
                    cells = sortedMapOf()
                    column = -1
                }
                "c" -> {
                    column = attributes.getValue("r")?.let(::columnOf) ?: (column + 1)
                    if (column >= budget.limits.maxColumns) throw ImportFileException("A report has too many columns")
                    type = attributes.getValue("t")
                    style = attributes.getValue("s")?.toIntOrNull()
                    formula = false
                    value.clear()
                    inline.clear()
                }
                "f" -> formula = true
                "v" -> inValue = true
                "t" -> inInline = true
            }
        }

        override fun endElement(
            uri: String,
            localName: String,
            qName: String,
        ) {
            when (localName) {
                "v" -> inValue = false
                "t" -> inInline = false
                "c" -> cells[column] = cell()
                "row" ->
                    if (cells.values.any { it.text.isNotBlank() }) {
                        budget.row()
                        val width = cells.lastKey() + 1
                        budget.cells(width)
                        rows += ImportRow(rowNumber, List(width) { cells[it] ?: ImportCell("") })
                    }
            }
        }

        override fun characters(
            ch: CharArray,
            start: Int,
            length: Int,
        ) {
            if (inValue) value.appendRange(ch, start, start + length)
            if (inInline) inline.appendRange(ch, start, start + length)
        }

        private fun cell(): ImportCell {
            val raw = value.toString()
            return when (type) {
                "s" -> {
                    val index = raw.trim().toIntOrNull() ?: throw ImportFileException("\"$label\" is a damaged workbook")
                    ImportCell(shared.getOrNull(index) ?: throw ImportFileException("\"$label\" is a damaged workbook"), formula)
                }
                "inlineStr" -> ImportCell(inline.toString(), formula)
                "str", "b", "e", "d" -> ImportCell(raw, formula)
                else -> ImportCell(raw, formula, serialDate = raw.isNotEmpty() && styles.isDate(style))
            }
        }

        /** The 0-based column of a reference such as `AB12`. */
        private fun columnOf(reference: String): Int {
            var index = 0
            for (c in reference) {
                if (c !in 'A'..'Z') break
                index = index * 26 + (c - 'A' + 1)
            }
            return index - 1
        }
    }
}
