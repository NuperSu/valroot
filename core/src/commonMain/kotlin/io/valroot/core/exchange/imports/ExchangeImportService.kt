package io.valroot.core.exchange.imports

import io.valroot.core.application.AppContext
import io.valroot.core.application.AppService
import io.valroot.core.exchange.ExchangeKind
import io.valroot.core.exchange.ExchangeProviders
import io.valroot.core.exchange.ExchangeRow
import io.valroot.core.exchange.ImportOutcome
import io.valroot.core.exchange.ImportedEvents
import io.valroot.core.model.AccountId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.OwnerId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.ownership.TargetIdentity
import io.valroot.core.ownership.TargetRole
import io.valroot.core.ownership.TrackingTarget
import io.valroot.core.sortedByKeys

/** Which account an import's records go to: by the exchange's user ID, or by the user's choice when the files do not say. */
data class ImportAssignment(
    val exchange: ExchangeKind,
    val uid: String?,
    /** The tracked account, or the user's choice; null while it must be chosen. */
    val account: AccountId?,
    val records: Int,
) {
    /** Identifies the assignment in [ImportChoices.accounts]. */
    val key: String get() = "${exchange.id}:${uid ?: "?"}"
}

/** The user's choice of account for an assignment. */
sealed interface AccountChoice {
    data class Existing(
        val account: AccountId,
    ) : AccountChoice

    /** A new account known only from its exports, identified by the exchange's user ID. */
    data class New(
        val label: String,
        val uid: String,
        val owner: OwnerId? = null,
    ) : AccountChoice
}

/** What the user chose in a preview: the offset for times the files do not place, accounts and group decisions. */
data class ImportChoices(
    val offset: ImportOffset? = null,
    val accounts: Map<String, AccountChoice> = emptyMap(),
    val decisions: Map<String, GroupDecision> = emptyMap(),
    /** Excluded, conflicting and unresolved rows are left out knowingly. */
    val acknowledgeExclusions: Boolean = false,
)

/** One table of a preview: its report (null when not recognised), rows and date range, and what stops it. */
data class PreviewTable(
    val label: String,
    val report: ExportReport?,
    val rows: Int,
    val fromMillis: Long?,
    val toMillis: Long?,
    val needsOffset: Boolean,
    val error: String?,
)

/** Records the user must decide on: the same event as the account's, or distinct ones. */
data class AmbiguousGroup(
    val key: String,
    val exchange: ExchangeKind,
    val kind: RecordKind,
    val atMillis: Long,
    val coins: Map<String, ExactDecimal>,
    val records: Int,
    val candidates: Int,
)

/** Coins moved across one exchange's futures boundary by an import: in, out and net, per coin. */
data class FuturesFlow(
    val exchange: ExchangeKind,
    val coin: String,
    val toFutures: ExactDecimal,
    val fromFutures: ExactDecimal,
) {
    /** Net coins transferred into futures; this is a flow, not a futures balance or profit. */
    val net: ExactDecimal get() = toFutures - fromFutures
}

/** What an import would do, before anything is saved. [blockers] say what must be chosen first. */
class ImportPreview internal constructor(
    val tables: List<PreviewTable>,
    val assignments: List<ImportAssignment>,
    val counts: Map<ImportStatus, Int>,
    val groups: List<AmbiguousGroup>,
    val futuresFlows: List<FuturesFlow>,
    val blockers: List<String>,
    internal val choices: ImportChoices,
    internal val planned: List<Planned>,
) {
    val canCommit: Boolean get() = blockers.isEmpty()
}

/** One record of a preview with its key, assignment and status. */
internal class Planned(
    val table: DetectedTable,
    val record: ImportedRecord,
    val key: String,
    val assignment: String,
    val status: ImportStatus,
    val offset: ImportOffset?,
)

/**
 * Imports exchange export files into exchange accounts: previews what files hold and what would change, commits a
 * batch atomically, lists receipts and undoes a batch. Reading files happens in the host ([ExportFileReader]);
 * everything here works on their tables and needs no network.
 */
class ExchangeImportService internal constructor(
    ctx: AppContext,
) : AppService(ctx) {
    private val repository = ImportRepository(db)

    /** Balance-log and transfer evidence also participates in a wallet-aware decoder without adding a second movement. */
    private fun decodeRecord(record: ImportedRecord): Boolean =
        record.moves || record.details["bybitWalletLog"] != null || record.details["bybitTransferEvidence"] != null

    fun preview(
        tables: List<ImportTable>,
        choices: ImportChoices = ImportChoices(),
    ): ImportPreview {
        val previewTables = mutableListOf<PreviewTable>()
        val read = mutableListOf<Triple<DetectedTable, ImportedRecord, ImportOffset?>>()
        val blockers = mutableListOf<String>()
        tables.forEach { table ->
            val detected = ExportReports.detect(table)
            if (detected == null) {
                previewTables +=
                    PreviewTable(table.label, null, table.rows.size, null, null, needsOffset = false, error = "Not a supported export")
                return@forEach
            }
            val offsets = if (detected.report.role == EvidenceRole.DEFERRED) emptyMap() else detected.offsets(choices.offset)
            if (offsets.values.any { it == null }) {
                previewTables +=
                    PreviewTable(table.label, detected.report, detected.rows.size, null, null, needsOffset = true, error = null)
                blockers += "Choose the time zone of ${table.label}"
                return@forEach
            }
            val adapter = ExchangeProviders.of(detected.report.exchange).exports!!
            val records =
                try {
                    adapter.interpret(detected, offsets.mapValues { it.value!! })
                } catch (failure: ImportFileException) {
                    previewTables +=
                        PreviewTable(
                            table.label,
                            detected.report,
                            detected.rows.size,
                            null,
                            null,
                            needsOffset = false,
                            error = failure.message,
                        )
                    blockers += failure.message ?: "${table.label} could not be read"
                    return@forEach
                }
            previewTables +=
                PreviewTable(
                    table.label,
                    detected.report,
                    detected.rows.size,
                    records.minOfOrNull { it.atMillis },
                    records.maxOfOrNull { it.atMillis },
                    needsOffset = false,
                    error = null,
                )
            val offset = offsets.values.firstOrNull()
            records.forEach { read += Triple(detected, it, offset) }
        }

        // Records without a user ID belong to the batch's only account of their exchange, if there is just one.
        val uidsByExchange =
            read
                .mapNotNull { (_, record, _) ->
                    record.uid?.let { record.exchange to it }
                }.groupBy({ it.first }, { it.second })

        fun assignmentOf(record: ImportedRecord): String {
            val uid = record.uid ?: uidsByExchange[record.exchange]?.distinct()?.singleOrNull()
            return "${record.exchange.id}:${uid ?: "?"}"
        }
        val keyed = keyRecords(read)
        val assignments =
            keyed
                .groupBy { assignmentOf(it.second) }
                .map { (key, list) ->
                    val exchange = list.first().second.exchange
                    val uid = key.substringAfter(':').takeIf { it != "?" }
                    val chosen =
                        when (val choice = choices.accounts[key]) {
                            is AccountChoice.Existing -> choice.account
                            is AccountChoice.New -> null
                            null ->
                                uid?.let { id ->
                                    app.exchanges
                                        .targets(exchange)
                                        .firstOrNull { it.second == id }
                                        ?.first
                                }
                        }
                    ImportAssignment(exchange, uid, chosen, list.size)
                }
        assignments.forEach { assignment ->
            val choice = choices.accounts[assignment.key]
            val label = assignment.exchange.label
            when {
                assignment.account == null && choice !is AccountChoice.New ->
                    blockers +=
                        "Choose the $label account for ${assignment.records} records" + (assignment.uid?.let { " of user $it" } ?: "")
                choice is AccountChoice.New && assignment.uid != null && choice.uid != assignment.uid ->
                    blockers += "The files belong to $label user ${assignment.uid}, not ${choice.uid}"
                choice is AccountChoice.New && app.exchanges.targets(assignment.exchange).any { it.second == choice.uid } ->
                    blockers += "This $label account is already tracked; choose it instead"
                assignment.account != null && assignment.uid != null -> {
                    val trackedUid =
                        app.exchanges
                            .targets(assignment.exchange)
                            .firstOrNull { it.first == assignment.account }
                            ?.second
                    if (trackedUid !=
                        assignment.uid
                    ) {
                        blockers += "The files belong to $label user ${assignment.uid}, not the chosen account"
                    }
                }
            }
        }

        val planned = mutableListOf<Planned>()
        val groups = mutableListOf<AmbiguousGroup>()
        keyed.groupBy { assignmentOf(it.second) }.forEach { (assignmentKey, list) ->
            val assignment = assignments.single { it.key == assignmentKey }
            val account = assignment.account
            val saved = account?.let { app.exchanges.savedRows(it) }.orEmpty()
            val savedKeys = saved.filter { it.stream == ImportedEvents.STREAM }.map { it.key }.toSet()
            val moving = list.filter { (_, record) -> decodeRecord(record) }
            val fresh = moving.filter { it.first !in savedKeys }
            val outcomes =
                if (fresh.isEmpty()) {
                    emptyMap()
                } else {
                    val uid = uidOf(assignment, choices) ?: "unknown"
                    val extra =
                        fresh.map { (key, record) ->
                            ExchangeRow(ImportedEvents.STREAM, key, record.atMillis, ImportedEvents.payload(record), "preview:$key")
                        } +
                            choices.decisions.map { (group, decision) ->
                                ExchangeRow(ImportedEvents.DECISIONS, group, 0, decision.name, "preview")
                            }
                    val coins = fresh.flatMap { (_, record) -> record.legs.map { it.coin } }.toSet() + ImportedEvents.coins(saved)
                    val assets = app.exchanges.knownAssets(assignment.exchange.id, coins)
                    app.exchanges.decodeWith(assignment.exchange, account, uid, extra, assets).imports
                }
            list.forEach { (key, record) ->
                val (table, _, offset) = read.first { it.second === record }
                val status =
                    when {
                        record.role == EvidenceRole.SUPPORTING || record.kind == RecordKind.ORDER -> ImportStatus.SUPPORTING
                        record.role == EvidenceRole.CHECKPOINT || record.kind == RecordKind.SNAPSHOT -> ImportStatus.CHECKPOINT
                        record.role == EvidenceRole.DEFERRED || record.kind == RecordKind.DERIVATIVE -> ImportStatus.DEFERRED
                        record.kind == RecordKind.UNKNOWN -> ImportStatus.UNRESOLVED
                        record.status != RecordStatus.COMPLETED -> ImportStatus.NOT_COMPLETED
                        record.kind == RecordKind.INTERNAL_TRANSFER -> ImportStatus.INTERNAL
                        key in savedKeys -> ImportStatus.ALREADY_IMPORTED
                        else -> status(outcomes[key])
                    }
                planned += Planned(table, record, key, assignmentKey, status, offset)
            }
            outcomes.values.filterIsInstance<ImportOutcome.Ambiguous>().distinctBy { it.group }.forEach { ambiguous ->
                val members = fresh.filter { (key, _) -> (outcomes[key] as? ImportOutcome.Ambiguous)?.group == ambiguous.group }
                val first = members.first().second
                groups +=
                    AmbiguousGroup(
                        ambiguous.group,
                        assignment.exchange,
                        first.kind,
                        first.atMillis,
                        first.legs.filter { it.role != io.valroot.core.ledger.MovementRole.FEE }.associate { it.coin to it.amount },
                        ambiguous.records,
                        ambiguous.candidates,
                    )
            }
        }
        val counts = planned.groupingBy { it.status }.eachCount()
        groups.filter { it.key !in choices.decisions }.takeIf { it.isNotEmpty() }?.let {
            blockers += "Decide ${it.size} group(s) of records that may already be in the account"
        }
        val leftOut = listOf(ImportStatus.EXCLUDED, ImportStatus.CONFLICT, ImportStatus.UNRESOLVED).sumOf { counts[it] ?: 0 }
        if (leftOut > 0 && !choices.acknowledgeExclusions) blockers += "Confirm that $leftOut row(s) are left out"
        if (planned.isEmpty() && blockers.isEmpty()) blockers += "The files hold no records to import"
        return ImportPreview(
            previewTables,
            assignments,
            counts,
            groups,
            futuresFlows(planned.map { it.record }),
            blockers,
            choices,
            planned,
        )
    }

    /** The exchange user ID of an assignment: the files', the new account's, or the chosen account's. */
    private fun uidOf(
        assignment: ImportAssignment,
        choices: ImportChoices,
    ): String? =
        assignment.uid
            ?: (choices.accounts[assignment.key] as? AccountChoice.New)?.uid
            ?: assignment.account?.let { account ->
                app.exchanges
                    .targets(assignment.exchange)
                    .firstOrNull { it.first == account }
                    ?.second
            }

    private fun status(outcome: ImportOutcome?): ImportStatus =
        when (outcome) {
            is ImportOutcome.New -> ImportStatus.NEW
            is ImportOutcome.Same -> ImportStatus.MATCHED
            is ImportOutcome.Ambiguous -> ImportStatus.AMBIGUOUS
            is ImportOutcome.Excluded -> ImportStatus.EXCLUDED
            ImportOutcome.Conflict -> ImportStatus.CONFLICT
            ImportOutcome.CoinPending, null -> ImportStatus.COIN_PENDING
        }

    /** Each record's key: its report, its content and its place among equal records of that report. */
    private fun keyRecords(read: List<Triple<DetectedTable, ImportedRecord, ImportOffset?>>): List<Pair<String, ImportedRecord>> {
        val result = mutableListOf<Pair<String, ImportedRecord>>()
        read.groupBy { it.first.table }.forEach { (_, ofTable) ->
            val records = ofTable.map { it.second }
            val keys = ImportValues.keys(records.first().report, records.map(::content))
            records.forEachIndexed { index, record -> result += keys[index] to record }
        }
        // The same record in two files of a batch (overlapping exports) is one record.
        return result.distinctBy { it.first }
    }

    private fun content(record: ImportedRecord): String =
        listOf(
            record.uid.orEmpty(),
            record.kind.name,
            record.status.name,
            record.atMillis.toString(),
            record.legs.joinToString(";") { "${it.coin}=${it.amount}/${it.role}" },
            record.wallet.orEmpty(),
            record.namespace.orEmpty(),
            record.nativeId.orEmpty(),
            record.details
                .sortedByKeys()
                .entries
                .joinToString(";") { "${it.key}=${it.value}" },
        ).joinToString("|")

    private fun futuresFlows(records: List<ImportedRecord>): List<FuturesFlow> =
        records
            .filter { it.moves && (it.kind == RecordKind.FUTURES_IN || it.kind == RecordKind.FUTURES_OUT) }
            .flatMap { record -> record.legs.map { Triple(record.exchange, it.coin, record.kind to it.amount) } }
            .groupBy { it.first to it.second }
            .map { (key, list) ->
                fun sum(kind: RecordKind) =
                    list
                        .filter { it.third.first == kind }
                        .map { magnitude(it.third.second) }
                        .fold(ZERO) { a, b -> a + b }
                FuturesFlow(key.first, key.second, sum(RecordKind.FUTURES_IN), sum(RecordKind.FUTURES_OUT))
            }

    /** Coins moved across each exchange's futures boundary by the imports in [account]'s history. */
    fun futuresFlows(account: AccountId): List<FuturesFlow> {
        val kind =
            ExchangeKind.entries.firstOrNull { kind -> app.exchanges.targets(kind).any { it.first == account } } ?: return emptyList()
        val rows = app.exchanges.savedRows(account)
        val uid =
            app.exchanges
                .targets(kind)
                .single { it.first == account }
                .second
        val assets = app.exchanges.historyAssets(kind, account)
        val outcomes = app.exchanges.decodeWith(kind, account, uid, emptyList(), assets).imports
        val seen = mutableSetOf<String>()
        return rows
            .filter { it.stream == ImportedEvents.STREAM }
            .filter { row ->
                val activity =
                    when (val outcome = outcomes[row.key]) {
                        is ImportOutcome.New -> outcome.activity.value
                        is ImportOutcome.Same -> outcome.activity.value
                        else -> null
                    }
                activity != null && seen.add(activity)
            }.map { ImportedEvents.parse(it.payload) }
            .filter { it.moves && (it.kind == RecordKind.FUTURES_IN || it.kind == RecordKind.FUTURES_OUT) }
            .flatMap { record -> record.legs.map { Triple(it.coin, record.kind, it.amount) } }
            .groupBy { it.first }
            .map { (coin, list) ->
                fun sum(of: RecordKind) = list.filter { it.second == of }.map { magnitude(it.third) }.fold(ZERO) { a, b -> a + b }
                FuturesFlow(kind, coin, sum(RecordKind.FUTURES_IN), sum(RecordKind.FUTURES_OUT))
            }
    }

    /**
     * Saves [preview]'s batch in one transaction: new accounts, source rows with their cells, the imported events and
     * the user's decisions, then derives each account's history again and checks the outcome against the preview. Any
     * difference (for example records an API refresh added since) rolls everything back.
     */
    fun commit(preview: ImportPreview): ImportReceipt {
        require(preview.canCommit) { preview.blockers.first() }
        val batch = "import-${now()}-${kotlin.random.Random.nextLong().toULong().toString(16)}"
        val byAssignment = preview.planned.groupBy { it.assignment }
        // New coins are registered before the transaction, which must not nest another.
        val newAccounts =
            preview.assignments
                .filter { it.account == null }
                .associate { it.key to AccountId("${it.exchange.id}-import-${kotlin.random.Random.nextLong().toULong().toString(16)}") }
        val accounts = preview.assignments.associate { it.key to (it.account ?: newAccounts.getValue(it.key)) }
        val assets =
            preview.assignments.associate { assignment ->
                val account = accounts.getValue(assignment.key)
                val coins = byAssignment[assignment.key].orEmpty().flatMap { it.record.legs.map { leg -> leg.coin } }.toSet()
                assignment.key to
                    if (assignment.account != null) {
                        app.exchanges.historyAssets(assignment.exchange, account, coins)
                    } else {
                        app.exchanges.assets(assignment.exchange.id, coins)
                    }
            }
        val uids =
            preview.assignments.associate {
                it.key to
                    requireNotNull(uidOf(it, preview.choices)) { "The account of ${it.key} has no user ID" }
            }
        val receipt =
            db.transaction {
                val catalog = ownership.loadInTransaction()
                var owners = catalog.owners
                var addedAccounts = catalog.accounts
                var targets = catalog.targets
                preview.assignments.filter { it.account == null }.forEach { assignment ->
                    val choice = preview.choices.accounts[assignment.key] as AccountChoice.New
                    val owner =
                        choice.owner ?: owners.firstOrNull()?.id ?: OwnerId("owner-$batch").also { owners = owners + Owner(it, "Personal") }
                    val account = newAccounts.getValue(assignment.key)
                    addedAccounts = addedAccounts + Account(account, owner, choice.label, AccountKind.ACCOUNT)
                    targets = targets +
                        TrackingTarget(
                            TrackingTargetId("${assignment.exchange.id}-${account.value}"),
                            account,
                            TargetIdentity(assignment.exchange.network, "uid", choice.uid),
                            TargetRole.LEAF,
                            choice.label,
                        )
                }
                if (newAccounts.isNotEmpty()) {
                    ownership.replaceInTransaction(
                        catalog.copy(owners = owners, accounts = addedAccounts, targets = targets),
                    )
                }
                repository.saveBatch(
                    batch,
                    now(),
                    preview.planned.map { ImportedFile(it.table.table.file, it.table.table.fileHash) }.distinct(),
                    accounts.values.distinct(),
                )
                val finalStatuses = mutableMapOf<String, ImportStatus>()
                preview.assignments.forEach { assignment ->
                    val account = accounts.getValue(assignment.key)
                    val uid = uids.getValue(assignment.key)
                    val saved =
                        app.exchanges
                            .savedRows(account)
                            .filter { it.stream == ImportedEvents.STREAM }
                            .map { it.key }
                            .toSet()
                    byAssignment[assignment.key].orEmpty().filter { decodeRecord(it.record) }.forEach { planned ->
                        if (planned.key !in saved) {
                            val source =
                                app.exchanges.ingestRecord(
                                    assignment.exchange,
                                    account,
                                    uid,
                                    "${ImportedEvents.STREAM}:${planned.key}",
                                    ImportedEvents.payload(planned.record),
                                    "${planned.record.report}-${planned.table.report.version}",
                                    origin = "file",
                                )
                            app.exchanges.saveRowInTransaction(
                                account,
                                ExchangeRow(
                                    ImportedEvents.STREAM,
                                    planned.key,
                                    planned.record.atMillis,
                                    ImportedEvents.payload(planned.record),
                                    source.value,
                                ),
                            )
                        }
                        repository.link(account, planned.key, batch)
                    }
                    preview.choices.decisions.forEach { (group, decision) ->
                        app.exchanges.saveRowInTransaction(account, ExchangeRow(ImportedEvents.DECISIONS, group, 0, decision.name, batch))
                    }
                    val decoded = app.exchanges.rederiveInTransaction(assignment.exchange, account, uid, assets.getValue(assignment.key))
                    byAssignment[assignment.key].orEmpty().forEach { planned ->
                        val outcome = decoded.imports[planned.key]
                        val final =
                            if (planned.status in setOf(ImportStatus.NEW, ImportStatus.MATCHED, ImportStatus.COIN_PENDING) &&
                                outcome != null
                            ) {
                                status(outcome)
                            } else {
                                planned.status
                            }
                        if (final == ImportStatus.AMBIGUOUS && planned.status != ImportStatus.AMBIGUOUS) {
                            throw ImportFileException("The account changed since the preview; preview the import again")
                        }
                        finalStatuses[planned.key] = final
                    }
                }
                preview.planned.forEachIndexed { index, planned ->
                    repository.saveSource(
                        batch,
                        index,
                        ImportSourceRow(
                            planned.record.source.file,
                            planned.record.source.member,
                            planned.record.source.sheet,
                            planned.record.details["rows"]
                                ?.split(
                                    ',',
                                )?.mapNotNull { it.toIntOrNull() } ?: listOf(planned.record.source.row),
                            planned.record.report,
                            accounts.getValue(planned.assignment),
                            planned.key,
                            finalStatuses[planned.key] ?: planned.status,
                            planned.record.cells,
                        ),
                        planned.record.source.fileHash,
                        planned.table.report.version,
                        planned.offset?.minutes,
                    )
                }
                repository.receipts().single { it.id == batch }
            }
        invalidate()
        return receipt
    }

    fun receipts(): List<ImportReceipt> = repository.receipts()

    fun sources(batch: String): List<ImportSourceRow> = repository.sources(batch)

    /**
     * Undoes [batch]: removes the events only it supported and derives the affected accounts' history again. Events
     * another batch or the API also supports stay, with their notes and classifications; the receipt stays, marked undone.
     */
    fun undo(batch: String): ImportReceipt {
        val receipt = repository.receipts().singleOrNull { it.id == batch } ?: throw IllegalArgumentException("Unknown import")
        require(receipt.undoneAtMillis == null) { "This import was already undone" }
        val kinds =
            receipt.accounts.associateWith { account ->
                ExchangeKind.entries.firstNotNullOf { kind ->
                    app.exchanges.targets(kind).firstOrNull { it.first == account }?.let {
                        kind to
                            it.second
                    }
                }
            }
        val assets = kinds.mapValues { (account, kindUid) -> app.exchanges.historyAssets(kindUid.first, account) }
        val undone =
            db.transaction {
                repository.unlink(batch).forEach { (account, keys) ->
                    keys.forEach { app.exchanges.deleteRowInTransaction(account, ImportedEvents.STREAM, it) }
                }
                repository.markUndone(batch, now())
                kinds.forEach { (account, kindUid) ->
                    app.exchanges.rederiveInTransaction(kindUid.first, account, kindUid.second, assets.getValue(account))
                }
                repository.receipts().single { it.id == batch }
            }
        invalidate()
        return undone
    }

    private fun magnitude(value: ExactDecimal) = if (value.coefficient.sign < 0) ExactDecimal(-value.coefficient, value.scale) else value

    private companion object {
        val ZERO = ExactDecimal.parse("0")
    }
}
