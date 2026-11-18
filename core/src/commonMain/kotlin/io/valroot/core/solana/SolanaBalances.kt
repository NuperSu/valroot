package io.valroot.core.solana

import io.valroot.core.model.ExactInteger

/** An account as stored history last left it: lamports, and for a token account its mint and raw amount. */
data class SolanaReplayedAccount(
    val lamports: ExactInteger,
    val mint: String?,
    val amount: ExactInteger?,
)

/** Stored history replayed up to one slot: each account as the latest transaction left it, and token-account owners. */
data class SolanaReplayedState(
    val accounts: Map<String, SolanaReplayedAccount>,
    val ownership: SolanaOwnership,
)

/**
 * Balance checks against the chain. Each snapshot batch is compared only with stored history replayed to that batch's
 * own slot, account by account, so batches read at different slots are never summed into one balance.
 */
object SolanaBalances {
    /** Every account in [transactions] (chain order) as the last one at or before [slot] left it. */
    fun at(
        transactions: List<SolanaTransaction>,
        slot: Long,
    ): SolanaReplayedState {
        val accounts = mutableMapOf<String, SolanaReplayedAccount>()
        var ownership = SolanaOwnership()
        transactions.takeWhile { it.slot <= slot }.forEach { transaction ->
            val post = transaction.postTokens.associateBy { transaction.accounts[it.accountIndex] }
            transaction.accounts.forEachIndexed { index, key ->
                val token = post[key]
                val previous = accounts[key]
                accounts[key] =
                    SolanaReplayedAccount(
                        transaction.postLamports[index],
                        token?.mint ?: previous?.mint.takeIf { !transaction.tokenBalancesKnown },
                        token?.amount ?: previous?.amount.takeIf { !transaction.tokenBalancesKnown },
                    )
            }
            ownership = ownership.after(transaction)
        }
        return SolanaReplayedState(accounts, ownership)
    }

    /** Token accounts [wallet] owns in [state] that still hold lamports: the ones a balance check reads. */
    fun openAccounts(
        wallet: String,
        state: SolanaReplayedState,
    ): List<String> =
        state.ownership.tokenAccounts
            .filter { (key, account) -> account.owner == wallet && (state.accounts[key]?.lamports?.sign ?: 0) > 0 }
            .keys
            .sorted()

    /**
     * Short descriptions of accounts in [batch] whose chain state differs from [state] (history replayed to the batch's
     * slot); empty when they all agree. Wrapped SOL accounts count by lamports only.
     */
    fun differences(
        wallet: String,
        batch: SolanaAccountBatch,
        state: SolanaReplayedState,
    ): List<String> =
        batch.accounts.mapNotNull { (key, chain) ->
            val replayed = state.accounts[key]
            val ownedInHistory = key == wallet || state.ownership.tokenAccounts[key]?.owner == wallet
            val ownedOnChain = chain != null && (key == wallet || chain.owner == wallet)
            val label = "${key.take(4)}…${key.takeLast(4)}"
            when {
                !ownedOnChain && !ownedInHistory -> null
                !ownedOnChain -> if ((replayed?.lamports?.sign ?: 0) == 0) null else "$label is no longer held but history still has it"
                !ownedInHistory -> "$label is held but missing from history"
                chain!!.lamports != (replayed?.lamports ?: ExactInteger.ZERO) -> "SOL in $label differs"
                chain.mint != null &&
                    chain.mint !in SolanaIds.WRAPPED_SOL_MINTS &&
                    (chain.amount ?: ExactInteger.ZERO) != (replayed?.amount ?: ExactInteger.ZERO) -> "Token amount in $label differs"
                else -> null
            }
        }

    /**
     * Assets whose total in [activities] (those at or before the state's slot) differs from what the accounts [wallet]
     * owns in [state] hold: history that starts with balances it does not explain, such as a token account someone
     * else created in a transaction the history does not include. NFTs ([ignoredMints]) are not compared.
     */
    fun unexplained(
        wallet: String,
        state: SolanaReplayedState,
        activities: List<io.valroot.core.ledger.Activity>,
        ignoredMints: Set<String>,
    ): List<String> {
        val held = mutableMapOf<io.valroot.core.model.AssetId, ExactInteger>()

        fun add(
            to: MutableMap<io.valroot.core.model.AssetId, ExactInteger>,
            asset: io.valroot.core.model.AssetId,
            amount: ExactInteger,
        ) {
            to[asset] = (to[asset] ?: ExactInteger.ZERO) + amount
        }
        state.accounts.forEach { (key, account) ->
            if (key != wallet && state.ownership.tokenAccounts[key]?.owner != wallet) return@forEach
            add(held, SolanaIds.NATIVE, account.lamports)
            val mint = account.mint
            if (mint != null && mint !in SolanaIds.WRAPPED_SOL_MINTS && mint !in ignoredMints) {
                add(held, SolanaIds.token(mint), account.amount ?: ExactInteger.ZERO)
            }
        }
        val ledger = mutableMapOf<io.valroot.core.model.AssetId, ExactInteger>()
        activities.flatMap { it.movements }.forEach { add(ledger, it.amount.assetId, it.amount.atomicUnits) }
        return (held.keys + ledger.keys)
            .filter { (held[it] ?: ExactInteger.ZERO) != (ledger[it] ?: ExactInteger.ZERO) }
            .map { asset ->
                val name = SolanaIds.mintOf(asset)?.let { "${it.take(4)}…${it.takeLast(4)}" } ?: "SOL"
                "History does not explain all $name held"
            }
    }
}
