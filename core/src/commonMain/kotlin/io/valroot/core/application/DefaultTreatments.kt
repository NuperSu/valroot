package io.valroot.core.application

import io.valroot.core.accounting.QuoteValue
import io.valroot.core.accounting.Treatment
import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.Movement
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.AccountId
import io.valroot.core.model.AssetId
import io.valroot.core.model.Rational

/**
 * Treatments for an activity the user has not classified (wallet or exchange), from each movement's recorded role. A
 * classification the user saves replaces these.
 *
 * - Trade legs: a two-sided trade has one value for both legs, so proceeds equal cost. It is the report currency amount
 *   when a leg is a currency, otherwise the market value of the coin given, otherwise of the coin received. Trades with
 *   more legs value each leg at its own market value.
 * - Fees: expense disposals at zero proceeds, as for manual entries.
 * - Rewards received: acquired at their market value.
 * - Other incoming coins (receipts, deposits, openings, balance fills): bought at their market value at that time.
 * - Other outgoing coins (sends, withdrawals, balance fills): sold at their market value at that time.
 *
 * A transfer link between two of the owner's accounts (reviewed, or found from a shared on-chain transaction) turns a
 * departure and its arrival into a transfer that keeps the lots' age and basis instead. Without a historical price a
 * value stays unknown.
 */
internal object DefaultTreatments {
    fun forActivity(
        activity: Activity,
        quote: AssetId,
        isCurrency: (AssetId) -> Boolean,
        sameOwner: (AccountId, AccountId) -> Boolean,
        worth: (Movement) -> QuoteValue?,
    ): List<Treatment> {
        val transfers = internalTransfers(activity, sameOwner)
        val paired = transfers.flatMap { listOf(it.outgoing, it.incoming) }.toSet()
        val legs = activity.movements.filter { it.role == MovementRole.TRADE_LEG }
        val incomingLegs = legs.filter { it.amount.atomicUnits.sign > 0 }
        val outgoingLegs = legs.filter { it.amount.atomicUnits.sign < 0 }
        val tradeValue =
            if (incomingLegs.size == 1 && outgoingLegs.size == 1) {
                val ordered =
                    legs.sortedBy {
                        if (isCurrency(it.amount.assetId)) {
                            0
                        } else if (it in outgoingLegs) {
                            1
                        } else {
                            2
                        }
                    }
                ordered.firstNotNullOfOrNull(worth)
            } else {
                null
            }

        fun legValue(movement: Movement) =
            if (tradeValue != null) {
                tradeValue
            } else if (legs.size > 2) {
                worth(movement)
            } else {
                null
            }
        return transfers +
            activity.movements.filter { it.id !in paired }.map { movement ->
                val incoming = movement.amount.atomicUnits.sign > 0
                when {
                    movement.role == MovementRole.TRADE_LEG && incoming -> Treatment.Acquire(movement.id, legValue(movement))
                    movement.role == MovementRole.TRADE_LEG -> Treatment.Dispose(movement.id, legValue(movement))
                    movement.role == MovementRole.FEE && !incoming ->
                        Treatment.Dispose(
                            movement.id,
                            QuoteValue(quote, Rational.ZERO),
                            fee = true,
                        )
                    movement.role == MovementRole.REWARD && incoming -> Treatment.Acquire(movement.id, worth(movement))
                    incoming -> Treatment.Acquire(movement.id, worth(movement))
                    else -> Treatment.Dispose(movement.id, worth(movement))
                }
            }
    }

    /**
     * Pairs one activity's departure and arrival of the same asset and amount in two accounts of one owner (a send
     * between two tracked wallets in one transaction): a transfer, not a sale and a purchase. Fees and trade legs stay out.
     */
    private fun internalTransfers(
        activity: Activity,
        sameOwner: (AccountId, AccountId) -> Boolean,
    ): List<Treatment.InternalTransfer> {
        val candidates = activity.movements.filter { it.role != MovementRole.FEE && it.role != MovementRole.TRADE_LEG }
        val arrivals = candidates.filter { it.amount.atomicUnits.sign > 0 }.toMutableList()
        return candidates.filter { it.amount.atomicUnits.sign < 0 }.mapNotNull { out ->
            arrivals
                .firstOrNull { into ->
                    into.accountId != out.accountId &&
                        sameOwner(out.accountId, into.accountId) &&
                        into.amount.assetId == out.amount.assetId &&
                        into.amount.atomicUnits == -out.amount.atomicUnits
                }?.let { into ->
                    arrivals.remove(into)
                    Treatment.InternalTransfer(out.id, into.id)
                }
        }
    }
}
