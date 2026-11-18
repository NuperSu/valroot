package io.valroot.core.application

import io.valroot.core.bitcoin.BITCOIN_MAINNET
import io.valroot.core.bitcoin.BitcoinProviderException
import io.valroot.core.bitcoin.BitcoinProviderFailure
import io.valroot.core.bitcoin.EsploraTransport
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
import io.valroot.core.sync.JobState
import io.valroot.testing.utxoResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BitcoinMultiAddressAccountTest {
    private val addresses = listOf("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", "1BoatSLRHtKNngkdXEeobR76b53LETtpyT")
    private val account = AccountId("wallet")
    private val hash = "b".repeat(64)

    private fun application(db: BundledSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { 15_552_000_000L }
        val owner = OwnerId("owner")
        app.accounts.saveOwner(Owner(owner, "Owner"))
        app.accounts.saveAccount(Account(account, owner, "Wallet", AccountKind.WALLET))
        addresses.forEachIndexed { index, address ->
            app.accounts.saveTarget(
                TrackingTarget(
                    TrackingTargetId("address-$index"),
                    account,
                    TargetIdentity(BITCOIN_MAINNET, "address", address),
                    TargetRole.LEAF,
                    "Address $index",
                ),
            )
        }
        app.sources.setConsent(true)
        return app
    }

    private inner class Provider(
        private val count: Int,
    ) {
        val historyCalls = mutableListOf<String>()
        var failAddress: String? = null

        private fun txid(
            address: String,
            index: Int,
        ): String = (addresses.indexOf(address) * 100 + index + 1).toString(16).padStart(64, '0')

        private fun transaction(
            address: String,
            index: Int,
        ): String {
            val value = (addresses.indexOf(address) + 1) * 100
            val output = """{"scriptpubkey_address":"$address","value":$value}"""
            val status = """{"confirmed":true,"block_time":1,"block_height":1,"block_hash":"$hash"}"""
            return """{"txid":"${txid(address, index)}","vin":[{"is_coinbase":true}],"vout":[$output],"fee":0,"status":$status}"""
        }

        val transport =
            EsploraTransport { path ->
                when {
                    path == "/blocks/tip/hash" || path.startsWith("/block-height/") -> hash
                    path == "/block/$hash" -> """{"id":"$hash","height":110}"""
                    path.startsWith("/address/") -> {
                        val address = path.removePrefix("/address/").substringBefore('/')
                        check(address in addresses)
                        when {
                            path.endsWith("/txs/mempool") -> "[]"
                            path.contains("/txs/chain") -> {
                                historyCalls.add(path)
                                if (address == failAddress) throw BitcoinProviderException(BitcoinProviderFailure.TIMEOUT)
                                val cursor = path.substringAfter("/txs/chain/", "")
                                val start = if (cursor.isEmpty()) 0 else (0 until count).single { txid(address, it) == cursor } + 1
                                (start until minOf(start + 25, count)).joinToString(",", "[", "]") { transaction(address, it) }
                            }
                            path.endsWith("/utxo") -> utxoResponse(address, (0 until count).map { transaction(address, it) })
                            else -> """{"chain_stats":{"tx_count":$count},"mempool_stats":{"tx_count":0}}"""
                        }
                    }
                    else -> error("Unexpected request $path")
                }
            }
    }

    @Test
    fun connectionRefreshRequestsOnlySelectedAccountAndRetainsOtherHoldings() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = application(db)
            val other = AccountId("other")
            app.accounts.saveAccount(Account(other, OwnerId("owner"), "Other", AccountKind.WALLET))
            val second = app.accounts.targets(account).single { it.identity.canonicalKey == addresses[1] }
            app.accounts.saveTarget(second.copy(accountId = other))
            val provider = Provider(1)
            assertEquals(listOf(JobState.COMPLETED, JobState.COMPLETED), app.bitcoin.refresh(provider.transport))
            val globallyAccepted = app.bitcoin.acceptedScan()
            val holdings = app.snapshot().balances.quantities
            provider.historyCalls.clear()
            val plan = HostRefresh(app, account = account)
            val connectors =
                plan.connectors(
                    bitcoin = { BitcoinConnector(provider.transport, app.sources.endpoint()) },
                    refreshCatalog = { error("No exchange catalog request expected") },
                )
            assertEquals(listOf(JobState.COMPLETED), plan.run(connectors, false) {}.single().jobs)
            assertTrue(provider.historyCalls.all { addresses[0] in it })
            assertTrue(provider.historyCalls.isNotEmpty())
            assertEquals(globallyAccepted, app.bitcoin.acceptedScan())
            assertEquals(holdings, app.snapshot().balances.quantities)
            assertTrue(
                app.analytics
                    .accountOverview(null)
                    .single { it.account.id == other }
                    .accepted != null,
            )
        }
    }

    @Test fun connectionRefreshPreservesAnUnfinishedGlobalScan() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = application(db)
            val other = AccountId("other")
            app.accounts.saveAccount(Account(other, OwnerId("owner"), "Other", AccountKind.WALLET))
            val second = app.accounts.targets(account).single { it.identity.canonicalKey == addresses[1] }
            app.accounts.saveTarget(second.copy(accountId = other))
            val provider = Provider(26)
            provider.failAddress = addresses[1]
            assertEquals(listOf(JobState.COMPLETED, JobState.READY), app.bitcoin.refresh(provider.transport))
            val failure =
                assertFailsWith<BitcoinProviderException> {
                    app.bitcoin.refresh(EsploraTransport { error("Unexpected request") }, accounts = setOf(account))
                }
            assertEquals(BitcoinProviderFailure.SCAN_SCOPE_PENDING, failure.reason)
            provider.failAddress = null
            assertEquals(listOf(JobState.COMPLETED, JobState.COMPLETED), app.bitcoin.refresh(provider.transport))
            assertEquals(1, provider.historyCalls.count { it == "/address/${addresses[0]}/txs/chain" })
        }
    }

    private fun assertComplete(
        db: BundledSqliteDatabase,
        app: PortfolioApplication,
        count: Int,
    ) {
        assertEquals(
            (count * 300).toString(),
            app
                .snapshot()
                .balances.quantities.values
                .single()
                .toString(),
        )
        assertEquals((count * 2).toString(), db.query("SELECT COUNT(*) FROM ledger_activities").single().single())
        assertEquals((count * 2).toString(), db.query("SELECT COUNT(*) FROM raw_records").single().single())
        assertEquals(0, app.bitcoin.utxoReview().total)
        assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
    }

    @Test
    fun equalCountsUnderOneAccountFetchBothAddressesAndRepeatWithoutDuplicates() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = application(db)
            val provider = Provider(1)
            repeat(2) {
                assertEquals(listOf(JobState.COMPLETED, JobState.COMPLETED), app.bitcoin.refresh(provider.transport))
                assertComplete(db, app, 1)
            }
            // The repeat finds unchanged counts for both addresses, so it fetches no history again.
            addresses.forEach { address ->
                assertEquals(1, provider.historyCalls.count { it == "/address/$address/txs/chain" })
            }
        }
    }

    @Test
    fun secondAddressFailureResumesWithItsOwnPaginationAfterApplicationRecreation() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = application(db)
            val provider = Provider(26)
            provider.failAddress = addresses[1]
            assertEquals(listOf(JobState.COMPLETED, JobState.READY), app.bitcoin.refresh(provider.transport))
            assertTrue(
                app
                    .snapshot()
                    .balances.quantities
                    .isEmpty(),
            )
            provider.failAddress = null
            val resumed = PortfolioApplication(db) { 15_552_000_000L }
            assertEquals(listOf(JobState.COMPLETED, JobState.COMPLETED), resumed.bitcoin.refresh(provider.transport))
            assertComplete(db, resumed, 26)
            addresses.forEach { address ->
                assertTrue(provider.historyCalls.any { it == "/address/$address/txs/chain" })
                assertTrue(provider.historyCalls.any { it.startsWith("/address/$address/txs/chain/") })
            }
            assertEquals(listOf(JobState.COMPLETED, JobState.COMPLETED), resumed.bitcoin.refresh(provider.transport))
            assertComplete(db, resumed, 26)
        }
    }
}
