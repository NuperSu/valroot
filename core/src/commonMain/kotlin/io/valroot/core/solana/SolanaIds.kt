package io.valroot.core.solana

import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId

/**
 * Identities on Solana mainnet. Addresses, mints and signatures are Base58 and case-sensitive, so they are kept exactly
 * as written everywhere (see [io.valroot.core.model.TransactionIds]).
 *
 * - Addresses and mints: Base58 of 32 bytes. Signatures: Base58 of 64 bytes.
 * - Assets: `solana:mainnet/native` (SOL, 9 decimals) and `solana:mainnet/token:<mint>`.
 * - Activities: one per transaction and watched address, `solana:mainnet:<signature>:<address>`, so a transaction
 *   between two watched wallets is two activities; staking rewards are `solana:mainnet:reward:<stake>:<epoch>:<address>`.
 */
object SolanaIds {
    const val NETWORK = "mainnet"
    const val TRANSACTION_PREFIX = "solana:$NETWORK:"
    const val NATIVE_DECIMALS = 9
    val NATIVE = AssetId("solana:$NETWORK/native")
    private const val TOKEN_PREFIX = "solana:$NETWORK/token:"
    private const val REWARD = "reward"

    /** Wrapped SOL of the Token and Token-2022 programs: their accounts count by lamports, never by token amount. */
    val WRAPPED_SOL_MINTS = setOf("So11111111111111111111111111111111111111112", "9pan9bMn5HatX4EJdBwg9VgCa7Uz5HL8N1m5D3NdXejP")

    fun isAddress(text: String): Boolean = decodedSize(text) == 32

    fun isSignature(text: String): Boolean = decodedSize(text) == 64

    fun token(mint: String): AssetId {
        require(isAddress(mint)) { "Invalid Solana mint" }
        return AssetId(TOKEN_PREFIX + mint)
    }

    /** The mint of a token asset, or null for SOL and other assets. */
    fun mintOf(asset: AssetId): String? = asset.value.takeIf { it.startsWith(TOKEN_PREFIX) }?.removePrefix(TOKEN_PREFIX)

    fun transactionActivity(
        signature: String,
        address: String,
    ): ActivityId {
        require(isSignature(signature) && isAddress(address)) { "Invalid Solana signature or address" }
        return ActivityId("$TRANSACTION_PREFIX$signature:$address")
    }

    fun rewardActivity(
        stakeAccount: String,
        epoch: Long,
        address: String,
    ): ActivityId {
        require(isAddress(stakeAccount) && isAddress(address) && epoch >= 0) { "Invalid Solana reward" }
        return ActivityId("$TRANSACTION_PREFIX$REWARD:$stakeAccount:$epoch:$address")
    }

    /** The signature of a transaction activity ID, exactly as written; null for rewards and anything else. */
    fun signatureOf(activityId: String): String? {
        if (!activityId.startsWith(TRANSACTION_PREFIX)) return null
        val parts = activityId.removePrefix(TRANSACTION_PREFIX).split(':')
        return parts.takeIf { it.size == 2 && isSignature(it[0]) && isAddress(it[1]) }?.get(0)
    }

    private fun decodedSize(text: String): Int? =
        if (text.length !in 32..88) {
            null
        } else {
            runCatching {
                fr.acinq.bitcoin.Base58
                    .decode(text)
                    .size
            }.getOrNull()
        }
}
