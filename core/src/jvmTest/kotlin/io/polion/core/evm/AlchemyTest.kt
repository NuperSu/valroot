package io.polion.core.evm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Responses captured from Alchemy's Portfolio APIs on 2026-11-18 for vitalik.eth (0xd8dA…6045), a public address: its
 * newest transfers on BNB Smart Chain and OP, and the first page of its token balances on five networks.
 */
class AlchemyTest {
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/alchemy/$name")).readText()

    private val wallet = "0xd8da6bf26964af9d7eed9e03e53415d37aa96045"

    @Test fun transfersMapToTheLedgerModelPerNetwork() {
        val page = Alchemy.parseTransfers(fixture("transfers-bnb-op.json"))
        // The newest ten transfers; the page key continues to older ones.
        assertNotNull(page.pageKey)
        assertTrue(page.errors.isEmpty())
        // Three native BNB payments in; the receipt later adds fee and status.
        assertEquals(3, page.transactions.size)
        val (network, bnb) = page.transactions.first()
        assertEquals("bnb-mainnet", network)
        assertEquals(wallet, bnb.to)
        assertEquals("1", bnb.value.toString())
        assertEquals("0xf623c87c0ea3c6c509cf75efd17bec3b5b0248c8f681f58c7b9cb32a837f87c0", bnb.hash)
        // Seven token transfers on two networks, amounts exact and decimals from the response.
        assertEquals(7, page.tokens.size)
        assertEquals(setOf("bnb-mainnet", "opt-mainnet"), page.tokens.map { it.first }.toSet())
        // A look-alike of BSC-USD (Tether on BNB Smart Chain) from another contract, sent as spam.
        val fake = page.tokens.single { it.second.contract == "0x9fe2314e10c1dc2073eb09771e707cb120a127ef" }.second
        assertEquals("BSC-USD", fake.symbol)
        assertEquals(wallet, fake.to)
        assertEquals("11111000000000000000000", fake.amount.toString())
        assertEquals(62, fake.logIndex)
        assertEquals(18, fake.decimals)
        // An ERC-1155 item keeps its token ID.
        val item = page.tokens.first { it.second.standard == EvmTokenStandard.ERC1155 }.second
        assertEquals("1", item.tokenId.toString())
        // Token names are attacker-controlled: a look-alike "U S DT" keeps its visible letters only, and the invisible
        // Khmer vowels between them are dropped.
        val disguised = fixture("transfers-bnb-op.json").replace("\"asset\":\"BSC-USD\"", "\"asset\":\"U\\u17b5S\\u17b5D\\u03a4\"")
        assertEquals(
            "USDΤ",
            Alchemy
                .parseTransfers(disguised)
                .tokens
                .single { it.second.contract == "0x9fe2314e10c1dc2073eb09771e707cb120a127ef" }
                .second.symbol,
        )
    }

    @Test fun balancesIncludeTheNativeCoin() {
        val (balances, errors) = Alchemy.parseTokenBalances(fixture("tokens.json"))
        assertTrue(errors.isEmpty())
        val native = balances.single { it.first == "bnb-mainnet" && it.second.standard == EvmTokenStandard.NATIVE }.second
        assertEquals("170081301336104041", native.value.toString())
        // The first page holds 100 balances, one native coin per network among them.
        assertEquals(100, balances.size)
        assertEquals(5, balances.count { it.second.standard == EvmTokenStandard.NATIVE })
    }

    @Test fun receiptsGiveFeeIncludingTheL1DataFee() {
        val payload =
            """[{"jsonrpc":"2.0","id":0,"result":{"transactionHash":"0x${"a".repeat(
                64,
            )}","from":"$wallet","blockNumber":"0x10","transactionIndex":"0x2",
            |"status":"0x1","gasUsed":"0x5208","effectiveGasPrice":"0x3b9aca00","l1Fee":"0x64"}},
            |{"jsonrpc":"2.0","id":1,"result":{"transactionHash":"0x${"b".repeat(
                64,
            )}","from":"$wallet","blockNumber":"0x11","transactionIndex":"0x0",
            |"status":"0x0","gasUsed":"0x5208","effectiveGasPrice":"0x1"}}]
            """.trimMargin()
        val (ok, failed) = Alchemy.parseReceipts(payload)
        // 21000 × 1 gwei + 100 wei of L1 fee.
        assertEquals("21000000000100", ok.fee.toString())
        assertTrue(ok.success)
        assertEquals("21000", failed.fee.toString())
        assertTrue(!failed.success)
        assertFails { Alchemy.parseReceipts("""[{"jsonrpc":"2.0","id":0,"result":null}]""") }
        assertEquals(7L, Alchemy.parseNonce("""{"jsonrpc":"2.0","id":1,"result":"0x7"}"""))
    }

    @Test fun requestsStayWithinAlchemysLimits() {
        val body = Alchemy.transfersBody(wallet, listOf(1, 56, 8453, 10, 42161), pageKey = "abc")
        assertTrue(body.contains("\"networks\":[\"eth-mainnet\",\"bnb-mainnet\",\"base-mainnet\",\"opt-mainnet\",\"arb-mainnet\"]"))
        assertTrue(body.contains("\"pageKey\":\"abc\""))
        assertFails { Alchemy.transfersBody(wallet, listOf(1, 56, 8453, 10, 42161, 137)) }
        // Contract-made transfers are listed only on some networks.
        assertFails { Alchemy.transfersBody(wallet, listOf(56), internal = true) }
        assertEquals("https://bnb-mainnet.g.alchemy.com/v2/{key}", Alchemy.rpcUrl(56))
        assertFails { Alchemy.rpcUrl(999) }
        assertFails { Alchemy.receiptsBody(List(51) { "0x" + "c".repeat(64) }) }
    }
}
