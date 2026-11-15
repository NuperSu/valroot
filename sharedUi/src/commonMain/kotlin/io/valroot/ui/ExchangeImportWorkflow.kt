package io.valroot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.exchange.ExchangeKind
import io.valroot.core.exchange.imports.AccountChoice
import io.valroot.core.exchange.imports.FuturesFlow
import io.valroot.core.exchange.imports.GroupDecision
import io.valroot.core.exchange.imports.ImportAssignment
import io.valroot.core.exchange.imports.ImportChoices
import io.valroot.core.exchange.imports.ImportOffset
import io.valroot.core.exchange.imports.ImportPreview
import io.valroot.core.exchange.imports.ImportReceipt
import io.valroot.core.exchange.imports.ImportStatus
import io.valroot.core.exchange.imports.ImportTable
import io.valroot.core.model.AccountId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The preview is tied to the application instance, so a restored database never receives an old preview. */
@Composable
internal fun ExchangeImportDialog(
    app: PortfolioApplication,
    host: PlatformActions,
    data: PortfolioSnapshot,
    close: () -> Unit,
    changed: (String) -> Unit,
) {
    androidx.compose.runtime.key(app) { ExchangeImportContent(app, host, data, close, changed) }
}

@Composable
private fun ExchangeImportContent(
    app: PortfolioApplication,
    host: PlatformActions,
    data: PortfolioSnapshot,
    close: () -> Unit,
    changed: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var active by remember(app) { mutableStateOf(true) }
    var tables by remember(app) { mutableStateOf<List<ImportTable>>(emptyList()) }
    var choices by remember(app) { mutableStateOf(ImportChoices()) }
    var preview by remember(app) { mutableStateOf<ImportPreview?>(null) }
    var receipts by remember(app) { mutableStateOf<List<ImportReceipt>>(emptyList()) }
    var targets by remember(app) { mutableStateOf<Map<ExchangeKind, List<Pair<AccountId, String>>>>(emptyMap()) }
    var flows by remember(app) { mutableStateOf<List<FuturesFlow>>(emptyList()) }
    var busy by remember(app) { mutableStateOf(false) }
    var error by remember(app) { mutableStateOf<String?>(null) }
    var message by remember(app) { mutableStateOf<String?>(null) }
    var refresh by remember(app) { mutableStateOf(0) }
    var undo by remember(app) { mutableStateOf<String?>(null) }
    DisposableEffect(app) { onDispose { active = false } }
    LaunchedEffect(app, refresh) {
        try {
            withContext(host.dispatcher) {
                val currentTargets = ExchangeKind.entries.associateWith { app.exchanges.targets(it) }
                val currentReceipts = app.exchangeImports.receipts()
                val currentFlows = currentTargets.values.flatten().flatMap { app.exchangeImports.futuresFlows(it.first) }
                Triple(currentTargets, currentReceipts, currentFlows)
            }.let { (accounts, history, futures) ->
                targets = accounts
                receipts = history
                flows = futures
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "Import history could not be loaded."
        }
    }
    LaunchedEffect(app, tables, choices) {
        preview = null
        if (tables.isNotEmpty()) {
            busy = true
            try {
                preview = withContext(host.dispatcher) { app.exchangeImports.preview(tables, choices) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "The import could not be previewed. Choose the files again."
            } finally {
                busy = false
            }
        }
    }
    FormDialog("Import exchange exports", { if (!busy) close() }) {
        Text("Choose Binance or Bybit ZIP/CSV exports, or MEXC XLSX exports. Files stay on this device.")
        OutlinedButton(enabled = !busy, modifier = Modifier.testTag("exchange-import-files"), onClick = {
            busy = true
            error = null
            host.openExchangeExports { selected, failure ->
                if (active) {
                    busy = false
                    error = failure
                    if (selected != null) {
                        choices = ImportChoices()
                        tables = selected
                        message = null
                    }
                }
            }
        }) { Text("Choose export files") }
        if (busy) Text("Reading exchange history…")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        message?.let { Text(it) }
        preview?.let { current ->
            current.tables.forEach { table ->
                Text(table.label, fontWeight = FontWeight.SemiBold)
                Text("${table.report?.label ?: "Unrecognised report"} · ${table.rows} rows")
                table.report?.let { Text("${it.exchange.label} · ${it.family.name.lowercase()} · ${it.role.name.lowercase()}") }
                if (table.fromMillis != null) Text("${importTimestamp(table.fromMillis)} – ${importTimestamp(table.toMillis)}")
                table.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            if (current.tables.any { it.needsOffset } || choices.offset != null) {
                OffsetChoice(choices.offset) { choices = choices.copy(offset = it) }
            }
            current.assignments.forEach { assignment ->
                ImportAccountChoice(assignment, targets[assignment.exchange].orEmpty(), data, choices.accounts[assignment.key]) {
                    choices = choices.copy(accounts = choices.accounts + (assignment.key to it))
                }
            }
            ImportCounts(current.counts)
            current.groups.forEach { group ->
                HorizontalDivider()
                Text("${group.exchange.label} · ${group.kind.name.lowercase()} · ${importTimestamp(group.atMillis)}")
                Text("${group.records} records may match ${group.candidates} existing events")
                Text(group.coins.entries.joinToString { "${it.value} ${it.key}" })
                GroupDecision.entries.forEach { decision ->
                    TextButton(onClick = { choices = choices.copy(decisions = choices.decisions + (group.key to decision)) }) {
                        Text(
                            when (decision) {
                                GroupDecision.SAME -> "Same event"
                                GroupDecision.DISTINCT -> "Distinct event"
                                GroupDecision.EXCLUDED -> "Exclude"
                            },
                        )
                    }
                }
            }
            if (listOf(ImportStatus.EXCLUDED, ImportStatus.CONFLICT, ImportStatus.UNRESOLVED).any { (current.counts[it] ?: 0) > 0 }) {
                TextButton(onClick = { choices = choices.copy(acknowledgeExclusions = !choices.acknowledgeExclusions) }) {
                    Checkbox(choices.acknowledgeExclusions, null)
                    Text("I acknowledge excluded, conflicting and unresolved rows are left out")
                }
            }
            FuturesSummary(current.futuresFlows)
            current.blockers.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(enabled = !busy && current.canCommit, modifier = Modifier.testTag("exchange-import-commit"), onClick = {
                busy = true
                scope.launch {
                    try {
                        withContext(host.dispatcher) { app.exchangeImports.commit(current) }
                        tables = emptyList()
                        preview = null
                        refresh++
                        message = "Exchange history imported"
                        changed("Exchange history imported")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "Import could not be saved. Preview the files again; the account may have changed."
                        preview = null
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Import history") }
        }
        HorizontalDivider()
        Text("Import receipts", fontWeight = FontWeight.SemiBold)
        if (receipts.isEmpty()) Text("No exchange exports imported yet")
        receipts.forEach { receipt ->
            Text(importTimestamp(receipt.importedAtMillis), fontWeight = FontWeight.SemiBold)
            Text(receipt.files.joinToString { it.name })
            Text(receipt.reports.entries.joinToString { "${it.key}: ${it.value}" })
            ImportCounts(receipt.counts)
            ExchangeImportReceiptSources(app, host, receipt, data)
            if (receipt.undoneAtMillis != null) {
                Text("Undone")
            } else if (undo == receipt.id) {
                Text("Remove this batch's contributions? Events supported by other imports or API history stay.")
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            withContext(host.dispatcher) { app.exchangeImports.undo(receipt.id) }
                            undo = null
                            tables = emptyList()
                            preview = null
                            refresh++
                            changed("Exchange import undone")
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = "This import could not be undone."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Confirm undo") }
                TextButton(enabled = !busy, onClick = { undo = null }) { Text("Keep import") }
            } else {
                TextButton(enabled = !busy, onClick = { undo = receipt.id }) { Text("Undo import") }
            }
        }
        FuturesSummary(flows)
    }
}

@Composable
private fun ImportAccountChoice(
    assignment: ImportAssignment,
    targets: List<Pair<AccountId, String>>,
    data: PortfolioSnapshot,
    choice: AccountChoice?,
    choose: (AccountChoice) -> Unit,
) {
    var creating by remember(assignment.key) { mutableStateOf(choice is AccountChoice.New) }
    var label by remember(assignment.key) { mutableStateOf(assignment.exchange.label) }
    var uid by remember(assignment.key) { mutableStateOf(assignment.uid.orEmpty()) }
    HorizontalDivider()
    Text("${assignment.exchange.label}: ${assignment.records} records", fontWeight = FontWeight.SemiBold)
    assignment.uid?.let { Text("Exchange user ID: $it") }
    assignment.account?.let { account ->
        Text(
            "Assigned to ${data.ownership.accounts
                .find { it.id == account }
                ?.label ?: "exchange account"}",
        )
    }
    targets.forEach { (account, accountUid) ->
        TextButton(onClick = {
            creating = false
            choose(AccountChoice.Existing(account))
        }) {
            Text(
                "${data.ownership.accounts
                    .find { it.id == account }
                    ?.label ?: assignment.exchange.label} · $accountUid",
            )
        }
    }
    TextButton(onClick = { creating = true }) { Text("Create account from exports") }
    if (creating) {
        Field("Account label", label) { label = it }
        if (assignment.uid == null) Field("Exchange user ID", uid) { uid = it }
        OutlinedButton(
            enabled = label.isNotBlank() && uid.isNotBlank(),
            onClick = { choose(AccountChoice.New(label.trim(), uid.trim())) },
        ) {
            Text("Use new account")
        }
        if (choice is AccountChoice.New) Text("New account: ${choice.label} · ${choice.uid}")
    }
}

@Composable
private fun OffsetChoice(
    offset: ImportOffset?,
    choose: (ImportOffset) -> Unit,
) {
    var value by remember { mutableStateOf(offset?.minutes?.toString().orEmpty()) }
    Text("Choose the UTC offset for report times without a stated zone. Stated report offsets take precedence.")
    Field("UTC offset in minutes (UTC = 0, UTC+8 = 480)", value) { value = it }
    val minutes = value.toIntOrNull()?.takeIf { it in -840..840 }
    TextButton(enabled = minutes != null, onClick = { minutes?.let { choose(ImportOffset(it)) } }) { Text("Apply time zone") }
    offset?.let { Text("Selected: ${it.label}") }
}

@Composable
private fun ImportCounts(counts: Map<ImportStatus, Int>) {
    Text(counts.entries.joinToString(" · ") { "${it.key.name.lowercase().replace('_', ' ')}: ${it.value}" })
}

@Composable
private fun FuturesSummary(flows: List<FuturesFlow>) {
    if (flows.isEmpty()) return
    Column {
        Text("Futures transfers", fontWeight = FontWeight.SemiBold)
        Text("Transfers only. Futures balances are outside portfolio totals; returns have unknown cost basis.")
        flows.forEach { flow ->
            Text("${flow.exchange.label} ${flow.coin}: to futures ${flow.toFutures}, from futures ${flow.fromFutures}, net ${flow.net}")
        }
    }
}

internal fun importTimestamp(millis: Long?): String =
    millis?.let {
        kotlin.time.Instant
            .fromEpochMilliseconds(it)
            .toString()
    } ?: "Unknown time"
