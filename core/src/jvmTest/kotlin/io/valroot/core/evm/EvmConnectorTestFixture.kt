package io.valroot.core.evm

import io.valroot.core.application.PortfolioApplication
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger

abstract class EvmConnectorTestFixture {
    protected val me = "0x1111111111111111111111111111111111111111"
    protected val other = "0x2222222222222222222222222222222222222222"
    protected val router = "0x3333333333333333333333333333333333333333"
    protected val usdc = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"
    protected val fakeUsdc = "0x" + "5".repeat(40)
    protected val punk = "0x" + "6".repeat(40)
    protected val chain = 8453L
    protected val huge = "0x" + "f".repeat(64)

    protected var slug = "base-mainnet"

    protected fun h(n: Int) = "0x" + n.toString(16).padStart(64, '0')

    protected fun hex(value: Long) = "0x" + value.toString(16)

    protected fun transfer(
        n: Int,
        category: String,
        from: String,
        to: String,
        raw: String? = null,
        contract: String? = null,
        decimals: Int = 18,
        tokenId: String? = null,
    ): String {
        val unique =
            when (category) {
                "external" -> "${h(n)}:external"
                "internal" -> "${h(n)}:internal:0"
                else -> "${h(n)}:log:1"
            }
        val fungible = raw?.let { ""","fungible":{"rawValue":"$it","value":1.0,"decimals":$decimals,"asset":"USDC"}""" } ?: ""
        val nft = tokenId?.let { ""","nfts":{"tokens":[{"tokenId":"$it","amount":"0x1"}]}""" } ?: ""
        val contractField = contract?.let { ""","contractAddress":"$it"""" } ?: ""
        return """{"network":"$slug","category":"$category","blockNum":"${hex(100L + n)}",""" +
            """"blockTimestamp":"2026-01-0${n % 9 + 1}T00:00:00.000Z","hash":"${h(n)}","from":"$from","to":"$to",""" +
            """"uniqueId":"$unique"$contractField$fungible$nft}"""
    }

    protected inner class FakeAlchemy {
        // Newest first, as Alchemy returns them with order "desc".
        val transfers =
            listOf(
                transfer(9, "erc721", other, me, contract = punk, tokenId = huge),
                transfer(8, "erc20", me, other, raw = hex(1_000_000), contract = fakeUsdc, decimals = 6),
                transfer(7, "erc20", me, router, raw = hex(2_500_000), contract = usdc, decimals = 6),
                transfer(6, "internal", router, me, raw = hex(250)),
                transfer(5, "erc20", other, me, raw = hex(5_000_000), contract = usdc, decimals = 6),
                transfer(4, "external", me, other, raw = "0xde0b6b3a7640000"),
                transfer(1, "external", other, me, raw = "0x29a2241af62c0000"),
            )

        // Receipts by hash: the sender, its gas, and an L1 data fee on top (Base is an OP-stack chain).
        val receipts =
            mutableMapOf(
                h(4) to Triple(me, 500L, 100L),
                h(7) to Triple(me, 50L, 0L),
                // Address poisoning: the scammer sent the transaction that moved the look-alike token "from" this address.
                h(8) to Triple(other, 999L, 0L),
                // A failed call Alchemy does not list, found by its nonce.
                h(20) to Triple(me, 300L, 0L),
            )

        // The address's sent transactions by nonce: hash and block. Nonce 3, if sent, has no transaction in its block.
        val sentByNonce = mutableListOf(h(4) to 104L, h(7) to 107L, h(20) to 110L)
        val failed = setOf(h(20))
        var sentCount = 3L
        var nativeBalance = "1999999999999999300"
        val requests = mutableListOf<String>()

        protected fun transfersResponse(body: String): String {
            val request = Json.parseToJsonElement(body).jsonObject
            val networks = request["networks"]!!.jsonArray.map { it.jsonPrimitive.content }
            val categories = request["category"]!!.jsonArray.map { it.jsonPrimitive.content }
            val max = request["maxCount"]!!.jsonPrimitive.content.toInt()
            val start = request["pageKey"]?.jsonPrimitive?.content?.toInt() ?: 0
            val matching =
                if (slug in networks) {
                    transfers.filter { item -> categories.any { item.contains("\"category\":\"$it\"") } }
                } else {
                    emptyList()
                }
            if (max == 1 && "internal" in categories && internalProbeError) {
                return """{"transfers":[],"error":{"partialErrors":[{"network":"$slug","state":"timeout"}]}}"""
            }
            if (max > 1 && failFullHistory) error("History unavailable")
            // Pages of two, so paging is exercised.
            val size = minOf(max, 2)
            val next = if (start + size < matching.size) "\"${start + size}\"" else "null"
            return """{"transfers":[${matching.drop(start).take(size).joinToString(",")}],"pageKey":$next}"""
        }

        /** When set, network discovery (several networks per request) gets this answer: an error, or partial errors. */
        var tokensAnswer: String? = null

        /** The chain head eth_blockNumber reports. */
        var latestBlock = 200L
        var blockHash = h(900)
        var receiptPosition = 1
        var internalProbeError = false
        var failFullHistory = false

        /** When set, the single-chain balance read fails. */
        var failBalances = false

        /** When set, network discovery fails for every group of networks without this address's chain. */
        var failOtherGroups = false

        protected fun tokensResponse(body: String): String {
            val networks =
                Json
                    .parseToJsonElement(body)
                    .jsonObject["addresses"]!!
                    .jsonArray[0]
                    .jsonObject["networks"]!!
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            if (networks.size > 1) tokensAnswer?.let { return it }
            if (networks.size == 1 && failBalances) return """{"error":{"message":"busy"}}"""
            if (networks.size > 1 && failOtherGroups && slug !in networks) return """{"error":{"message":"busy"}}"""
            if (slug !in networks) return """{"data":{"tokens":[]}}"""
            val native = "0x" + BigInteger(nativeBalance).toString(16)
            return """{"data":{"tokens":[{"address":"$me","network":"$slug","tokenAddress":null,"tokenBalance":"$native"},""" +
                """{"address":"$me","network":"$slug","tokenAddress":"$usdc","tokenBalance":"${hex(2_500_000)}"},""" +
                """{"address":"$me","network":"$slug","tokenAddress":"$fakeUsdc","tokenBalance":"0x0"}]}}"""
        }

        protected fun rpcResponse(body: String): String {
            val single = Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
            if (single != null) {
                val method = single["method"]!!.jsonPrimitive.content
                val params = single["params"]!!.jsonArray
                val result =
                    when (method) {
                        "eth_blockNumber" -> "\"${hex(latestBlock)}\""
                        "eth_getTransactionCount" -> {
                            val at = params[1].jsonPrimitive.content
                            val count =
                                if (at == "latest") {
                                    sentCount
                                } else {
                                    val block = at.removePrefix("0x").toLong(16)
                                    (0 until sentCount).count { blockOf(it) <= block }.toLong()
                                }
                            "\"${hex(count)}\""
                        }
                        "eth_getBlockByNumber" -> {
                            val block =
                                params[0]
                                    .jsonPrimitive.content
                                    .removePrefix("0x")
                                    .toLong(16)
                            val mine =
                                sentByNonce.withIndex().filter { it.value.second == block }.joinToString("") { (nonce, tx) ->
                                    """,{"hash":"${tx.first}","from":"$me","to":"$router","nonce":"${hex(nonce.toLong())}",""" +
                                        """"transactionIndex":"0x2","value":"0x0"}"""
                                }
                            """{"number":"${hex(block)}","timestamp":"${hex(1_768_003_200L + block)}","transactions":[""" +
                                """{"hash":"${h(
                                    999,
                                )}","from":"$other","to":"$me","nonce":"0x5","transactionIndex":"0x0","value":"0x0"}$mine]}"""
                        }
                        else -> error("Unexpected method $method")
                    }
                return """{"jsonrpc":"2.0","id":1,"result":$result}"""
            }
            val calls = Json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
            return calls
                .mapIndexed { index, call ->
                    val hash = call["params"]!!.jsonArray[0].jsonPrimitive.content
                    if (call["method"]!!.jsonPrimitive.content == "eth_getTransactionByHash") {
                        val nonce = sentByNonce.indexOfFirst { it.first == hash }
                        """{"jsonrpc":"2.0","id":$index,"result":{"hash":"$hash","nonce":"${hex(nonce.toLong())}"}}"""
                    } else {
                        // A transaction someone else sent: its receipt gives its block and position, and no fee here.
                        val n = (0..2000).firstOrNull { h(it) == hash }
                        val (from, gas, l1) = receipts[hash] ?: Triple(other, 0L, 0L)
                        val block = if (hash in receipts || n == null) "0x10" else hex(100L + n)
                        """{"jsonrpc":"2.0","id":$index,"result":{"transactionHash":"$hash","from":"$from","blockNumber":"$block",""" +
                            """"blockHash":"$blockHash","transactionIndex":"${hex(
                                receiptPosition.toLong(),
                            )}","status":"${if (hash in failed) "0x0" else "0x1"}","gasUsed":"${hex(
                                gas,
                            )}","effectiveGasPrice":"0x1","l1Fee":"${hex(l1)}"}}"""
                    }
                }.joinToString(",", "[", "]")
        }

        /** The block holding the transaction with [nonce]; a nonce without a transaction (nonce 3) is in block 112. */
        protected fun blockOf(nonce: Long): Long = sentByNonce.getOrNull(nonce.toInt())?.second ?: 112L

        val post = { url: String, body: String ->
            requests += url.substringAfterLast('/')
            when {
                url.endsWith("/transfers/by-address") -> transfersResponse(body)
                url.endsWith("/tokens/by-address") -> tokensResponse(body)
                url.startsWith("https://$slug.g.alchemy.com/v2/") -> rpcResponse(body)
                else -> error("Unexpected request $url")
            }
        }
    }

    protected fun setUp(db: BundledSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { 1_800_000_000_000L }
        app.snapshot()
        app.accounts.saveOwner(Owner(OwnerId("owner"), "Owner"))
        app.accounts.saveAccountAndTarget(
            Account(AccountId("evm"), OwnerId("owner"), "EVM", AccountKind.WALLET),
            TrackingTarget(TrackingTargetId("evm"), AccountId("evm"), TargetIdentity(EVM_FAMILY, "address", me), TargetRole.LEAF, "EVM"),
        )
        app.evm.setConsent(true)
        app.market.setConsent(true)

        fun fixture(name: String) = requireNotNull(javaClass.getResource("/coingecko/$name")).readText()
        app.evm.refreshCatalog(
            { url, _ -> if (url.contains("asset_platforms")) fixture("asset_platforms.json") else fixture("coins_list.json") },
        )
        return app
    }

    /** 1inch's history: events with their transaction hash, block, sender and token actions. */
    protected fun oneInchHistory(vararg events: String) = """{"items":[${events.joinToString(",")}]}"""

    protected fun oneInchEvent(
        n: Int,
        sender: String,
        vararg actions: String,
    ) = """{"timeMs":${1_767_225_600_000L + n * 86_400_000L},"details":{"txHash":"${h(n)}","blockNumber":${100 + n},""" +
        """"fromAddress":"$sender","tokenActions":[${actions.joinToString(",")}]}}"""

    protected fun action(
        standard: String,
        from: String,
        to: String,
        amount: String,
        contract: String = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
    ) = """{"standard":"$standard","address":"$contract","fromAddress":"$from","toAddress":"$to","amount":"$amount"}"""
}
