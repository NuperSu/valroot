package io.valroot.core.solana

import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.ledger.Movement
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.AccountId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.EventId
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.SourceRecordId

/** A token account as the ownership replay knows it: its owner and mint. */
data class SolanaTokenAccount(
    val owner: String,
    val mint: String,
)

/**
 * Token-account ownership derived by replaying transactions in chain order from an empty state. It is never changed
 * from instructions out of order: a newly found older transaction means replaying again from before it.
 */
data class SolanaOwnership(
    val tokenAccounts: Map<String, SolanaTokenAccount> = emptyMap(),
) {
    /**
     * This state after [transaction]: token-program instructions (initialize, owner change, close) in execution order,
     * then the owners the node recorded after it. A failed transaction changes nothing.
     */
    fun after(transaction: SolanaTransaction): SolanaOwnership {
        if (transaction.failed) return this
        val accounts = tokenAccounts.toMutableMap()
        transaction.instructions.filter { it.program in TOKEN_PROGRAMS }.forEach { instruction ->
            val account = instruction.text("account") ?: return@forEach
            when (instruction.type) {
                "initializeAccount", "initializeAccount2", "initializeAccount3" -> {
                    val owner = instruction.text("owner")
                    val mint = instruction.text("mint")
                    if (owner != null && mint != null) accounts[account] = SolanaTokenAccount(owner, mint)
                }
                "setAuthority" ->
                    if (instruction.text("authorityType") == "accountOwner") {
                        val owner = instruction.text("newAuthority")
                        val current = accounts[account]
                        if (owner != null && current != null) accounts[account] = current.copy(owner = owner)
                    }
                "closeAccount" -> accounts.remove(account)
            }
        }
        transaction.postTokens.forEach { balance ->
            val owner = balance.owner ?: return@forEach
            accounts[transaction.accounts[balance.accountIndex]] = SolanaTokenAccount(owner, balance.mint)
        }
        return SolanaOwnership(accounts)
    }

    companion object {
        val TOKEN_PROGRAMS = setOf("spl-token", "spl-token-2022")
    }
}

/**
 * Decodes what one transaction did to a watched wallet. Ownership is taken on each side separately: a balance counts
 * after the transaction when the wallet owns its account after it, and before when it owned it before, so an owner
 * change moves a whole token balance even though its amount does not change.
 *
 * - SOL: the lamport change over the wallet and the token accounts it owns, so rent locked in its own token accounts
 *   stays SOL held. When the wallet pays the fee, the fee is a FEE movement and the rest a TRANSFER.
 * - Tokens: the amount change per mint over owned token accounts. Wrapped SOL (both native mints) counts by lamports
 *   only, so its token amount is ignored.
 * - Changes of different assets in opposite directions are a swap: a TRADE whose movements are TRADE_LEG.
 * - A failed transaction carries only its fee.
 */
object SolanaDecoder {
    const val VERSION = "solana-1"

    fun decode(
        wallet: String,
        account: AccountId,
        transaction: SolanaTransaction,
        before: SolanaOwnership,
        source: SourceRecordId,
    ): Activity? {
        val after = before.after(transaction)
        val preOwner = transaction.preTokens.associate { transaction.accounts[it.accountIndex] to it.owner }
        val postOwner = transaction.postTokens.associate { transaction.accounts[it.accountIndex] to it.owner }

        fun ownedBefore(key: String) = key == wallet || (preOwner[key] ?: before.tokenAccounts[key]?.owner) == wallet

        fun ownedAfter(key: String) = key == wallet || (postOwner[key] ?: after.tokenAccounts[key]?.owner) == wallet
        val deltas = linkedMapOf<AssetId, ExactInteger>()

        fun add(
            asset: AssetId,
            amount: ExactInteger,
        ) {
            deltas[asset] = (deltas[asset] ?: ExactInteger.ZERO) + amount
        }
        transaction.accounts.forEachIndexed { index, key ->
            if (ownedAfter(key)) add(SolanaIds.NATIVE, transaction.postLamports[index])
            if (ownedBefore(key)) add(SolanaIds.NATIVE, -transaction.preLamports[index])
        }
        transaction.postTokens.filter { it.mint !in SolanaIds.WRAPPED_SOL_MINTS }.forEach {
            if (ownedAfter(transaction.accounts[it.accountIndex])) add(SolanaIds.token(it.mint), it.amount)
        }
        transaction.preTokens.filter { it.mint !in SolanaIds.WRAPPED_SOL_MINTS }.forEach {
            if (ownedBefore(transaction.accounts[it.accountIndex])) add(SolanaIds.token(it.mint), -it.amount)
        }
        val id = SolanaIds.transactionActivity(transaction.signature, wallet).value
        val paysFee = transaction.feePayer == wallet && transaction.fee.sign > 0
        // The fee left the wallet's SOL along with any transfer: the transfer is the rest.
        if (paysFee) add(SolanaIds.NATIVE, transaction.fee)
        val values = deltas.filterValues { it.sign != 0 }
        val swap =
            values.values.any { it.sign > 0 } &&
                values.values.any { it.sign < 0 } &&
                values.keys.size > 1
        val movements =
            values.map { (asset, amount) ->
                Movement(
                    EventId("$id:${asset.value}"),
                    account,
                    AtomicAmount(asset, amount),
                    if (swap) MovementRole.TRADE_LEG else MovementRole.TRANSFER,
                )
            } +
                listOfNotNull(
                    if (paysFee) {
                        Movement(
                            EventId("$id:fee"),
                            account,
                            AtomicAmount(SolanaIds.NATIVE, -transaction.fee),
                            MovementRole.FEE,
                        )
                    } else {
                        null
                    },
                )
        if (movements.isEmpty()) return null
        val timestamp = requireNotNull(transaction.blockTimeMillis) { "Transaction has no block time" }
        val kind =
            when {
                swap -> ActivityKind.TRADE
                values.isEmpty() -> ActivityKind.PAYMENT
                values.values.all { it.sign > 0 } -> ActivityKind.DEPOSIT
                else -> ActivityKind.PAYMENT
            }
        return Activity(
            io.valroot.core.model
                .ActivityId(id),
            timestamp,
            transaction.orderingKey,
            kind,
            ActivityStatus.CONFIRMED,
            movements,
            setOf(source),
            VERSION,
        )
    }

    /**
     * Replays [transactions] (any order; sorted here into chain order) for [wallet] from an empty ownership state and
     * returns each decoded activity with the ownership after the last one. Transactions whose order within their slot
     * is unknown stop the replay there: ownership after them cannot be trusted, so the rest waits.
     */
    fun replay(
        wallet: String,
        account: AccountId,
        transactions: List<SolanaTransaction>,
        source: (SolanaTransaction) -> SourceRecordId,
    ): Replay {
        val ordered = transactions.distinctBy { it.signature }.sortedWith(compareBy({ it.slot }, { it.index ?: -1 }, { it.signature }))
        val ambiguous = ordered.groupBy { it.slot }.filterValues { group -> group.size > 1 && group.any { it.index == null } }.keys
        var ownership = SolanaOwnership()
        val activities = mutableListOf<Activity>()
        for (transaction in ordered) {
            if (transaction.slot in ambiguous) return Replay(activities, ownership, deferredFromSlot = transaction.slot)
            decode(wallet, account, transaction, ownership, source(transaction))?.let(activities::add)
            ownership = ownership.after(transaction)
        }
        return Replay(activities, ownership, null)
    }

    data class Replay(
        val activities: List<Activity>,
        val ownership: SolanaOwnership,
        /** The first slot whose transaction order is unknown; history from it on is not projected yet. */
        val deferredFromSlot: Long?,
    )
}
