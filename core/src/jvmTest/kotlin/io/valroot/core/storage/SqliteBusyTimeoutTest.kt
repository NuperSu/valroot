package io.valroot.core.storage

import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class SqliteBusyTimeoutTest {
    /** A file from an earlier version is in rollback-journal mode; opening it beside a writer must wait, not fail. */
    @Test fun openingARollbackJournalFileBesideAWriterWaitsToSwitchItToWal() {
        val file = Files.createTempFile("valroot-journal", ".sqlite")
        try {
            BundledSqliteDatabase(file.toString()).use { writer ->
                writer.query("PRAGMA journal_mode = DELETE")
                writer.execute("CREATE TABLE t (v TEXT)")
                val started = java.util.concurrent.CountDownLatch(1)
                val holder =
                    thread {
                        writer.transaction {
                            writer.execute("INSERT INTO t VALUES ('a')")
                            started.countDown()
                            Thread.sleep(1_000)
                        }
                    }
                started.await()
                BundledSqliteDatabase(file.toString()).use { reader ->
                    assertEquals("1", reader.query("SELECT COUNT(*) FROM t").single().single())
                    assertEquals("wal", reader.query("PRAGMA journal_mode").single().single())
                }
                holder.join()
            }
        } finally {
            listOf("", "-wal", "-shm", "-journal").forEach {
                Files.deleteIfExists(
                    java.nio.file.Path
                        .of(file.toString() + it),
                )
            }
        }
    }

    @Test fun aSecondConnectionWaitsForAWriterInsteadOfFailing() {
        val file = Files.createTempFile("valroot-busy", ".sqlite")
        try {
            BundledSqliteDatabase(file.toString()).use { first ->
                BundledSqliteDatabase(file.toString()).use { second ->
                    assertEquals("30000", second.query("PRAGMA busy_timeout").single().single())
                    first.execute("CREATE TABLE t (v TEXT)")
                    val writer =
                        thread {
                            first.transaction {
                                first.execute("INSERT INTO t VALUES ('a')")
                                Thread.sleep(500)
                            }
                        }
                    Thread.sleep(100)
                    // Without a busy timeout this write fails immediately with SQLITE_BUSY.
                    second.execute("INSERT INTO t VALUES ('b')")
                    writer.join()
                    assertEquals(2, second.query("SELECT v FROM t").size)
                }
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
