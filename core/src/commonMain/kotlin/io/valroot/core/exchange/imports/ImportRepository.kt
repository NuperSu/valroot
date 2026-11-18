package io.valroot.core.exchange.imports

import io.valroot.core.exchange.ExchangeRow
import io.valroot.core.exchange.ImportedEvents
import io.valroot.core.exchange.bybit.BybitExports
import io.valroot.core.model.AccountId
import io.valroot.core.storage.SqliteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One file of an import batch, as the receipt lists it. */
data class ImportedFile(
    val name: String,
    val hash: String,
)

/** A committed import batch: what it read and what became of it, and whether it was undone. */
data class ImportReceipt(
    val id: String,
    val importedAtMillis: Long,
    val files: List<ImportedFile>,
    val accounts: List<AccountId>,
    val counts: Map<ImportStatus, Int>,
    val reports: Map<String, Int>,
    val undoneAtMillis: Long?,
)

/** What became of one source row of an import. */
enum class ImportStatus {
    /** A new economic event. */
    NEW,

    /** The same event as one the account had (API or an earlier import); now further evidence for it. */
    MATCHED,

    /** Already imported by an earlier batch. */
    ALREADY_IMPORTED,

    /** Order summaries: kept as evidence, never trades. */
    SUPPORTING,

    /** Balance snapshots: compared, never deposits. */
    CHECKPOINT,

    /** Futures and derivative rows kept for later futures accounting. */
    DEFERRED,

    /** Pending, cancelled or failed: moves nothing. */
    NOT_COMPLETED,

    /** Moves between the account's own wallets. */
    INTERNAL,

    /** An operation Valroot does not know yet. */
    UNRESOLVED,

    /** Part of a group that waits for the user: the same event as one the account has, or a distinct one. */
    AMBIGUOUS,

    /** Excluded by the user. */
    EXCLUDED,

    /** Waiting for the user's coin choice. */
    COIN_PENDING,

    /** A statement row that disagrees with the detailed records of its moment. */
    CONFLICT,
}

/** One saved source row of an import batch. */
data class ImportSourceRow(
    val file: String,
    val member: String?,
    val sheet: String?,
    val rows: List<Int>,
    val report: String,
    val account: AccountId?,
    val key: String?,
    val status: ImportStatus,
    val cells: List<String>,
)

private const val BATCHES = "SELECT id, imported_at, files, accounts, undone_at FROM exchange_import_batches ORDER BY imported_at DESC, id"

/** Import batches, their source rows and which batches support each imported event; all part of encrypted backups. */
internal class ImportRepository(
    private val db: SqliteDatabase,
) {
    init {
        db.execute(
            "CREATE TABLE IF NOT EXISTS exchange_import_batches (id TEXT PRIMARY KEY, imported_at INTEGER NOT NULL, " +
                "files TEXT NOT NULL, accounts TEXT NOT NULL, undone_at INTEGER)",
        )
        db.execute(
            "CREATE TABLE IF NOT EXISTS exchange_import_sources (batch TEXT NOT NULL, seq INTEGER NOT NULL, file_name TEXT NOT NULL, " +
                "file_hash TEXT NOT NULL, member TEXT, sheet TEXT, rows TEXT NOT NULL, report TEXT NOT NULL, " +
                "parser_version INTEGER NOT NULL, time_offset INTEGER, account TEXT, record_key TEXT, status TEXT NOT NULL, cells TEXT NOT NULL, PRIMARY KEY(batch, seq))",
        )
        db.execute(
            "CREATE TABLE IF NOT EXISTS exchange_import_events (account TEXT NOT NULL, record_key TEXT NOT NULL, batch TEXT NOT NULL, " +
                "PRIMARY KEY(account, record_key, batch))",
        )
    }

    fun saveBatch(
        id: String,
        at: Long,
        files: List<ImportedFile>,
        accounts: List<AccountId>,
    ) = db.execute(
        "INSERT INTO exchange_import_batches VALUES (?, ?, ?, ?, NULL)",
        listOf(
            id,
            at.toString(),
            JsonArray(files.map { JsonArray(listOf(JsonPrimitive(it.name), JsonPrimitive(it.hash))) }).toString(),
            accounts.joinToString(",") { it.value },
        ),
    )

    fun saveSource(
        batch: String,
        seq: Int,
        source: ImportSourceRow,
        fileHash: String,
        parserVersion: Int,
        offsetMinutes: Int?,
    ) = db.execute(
        "INSERT INTO exchange_import_sources VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        listOf(
            batch,
            seq.toString(),
            source.file,
            fileHash,
            source.member,
            source.sheet,
            source.rows.joinToString(","),
            source.report,
            parserVersion.toString(),
            offsetMinutes?.toString(),
            source.account?.value,
            source.key,
            source.status.name,
            JsonArray(source.cells.map(::JsonPrimitive)).toString(),
        ),
    )

    fun link(
        account: AccountId,
        key: String,
        batch: String,
    ) = db.execute("INSERT OR IGNORE INTO exchange_import_events VALUES (?, ?, ?)", listOf(account.value, key, batch))

    /** Removes [batch]'s links and returns the events no other batch supports any more, by account. */
    fun unlink(batch: String): Map<AccountId, List<String>> {
        val linked =
            db
                .query("SELECT account, record_key FROM exchange_import_events WHERE batch=?", listOf(batch))
                .map { AccountId(it[0]!!) to it[1]!! }
        db.execute("DELETE FROM exchange_import_events WHERE batch=?", listOf(batch))
        return linked
            .filter { (account, key) ->
                db
                    .query("SELECT 1 FROM exchange_import_events WHERE account=? AND record_key=? LIMIT 1", listOf(account.value, key))
                    .isEmpty()
            }.groupBy({ it.first }, { it.second })
    }

    /** Maps statement copies using their ordinal within each original source table. Missing provenance stays separate. */
    fun aliases(
        account: AccountId,
        rows: List<ExchangeRow>,
    ): Map<String, String> {
        val records = rows.filter { it.stream == ImportedEvents.STREAM }.associate { it.key to ImportedEvents.parse(it.payload) }
        if (records.size < 2) return emptyMap()
        val pinned =
            records
                .mapNotNull { (key, record) ->
                    record.details[StatementIdentity.CANONICAL_KEY]?.takeIf { it != key && it in records }?.let { key to it }
                }.toMap()
        val result = pinned.toMutableMap()
        val pinnedKeys = pinned.keys + pinned.values
        val identities = records.mapValues { StatementIdentity.of(it.value) }
        val evidence =
            db
                .query(
                    "SELECT s.record_key, s.batch, s.file_hash, s.member, s.sheet, s.rows FROM exchange_import_sources s " +
                        "JOIN exchange_import_batches b ON b.id=s.batch WHERE s.account=? AND b.undone_at IS NULL " +
                        "ORDER BY b.imported_at, b.rowid, s.seq",
                    listOf(account.value),
                ).filter { identities[it[0]] != null }
                .groupBy { identities[it[0]] }
        val order = records.keys.withIndex().associate { it.value to it.index }
        evidence.values.forEach { group ->
            val ordinals = mutableMapOf<String, MutableSet<Int>>()
            group.groupBy { it.subList(1, 5) }.values.forEach { table ->
                table
                    .distinctBy { it[0] }
                    .sortedBy { source ->
                        source[5].orEmpty().substringBefore(',').toIntOrNull() ?: Int.MAX_VALUE
                    }.forEachIndexed { ordinal, source ->
                        ordinals.getOrPut(source[0]!!) { mutableSetOf() } += ordinal
                    }
            }
            val candidates = ordinals.filterValues { it.size == 1 }.keys - pinnedKeys
            candidates.groupBy { ordinals.getValue(it).single() }.values.forEach { copies ->
                val canonical = copies.minBy { order.getValue(it) }
                copies.filter { it != canonical }.forEach { result[it] = canonical }
            }
        }
        return result
    }

    /** Persists alias identity and gives its canonical event support from every active alias batch. */
    fun supportAliases(
        account: AccountId,
        rows: List<ExchangeRow>,
    ) {
        aliases(account, rows).forEach { (alias, canonical) ->
            val row = rows.single { it.stream == ImportedEvents.STREAM && it.key == alias }
            db.execute(
                "UPDATE exchange_rows SET payload=? WHERE account=? AND stream=? AND key=?",
                listOf(aliasPayload(row, canonical), account.value, ImportedEvents.STREAM, alias),
            )
            db
                .query("SELECT batch FROM exchange_import_events WHERE account=? AND record_key=?", listOf(account.value, alias))
                .forEach { link(account, canonical, it[0]!!) }
        }
    }

    fun annotateAliases(
        account: AccountId,
        rows: List<ExchangeRow>,
    ): List<ExchangeRow> {
        val aliases = aliases(account, rows)
        return rows.map { row ->
            aliases[row.key]
                ?.takeIf { row.stream == ImportedEvents.STREAM }
                ?.let { row.copy(payload = aliasPayload(row, it)) } ?: row
        }
    }

    /** Fill evidence follows its original receipt while the wallet log keeps its stable import key. */
    fun annotateBybitFills(
        account: AccountId,
        rows: List<ExchangeRow>,
    ): List<ExchangeRow> {
        val missing =
            rows
                .asSequence()
                .filter { it.stream == ImportedEvents.STREAM }
                .mapNotNull { row ->
                    val record = ImportedEvents.parse(row.payload)
                    row.key.takeIf {
                        BybitExports.isUnifiedReport(record.report) &&
                            record.details["bybitWalletLog"] != null &&
                            record.kind in setOf(RecordKind.TRADE, RecordKind.CONVERT) &&
                            BybitExports.fillFields.none(record.details::containsKey)
                    }
                }.toSet()
        if (missing.isEmpty()) return rows
        val evidence = mutableMapOf<String, Map<String, String>>()
        db
            .query(
                "SELECT s.record_key, s.report, s.cells FROM exchange_import_sources s " +
                    "JOIN exchange_import_batches b ON b.id=s.batch WHERE s.account=? AND b.undone_at IS NULL " +
                    "ORDER BY b.imported_at, b.rowid, s.seq",
                listOf(account.value),
            ).forEach { (key, report, rawCells) ->
                if (key == null || key !in missing || key in evidence || report == null || rawCells == null) return@forEach
                val cells = Json.parseToJsonElement(rawCells).jsonArray.map { it.jsonPrimitive.content }
                val fields = BybitExports.fillEvidence(report, cells)
                if (fields.isNotEmpty()) evidence[key] = fields
            }
        return rows.map { row ->
            val fields = evidence[row.key]?.takeIf { row.stream == ImportedEvents.STREAM } ?: return@map row
            val payload = Json.parseToJsonElement(row.payload).jsonObject
            val details = payload.getValue("details").jsonObject + fields.mapValues { JsonPrimitive(it.value) }
            row.copy(payload = JsonObject(payload + ("details" to JsonObject(details))).toString())
        }
    }

    private fun aliasPayload(
        row: ExchangeRow,
        canonical: String,
    ): String {
        val payload = Json.parseToJsonElement(row.payload).jsonObject
        val details = payload.getValue("details").jsonObject + (StatementIdentity.CANONICAL_KEY to JsonPrimitive(canonical))
        return JsonObject(payload + ("details" to JsonObject(details))).toString()
    }

    fun markUndone(
        batch: String,
        at: Long,
    ) = db.execute("UPDATE exchange_import_batches SET undone_at=? WHERE id=?", listOf(at.toString(), batch))

    fun receipts(): List<ImportReceipt> =
        db.query(BATCHES).map { row ->
            val id = row[0]!!
            val sources = db.query("SELECT status, report FROM exchange_import_sources WHERE batch=?", listOf(id))
            ImportReceipt(
                id = id,
                importedAtMillis = row[1]!!.toLong(),
                files =
                    Json.parseToJsonElement(row[2]!!).jsonArray.map { file ->
                        val (name, hash) = file.jsonArray.map { it.jsonPrimitive.content }
                        ImportedFile(name, hash)
                    },
                accounts = row[3]!!.split(',').filter { it.isNotEmpty() }.map(::AccountId),
                counts = sources.groupingBy { ImportStatus.valueOf(it[0]!!) }.eachCount(),
                reports = sources.groupingBy { it[1]!! }.eachCount(),
                undoneAtMillis = row[4]?.toLong(),
            )
        }

    fun sources(batch: String): List<ImportSourceRow> =
        db
            .query(
                "SELECT file_name, member, sheet, rows, report, account, record_key, status, cells FROM exchange_import_sources " +
                    "WHERE batch=? ORDER BY seq",
                listOf(batch),
            ).map { row ->
                ImportSourceRow(
                    file = row[0]!!,
                    member = row[1],
                    sheet = row[2],
                    rows = row[3]!!.split(',').mapNotNull { it.toIntOrNull() },
                    report = row[4]!!,
                    account = row[5]?.let(::AccountId),
                    key = row[6],
                    status = ImportStatus.valueOf(row[7]!!),
                    cells = Json.parseToJsonElement(row[8]!!).jsonArray.map { it.jsonPrimitive.content },
                )
            }
}
