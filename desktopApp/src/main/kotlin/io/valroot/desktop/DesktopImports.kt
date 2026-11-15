package io.valroot.desktop

import io.valroot.core.exchange.imports.ExportFileReader
import io.valroot.core.exchange.imports.ImportBudget
import io.valroot.core.exchange.imports.ImportFileException
import io.valroot.core.exchange.imports.ImportTable
import java.nio.file.Files
import javax.swing.SwingUtilities

/** Reads a selected batch off the UI thread, sharing one expansion and row budget across its files. */
internal class DesktopImports(
    private val state: DesktopState,
) {
    fun open(onResult: (List<ImportTable>?, String?) -> Unit) {
        SwingUtilities.invokeLater {
            val paths =
                runCatching { BackupFiles().chooseExchangeExports() }.getOrElse {
                    onResult(null, "Could not open the file picker")
                    return@invokeLater
                }
            if (paths.isEmpty()) {
                onResult(null, null)
                return@invokeLater
            }
            state.executor.execute {
                val result =
                    runCatching {
                        val budget = ImportBudget()
                        paths.flatMap { path ->
                            Files.newInputStream(path).use { ExportFileReader.read(path.fileName.toString(), it, budget) }
                        }
                    }
                SwingUtilities.invokeLater {
                    if (!state.closing.get()) {
                        result.fold(
                            { onResult(it, null) },
                            { onResult(null, (it as? ImportFileException)?.message ?: "Could not read the selected exports") },
                        )
                    }
                }
            }
        }
    }
}
