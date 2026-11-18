package io.valroot.core.application

import io.valroot.core.evm.EVM_FAMILY
import io.valroot.core.exchange.ExchangeKind
import io.valroot.core.model.AccountId
import io.valroot.core.model.OwnerId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.ownership.TargetIdentity
import io.valroot.core.ownership.TargetRole
import io.valroot.core.ownership.TrackingTarget
import io.valroot.core.storage.BundledSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistorySourcesTest {
    private fun app(db: BundledSqliteDatabase) =
        PortfolioApplication(db) { 1_800_000_000_000L }.also {
            it.snapshot()
            it.accounts.saveOwner(Owner(OwnerId("o"), "Owner"))
        }

    private fun PortfolioApplication.track(
        id: String,
        identity: TargetIdentity,
    ) = accounts.saveAccountAndTarget(
        Account(AccountId(id), OwnerId("o"), id, AccountKind.WALLET),
        TrackingTarget(TrackingTargetId(id), AccountId(id), identity, TargetRole.LEAF, id),
    )

    @Test fun anEvmOnlyPortfolioHasAHistorySourceWithoutBitcoinConsent() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = app(db)
            assertFalse(app.sources.anyHistorySource())
            app.track("evm", TargetIdentity(EVM_FAMILY, "address", "0x" + "a".repeat(40)))
            assertFalse(app.sources.anyHistorySource(), "EVM needs its own consent")
            app.evm.setConsent(true)
            assertFalse(app.sources.consent())
            assertTrue(app.sources.anyHistorySource())
        }
    }

    @Test fun anExchangeOnlyPortfolioHasAHistorySource() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = app(db)
            app.track("mexc", TargetIdentity(ExchangeKind.MEXC.network, "uid", "1"))
            assertTrue(app.sources.anyHistorySource())
        }
    }
}
