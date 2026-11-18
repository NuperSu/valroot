package io.valroot.core.solana

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
)

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
) {
    /** The fee payer: always the first account. */
    val feePayer: String get() = accounts.first()

    /** Chain order: slot, then position; equal slots without a position cannot be ordered. */
    val orderingKey: String get() =
        "solana-chain:${slot.toString().padStart(20, '0')}:${index?.toString()?.padStart(10, '0') ?: "unknown"}:$signature"

    companion object {
        /** Parses one history entry; throws [IllegalArgumentException] for a malformed or unexpected one. */
        fun parse(entry: JsonObject): SolanaTransaction =
            try {
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
                        SolanaTokenBalance(
                            balance
                                .getValue("accountIndex")
                                .jsonPrimitive.content
                                .toInt(),
                            balance.getValue("mint").jsonPrimitive.content,
                            (balance["owner"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                            ExactInteger.parse(amount.getValue("amount").jsonPrimitive.content),
                            amount
                                .getValue("decimals")
                                .jsonPrimitive.content
                                .toInt(),
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
