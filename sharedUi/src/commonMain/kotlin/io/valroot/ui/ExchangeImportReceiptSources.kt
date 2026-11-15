package io.valroot.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.exchange.imports.ImportReceipt
import io.valroot.core.exchange.imports.ImportSourceRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Receipt provenance is loaded on demand and rendered one page at a time. Original cell values stay private. */
@Composable
internal fun ExchangeImportReceiptSources(
    app: PortfolioApplication,
    host: PlatformActions,
    receipt: ImportReceipt,
    data: PortfolioSnapshot,
) {
    var expanded by remember(app, receipt.id) { mutableStateOf(false) }
    var rows by remember(app, receipt.id) { mutableStateOf<List<ImportSourceRow>?>(null) }
    var error by remember(app, receipt.id) { mutableStateOf(false) }
    var retry by remember(app, receipt.id) { mutableStateOf(0) }
    var page by remember(app, receipt.id) { mutableStateOf(0) }
    LaunchedEffect(app, receipt.id, expanded, retry) {
        if (!expanded || rows != null) return@LaunchedEffect
        error = false
        try {
            rows = withContext(host.dispatcher) { app.exchangeImports.sources(receipt.id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = true
        }
    }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide source rows" else "View source rows") }
    if (!expanded) return
    val sources = rows
    if (error) {
        Text("Source rows could not be loaded.", color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { retry++ }) { Text("Retry source rows") }
    } else if (sources == null) {
        Text("Loading source rows…")
    } else if (sources.isEmpty()) {
        Text("No source rows")
    } else {
        val start = page * SOURCE_PAGE_SIZE
        Text("Source rows ${start + 1}–${minOf(start + SOURCE_PAGE_SIZE, sources.size)} of ${sources.size}")
        sources.drop(start).take(SOURCE_PAGE_SIZE).forEachIndexed { index, row ->
            Text(
                listOfNotNull(row.file, row.member, row.sheet).joinToString(" › "),
                fontWeight = FontWeight.SemiBold,
            )
            Text("Original rows: ${row.rows.joinToString()}", modifier = Modifier.testTag("exchange-source-row-${start + index}"))
            Text("Report: ${row.report}")
            row.account?.let { account ->
                Text(
                    "Account: ${data.ownership.accounts
                        .find { it.id == account }
                        ?.label ?: "Exchange account"}",
                )
            }
            Text("Outcome: ${row.status.name.lowercase().replace('_', ' ')}")
        }
        if (page > 0) TextButton(onClick = { page-- }) { Text("Previous source rows") }
        if (start + SOURCE_PAGE_SIZE < sources.size) TextButton(onClick = { page++ }) { Text("Next source rows") }
    }
}

private const val SOURCE_PAGE_SIZE = 50
