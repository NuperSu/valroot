package io.valroot.core.application

import io.valroot.core.accounting.QuoteValue
import io.valroot.core.accounting.Treatment
import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.Movement
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.AssetId
import io.valroot.core.model.Rational

/**
 * Treatments for an imported exchange activity the user has not classified, from each movement's recorded role. Values
 * the records do not give stay unknown; a classification the user saves replaces these.
 *
 * - Trade legs: an exchange's two-sided trade has one value for both legs, so proceeds equal cost. It is the report
 *   currency amount when a leg is a currency, otherwise the market value of the coin given, otherwise of the coin
 *   received. Trades with more legs value each leg at its own market value.
 * - Fees: expense disposals at zero proceeds, as for manual entries.
 * - Rewards received: acquired at their market value.
 * - Other incoming coins (deposits, openings, balance fills): openings of unknown cost.
 * - Other outgoing coins (withdrawals, balance fills): disposals of unknown proceeds. An accepted transfer link turns a
 *   withdrawal and its deposit into a transfer that keeps the lots' basis.
 */
internal object DefaultTreatments {
    fun exchange(
        activity: Activity,
        quote: AssetId,
        isCurrency: (AssetId) -> Boolean,
        worth: (Movement) -> QuoteValue?,
    ): List<Treatment> {
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
        return activity.movements.map { movement ->
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
                incoming -> Treatment.Acquire(movement.id, null, opening = true)
                else -> Treatment.Dispose(movement.id, null)
            }
        }
    }
}
