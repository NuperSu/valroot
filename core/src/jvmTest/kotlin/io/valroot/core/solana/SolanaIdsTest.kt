package io.valroot.core.solana

import io.valroot.core.market.CoingeckoCatalog
import io.valroot.core.model.TransactionIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Public program and mint addresses only; no personal data. */
class SolanaIdsTest {
    private val system = "11111111111111111111111111111111"
    private val usdc = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private val signature = "5" + "K".repeat(86)

    @Test fun addressesAndSignaturesAreBase58OfTheirLength() {
        assertTrue(SolanaIds.isAddress(system))
        assertTrue(SolanaIds.isAddress(usdc))
        assertFalse(SolanaIds.isAddress(usdc.lowercase()), "case is part of the address")
        assertFalse(SolanaIds.isAddress("0x" + "a".repeat(40)))
        assertFalse(SolanaIds.isAddress(usdc + "1"))
        assertTrue(SolanaIds.isSignature(signature))
        assertFalse(SolanaIds.isSignature(usdc))
    }

    @Test fun activityIdsAreScopedToTheWatchedAddressAndKeepCase() {
        val id = SolanaIds.transactionActivity(signature, usdc)
        assertEquals("solana:mainnet:$signature:$usdc", id.value)
        assertEquals(signature, SolanaIds.signatureOf(id.value))
        assertNull(SolanaIds.signatureOf(SolanaIds.rewardActivity(usdc, 700, system).value))
        assertEquals(usdc, SolanaIds.mintOf(SolanaIds.token(usdc)))
        assertNull(SolanaIds.mintOf(SolanaIds.NATIVE))
        assertTrue(SolanaIds.WRAPPED_SOL_MINTS.all(SolanaIds::isAddress))
    }

    @Test fun transactionIdsLowercaseHexOnly() {
        assertEquals("0xabcdef", TransactionIds.normalize(" 0xABCdef "))
        assertEquals("deadbeef", TransactionIds.normalize("DEADBEEF"))
        assertEquals(signature, TransactionIds.normalize(signature))
    }

    @Test fun catalogKeepsCaseSensitiveContractsAndLowercasesHex() {
        val payload =
            """[{"id":"usd-coin","symbol":"usdc","name":"USDC","platforms":{"solana":"$usdc",""" +
                """"ethereum":"0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"}}]"""
        val platforms =
            listOf(
                io.valroot.core.market
                    .EvmPlatform("ethereum", 1, "Ethereum", "ethereum"),
            )
        val catalog = CoingeckoCatalog(platforms, CoingeckoCatalog.parseContracts(payload, platforms))
        assertEquals("usd-coin", catalog.platformCoin("solana", usdc))
        assertNull(catalog.platformCoin("solana", usdc.lowercase()))
        assertEquals("usd-coin", catalog.tokenCoin(1, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"))
        assertTrue(CoingeckoCatalog.decode(catalog.encode()).casePreserved)
        // A cache written before case was kept is refetched.
        assertFalse(CoingeckoCatalog.decode("C\tsolana\t${usdc.lowercase()}\tusd-coin\n").casePreserved)
    }
}
