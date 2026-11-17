package io.valroot.core.application

import io.valroot.core.accounting.QuoteValue
import io.valroot.core.accounting.Treatment
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
import io.valroot.core.model.Rational
import io.valroot.core.model.SourceRecordId
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultTreatmentsTest {
    private val usd = AssetId("USD")
    private val btc = AssetId("coin:bitcoin")
    private val usdt = AssetId("coin:tether")
    private val bnb = AssetId("coin:binancecoin")
    private val account = AccountId("x")

    private fun movement(
        id: String,
        asset: AssetId,
        units: Long,
        role: MovementRole,
    ) = Movement(EventId(id), account, AtomicAmount(asset, ExactInteger.parse(units.toString())), role)

    private fun activity(vararg movements: Movement) =
        Activity(
            ActivityId("a"),
            1,
            "x:1",
            ActivityKind.OTHER,
            ActivityStatus.CONFIRMED,
            movements.toList(),
            setOf(SourceRecordId("s")),
            "test-1",
        )

    private fun value(n: String) = QuoteValue(usd, Rational.of(n))

    private fun treat(
        activity: Activity,
        prices: Map<AssetId, String>,
    ) = DefaultTreatments.forActivity(activity, usd, { it == usd }, { _, _ -> true }) { m -> prices[m.amount.assetId]?.let(::value) }

    @Test
    fun aTwoSidedTradeHasOneValueForBothLegsPreferringTheCoinGiven() {
        val buy = activity(movement("in", btc, 1, MovementRole.TRADE_LEG), movement("out", usdt, -50000, MovementRole.TRADE_LEG))
        assertEquals(
            listOf(Treatment.Acquire(EventId("in"), value("49990")), Treatment.Dispose(EventId("out"), value("49990"))),
            treat(buy, mapOf(usdt to "49990", btc to "50100")),
        )
        // Without a price for the coin given, the coin received sets the value; without either it stays unknown.
        assertEquals(value("50100"), (treat(buy, mapOf(btc to "50100"))[0] as Treatment.Acquire).cost)
        assertEquals(null, (treat(buy, emptyMap())[1] as Treatment.Dispose).proceeds)
    }

    @Test
    fun aCurrencyLegSetsTheTradeValue() {
        val buy = activity(movement("in", btc, 1, MovementRole.TRADE_LEG), movement("out", usd, -50000, MovementRole.TRADE_LEG))
        assertEquals(value("50000"), (treat(buy, mapOf(usd to "50000", btc to "50100"))[0] as Treatment.Acquire).cost)
    }

    @Test
    fun unlinkedArrivalsAndDeparturesTakeTheirMarketValue() {
        val mixed =
            activity(
                movement("fee", bnb, -1, MovementRole.FEE),
                movement("reward", bnb, 2, MovementRole.REWARD),
                movement("deposit", btc, 3, MovementRole.TRANSFER),
                movement("withdrawal", usdt, -4, MovementRole.TRANSFER),
            )
        assertEquals(
            listOf(
                Treatment.Dispose(EventId("fee"), QuoteValue(usd, Rational.ZERO), fee = true),
                Treatment.Acquire(EventId("reward"), value("600")),
                Treatment.Acquire(EventId("deposit"), value("1")),
                Treatment.Dispose(EventId("withdrawal"), value("1")),
            ),
            treat(mixed, mapOf(bnb to "600", btc to "1", usdt to "1")),
        )
        // Without a historical price the value stays unknown; the movement is still classified.
        assertEquals(Treatment.Acquire(EventId("deposit"), null), treat(mixed, emptyMap())[2])
    }

    @Test
    fun aSendBetweenTwoOwnAccountsInOneActivityIsATransfer() {
        val send =
            Activity(
                ActivityId("bitcoin:tx"),
                0,
                "test-2",
                ActivityKind.OTHER,
                ActivityStatus.CONFIRMED,
                listOf(
                    Movement(EventId("out"), AccountId("a"), AtomicAmount(btc, ExactInteger.parse("-5")), MovementRole.TRANSFER),
                    Movement(EventId("in"), AccountId("b"), AtomicAmount(btc, ExactInteger.parse("5")), MovementRole.TRANSFER),
                    Movement(EventId("fee"), AccountId("a"), AtomicAmount(btc, ExactInteger.parse("-1")), MovementRole.FEE),
                ),
                setOf(SourceRecordId("s")),
                "test-2",
            )
        assertEquals(
            listOf(
                Treatment.InternalTransfer(EventId("out"), EventId("in")),
                Treatment.Dispose(EventId("fee"), QuoteValue(usd, Rational.ZERO), fee = true),
            ),
            treat(send, mapOf(btc to "1")),
        )
        // Accounts of different owners are not a transfer: a sale and a purchase at market value.
        assertEquals(
            listOf(Treatment.Dispose(EventId("out"), value("1")), Treatment.Acquire(EventId("in"), value("1"))),
            DefaultTreatments.forActivity(send, usd, { it == usd }, { _, _ -> false }) { value("1") }.take(2),
        )
    }
}
