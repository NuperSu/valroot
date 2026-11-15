package io.valroot.android

import io.valroot.core.exchange.imports.ExportFileReader
import io.valroot.core.exchange.imports.ImportBudget
import io.valroot.core.exchange.imports.ImportFileException
import io.valroot.core.exchange.imports.ImportTable

/** SAF documents are consumed while the picker grant is valid; parsing runs on the database worker. */
internal class AndroidImports(private val state: AndroidState) {
    fun open(onResult: (List<ImportTable>?, String?) -> Unit) {
        state.main.post {
            state.files.chooseExchangeExports { uris ->
                if (uris.isEmpty()) {
                    onResult(null, null)
                } else {
                    state.executor.execute {
                        val result = runCatching {
                            val budget = ImportBudget()
                            uris.flatMap { uri ->
                                state.files.read(uri) {
                                    ExportFileReader.read(state.files.displayName(uri) ?: "export", it, budget)
                                }
                            }
                        }
                        state.main.post {
                            if (!state.closed) result.fold(
                                { onResult(it, null) },
                                { onResult(null, (it as? ImportFileException)?.message ?: "Could not read the selected exports") },
                            )
                        }
                    }
                }
            }
        }
    }
}
