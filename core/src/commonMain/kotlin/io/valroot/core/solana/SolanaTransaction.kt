package io.valroot.core.solana

import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A token account's balance before or after a transaction, as the node reports it. */
data class SolanaTokenBalance(
    val accountIndex: Int,
    val mint: String,
    /** Missing before December 2022, when nodes started recording token-account owners. */
    val owner: String?,
    val amount: ExactInteger,
    val decimals: Int,
    /**
     * False when the node's display amount is not the raw amount shifted by the decimals, as with Token-2022's scaled
     * UI amount and interest-bearing extensions, whose amounts Valroot does not interpret.
     */
    val plainAmount: Boolean = true,
)

/** A transaction's signature and place in the chain. */
data class SolanaPosition(
    val signature: String,
    val slot: Long,
    val index: Int?,
)

/** A transaction in a version this parser does not read; it is stored raw and reported, never decoded by guesswork. */
class UnsupportedSolanaTransaction(
    val position: SolanaPosition,
    val version: String,
) : IllegalArgumentException("Unsupported Solana transaction version")

/** One executed instruction, by name when the node parses its program (`spl-token`, `system`, `stake`, …). */
data class SolanaInstruction(
    val program: String?,
    val type: String?,
    val info: JsonObject,
) {
    fun text(key: String): String? = (info[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}

/**
 * One finalized transaction from `getTransactionsForAddress` (or `getTransaction`) in the `jsonParsed` encoding: its
 * place in the chain, accounts (lookup-table addresses included), balances on both sides, and every executed instruction
 * in order, top-level ones each followed by the instructions it invoked (CPI).
 */
data class SolanaTransaction(
    val signature: String,
    val slot: Long,
    /** The position within the slot; null when the source does not give it (then order within the slot is unknown). */
    val index: Int?,
    val blockTimeMillis: Long?,
    val failed: Boolean,
    val fee: ExactInteger,
    val accounts: List<String>,
    val preLamports: List<ExactInteger>,
    val postLamports: List<ExactInteger>,
    val preTokens: List<SolanaTokenBalance>,
    val postTokens: List<SolanaTokenBalance>,
    val instructions: List<SolanaInstruction>,
    /** False when the node did not record token balances: then the token lists are unknown, not empty. */
    val tokenBalancesKnown: Boolean = true,
) {
    /**
     * How complete this response is: a known position, block time and token balances each count. Of two responses for
     * one signature the more complete is kept.
     */
    val quality: Int get() = listOf(index != null, blockTimeMillis != null, tokenBalancesKnown).count { it }

    /** The fee payer: always the first account. */
    val feePayer: String get() = accounts.first()

    /** Chain order: slot, then position; equal slots without a position cannot be ordered. */
    val orderingKey: String get() =
        "solana-chain:${slot.toString().padStart(20, '0')}:${index?.toString()?.padStart(10, '0') ?: "unknown"}:$signature"

    companion object {
        /** Transaction versions whose `jsonParsed` shape this parser reads. */
        val VERSIONS = setOf("legacy", "0", "1")

        /**
         * The signature and chain position of a history entry, read without parsing the rest, so an entry in a format
         * this parser does not read can still be stored and ordered.
         */
        fun positionOf(entry: JsonObject): SolanaPosition =
            try {
                val signature =
                    entry
                        .getValue("transaction")
                        .jsonObject
                        .getValue("signatures")
                        .jsonArray
                        .first()
                        .jsonPrimitive.content
                require(SolanaIds.isSignature(signature)) { "Invalid signature" }
                SolanaPosition(
                    signature,
                    entry
                        .getValue("slot")
                        .jsonPrimitive.content
                        .toLong(),
                    (entry["transactionIndex"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toInt(),
                )
            } catch (failure: IllegalArgumentException) {
                throw failure
            } catch (failure: RuntimeException) {
                throw IllegalArgumentException("Malformed Solana transaction", failure)
            }

        /**
         * Parses one history entry. Throws [UnsupportedSolanaTransaction] for a transaction version outside [VERSIONS]
         * and [IllegalArgumentException] for a malformed entry.
         */
        fun parse(entry: JsonObject): SolanaTransaction {
            val version = (entry["version"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: "legacy"
            if (version !in VERSIONS) throw UnsupportedSolanaTransaction(positionOf(entry), version)
            return try {
                val transaction = entry.getValue("transaction").jsonObject
                val message = transaction.getValue("message").jsonObject
                val meta = entry.getValue("meta").jsonObject
                val accounts =
                    message.getValue("accountKeys").jsonArray.map { key ->
                        (if (key is JsonObject) key.getValue("pubkey") else key).jsonPrimitive.content
                    }
                val inner =
                    (meta["innerInstructions"] as? JsonArray)
                        .orEmpty()
                        .associate { group ->
                            group.jsonObject
                                .getValue("index")
                                .jsonPrimitive.content
                                .toInt() to
                                group.jsonObject.getValue("instructions").jsonArray
                        }
                val instructions =
                    message.getValue("instructions").jsonArray.flatMapIndexed { position, top ->
                        listOf(instruction(top)) + inner[position].orEmpty().map(::instruction)
                    }

                fun lamports(key: String) = meta.getValue(key).jsonArray.map { ExactInteger.parse(it.jsonPrimitive.content) }

                fun tokens(key: String) =
                    (meta[key] as? JsonArray).orEmpty().map { element ->
                        val balance = element.jsonObject
                        val amount = balance.getValue("uiTokenAmount").jsonObject
                        val raw = ExactInteger.parse(amount.getValue("amount").jsonPrimitive.content)
                        val decimals =
                            amount
                                .getValue("decimals")
                                .jsonPrimitive.content
                                .toInt()
                        val display = (amount["uiAmountString"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                        SolanaTokenBalance(
                            balance
                                .getValue("accountIndex")
                                .jsonPrimitive.content
                                .toInt(),
                            balance.getValue("mint").jsonPrimitive.content,
                            (balance["owner"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                            raw,
                            decimals,
                            display == null ||
                                runCatching { ExactDecimal.parse(display).compareTo(ExactDecimal(raw, decimals)) == 0 }.getOrDefault(false),
                        )
                    }
                val signature =
                    transaction
                        .getValue("signatures")
                        .jsonArray
                        .first()
                        .jsonPrimitive.content
                require(SolanaIds.isSignature(signature)) { "Invalid signature" }
                SolanaTransaction(
                    signature,
                    entry
                        .getValue("slot")
                        .jsonPrimitive.content
                        .toLong(),
                    (entry["transactionIndex"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toInt(),
                    (entry["blockTime"] as? JsonPrimitive)
                        ?.takeIf { it !is JsonNull }
                        ?.content
                        ?.toLong()
                        ?.times(1000),
                    meta["err"].let { it != null && it !is JsonNull },
                    ExactInteger.parse(meta.getValue("fee").jsonPrimitive.content),
                    accounts,
                    lamports("preBalances"),
                    lamports("postBalances"),
                    tokens("preTokenBalances"),
                    tokens("postTokenBalances"),
                    instructions,
                    meta["preTokenBalances"] is JsonArray && meta["postTokenBalances"] is JsonArray,
                ).also {
                    require(
                        it.accounts.isNotEmpty() && it.preLamports.size == it.accounts.size && it.postLamports.size == it.accounts.size,
                    ) {
                        "Balances do not match accounts"
                    }
                }
            } catch (failure: IllegalArgumentException) {
                throw failure
            } catch (failure: RuntimeException) {
                throw IllegalArgumentException("Malformed Solana transaction", failure)
            }
        }

        fun parse(text: String): SolanaTransaction = parse(Json.parseToJsonElement(text).jsonObject)

        private fun instruction(element: JsonElement): SolanaInstruction {
            val node = element.jsonObject
            val parsed = node["parsed"] as? JsonObject
            return SolanaInstruction(
                (node["program"] as? JsonPrimitive)?.content,
                (parsed?.get("type") as? JsonPrimitive)?.content,
                parsed?.get("info") as? JsonObject ?: JsonObject(emptyMap()),
            )
        }
    }
}
