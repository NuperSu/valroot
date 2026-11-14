package io.valroot.core.storage

import java.sql.DriverManager

class JvmSqliteDatabase(
    path: String,
) : SqliteDatabase {
    private val connection =
        DriverManager.getConnection("jdbc:sqlite:$path").apply {
            // The desktop opens several connections to one file; a writer makes the others wait instead of failing at once.
            createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
            // Write-ahead logging lets readers continue while a sync writes, as Android's driver already does. The last
            // connection to close checkpoints and removes the -wal file, so a closed database is one self-contained file.
            if (path != ":memory:") createStatement().use { it.executeQuery("PRAGMA journal_mode = WAL").close() }
        }
    private val lock = Any()

    override fun execute(
        sql: String,
        arguments: List<String?>,
    ) = synchronized(lock) {
        open().prepareStatement(sql).use { statement ->
            arguments.forEachIndexed { i, value -> statement.setString(i + 1, value) }
            statement.execute()
        }
        Unit
    }

    override fun query(
        sql: String,
        arguments: List<String?>,
    ): List<List<String?>> =
        synchronized(lock) {
            open().prepareStatement(sql).use { statement ->
                arguments.forEachIndexed { i, value -> statement.setString(i + 1, value) }
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add((1..result.metaData.columnCount).map { result.getString(it) })
                    }
                }
            }
        }

    override fun <T> transaction(block: () -> T): T =
        synchronized(lock) {
            check(open().autoCommit) { "Nested transactions are unsupported" }
            connection.autoCommit = false
            try {
                val result = block()
                connection.commit()
                result
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }

    /** Create a transactionally consistent snapshot in a new file. */
    fun snapshot(destination: String) =
        synchronized(lock) {
            check(open().autoCommit) { "Snapshot requires no active transaction" }
            open().prepareStatement("VACUUM INTO ?").use { statement ->
                statement.setString(1, destination)
                statement.execute()
            }
        }

    override fun close() =
        synchronized(lock) {
            closed = true
            connection.close()
        }

    private var closed = false

    private fun open(): java.sql.Connection = if (closed) throw DatabaseClosed() else connection
}
