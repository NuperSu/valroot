package io.valroot.core.solana

import fr.acinq.bitcoin.Base58
import io.valroot.core.application.ActivityFilter
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.SourceRefreshOptions
import io.valroot.core.ledger.ActivityStatus
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A fake Helius serving synthetic history and chain state; every address and signature is generated, none is real. */
class SolanaConnectorTest {
    private fun address(seed: Int) = Base58.encode(ByteArray(32) { (seed * 7 + it).toByte() })

    private fun signature(seed: Int) = Base58.encode(ByteArray(64) { (seed * 11 + it).toByte() })

    private val wallet = address(1)
    private val other = address(2)
    private val ata = address(3)
    private val usdc = address(4)
    private val otherAta = address(5)
    private val nft = address(6)
    private val nftAccount = address(7)
    private val account = AccountId("sol")
    private val rent = 2_039_280L

    private data class Token(
        val index: Int,
        val mint: String,
        val owner: String,
        val amount: Long,
    )

    private data class Entry(
        val slot: Long,
        val index: Int?,
        val json: JsonObject,
    )

    private fun entry(
        seed: Int,
        slot: Long,
        index: Int?,
        accounts: List<String>,
        pre: List<Long>,
        post: List<Long>,
        preTokens: List<Token> = emptyList(),
        postTokens: List<Token> = emptyList(),
        instructions: List<String> = emptyList(),
        blockTime: Boolean = true,
        version: String = "0",
    ): Entry {
        fun tokens(list: List<Token>) =
            list.joinToString(",", "[", "]") {
                """{"accountIndex":${it.index},"mint":"${it.mint}","owner":"${it.owner}","programId":"x",""" +
                    """"uiTokenAmount":{"amount":"${it.amount}","decimals":6}}"""
            }
        val keys = accounts.joinToString(",", "[", "]") { """{"pubkey":"$it","signer":false,"writable":true,"source":"transaction"}""" }
        val top = instructions.ifEmpty { listOf("""{"program":"system","programId":"x","parsed":{"type":"transfer","info":{}}}""") }
        val text =
            """{"slot":$slot,"transactionIndex":$index,"blockTime":${if (blockTime) 1_700_000_000 + slot else null},"version":$version,
              "transaction":{"signatures":["${signature(
                seed,
            )}"],"message":{"accountKeys":$keys,"instructions":${top.joinToString(",", "[", "]")}}},
              "meta":{"err":null,"fee":5000,"preBalances":${pre.joinToString(
                ",",
                "[",
                "]",
            )},"postBalances":${post.joinToString(",", "[", "]")},
                "preTokenBalances":${tokens(preTokens)},"postTokenBalances":${tokens(postTokens)},"innerInstructions":[]}}"""
        return Entry(slot, index, Json.parseToJsonElement(text).jsonObject)
    }

    private val receiveSol = entry(1, 10, 0, listOf(other, wallet), listOf(5_000_000_000, 0), listOf(3_999_995_000, 1_000_000_000))
    private val receiveUsdc =
        entry(
            2,
            20,
            0,
            listOf(wallet, ata, otherAta),
            listOf(1_000_000_000, 0, rent),
            listOf(1_000_000_000 - rent - 5_000, rent, rent),
            preTokens = listOf(Token(2, usdc, other, 500)),
            postTokens = listOf(Token(1, usdc, wallet, 150), Token(2, usdc, other, 350)),
            instructions =
                listOf(
                    """{"program":"spl-token","programId":"x","parsed":{"type":"initializeAccount3",""" +
                        """"info":{"account":"$ata","mint":"$usdc","owner":"$wallet"}}}""",
                ),
        )
    private val afterUsdc = 1_000_000_000 - rent - 5_000
    private val sendSol =
        entry(3, 20, 1, listOf(wallet, other), listOf(afterUsdc, 1), listOf(afterUsdc - 100_000_000 - 5_000, 100_000_001))
    private val walletLamports = afterUsdc - 100_000_000 - 5_000

    /** Serves history pages of [pageSize] in chain order, account state and mint metadata, counting each method. */
    private inner class FakeHelius(
        var entries: List<Entry>,
        private val pageSize: Int = 2,
    ) {
        var slot = 1_000L
        var chain = mutableMapOf<String, String>()
        var tokenAccounts = mutableMapOf<String, String>()
        var assets = mutableMapOf<String, String>()
        var blockTimes = mutableMapOf<Long, Long>()
        val failingBlockTimes = mutableSetOf<Long>()
        val methods = mutableListOf<String>()

        fun hold(
            address: String,
            lamports: Long,
            token: Pair<String, Long>? = null,
        ) {
            val data =
                token?.let { (mint, amount) ->
                    """{"program":"spl-token","parsed":{"type":"account","info":{"mint":"$mint","owner":"$wallet",""" +
                        """"tokenAmount":{"amount":"$amount","decimals":6}}}}"""
                } ?: "[\"\",\"base64\"]"
            chain[address] = """{"lamports":$lamports,"owner":"x","data":$data}"""
            if (token != null) tokenAccounts[address] = chain.getValue(address)
        }

        fun post(body: String): String {
            val request = Json.parseToJsonElement(body).jsonObject
            val method = request.getValue("method").jsonPrimitive.content
            methods += method
            val params = request.getValue("params")
            if (method == Helius.BLOCK_TIME &&
                params.jsonArray[0]
                    .jsonPrimitive.content
                    .toLong() in failingBlockTimes
            ) {
                return """{"jsonrpc":"2.0","id":1,"error":{"code":-32009,"message":"Slot skipped"}}"""
            }
            val result =
                when (method) {
                    Helius.SLOT -> slot.toString()
                    Helius.BLOCK_TIME ->
                        blockTimes[
                            params.jsonArray[0]
                                .jsonPrimitive.content
                                .toLong(),
                        ]?.toString() ?: "null"
                    Helius.TOKEN_ACCOUNTS -> {
                        val program =
                            params.jsonArray[1]
                                .jsonObject
                                .getValue("programId")
                                .jsonPrimitive.content
                        val listed = if (program == SolanaOwnership.TOKEN_PROGRAM_IDS.first()) tokenAccounts else emptyMap()
                        """{"context":{"slot":$slot},"value":${listed.entries.joinToString(",", "[", "]") {
                            """{"pubkey":"${it.key}","account":${it.value}}"""
                        }}}"""
                    }
                    Helius.ACCOUNTS -> {
                        val keys = params.jsonArray[0].jsonArray.map { it.jsonPrimitive.content }
                        """{"context":{"slot":$slot},"value":${keys.joinToString(",", "[", "]") { chain[it] ?: "null" }}}"""
                    }
                    Helius.ASSETS -> {
                        val ids =
                            params.jsonObject
                                .getValue("ids")
                                .jsonArray
                                .map { it.jsonPrimitive.content }
                        ids.joinToString(",", "[", "]") { id ->
                            assets[id]?.let { """{"id":"$id","interface":"$it","content":{"metadata":{"name":"Coin","symbol":"CN"}}}""" }
                                ?: "null"
                        }
                    }
                    Helius.HISTORY -> {
                        val token =
                            params.jsonArray[1]
                                .jsonObject["paginationToken"]
                                ?.jsonPrimitive
                                ?.content
                                ?.split(':')
                        val after =
                            entries
                                .sortedWith(compareBy({ it.slot }, { it.index ?: -1 }))
                                .filter { token == null || it.slot to (it.index ?: -1) > token[0].toLong() to token[1].toInt() }
                        val page = after.take(pageSize)
                        val next = if (after.size > pageSize) page.last().let { "\"${it.slot}:${it.index ?: -1}\"" } else "null"
                        """{"data":${page.joinToString(",", "[", "]") { it.json.toString() }},"paginationToken":$next}"""
                    }
                    else -> error("Unexpected method $method")
                }
            return """{"jsonrpc":"2.0","id":1,"result":$result}"""
        }
    }

    private operator fun Pair<Long, Int>.compareTo(other: Pair<Long, Int>) = compareValuesBy(this, other, { it.first }, { it.second })

    private fun setUp(db: BundledSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { 1_800_000_000_000L }
        app.snapshot()
        app.accounts.saveOwner(Owner(OwnerId("owner"), "Owner"))
        app.accounts.saveAccountAndTarget(
            Account(account, OwnerId("owner"), "Solana", AccountKind.WALLET),
            TrackingTarget(
                TrackingTargetId("sol"),
                account,
                TargetIdentity(SolanaIds.NETWORK_ID, "address", wallet),
                TargetRole.LEAF,
                "SOL",
            ),
        )
        app.solana.setConsent(true)
        return app
    }

    private fun fakeWithChain(pageSize: Int = 2) =
        FakeHelius(listOf(receiveSol, receiveUsdc, sendSol), pageSize).apply {
            hold(wallet, walletLamports)
            hold(ata, rent, usdc to 150)
            assets[usdc] = "FungibleToken"
        }

    private fun activities(app: PortfolioApplication) =
        app
            .snapshot(ActivityFilter(limit = 500))
            .activities
            .map { it.activity }
            .filter { it.status == ActivityStatus.CONFIRMED && it.id.value.startsWith(SolanaIds.TRANSACTION_PREFIX) }

    private fun totals(app: PortfolioApplication) =
        activities(app)
            .flatMap { it.movements }
            .groupBy { it.amount.assetId.value }
            .mapValues { (_, list) ->
                list.fold(java.math.BigInteger.ZERO) { sum, m ->
                    sum +
                        java.math.BigInteger(m.amount.atomicUnits.toString())
                }
            }

    @Test fun aRefreshStoresRawHistoryProjectsItAndMatchesTheChain() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val helius = fakeWithChain()
            val outcome = SolanaConnector(helius::post).refresh(app, SourceRefreshOptions())
            assertEquals(listOf(JobState.COMPLETED), outcome.jobs)
            assertEquals(3, activities(app).size)
            // SOL held includes the rent locked in the wallet's own token account.
            assertEquals(
                mapOf(SolanaIds.NATIVE.value to (walletLamports + rent).toBigInteger(), SolanaIds.token(usdc).value to 150.toBigInteger()),
                totals(app),
            )
            val state = app.solana.state(wallet)
            assertEquals(SolanaService.MATCHED, state.balanceState)
            assertNull(state.coverage)
            assertEquals(helius.slot, app.solana.stream(wallet).scannedThrough)
            // A second refresh reads only what is new and changes nothing.
            val before = helius.methods.count { it == Helius.HISTORY }
            SolanaConnector(helius::post).refresh(app, SourceRefreshOptions())
            assertEquals(1, helius.methods.count { it == Helius.HISTORY } - before)
            assertEquals(3, activities(app).size)
            assertEquals(3, app.solana.transactions(wallet).size)
            assertEquals(SolanaService.MATCHED, app.solana.state(wallet).balanceState)
        }
    }

    @Test fun anInterruptedReadResumesWithSameSlotTransactionsAcrossPagesAndTheCheckWaits() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val helius = fakeWithChain(pageSize = 2)
            // Budget for the snapshot (3), the target slot (1), one page and its mint lookup.
            val outcome = SolanaConnector(helius::post, maxRequests = 6).refresh(app, SourceRefreshOptions())
            assertTrue(outcome.notes.single().contains("still being read"))
            assertEquals(2, activities(app).size)
            assertNull(app.solana.stream(wallet).scannedThrough)
            // History has not reached the snapshot, so the check waits instead of reporting a difference.
            assertEquals(SolanaService.PENDING, app.solana.state(wallet).balanceState)
            assertEquals(1, app.solana.snapshot(wallet).size)

            // The second transaction of slot 20 is on the next page; resuming orders it after the first.
            SolanaConnector(helius::post).refresh(app, SourceRefreshOptions())
            assertEquals(3, activities(app).size)
            assertEquals(SolanaService.MATCHED, app.solana.state(wallet).balanceState)
            assertTrue(app.solana.snapshot(wallet).isEmpty())
            assertEquals(1, helius.methods.count { it == Helius.ACCOUNTS }, "the saved snapshot is the target until history reaches it")
        }
    }

    @Test fun anEmptyHistoryIsReadThroughItsTargetSlot() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val helius = FakeHelius(emptyList())
            SolanaConnector(helius::post).refresh(app, SourceRefreshOptions())
            val stream = app.solana.stream(wallet)
            assertEquals(helius.slot, stream.scannedThrough)
            assertNull(stream.cursor)
            assertEquals(SolanaService.MATCHED, app.solana.state(wallet).balanceState)
        }
    }

    @Test fun aChainBalanceHistoryDoesNotExplainIsADifference() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val helius = fakeWithChain()
            helius.hold(wallet, walletLamports + 1)
            SolanaConnector(helius::post).refresh(app, SourceRefreshOptions())
            val state = app.solana.state(wallet)
            assertEquals(SolanaService.DIFFERENT, state.balanceState)
            assertTrue(state.balanceDifference!!.startsWith("SOL in"))
        }
    }

    @Test fun nftsAreLeftOutOnceClassifiedAndTheirActivitiesSuperseded() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val receiveNft =
                entry(
                    4,
                    30,
                    0,
                    listOf(other, nftAccount),
                    listOf(10_000, rent),
                    listOf(5_000, rent),
                    preTokens = listOf(Token(1, nft, wallet, 0)),
                    postTokens = listOf(Token(1, nft, wallet, 1)),
                )
            val helius = fakeWithChain().apply { entries = entries + receiveNft }
            helius.hold(nftAccount, rent, nft to 1)
            SolanaConnector(helius::post).refresh(app, SourceRefreshOptions())
            // Without recognizable metadata the mint is tracked as fungible and noted.
            assertEquals(4, activities(app).size)
            assertTrue(
                app.solana
                    .state(wallet)
                    .coverage!!
                    .contains("1 token not yet classified"),
            )

            helius.assets[nft] = "V1_NFT"
            SolanaConnector(helius::post).refresh(app, SourceRefreshOptions())
            assertEquals(3, activities(app).size)
            assertTrue(SolanaIds.token(nft).value !in totals(app))
            assertEquals("1 NFT not tracked", app.solana.state(wallet).coverage)
            // The NFT's account already held its rent when history first saw it, so that SOL is unexplained.
            assertEquals("History does not explain all SOL held", app.solana.state(wallet).balanceDifference)
        }
    }

    private fun Entry.without(field: String) =
        copy(
            json = JsonObject(json + (field to kotlinx.serialization.json.JsonNull)),
            index =
                if (field ==
                    "transactionIndex"
                ) {
                    null
                } else {
                    index
                },
        )

    @Test fun aMoreCompleteResponseForAStoredSignatureReplacesItAndIsReplayed() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val helius = fakeWithChain().apply { entries = listOf(receiveSol.without("transactionIndex"), receiveUsdc, sendSol) }
            SolanaConnector(helius::post).refresh(app, SourceRefreshOptions())
            val first = app.solana.transactions(wallet).first()
            assertNull(first.index)
            // Read again from the start: the response now has the position, so it is kept and replayed.
            helius.entries = listOf(receiveSol, receiveUsdc, sendSol)
            SolanaConnector(helius::post).refresh(app, SourceRefreshOptions(recoverHistory = true))
            assertEquals(
                0,
                app.solana
                    .transactions(wallet)
                    .first()
                    .index,
            )
            assertTrue(
                activities(app).single { it.id.value.contains(first.signature) }.orderingKey.endsWith("0000000000:${first.signature}"),
            )
            // An equally or less complete response never replaces the kept one, whatever its hash.
            helius.entries = listOf(receiveSol.without("transactionIndex"), receiveUsdc, sendSol)
            SolanaConnector(helius::post).refresh(app, SourceRefreshOptions(recoverHistory = true))
            assertEquals(
                0,
                app.solana
                    .transactions(wallet)
                    .first()
                    .index,
            )
            assertEquals(3, activities(app).size)
        }
    }

    @Test fun aMissingBlockTimeIsRecoveredWithoutStallingTheStream() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val undated = receiveSol.copy(json = JsonObject(receiveSol.json + ("blockTime" to kotlinx.serialization.json.JsonNull)))
            val helius = fakeWithChain().apply { entries = listOf(undated, receiveUsdc, sendSol) }
            // The node has no time for the block yet: the activity waits, the stream still advances, the check waits.
            val outcome = SolanaConnector(helius::post).refresh(app, SourceRefreshOptions())
            assertEquals(listOf(JobState.COMPLETED), outcome.jobs)
            assertEquals(2, activities(app).size)
            assertEquals(helius.slot, app.solana.stream(wallet).scannedThrough)
            assertEquals(SolanaService.PENDING, app.solana.state(wallet).balanceState)
            assertTrue(
                app.solana
                    .state(wallet)
                    .coverage!!
                    .contains("1 transaction waiting for its block time"),
            )
            // Once the block's time is known, the activity is dated and the saved snapshot matches.
            helius.blockTimes[receiveSol.slot] = 1_700_000_005L
            SolanaConnector(helius::post).refresh(app, SourceRefreshOptions())
            assertEquals(3, activities(app).size)
            assertEquals(
                1_700_000_005_000L,
                activities(app).single { it.orderingKey.contains(":00000000000000000010:") }.occurredAtEpochMillis,
            )
            assertEquals(SolanaService.MATCHED, app.solana.state(wallet).balanceState)
        }
    }

    @Test fun aReplayStoppedAtAnUnorderedSlotKeepsTheCheckPending() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            // Both slot-20 transactions without positions: projection stops at slot 20, below the snapshot.
            val helius =
                fakeWithChain(pageSize = 5).apply {
                    entries = listOf(receiveSol, receiveUsdc.without("transactionIndex"), sendSol.without("transactionIndex"))
                }
            SolanaConnector(helius::post).refresh(app, SourceRefreshOptions())
            assertEquals(helius.slot, app.solana.stream(wallet).scannedThrough)
            assertEquals(1, activities(app).size)
            assertEquals(SolanaService.PENDING, app.solana.state(wallet).balanceState)
            assertNull(app.solana.state(wallet).balanceDifference)
            assertEquals(1, app.solana.snapshot(wallet).size, "the snapshot is kept for when the order is known")
        }
    }

    @Test fun aTransactionInAnUnreadableVersionIsStoredAndReported() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val future = entry(9, 40, 0, listOf(other, wallet), listOf(10, 0), listOf(5, 5), version = "2")
            val helius = fakeWithChain().apply { entries = entries + future }
            val outcome = SolanaConnector(helius::post).refresh(app, SourceRefreshOptions())
            assertEquals(listOf(JobState.COMPLETED), outcome.jobs)
            assertEquals(helius.slot, app.solana.stream(wallet).scannedThrough)
            assertEquals(3, activities(app).size)
            assertTrue(
                app.solana
                    .state(wallet)
                    .coverage!!
                    .contains("1 transaction in a format Valroot cannot read yet"),
            )
            // It lies before the snapshot, so the ledger is partial there: the check waits and keeps its snapshot.
            assertEquals(SolanaService.PENDING, app.solana.state(wallet).balanceState)
            assertEquals(1, app.solana.snapshot(wallet).size)
        }
    }

    @Test fun aFailingBlockTimeLookupDoesNotHoldBackOtherSlots() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)

            fun undated(entry: Entry) = entry.copy(json = JsonObject(entry.json + ("blockTime" to kotlinx.serialization.json.JsonNull)))
            val helius = fakeWithChain().apply { entries = listOf(undated(receiveSol), undated(receiveUsdc), sendSol) }
            helius.failingBlockTimes += receiveSol.slot
            helius.blockTimes[receiveUsdc.slot] = 1_700_000_020L
            SolanaConnector(helius::post).refresh(app, SourceRefreshOptions())
            // The later slot's time was saved although the earlier lookup failed.
            assertEquals(2, activities(app).size)
            assertTrue(
                app.solana
                    .state(wallet)
                    .coverage!!
                    .contains("1 transaction waiting for its block time"),
            )
            // The failed slot is asked after slots that have not failed.
            assertEquals(listOf(15L, receiveSol.slot), app.solana.blockTimeOrder(listOf(receiveSol.slot, 15L)))
            helius.failingBlockTimes.clear()
            helius.blockTimes[receiveSol.slot] = 1_700_000_010L
            SolanaConnector(helius::post).refresh(app, SourceRefreshOptions())
            assertEquals(3, activities(app).size)
            assertEquals(SolanaService.MATCHED, app.solana.state(wallet).balanceState)
        }
    }
}
