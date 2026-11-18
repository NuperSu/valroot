package io.valroot.core.solana

import fr.acinq.bitcoin.Base58
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.AccountId
import io.valroot.core.model.AssetId
import io.valroot.core.model.SourceRecordId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic transactions in Helius's `jsonParsed` shape; every address and signature is generated, none is real. */
class SolanaDecoderTest {
    private fun address(seed: Int) = Base58.encode(ByteArray(32) { (seed * 7 + it).toByte() })

    private fun signature(seed: Int) = Base58.encode(ByteArray(64) { (seed * 11 + it).toByte() })

    private val wallet = address(1)
    private val other = address(2)
    private val ata = address(3)
    private val usdc = address(4)
    private val wsolAccount = address(5)
    private val account = AccountId("sol")
    private val source = SourceRecordId("raw")
    private val usdcAsset get() = SolanaIds.token(usdc)

    private data class Token(
        val index: Int,
        val mint: String,
        val owner: String?,
        val amount: Long,
    )

    private fun ins(
        program: String,
        type: String,
        vararg info: Pair<String, String>,
    ) = """{"program":"$program","programId":"x","parsed":{"type":"$type","info":{${info.joinToString(
        ",",
    ) { "\"${it.first}\":\"${it.second}\"" }}}}}"""

    private fun tx(
        seed: Int,
        accounts: List<String>,
        pre: List<Long>,
        post: List<Long>,
        preTokens: List<Token> = emptyList(),
        postTokens: List<Token> = emptyList(),
        instructions: List<String> = emptyList(),
        inner: List<String> = emptyList(),
        fee: Long = 5000,
        failed: Boolean = false,
        slot: Long = 100L + seed,
        index: Int? = 0,
    ): SolanaTransaction {
        fun tokens(list: List<Token>) =
            list.joinToString(",", "[", "]") {
                """{"accountIndex":${it.index},"mint":"${it.mint}",${it.owner?.let { o ->
                    "\"owner\":\"$o\","
                } ?: ""}"uiTokenAmount":{"amount":"${it.amount}","decimals":6}}"""
            }
        val keys = accounts.joinToString(",", "[", "]") { """{"pubkey":"$it","signer":false,"writable":true,"source":"transaction"}""" }
        val innerJson = if (inner.isEmpty()) "[]" else """[{"index":0,"instructions":${inner.joinToString(",", "[", "]")}}]"""
        val top = (instructions.ifEmpty { listOf(ins("system", "transfer")) }).joinToString(",", "[", "]")
        return SolanaTransaction.parse(
            """{"slot":$slot,"transactionIndex":${index ?: "null"},"blockTime":1700000000,"version":0,
              "transaction":{"signatures":["${signature(seed)}"],"message":{"accountKeys":$keys,"instructions":$top}},
              "meta":{"err":${if (failed) "{\"InstructionError\":[0,\"Custom\"]}" else "null"},"fee":$fee,
                "preBalances":${pre.joinToString(",", "[", "]")},"postBalances":${post.joinToString(",", "[", "]")},
                "preTokenBalances":${tokens(preTokens)},"postTokenBalances":${tokens(postTokens)},"innerInstructions":$innerJson}}""",
        )
    }

    private fun decode(
        transaction: SolanaTransaction,
        before: SolanaOwnership = SolanaOwnership(),
        watched: String = wallet,
    ) = SolanaDecoder.decode(watched, account, transaction, before, source)

    private fun amounts(transaction: SolanaTransaction) =
        decode(transaction)!!.movements.associate {
            (if (it.role == MovementRole.FEE) "fee" else it.amount.assetId.value) to
                it.amount.atomicUnits.toString()
        }

    @Test fun receivingAndSendingSolSplitTheFee() {
        val receive = tx(1, listOf(other, wallet), listOf(5_000_000_000, 1_000), listOf(3_999_995_000, 1_000_001_000))
        assertEquals(mapOf(SolanaIds.NATIVE.value to "1000000000"), amounts(receive))
        assertEquals(ActivityKind.DEPOSIT, decode(receive)!!.kind)
        val send = tx(2, listOf(wallet, other), listOf(5_000_000_000, 0), listOf(3_999_995_000, 1_000_000_000))
        assertEquals(mapOf(SolanaIds.NATIVE.value to "-1000000000", "fee" to "-5000"), amounts(send))
    }

    @Test fun aFailedTransactionCarriesOnlyItsFeeAndChangesNoOwnership() {
        val failed =
            tx(
                3,
                listOf(wallet, ata),
                listOf(10_000, 0),
                listOf(5_000, 0),
                inner = listOf(ins("spl-token", "initializeAccount3", "account" to ata, "mint" to usdc, "owner" to wallet)),
                failed = true,
            )
        assertEquals(mapOf("fee" to "-5000"), amounts(failed))
        assertTrue(SolanaOwnership().after(failed).tokenAccounts.isEmpty())
    }

    @Test fun aTokenArrivingAtTheWalletsTokenAccountCountsWithoutTheWalletInTheTransaction() {
        val arrival =
            tx(
                4,
                listOf(other, ata, address(9)),
                listOf(10_000, 2_039_280, 2_039_280),
                listOf(5_000, 2_039_280, 2_039_280),
                preTokens = listOf(Token(1, usdc, wallet, 0), Token(2, usdc, other, 500)),
                postTokens = listOf(Token(1, usdc, wallet, 150), Token(2, usdc, other, 350)),
            )
        assertEquals(mapOf(usdcAsset.value to "150"), amounts(arrival))
    }

    @Test fun rentLockedInOwnTokenAccountsStaysSolHeldAndComesBackOnClose() {
        // CPI: the associated-token program initializes the account inside the wallet's instruction.
        val create =
            tx(
                5,
                listOf(wallet, ata),
                listOf(10_000_000, 0),
                listOf(10_000_000 - 2_039_280 - 5_000, 2_039_280),
                postTokens = listOf(Token(1, usdc, wallet, 0)),
                instructions = listOf(ins("spl-associated-token-account", "create", "account" to ata, "mint" to usdc, "wallet" to wallet)),
                inner = listOf(ins("spl-token", "initializeAccount3", "account" to ata, "mint" to usdc, "owner" to wallet)),
            )
        assertEquals(mapOf("fee" to "-5000"), amounts(create))
        val owned = SolanaOwnership().after(create)
        assertEquals(wallet, owned.tokenAccounts.getValue(ata).owner)
        val close =
            tx(
                6,
                listOf(wallet, ata),
                listOf(1_000_000, 2_039_280),
                listOf(1_000_000 + 2_039_280 - 5_000, 0),
                preTokens = listOf(Token(1, usdc, wallet, 0)),
                instructions = listOf(ins("spl-token", "closeAccount", "account" to ata, "destination" to wallet, "owner" to wallet)),
            )
        assertEquals(mapOf("fee" to "-5000"), decode(close, owned)!!.movements.associate { "fee" to it.amount.atomicUnits.toString() })
        assertTrue(owned.after(close).tokenAccounts.isEmpty())
    }

    @Test fun wrappedSolOfBothNativeMintsCountsByLamportsOnly() {
        for (mint in SolanaIds.WRAPPED_SOL_MINTS) {
            val wrap =
                tx(
                    7,
                    listOf(wallet, wsolAccount),
                    listOf(5_000_000_000, 2_039_280),
                    listOf(3_999_995_000, 1_002_039_280),
                    preTokens = listOf(Token(1, mint, wallet, 0)),
                    postTokens = listOf(Token(1, mint, wallet, 1_000_000_000)),
                )
            assertEquals(mapOf("fee" to "-5000"), amounts(wrap), "wrapping with $mint moves no value")
        }
        // Lamports sent to the wrapped-SOL account before SyncNative are still the wallet's SOL.
        val unsynced =
            tx(
                8,
                listOf(wallet, wsolAccount),
                listOf(5_000_000_000, 2_039_280),
                listOf(3_999_995_000, 1_002_039_280),
                preTokens = listOf(Token(1, SolanaIds.WRAPPED_SOL_MINTS.first(), wallet, 0)),
                postTokens = listOf(Token(1, SolanaIds.WRAPPED_SOL_MINTS.first(), wallet, 0)),
            )
        assertEquals(mapOf("fee" to "-5000"), amounts(unsynced))
    }

    @Test fun anOwnerChangeMovesTheWholeBalanceEvenWithoutOwnerMetadata() {
        // Recorded owners (after December 2022).
        val recorded =
            tx(
                9,
                listOf(wallet, ata),
                listOf(10_000, 2_039_280),
                listOf(5_000, 2_039_280),
                preTokens = listOf(Token(1, usdc, wallet, 100)),
                postTokens = listOf(Token(1, usdc, other, 100)),
                instructions =
                    listOf(
                        ins(
                            "spl-token",
                            "setAuthority",
                            "account" to ata,
                            "authorityType" to "accountOwner",
                            "newAuthority" to other,
                        ),
                    ),
            )
        assertEquals(mapOf(usdcAsset.value to "-100", SolanaIds.NATIVE.value to "-2039280", "fee" to "-5000"), amounts(recorded))
        // Older history: owners come only from the replayed instructions.
        val before = SolanaOwnership(mapOf(ata to SolanaTokenAccount(wallet, usdc)))
        val old =
            tx(
                10,
                listOf(wallet, ata),
                listOf(10_000, 2_039_280),
                listOf(5_000, 2_039_280),
                preTokens = listOf(Token(1, usdc, null, 100)),
                postTokens = listOf(Token(1, usdc, null, 100)),
                instructions =
                    listOf(
                        ins(
                            "spl-token",
                            "setAuthority",
                            "account" to ata,
                            "authorityType" to "accountOwner",
                            "newAuthority" to other,
                        ),
                    ),
            )
        val movements =
            decode(old, before)!!.movements.associate {
                (if (it.role == MovementRole.FEE) "fee" else it.amount.assetId.value) to
                    it.amount.atomicUnits.toString()
            }
        assertEquals("-100", movements[usdcAsset.value])
        assertEquals(
            other,
            before
                .after(old)
                .tokenAccounts
                .getValue(ata)
                .owner,
        )
    }

    @Test fun aSwapIsATradeOfTradeLegs() {
        val swap =
            tx(
                11,
                listOf(wallet, ata, address(9)),
                listOf(5_000_000_000, 2_039_280, 2_039_280),
                listOf(3_999_995_000, 2_039_280, 2_039_280),
                preTokens = listOf(Token(1, usdc, wallet, 0), Token(2, usdc, other, 1_000)),
                postTokens = listOf(Token(1, usdc, wallet, 150), Token(2, usdc, other, 850)),
            )
        val activity = decode(swap)!!
        assertEquals(ActivityKind.TRADE, activity.kind)
        assertEquals(setOf(MovementRole.TRADE_LEG, MovementRole.FEE), activity.movements.map { it.role }.toSet())
    }

    @Test fun twoWatchedWalletsInOneTransactionAreTwoActivitiesOfOneSignature() {
        val send = tx(12, listOf(wallet, other), listOf(5_000_000_000, 0), listOf(3_999_995_000, 1_000_000_000))
        val sent = decode(send)!!
        val received = decode(send, watched = other)!!
        assertTrue(sent.id != received.id)
        assertEquals(SolanaIds.signatureOf(sent.id.value), SolanaIds.signatureOf(received.id.value))
        assertNull(decode(tx(13, listOf(address(8), address(9)), listOf(1, 1), listOf(1, 1), fee = 0)))
    }

    @Test fun replayFollowsChainOrderAndDefersSlotsWithoutAKnownOrder() {
        val init =
            tx(
                14,
                listOf(wallet, ata),
                listOf(10_000_000, 0),
                listOf(10_000_000 - 2_039_280 - 5_000, 2_039_280),
                inner = listOf(ins("spl-token", "initializeAccount3", "account" to ata, "mint" to usdc, "owner" to wallet)),
                slot = 500,
                index = 3,
            )
        val handOver =
            tx(
                15,
                listOf(wallet, ata),
                listOf(10_000, 2_039_280),
                listOf(5_000, 2_039_280),
                instructions =
                    listOf(
                        ins(
                            "spl-token",
                            "setAuthority",
                            "account" to ata,
                            "authorityType" to "accountOwner",
                            "newAuthority" to other,
                        ),
                    ),
                slot = 500,
                index = 7,
            )
        // Given in the wrong order, replay still applies the initialization first.
        val replay = SolanaDecoder.replay(wallet, account, listOf(handOver, init)) { source }
        assertNull(replay.deferredFromSlot)
        assertEquals(
            other,
            replay.ownership.tokenAccounts
                .getValue(ata)
                .owner,
        )
        // Without positions inside the slot, ownership from that slot on waits.
        val unordered = SolanaDecoder.replay(wallet, account, listOf(init.copy(index = null), handOver.copy(index = null))) { source }
        assertEquals(500L, unordered.deferredFromSlot)
        assertTrue(unordered.activities.isEmpty())
    }

    @Test fun assetIdsAreTheSolanaOnes() {
        assertEquals(AssetId("solana:mainnet/native"), SolanaIds.NATIVE)
    }
}
