package io.valroot.core.solana

import io.valroot.core.model.ExactInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One page of an address's history, oldest first, with the token that continues it (null at the end). */
data class SolanaHistoryPage(
    val entries: List<JsonObject>,
    val paginationToken: String?,
)

/** An account as the chain holds it: lamports, and for a token account its mint, owner and raw amount. */
data class SolanaChainAccount(
    val lamports: ExactInteger,
    val mint: String? = null,
    val owner: String? = null,
    val amount: ExactInteger? = null,
)

/** Accounts read in one request, all at its context [slot]; a null value is an account that does not exist. */
data class SolanaAccountBatch(
    val slot: Long,
    val accounts: Map<String, SolanaChainAccount?>,
)

/** How a mint is classified from its token-standard metadata. */
enum class SolanaMintKind {
    FUNGIBLE,
    NFT,

    /** No recognizable metadata: tracked as fungible, asked again later. */
    UNKNOWN,
}

data class SolanaMintInfo(
    val mint: String,
    val kind: SolanaMintKind,
    val symbol: String?,
    val name: String?,
)

/**
 * Helius's Solana mainnet JSON-RPC (including its `getTransactionsForAddress` history and the DAS `getAssetBatch`
 * method). Bodies are built here and only these methods are sent: the transport rejects any other.
 */
object Helius {
    const val URL = "https://mainnet.helius-rpc.com/"
    const val PAGE_SIZE = 100
    const val ACCOUNT_BATCH = 100
    const val ASSET_BATCH = 1000

    const val HISTORY = "getTransactionsForAddress"
    const val SLOT = "getSlot"
    const val ACCOUNTS = "getMultipleAccounts"
    const val TOKEN_ACCOUNTS = "getTokenAccountsByOwner"
    const val ASSETS = "getAssetBatch"
    const val BLOCK_TIME = "getBlockTime"
    val METHODS = setOf(HISTORY, SLOT, ACCOUNTS, TOKEN_ACCOUNTS, ASSETS, BLOCK_TIME)

    /** The address's finalized transactions oldest first, with token-account activity and every instruction parsed. */
    fun historyBody(
        address: String,
        paginationToken: String?,
    ): String {
        require(SolanaIds.isAddress(address)) { "Invalid Solana address" }
        return request(HISTORY) {
            add(JsonPrimitive(address))
            add(
                buildJsonObject {
                    put("transactionDetails", "full")
                    put("encoding", "jsonParsed")
                    put("sortOrder", "asc")
                    put("limit", PAGE_SIZE)
                    put("commitment", "finalized")
                    put("maxSupportedTransactionVersion", 1)
                    putJsonObject("filters") { put("tokenAccounts", "balanceChanged") }
                    paginationToken?.let { put("paginationToken", it) }
                },
            )
        }
    }

    fun slotBody(): String = request(SLOT) { add(buildJsonObject { put("commitment", "finalized") }) }

    fun blockTimeBody(slot: Long): String {
        require(slot >= 0) { "Invalid slot" }
        return request(BLOCK_TIME) { add(JsonPrimitive(slot)) }
    }

    /** A block's time in milliseconds, or null when the node has none for it. */
    fun parseBlockTime(text: String): Long? {
        val response =
            runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: throw IllegalStateException("Helius returned an unreadable response.")
        (response["error"] as? JsonObject)?.let { error ->
            throw IllegalStateException("Helius could not answer (error ${(error["code"] as? JsonPrimitive)?.content ?: "unknown"}).")
        }
        return (response["result"] as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }
            ?.content
            ?.toLong()
            ?.times(1000)
    }

    fun accountsBody(addresses: List<String>): String {
        require(addresses.size in 1..ACCOUNT_BATCH && addresses.all(SolanaIds::isAddress)) { "Invalid account batch" }
        return request(ACCOUNTS) {
            add(buildJsonArray { addresses.forEach { add(JsonPrimitive(it)) } })
            add(
                buildJsonObject {
                    put("encoding", "jsonParsed")
                    put("commitment", "finalized")
                },
            )
        }
    }

    fun tokenAccountsBody(
        owner: String,
        programId: String,
    ): String {
        require(SolanaIds.isAddress(owner) && programId in SolanaOwnership.TOKEN_PROGRAM_IDS) { "Invalid token account request" }
        return request(TOKEN_ACCOUNTS) {
            add(JsonPrimitive(owner))
            add(buildJsonObject { put("programId", programId) })
            add(
                buildJsonObject {
                    put("encoding", "jsonParsed")
                    put("commitment", "finalized")
                },
            )
        }
    }

    fun assetsBody(mints: List<String>): String {
        require(mints.size in 1..ASSET_BATCH && mints.all(SolanaIds::isAddress)) { "Invalid asset batch" }
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", ASSETS)
            putJsonObject("params") { putJsonArray("ids") { mints.forEach { add(JsonPrimitive(it)) } } }
        }.toString()
    }

    /** The method of a request body, or null when the body is not a single JSON-RPC request. */
    fun methodOf(body: String): String? =
        runCatching {
            (Json.parseToJsonElement(body).jsonObject["method"] as? JsonPrimitive)?.content
        }.getOrNull()

    fun parseHistory(text: String): SolanaHistoryPage {
        val result = result(text).jsonObject
        return SolanaHistoryPage(
            result.getValue("data").jsonArray.map { it.jsonObject },
            (result["paginationToken"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
        )
    }

    fun parseSlot(text: String): Long = result(text).jsonPrimitive.content.toLong()

    fun parseAccounts(
        text: String,
        addresses: List<String>,
    ): SolanaAccountBatch {
        val result = result(text).jsonObject
        val values = result.getValue("value").jsonArray
        require(values.size == addresses.size) { "Helius returned a different number of accounts" }
        return SolanaAccountBatch(
            contextSlot(result),
            addresses.zip(values).associate { (address, value) -> address to (value as? JsonObject)?.let(::account) },
        )
    }

    /** Token accounts the owner holds now, by address; the context slot is only for discovery, never for comparison. */
    fun parseTokenAccounts(text: String): Map<String, SolanaChainAccount> =
        result(text)
            .jsonObject
            .getValue("value")
            .jsonArray
            .associate { entry ->
                val item = entry.jsonObject
                item.getValue("pubkey").jsonPrimitive.content to account(item.getValue("account").jsonObject)
            }

    /** Classifies mints by their recognized token standard; mints Helius does not know are [SolanaMintKind.UNKNOWN]. */
    fun parseAssets(
        text: String,
        mints: List<String>,
    ): List<SolanaMintInfo> {
        val found =
            result(text)
                .jsonArray
                .mapNotNull { it as? JsonObject }
                .associateBy { (it["id"] as? JsonPrimitive)?.content }
        return mints.map { mint ->
            val asset = found[mint] ?: return@map SolanaMintInfo(mint, SolanaMintKind.UNKNOWN, null, null)
            val metadata = (asset["content"] as? JsonObject)?.get("metadata") as? JsonObject
            val tokenInfo = asset["token_info"] as? JsonObject

            fun text(
                from: JsonObject?,
                key: String,
            ) = (from?.get(key) as? JsonPrimitive)
                ?.takeIf { it !is JsonNull }
                ?.content
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val kind =
                when ((asset["interface"] as? JsonPrimitive)?.content) {
                    in FUNGIBLE_INTERFACES -> SolanaMintKind.FUNGIBLE
                    in NFT_INTERFACES -> SolanaMintKind.NFT
                    else -> SolanaMintKind.UNKNOWN
                }
            SolanaMintInfo(
                mint,
                kind,
                (text(tokenInfo, "symbol") ?: text(metadata, "symbol"))?.take(MAX_SYMBOL),
                text(metadata, "name")?.take(MAX_NAME),
            )
        }
    }

    private val FUNGIBLE_INTERFACES = setOf("FungibleToken", "FungibleAsset")
    private val NFT_INTERFACES = setOf("V1_NFT", "V1_PRINT", "V2_NFT", "LEGACY_NFT", "ProgrammableNFT", "MplCoreAsset", "MplCoreCollection")
    private const val MAX_SYMBOL = 20
    private const val MAX_NAME = 80

    private fun account(value: JsonObject): SolanaChainAccount {
        val lamports = ExactInteger.parse(value.getValue("lamports").jsonPrimitive.content)
        val parsed = ((value["data"] as? JsonObject)?.get("parsed") as? JsonObject)
        val info = parsed?.takeIf { (it["type"] as? JsonPrimitive)?.content == "account" }?.get("info") as? JsonObject
        val amount = (info?.get("tokenAmount") as? JsonObject)?.get("amount") as? JsonPrimitive
        return SolanaChainAccount(
            lamports,
            (info?.get("mint") as? JsonPrimitive)?.content,
            (info?.get("owner") as? JsonPrimitive)?.content,
            amount?.content?.let(ExactInteger::parse),
        )
    }

    private fun contextSlot(result: JsonObject): Long =
        result
            .getValue("context")
            .jsonObject
            .getValue("slot")
            .jsonPrimitive.content
            .toLong()

    /** The result of a JSON-RPC response; an error response throws with its code only, never echoing the request. */
    private fun result(text: String): JsonElement {
        val response =
            runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: throw IllegalStateException("Helius returned an unreadable response.")
        (response["error"] as? JsonObject)?.let { error ->
            val code = (error["code"] as? JsonPrimitive)?.content
            throw IllegalStateException("Helius could not answer (error ${code ?: "unknown"}).")
        }
        return response["result"]?.takeIf { it !is JsonNull } ?: throw IllegalStateException("Helius returned no result.")
    }

    private fun request(
        method: String,
        params: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit,
    ): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", method)
            put("params", buildJsonArray(params))
        }.toString()
}
