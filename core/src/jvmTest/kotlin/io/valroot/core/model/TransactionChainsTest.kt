package io.valroot.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionChainsTest {
    @Test fun resolvesOnlyUnambiguousNetworks() {
        assertEquals("bitcoin:mainnet", TransactionChains.normalize(" btc "))
        assertEquals("eip155:1", TransactionChains.normalize("ERC20"))
        assertEquals("eip155:56", TransactionChains.normalize("BEP20"))
        assertEquals("eip155:42161", TransactionChains.normalize("eip155:42161"))
        assertNull(TransactionChains.normalize("BTC/ETH"))
        assertNull(TransactionChains.normalize("AVAX"))
        assertNull(TransactionChains.normalize(""))
    }

    @Test fun identitiesIncludeChainsAndPreserveSignatureCase() {
        assertEquals(TransactionChains.identity("ETH", "0xAbCd"), TransactionChains.identity("ERC20", "0xabcd"))
        assertTrue(TransactionChains.conflict(TransactionChains.identity("ETH", "0xabcd"), TransactionChains.identity("BASE", "0xabcd")))
        assertEquals("solana:mainnet|AbZx", TransactionChains.identity("SOL", "AbZx"))
        assertNull(TransactionChains.identity("unknown", "0xabcd"))
    }
}
