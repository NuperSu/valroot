package io.valroot.core.application

import io.valroot.core.model.AccountId
import io.valroot.core.storage.BundledSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class LegacyBackupTest {
    /** `polion-format-3.polion` was exported by the app under its former name, Polion, before the rename. */
    @Test fun aBackupMadeUnderTheFormerNameRestoresWithItsArchivedAccounts() {
        val archive = requireNotNull(javaClass.getResource("/backup/polion-format-3.polion")).readBytes()
        BundledSqliteDatabase(":memory:").use { live ->
            val app = PortfolioApplication(live) { 1_800_000_000_000L }
            BundledSqliteDatabase(":memory:").use { staging ->
                app.backup.restoreToStaging(staging, archive, "legacy-fixture".toCharArray())
                val restored = PortfolioApplication(staging) { 1_800_000_000_000L }
                val tags =
                    restored
                        .snapshot()
                        .ownership.accounts
                        .associate { it.id to it.tags }
                assertEquals(emptySet(), tags.getValue(AccountId("kept")))
                assertEquals(setOf("valroot:archived"), tags.getValue(AccountId("old")))
            }
        }
    }
}
