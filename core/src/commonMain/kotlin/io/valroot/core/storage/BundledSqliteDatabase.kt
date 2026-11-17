package io.valroot.core.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * One connection to a SQLite file, on every platform. SQLite itself is bundled with the app (androidx.sqlite's bundled
 * driver), so Android, desktop and iOS run the same SQLite version with the same settings:
 * - write-ahead logging, so readers continue while another connection writes; the last connection to close checkpoints
 *   and removes the `-wal` file, so a closed database is one self-contained file;
 * - a [LOCK_WAIT_MILLIS] wait for another connection's lock (the screen, workers and refreshers each hold their own
 *   connection to the same file) instead of failing at once;
 * - transactions that take the write lock when they begin (`BEGIN IMMEDIATE`), so two writers queue instead of
 *   failing when a reader upgrades.
 *
 * Calls are serialized; a transaction's block may call [execute] and [query] on the same thread. Every value is read
 * as text, as SQLite converts it.
 */
class BundledSqliteDatabase(
    path: String,
) : SqliteDatabase {
    private val lock = SynchronizedObject()
    private val connection: SQLiteConnection = BundledSQLiteDriver().open(path)
    private var closed = false
    private var inTransaction = false

    init {
        try {
            connection.execSQL("PRAGMA busy_timeout = $LOCK_WAIT_MILLIS")
            if (path != ":memory:") {
                useWriteAheadLog()
                connection.execSQL("PRAGMA synchronous = NORMAL")
            }
        } catch (failure: Throwable) {
            connection.close()
            throw failure
        }
    }

    override fun execute(
        sql: String,
        arguments: List<String?>,
    ) = synchronized(lock) {
        open().prepare(sql).use { statement ->
            statement.bind(arguments)
            while (statement.step()) Unit
        }
    }

    override fun query(
        sql: String,
        arguments: List<String?>,
    ): List<List<String?>> =
        synchronized(lock) {
            open().prepare(sql).use { statement ->
                statement.bind(arguments)
                buildList {
                    while (statement.step()) {
                        add((0 until statement.getColumnCount()).map { if (statement.isNull(it)) null else statement.getText(it) })
                    }
                }
            }
        }

    override fun <T> transaction(block: () -> T): T =
        synchronized(lock) {
            check(!inTransaction) { "Nested transactions are unsupported" }
            open().execSQL("BEGIN IMMEDIATE")
            inTransaction = true
            try {
                val result = block()
                connection.execSQL("COMMIT")
                result
            } catch (failure: Throwable) {
                // SQLite may already have rolled back (a full disk, for one); the original failure is what matters.
                runCatching { connection.execSQL("ROLLBACK") }
                throw failure
            } finally {
                inTransaction = false
            }
        }

    /** Writes a transactionally consistent copy of the database to the new file [destination]. */
    fun snapshot(destination: String) =
        synchronized(lock) {
            check(!inTransaction) { "Snapshot requires no active transaction" }
            execute("VACUUM INTO ?", listOf(destination))
        }

    override fun close() =
        synchronized(lock) {
            if (!closed) {
                closed = true
                connection.close()
            }
        }

    /**
     * Switches a file still in rollback-journal mode (one written by an earlier version) to write-ahead logging. The
     * switch needs the file to itself, and SQLite refuses it at once, without the busy wait, while another connection is
     * writing; so a refusal is retried for up to [LOCK_WAIT_MILLIS]. A file already in WAL mode needs no switch.
     */
    private fun useWriteAheadLog() {
        var waited = 0L
        while (true) {
            try {
                val mode = connection.prepare("PRAGMA journal_mode").use { if (it.step()) it.getText(0) else "" }
                if (mode.equals("wal", ignoreCase = true)) return
                connection.prepare("PRAGMA journal_mode = WAL").use { it.step() }
                return
            } catch (failure: androidx.sqlite.SQLiteException) {
                val locked = failure.message.orEmpty().let { "locked" in it || "busy" in it.lowercase() || "code: 5" in it }
                if (!locked || waited >= LOCK_WAIT_MILLIS) throw failure
                sleepMillis(RETRY_MILLIS)
                waited += RETRY_MILLIS
            }
        }
    }

    private fun open(): SQLiteConnection = if (closed) throw DatabaseClosed() else connection

    private fun SQLiteStatement.bind(arguments: List<String?>) =
        arguments.forEachIndexed { index, value -> if (value == null) bindNull(index + 1) else bindText(index + 1, value) }

    companion object {
        /** How long a statement waits for another connection's lock before failing. */
        const val LOCK_WAIT_MILLIS = 30_000L
        private const val RETRY_MILLIS = 100L
    }
}
