package io.valroot.core.exchange.imports

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ImportLimitsTest {
    @Test
    fun wideCsvFailsBeforeBuildingAnUnboundedRow() {
        assertFailsWith<ImportFileException> { ExportCsv.read(",".repeat(300), ImportBudget()) }
    }

    @Test
    fun compactSnapshotDateUsesItsColumnOffset() {
        val compact = ImportTime.millis(ImportCell("20260101"), ImportOffset(480))
        val iso = ImportTime.millis(ImportCell("2026-01-01"), ImportOffset(480))
        kotlin.test.assertEquals(iso, compact)
    }

    @Test
    fun manyCellsShareOneBatchBudget() {
        val budget = ImportBudget(ImportLimits(maxCells = 4))
        ExportCsv.read("a,b\n", budget)
        assertFailsWith<ImportFileException> { ExportCsv.read("c,d,e\n", budget) }
    }
}
