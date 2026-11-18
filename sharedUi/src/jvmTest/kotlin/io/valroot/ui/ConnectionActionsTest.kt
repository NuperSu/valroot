package io.valroot.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.valroot.core.application.AccountOverview
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.bitcoin.BITCOIN_MAINNET
import io.valroot.core.evm.EVM_FAMILY
import io.valroot.core.exchange.ExchangeKind
import io.valroot.core.model.AccountId
import io.valroot.core.model.NetworkId
import io.valroot.core.model.OwnerId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.TargetIdentity
import io.valroot.core.ownership.TargetRole
import io.valroot.core.ownership.TrackingTarget
import io.valroot.core.storage.BundledSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ConnectionActionsTest {
    @Test fun providersFollowConnectionSources() {
        val account = Account(AccountId("account"), OwnerId("owner"), "Connection", AccountKind.WALLET)

        fun overview(network: NetworkId? = null) =
            AccountOverview(
                account,
                listOfNotNull(network).map { source ->
                    TrackingTarget(
                        TrackingTargetId("source"),
                        account.id,
                        TargetIdentity(source, "address", "public"),
                        TargetRole.LEAF,
                        "Source",
                    )
                },
                0,
                emptyList(),
                emptyList(),
                null,
                false,
            )
        assertEquals(emptyList(), accountProviderLabels(overview(), "https://bitcoin.example"))
        assertEquals(listOf("Alchemy"), accountProviderLabels(overview(EVM_FAMILY), "https://bitcoin.example"))
        assertEquals(listOf("Binance"), accountProviderLabels(overview(ExchangeKind.BINANCE.network), "https://bitcoin.example"))
        assertEquals(listOf("https://bitcoin.example"), accountProviderLabels(overview(BITCOIN_MAINNET), "https://bitcoin.example"))
    }

    @Test fun syncConnectionPassesItsAccountToHost() {
        BundledSqliteDatabase(":memory:").use { db ->
            TestHost().use { delegate ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.demo.seed()
                val account =
                    app
                        .snapshot()
                        .ownership.accounts
                        .single()
                        .id
                app.accounts.saveTarget(
                    TrackingTarget(
                        TrackingTargetId("source"),
                        account,
                        TargetIdentity(BITCOIN_MAINNET, "address", "1BoatSLRHtKNngkdXEeobR76b53LETtpyT"),
                        TargetRole.LEAF,
                        "Bitcoin",
                    ),
                )
                var requested: AccountId? = null
                val host =
                    object : PlatformActions by delegate {
                        override fun refreshAccount(
                            account: AccountId,
                            onResult: (String) -> Unit,
                        ) {
                            requested = account
                            onResult("Synced")
                        }
                    }
                runDesktopComposeUiTest(width = 600, height = 800) {
                    setContent {
                        ValrootTheme(AppearanceTheme.DARK) {
                            AccountsWorkspace(app, host, app.snapshot(), null, 0, SyncStatus(), {}, {}, {}, {}, {}, compact = true)
                        }
                    }
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodes(
                            androidx.compose.ui.test
                                .hasTestTag("connection-sync-${account.value}"),
                        ).fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("connection-sync-${account.value}").performClick()
                    assertEquals(account, requested)
                    assertEquals(0, delegate.refreshRequests)
                }
            }
        }
    }
}
