package io.valroot.core.evm

import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.AccountId
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.SourceRecordId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvmDecoderTest {
    private val me = "0x1111111111111111111111111111111111111111"
    private val other = "0x2222222222222222222222222222222222222222"
    private val usdc = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"
    private val account = AccountId("evm")
    private val source = SourceRecordId("raw")

    private fun h(n: Int) = "0x" + n.toString(16).padStart(64, '0')

    private fun token(
        hash: String,
        logIndex: Int,
        from: String,
        to: String,
    ) = EvmTokenTransfer(
        hash,
        10,
        logIndex,
        1_000,
        from,
        to,
        usdc,
        EvmTokenStandard.ERC20,
        null,
        ExactInteger.parse("5"),
        6,
        "USDC",
        "USDC",
        false,
    )

    private fun sent(
        hash: String,
        position: Int,
        value: Long,
    ) = EvmTransaction(hash, 10, position, 1_000, me, other, ExactInteger.parse(value.toString()), ExactInteger.parse("1"), true)

    @Test fun aPurchaseEarlierInTheBlockSortsBeforeALaterSaleWhateverItsLogIndex() {
        // Bought at position 7 (its token log is index 30); sold at position 8.
        val groups =
            EvmDecoder.group(
                8453,
                listOf(sent(h(2), 8, 0)),
                emptyList(),
                listOf(token(h(1), 30, other, me), token(h(2), 31, me, other)),
                mapOf(h(1) to 7),
            )
        val keys = groups.associate { it.hash to EvmDecoder.decode(it, me, account, source)!!.orderingKey }
        assertTrue(keys.getValue(h(1)) < keys.getValue(h(2)), keys.toString())
        // Without a position, a transaction sorts after the known ones of its block instead of borrowing a log index.
        val unknown = EvmDecoder.group(8453, emptyList(), emptyList(), listOf(token(h(3), 0, other, me))).single()
        assertTrue(EvmDecoder.decode(unknown, me, account, source)!!.orderingKey.contains(":unknown:"))
    }

    @Test fun aSwapsLegsAreTradeLegs() {
        val swap = EvmDecoder.group(8453, listOf(sent(h(4), 1, 1_000)), emptyList(), listOf(token(h(4), 2, other, me))).single()
        val activity = EvmDecoder.decode(swap, me, account, source)!!
        assertEquals(ActivityKind.TRADE, activity.kind)
        assertEquals(listOf(MovementRole.TRADE_LEG, MovementRole.TRADE_LEG, MovementRole.FEE), activity.movements.map { it.role })
    }
}
