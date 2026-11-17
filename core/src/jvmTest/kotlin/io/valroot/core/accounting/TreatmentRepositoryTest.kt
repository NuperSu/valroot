package io.valroot.core.accounting

import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.EventId
import io.valroot.core.model.Rational
import io.valroot.core.storage.BundledSqliteDatabase
import io.valroot.core.storage.Store
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class TreatmentRepositoryTest {
    @Test fun survivesReopen() {
        val path = Files.createTempFile("accounting", ".sqlite")
        try {
            val decisions =
                listOf(
                    Treatment.Acquire(EventId("a"), QuoteValue(AssetId("usd"), Rational.of("20"))),
                    Treatment.Dispose(EventId("b"), null, true),
                    Treatment.InternalTransfer(EventId("out"), EventId("in")),
                )
            BundledSqliteDatabase(path.toString()).use { db ->
                Store(db)
                TreatmentRepository(db).save(ActivityId("activity"), decisions)
            }
            BundledSqliteDatabase(path.toString()).use { db ->
                Store(db)
                assertEquals(decisions, TreatmentRepository(db).load(ActivityId("activity")))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
