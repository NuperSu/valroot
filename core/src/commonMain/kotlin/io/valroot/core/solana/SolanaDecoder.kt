package io.valroot.core.solana

import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.ledger.Movement
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.EventId
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.SourceRecordId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
     * This state after [transaction]: first the owners the node recorded before it, then token-program instructions
     * (initialize, owner change, close) in execution order, then the owners recorded after it. Recorded owners are
     * evidence whenever present; a failed transaction applies no instructions.
     */
    fun after(transaction: SolanaTransaction): SolanaOwnership {
        val accounts = tokenAccounts.toMutableMap()
        observe(transaction, transaction.preTokens, accounts)
        if (!transaction.failed) {
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
        }
        observe(transaction, transaction.postTokens, accounts)
        return SolanaOwnership(accounts)
    }

    /** This state with the owners the node recorded before [transaction]: what was true when it started. */
    fun before(transaction: SolanaTransaction): SolanaOwnership =
        SolanaOwnership(tokenAccounts.toMutableMap().also { observe(transaction, transaction.preTokens, it) })

    private fun observe(
        transaction: SolanaTransaction,
        balances: List<SolanaTokenBalance>,
        accounts: MutableMap<String, SolanaTokenAccount>,
    ) = balances.forEach { balance ->
        val owner = balance.owner ?: return@forEach
        accounts[transaction.accounts[balance.accountIndex]] = SolanaTokenAccount(owner, balance.mint)
    }

    companion object {
        val TOKEN_PROGRAMS = setOf("spl-token", "spl-token-2022")
        val TOKEN_PROGRAM_IDS = setOf("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
    }
}

/** Why part of a transaction's effect on a wallet is not known. */
enum class SolanaGapReason {
    /** The node did not record token balances for a transaction that used a token program. */
    TOKEN_BALANCES_UNAVAILABLE,

    /** A Token-2022 transfer from the wallet whose withheld fee cannot be proven from the transaction. */
    TRANSFER_FEE_UNKNOWN,

    /** A Token-2022 extension whose amounts Valroot does not interpret (confidential, scaled, interest-bearing). */
    UNSUPPORTED_EXTENSION,
}

/** Incomplete coverage from one transaction: for one token, or for all of its tokens when [asset] is null. */
data class SolanaGap(
    val signature: String,
    val asset: AssetId?,
    val reason: SolanaGapReason,
)

/** One transaction's effect on a wallet, with what it could not determine. */
data class SolanaDecoded(
    val activity: Activity?,
    val gaps: List<SolanaGap>,
    /** True when the transaction moved funds but has no block time, so its activity waits. */
    val undated: Boolean = false,
)

/**
 * Decodes what one transaction did to a watched wallet. Ownership is taken on each side separately: a balance counts
 * after the transaction when the wallet owns its account after it, and before when it owned it before, so an owner
 * change moves a whole token balance even though its amount does not change.
 *
 * - SOL: the lamport change over the wallet and the token accounts it owns, so rent locked in its own token accounts
 *   stays SOL held. When the wallet pays the fee, the fee is a FEE movement and the rest a TRANSFER.
 * - Tokens: the amount change per mint over owned token accounts. Wrapped SOL (both native mints) counts by lamports
 *   only, so its token amount is ignored.
 * - Token-2022 transfer fees on transfers from the wallet come only from evidence in the transaction: the fee stated
 *   by `transferCheckedWithFee`, or the amount sent minus what its destination received. The fee is a FEE movement in
 *   that token and the transfer is what arrived; a fee that cannot be proven is a [SolanaGapReason.TRANSFER_FEE_UNKNOWN].
 * - Changes of different assets in opposite directions are a swap: a TRADE whose movements are TRADE_LEG.
 * - A failed transaction carries only its fee.
 */
object SolanaDecoder {
    const val VERSION = "solana-2"
    private val TRANSFERS = setOf("transfer", "transferChecked", "transferCheckedWithFee")

    fun decode(
        wallet: String,
        account: AccountId,
        transaction: SolanaTransaction,
        known: SolanaOwnership,
        source: SourceRecordId,
        ignoredMints: Set<String> = emptySet(),
    ): SolanaDecoded {
        val before = known.before(transaction)
        val after = known.after(transaction)

        fun ownedBefore(key: String) = key == wallet || before.tokenAccounts[key]?.owner == wallet

        fun ownedAfter(key: String) = key == wallet || after.tokenAccounts[key]?.owner == wallet
        val deltas = linkedMapOf<AssetId, ExactInteger>()
        val fees = linkedMapOf<AssetId, ExactInteger>()
        val gaps = linkedSetOf<SolanaGap>()

        fun add(
            to: MutableMap<AssetId, ExactInteger>,
            asset: AssetId,
            amount: ExactInteger,
        ) {
            to[asset] = (to[asset] ?: ExactInteger.ZERO) + amount
        }
        transaction.accounts.forEachIndexed { index, key ->
            if (ownedAfter(key)) add(deltas, SolanaIds.NATIVE, transaction.postLamports[index])
            if (ownedBefore(key)) add(deltas, SolanaIds.NATIVE, -transaction.preLamports[index])
        }
        val tokens = TokenView(transaction)
        val skipped = SolanaIds.WRAPPED_SOL_MINTS + ignoredMints
        // Token changes need both sides: with either balance list unrecorded, one side alone would read as a whole
        // balance arriving or leaving, so none is counted and the gap below is reported instead.
        if (transaction.tokenBalancesKnown) {
            transaction.postTokens.filter { it.mint !in skipped }.forEach {
                if (ownedAfter(transaction.accounts[it.accountIndex])) add(deltas, SolanaIds.token(it.mint), it.amount)
            }
            transaction.preTokens.filter { it.mint !in skipped }.forEach {
                if (ownedBefore(transaction.accounts[it.accountIndex])) add(deltas, SolanaIds.token(it.mint), -it.amount)
            }
        }
        if (!transaction.tokenBalancesKnown && transaction.accounts.any { it in SolanaOwnership.TOKEN_PROGRAM_IDS }) {
            gaps += SolanaGap(transaction.signature, null, SolanaGapReason.TOKEN_BALANCES_UNAVAILABLE)
        }
        (transaction.preTokens + transaction.postTokens)
            .filter { !it.plainAmount && it.mint !in skipped }
            .filter { transaction.accounts[it.accountIndex].let { key -> ownedBefore(key) || ownedAfter(key) } }
            .forEach { gaps += SolanaGap(transaction.signature, SolanaIds.token(it.mint), SolanaGapReason.UNSUPPORTED_EXTENSION) }
        if (!transaction.failed) {
            transaction.instructions.filter { it.program == "spl-token-2022" }.forEach { instruction ->
                val touched = instruction.info.values.mapNotNull { (it as? JsonPrimitive)?.content }
                if (instruction.type?.contains("confidential", ignoreCase = true) == true &&
                    touched.any { ownedBefore(it) || ownedAfter(it) }
                ) {
                    val mint = instruction.text("mint") ?: touched.firstNotNullOfOrNull(tokens::mintOf)
                    gaps += SolanaGap(transaction.signature, mint?.let(SolanaIds::token), SolanaGapReason.UNSUPPORTED_EXTENSION)
                }
                // Without both balance sides no token change is counted, so a stated fee would leave a transfer made of
                // nothing; the unrecorded-balances gap already covers the transaction.
                if (instruction.type !in TRANSFERS || !transaction.tokenBalancesKnown) return@forEach
                val from = instruction.text("source") ?: return@forEach
                if (!ownedBefore(from)) return@forEach
                val mint = instruction.text("mint") ?: tokens.mintOf(from)
                if (mint in skipped) return@forEach
                val fee = tokens.transferFee(instruction)
                when {
                    mint == null -> gaps += SolanaGap(transaction.signature, null, SolanaGapReason.TRANSFER_FEE_UNKNOWN)
                    fee == null -> gaps += SolanaGap(transaction.signature, SolanaIds.token(mint), SolanaGapReason.TRANSFER_FEE_UNKNOWN)
                    fee.sign > 0 -> add(fees, SolanaIds.token(mint), fee)
                }
            }
        }
        val id = SolanaIds.transactionActivity(transaction.signature, wallet).value
        // The network fee left the wallet's SOL along with any transfer, and a withheld token fee left with the tokens
        // sent: the transfer is the rest.
        if (transaction.feePayer == wallet && transaction.fee.sign > 0) add(fees, SolanaIds.NATIVE, transaction.fee)
        fees.forEach { (asset, fee) -> add(deltas, asset, fee) }
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
                fees.map { (asset, fee) ->
                    val key = if (asset == SolanaIds.NATIVE) "$id:fee" else "$id:fee:${asset.value}"
                    Movement(EventId(key), account, AtomicAmount(asset, -fee), MovementRole.FEE)
                }
        if (movements.isEmpty()) return SolanaDecoded(null, gaps.toList())
        // An activity is never dated by guesswork: without its block time it waits until the time is recovered.
        val timestamp = transaction.blockTimeMillis ?: return SolanaDecoded(null, gaps.toList(), undated = true)
        val kind =
            when {
                swap -> ActivityKind.TRADE
                values.isEmpty() -> ActivityKind.PAYMENT
                values.values.all { it.sign > 0 } -> ActivityKind.DEPOSIT
                else -> ActivityKind.PAYMENT
            }
        val activity =
            Activity(ActivityId(id), timestamp, transaction.orderingKey, kind, ActivityStatus.CONFIRMED, movements, setOf(source), VERSION)
        return SolanaDecoded(activity, gaps.toList())
    }

    /**
     * Replays [transactions] (any order; sorted here into chain order) for [wallet] from an empty ownership state and
     * returns each decoded activity and coverage gap with the ownership after the last one. Transactions whose order
     * within their slot is unknown stop the replay there: ownership after them cannot be trusted, so the rest waits.
     *
     * A transaction without a block time still advances ownership, but its activity waits: its slot is listed in
     * [Replay.undatedSlots] until [blockTimes] (recovered times by slot) supplies one.
     *
     * With [resume], a replay of the earlier transactions, [transactions] are only the ones added after it and decoding
     * continues from its ownership; [canContinue] must hold.
     */
    fun replay(
        wallet: String,
        account: AccountId,
        transactions: List<SolanaTransaction>,
        ignoredMints: Set<String> = emptySet(),
        resume: Replay? = null,
        blockTimes: Map<Long, Long> = emptyMap(),
        source: (SolanaTransaction) -> SourceRecordId,
    ): Replay {
        val ordered =
            transactions
                .distinctBy { it.signature }
                .sortedWith(CHAIN_ORDER)
                .map {
                    if (it.blockTimeMillis == null &&
                        it.slot in blockTimes
                    ) {
                        it.copy(blockTimeMillis = blockTimes.getValue(it.slot))
                    } else {
                        it
                    }
                }
        require(resume == null || canContinue(resume, ordered, ignoredMints, blockTimes)) { "This replay cannot be continued" }
        val base = resume ?: Replay(emptyList(), emptyList(), SolanaOwnership(), null, null, ignoredMints)
        val ambiguous = ordered.groupBy { it.slot }.filterValues { group -> group.size > 1 && group.any { it.index == null } }.keys
        var ownership = base.ownership
        var last = base.last
        val activities = base.activities.toMutableList()
        val gaps = base.gaps.toMutableList()
        val undated = base.undatedSlots.toMutableSet()
        for (transaction in ordered) {
            if (transaction.slot in ambiguous || (last != null && last.slot == transaction.slot && last.index == null)) {
                return Replay(activities, gaps, ownership, transaction.slot, last, ignoredMints, undated)
            }
            val decoded = decode(wallet, account, transaction, ownership, source(transaction), ignoredMints)
            decoded.activity?.let(activities::add)
            if (decoded.undated) undated += transaction.slot
            gaps += decoded.gaps
            ownership = ownership.after(transaction)
            last = transaction
        }
        return Replay(activities, gaps, ownership, null, last, ignoredMints, undated)
    }

    /**
     * Whether [resume] can continue with [added]: it was not deferred, ignores the same mints, has no waiting activity
     * whose block time is now known, and they all follow it.
     */
    fun canContinue(
        resume: Replay,
        added: List<SolanaTransaction>,
        ignoredMints: Set<String>,
        blockTimes: Map<Long, Long> = emptyMap(),
    ): Boolean =
        resume.deferredFromSlot == null &&
            resume.ignoredMints == ignoredMints &&
            resume.undatedSlots.none { it in blockTimes } &&
            added.all { resume.last == null || CHAIN_ORDER.compare(it, resume.last) > 0 }

    /** Chain order: slot, then position in the slot, then signature. */
    val CHAIN_ORDER: Comparator<SolanaTransaction> = compareBy({ it.slot }, { it.index ?: -1 }, { it.signature })

    data class Replay(
        val activities: List<Activity>,
        val gaps: List<SolanaGap>,
        val ownership: SolanaOwnership,
        /** The first slot whose transaction order is unknown; history from it on is not projected yet. */
        val deferredFromSlot: Long?,
        /** The last transaction replayed. */
        val last: SolanaTransaction? = null,
        val ignoredMints: Set<String> = emptySet(),
        /** Slots of transactions whose activity waits for a block time. */
        val undatedSlots: Set<Long> = emptySet(),
    )

    /** Token balances of one transaction by account, for mint lookups and transfer-fee evidence. */
    private class TokenView(
        private val transaction: SolanaTransaction,
    ) {
        private val pre = transaction.preTokens.associateBy { transaction.accounts[it.accountIndex] }
        private val post = transaction.postTokens.associateBy { transaction.accounts[it.accountIndex] }

        /** How many balance-changing token instructions name each account; set-up and approvals move no tokens. */
        private val references =
            transaction.instructions
                .filter { it.program in SolanaOwnership.TOKEN_PROGRAMS && NEUTRAL.none { prefix -> it.type.orEmpty().startsWith(prefix) } }
                .flatMap { instruction ->
                    instruction.info.values
                        .mapNotNull { (it as? JsonPrimitive)?.content }
                        .distinct()
                }.groupingBy { it }
                .eachCount()

        private companion object {
            val NEUTRAL =
                listOf(
                    "initialize",
                    "approve",
                    "revoke",
                    "setAuthority",
                    "freeze",
                    "thaw",
                    "getAccountDataSize",
                    "reallocate",
                    "amountToUiAmount",
                    "uiAmountToAmount",
                )
        }

        fun mintOf(key: String): String? = (pre[key] ?: post[key])?.mint

        /**
         * The withheld fee of one Token-2022 transfer: as stated, or the amount sent minus what the destination received
         * when no other token instruction touched the destination. Null when neither is available.
         */
        fun transferFee(instruction: SolanaInstruction): ExactInteger? {
            if (instruction.type == "transferCheckedWithFee") return amount(instruction.info, "feeAmount")
            val sent = amount(instruction.info, "tokenAmount") ?: amount(instruction.info, "amount") ?: return null
            val to = instruction.text("destination") ?: return null
            if (references[to] != 1 || to == instruction.text("source")) return null
            if (!transaction.tokenBalancesKnown || (pre[to] == null && post[to] == null)) return null
            val received = (post[to]?.amount ?: ExactInteger.ZERO) - (pre[to]?.amount ?: ExactInteger.ZERO)
            val fee = sent - received
            return fee.takeIf { it.sign >= 0 && it <= sent }
        }

        private fun amount(
            info: JsonObject,
            key: String,
        ): ExactInteger? {
            val text =
                when (val value = info[key]) {
                    is JsonObject -> (value["amount"] as? JsonPrimitive)?.content
                    is JsonPrimitive -> value.content
                    else -> null
                }
            return text?.let { runCatching { ExactInteger.parse(it) }.getOrNull() }
        }
    }
}
