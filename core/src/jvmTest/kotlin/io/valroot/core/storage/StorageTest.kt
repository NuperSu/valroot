package io.valroot.core.storage

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class StorageTest {
    private fun observation(
        id: String,
        source: String,
        checksum: String = sha256("data"),
        payload: String = "data",
    ) = RawObservation(id, "btc", "txid", "mainnet", source, "2026-01-01T00:00:00Z", payload, "1", checksum, "context")

    @Test
    fun largeRevisionRepeatIngestionPreservesPayloadAndRejectsConflicts() {
        val path = Files.createTempFile("valroot-large-revision", ".sqlite")
        val payload = "\u20ac".repeat(1_100_000)
        val record = observation("large", "provider-a", sha256(payload), payload)
        try {
            Store(BundledSqliteDatabase(path.toString())).use { it.ingest(record) }
            BundledSqliteDatabase(path.toString()).use { db ->
                Store(db).use { store ->
                    store.ingest(record)
                    store.ingest(record.copy(sourceId = "provider-b"))
                    assertEquals("1", db.query("SELECT COUNT(*) FROM raw_revisions").single().single())
                    assertEquals(2, store.observations().size)
                    assertEquals("1", db.query("SELECT payload=? FROM raw_revisions", listOf(payload)).single().single())
                    assertFails { store.ingest(record.copy(payloadVersion = "2", sourceId = "provider-c")) }
                    assertEquals(2, store.observations().size)
                    db.execute("UPDATE raw_revisions SET payload=?", listOf(payload + "corrupt"))
                    assertFails { store.ingest(record.copy(sourceId = "provider-c")) }
                    assertEquals(2, store.observations().size)
                    assertEquals("ok", db.query("PRAGMA integrity_check").single().single())
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun reopenAndRetainDistinctProvenanceAndRevisions() {
        val path = Files.createTempFile("valroot", ".sqlite")
        try {
            Store(BundledSqliteDatabase(path.toString())).use { store ->
                store.ingest(observation("1", "provider-a"))
                assertEquals("1", store.ingest(observation("2", "provider-b")).value)
                store.ingest(observation("1", "provider-a"))
                store.ingest(observation("1", "provider-a", sha256("updated"), "updated"))
                assertEquals(1, store.records().size)
                assertEquals(2, store.revisions().size)
                assertEquals(3, store.observations().size)
                assertFails { store.ingest(observation("1", "provider-a", sha256("data"), "collision")) }
            }
            Store(BundledSqliteDatabase(path.toString())).use { store ->
                assertEquals(2, store.revisions().size)
                assertEquals(3, store.observations().size)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun rollbackAndForeignKeyEnforcement() {
        BundledSqliteDatabase(":memory:").use { db ->
            Store(db).use { store ->
                assertFails {
                    db.transaction {
                        store.ingestInTransaction(observation("1", "a"))
                        error("crash")
                    }
                }
                assertEquals(emptyList(), store.records())
                assertFails { db.execute("INSERT INTO raw_revisions VALUES ('missing', 'hash', 'payload', '1')") }
            }
        }
    }

    @Test
    fun rejectsFutureSchemaAndCorruptDatabase() {
        val path = Files.createTempFile("valroot-schema", ".sqlite")
        try {
            BundledSqliteDatabase(path.toString()).use { it.execute("PRAGMA user_version = 999") }
            assertFails { BundledSqliteDatabase(path.toString()).use { Store(it) } }
            Files.writeString(path, "not a database")
            assertFails { BundledSqliteDatabase(path.toString()).use { Store(it) } }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun serializesConcurrentIngestion() {
        BundledSqliteDatabase(":memory:").use { db ->
            Store(db).use { store ->
                val threads =
                    (0 until 20).map { i ->
                        Thread { store.ingest(observation("1", "provider-$i")) }.also { it.start() }
                    }
                threads.forEach { it.join() }
                assertEquals(1, store.records().size)
                assertEquals(20, store.observations().size)
            }
        }
    }

    @Test
    fun snapshotReopensWithCommittedData() {
        val original = Files.createTempFile("valroot-source", ".sqlite")
        val backup = original.resolveSibling(original.fileName.toString() + ".backup")
        try {
            BundledSqliteDatabase(original.toString()).use { db ->
                val store = Store(db)
                store.ingest(observation("1", "a"))
                db.snapshot(backup.toString())
            }
            Store(BundledSqliteDatabase(backup.toString())).use { store ->
                assertEquals(1, store.records().size)
                assertEquals(1, store.observations().size)
            }
        } finally {
            Files.deleteIfExists(original)
            Files.deleteIfExists(backup)
        }
    }

    @Test
    fun missingTableInCurrentSchemaIsRejected() {
        BundledSqliteDatabase(":memory:").use { db ->
            Store(db)
            db.execute("DROP TABLE ownership_tags")
            assertFails { Store(db) }
        }
    }
}
