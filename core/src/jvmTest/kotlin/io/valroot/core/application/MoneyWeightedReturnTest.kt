package io.valroot.core.application

import io.valroot.core.bitcoin.BITCOIN_ASSET
import io.valroot.core.market.PriceObservation
import io.valroot.core.model.AccountId
import io.valroot.core.model.ActivityId
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.OwnerId
import io.valroot.core.model.Price
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.reconcile.LinkState
import io.valroot.core.storage.BundledSqliteDatabase
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyWeightedReturnTest {
    private val day = 86_400_000L
    private val now = 400 * day
    private val usd = AssetId("USD")
    private val btc = BITCOIN_ASSET
    private val a = AccountId("a")
    private val b = AccountId("b")

    @Test fun rateMatchesAnIndependentSpreadsheetFigure() {
        // The widely published XIRR example (Actual/365): 37.34% a year.
        val dates = listOf(0, 60, 303, 411, 456)
        val amounts = listOf(-10_000.0, 2_750.0, 4_250.0, 3_250.0, 2_750.0)
        val (rate, status) = InternalRate.solve(dates.zip(amounts) { d, amount -> DatedFlow(d / 365.0, amount) })
        assertEquals(ReturnStatus.OK, status)
        assertEquals(0.373362535, rate!!, 1e-6)
        assertEquals(0.1, InternalRate.solve(listOf(DatedFlow(0.0, -1000.0), DatedFlow(1.0, 1100.0))).first!!, 1e-9)
    }

    @Test fun ambiguousAndOneSidedFlowsHaveNoRate() {
        // -1, +6, -11, +6 has rates of 0%, 100% and 200%.
        val ambiguous = listOf(-1.0, 6.0, -11.0, 6.0).mapIndexed { year, amount -> DatedFlow(year.toDouble(), amount) }
        assertEquals(ReturnStatus.NO_UNIQUE_RATE, InternalRate.solve(ambiguous).second)
        assertEquals(ReturnStatus.NO_INVESTMENT, InternalRate.solve(listOf(DatedFlow(0.0, 5.0), DatedFlow(1.0, 6.0))).second)
    }

    @Test fun closelySpacedAndTangentRootsAreNeverCertifiedUnique() {
        // With x = 1 / (1+r), these polynomials have nearby roots and a repeated root.
        for (roots in listOf(listOf(1.1, 1.10001, 2.0), listOf(1.1, 1.1))) {
            var coefficients = listOf(1.0)
            roots.forEach { root ->
                val next = MutableList(coefficients.size + 1) { 0.0 }
                coefficients.forEachIndexed { index, value ->
                    next[index] -= value / root
                    next[index + 1] += value
                }
                coefficients = next
            }
            val (rate, status) = InternalRate.solve(coefficients.mapIndexed { year, value -> DatedFlow(year.toDouble(), value) })
            assertNull(rate)
            assertTrue(status == ReturnStatus.NO_UNIQUE_RATE || status == ReturnStatus.UNVERIFIED_RATE)
        }
        // A withdrawal followed by a contribution may have one root, but the solver cannot certify it.
        assertEquals(
            ReturnStatus.UNVERIFIED_RATE,
            InternalRate.solve(listOf(DatedFlow(0.0, -100.0), DatedFlow(1.0, 10.0), DatedFlow(2.0, -5.0), DatedFlow(3.0, 120.0))).second,
        )
    }

    @Test fun futureTransactionsWaitForTheirDateAcrossCachedReads() {
        BundledSqliteDatabase(":memory:").use { db ->
            var clock = 10 * day
            val app = PortfolioApplication(db) { clock }
            app.snapshot()
            app.accounts.saveOwner(Owner(OwnerId("owner"), "Owner"))
            app.accounts.saveAccount(Account(a, OwnerId("owner"), "Exchange", AccountKind.ACCOUNT))
            app.transactions.saveTransaction(draft("future", TransactionType.REWARD, btc, "0.1", 20, "10000"))
            assertTrue(
                app
                    .snapshot()
                    .balances.quantities.values
                    .all { it.sign == 0 },
            )
            assertTrue(
                app.accounting
                    .report()
                    .report.lots
                    .isEmpty(),
            )
            clock = 20 * day
            assertEquals(
                "10000000",
                app
                    .snapshot()
                    .balances.quantities.values
                    .single { it.sign != 0 }
                    .toString(),
            )
            assertTrue(
                app.accounting
                    .report()
                    .report.lots
                    .isNotEmpty(),
            )
            clock = 10 * day
            assertTrue(
                app
                    .snapshot()
                    .balances.quantities.values
                    .all { it.sign == 0 },
            )
        }
    }

    @Test fun moneyInAndOutAtTheSameInstantCancels() {
        // Every rate satisfies -1000 + 1000 at one time: there was no investment, not an arbitrary rate.
        val (rate, status) = InternalRate.solve(listOf(DatedFlow(0.5, -1000.0), DatedFlow(0.5, 1000.0)))
        assertNull(rate)
        assertEquals(ReturnStatus.NO_INVESTMENT, status)
        // A cancelling pair beside real flows changes nothing.
        val base = listOf(DatedFlow(0.0, -1000.0), DatedFlow(1.0, 1100.0))
        assertEquals(0.1, InternalRate.solve(base + DatedFlow(0.5, -500.0) + DatedFlow(0.5, 500.0)).first!!, 1e-9)
    }

    private fun setUp(db: BundledSqliteDatabase): PortfolioApplication {
        val app = PortfolioApplication(db) { now }
        app.snapshot()
        app.accounts.saveOwner(Owner(OwnerId("owner"), "Owner"))
        app.accounts.saveAccount(Account(a, OwnerId("owner"), "Exchange", AccountKind.ACCOUNT))
        app.accounts.saveAccount(Account(b, OwnerId("owner"), "Cold wallet", AccountKind.WALLET))
        mapOf(10 to "10000", 100 to "20000", 200 to "15000", 250 to "20000", 260 to "20000").forEach { (at, price) ->
            app.prices.recordManual(PriceObservation("p$at", Price(btc, usd, ExactDecimal.parse(price)), null, at * day, at * day, true))
        }
        app.prices.recordManual(PriceObservation("now", Price(btc, usd, ExactDecimal.parse("40000")), null, now - 3_600_000L, now, true))
        return app
    }

    private fun draft(
        id: String,
        type: TransactionType,
        asset: AssetId,
        quantity: String,
        atDay: Long,
        price: String? = null,
        account: AccountId = a,
        destination: AccountId? = null,
        incoming: Boolean = false,
    ) = TransactionDraft(
        ActivityId(id),
        type,
        asset,
        account,
        ExactDecimal.parse(quantity),
        atDay * day,
        usd,
        price?.let(ExactDecimal::parse),
        destination,
        incomingExternal = incoming,
    )

    private fun record(app: PortfolioApplication) {
        app.transactions.saveTransaction(draft("deposit", TransactionType.DEPOSIT, usd, "1000", 10))
        app.transactions.saveTransaction(draft("buy", TransactionType.BUY, btc, "0.1", 10, "10000"))
        app.transactions.saveTransaction(draft("move", TransactionType.TRANSFER, btc, "0.05", 100, destination = b))
        app.transactions.saveTransaction(draft("withdraw", TransactionType.WITHDRAWAL, btc, "0.02", 200))
        app.transactions.saveTransaction(draft("reward", TransactionType.REWARD, btc, "0.01", 250, "20000", account = b))
        app.transactions.saveTransaction(draft("fee", TransactionType.FEE, btc, "0.001", 260))
    }

    @Test fun contributionsWithdrawalsAndGainFollowTheDefinitions() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            record(app)
            val result = app.analytics.moneyWeightedReturn(null, 5 * day, now)
            // Only the cash deposit and the external withdrawal are flows: the buy, the move between own accounts,
            // the reward and the fee are part of the return.
            assertEquals(ReturnStatus.OK, result.status)
            assertEquals(ExactDecimal.parse("0"), result.startValue)
            assertEquals(0, result.contributions!!.compareTo(ExactDecimal.parse("1000")))
            assertEquals(0, result.withdrawals!!.compareTo(ExactDecimal.parse("300")))
            // 0.089 BTC at 40,000 = 3,560; gain = 3,560 - 0 - 1,000 + 300.
            assertEquals(0, result.endValue!!.compareTo(ExactDecimal.parse("3560")))
            assertEquals(0, result.gain!!.compareTo(ExactDecimal.parse("2860")))
            // Independent check: the investor flows discount to zero at the reported annual rate.
            val year = 365.25 * day
            val span = (now - 5 * day) / year
            val rate = (1 + result.periodReturn!!).pow(1 / span) - 1
            val npv =
                -1000 * (1 + rate).pow(-(5 * day) / year) +
                    300 * (1 + rate).pow(-(195 * day) / year) +
                    3560 * (1 + rate).pow(-span)
            assertTrue(abs(npv) < 1e-6, "npv $npv")
            assertEquals(rate, result.annualReturn!!, 1e-9)

            val activity = app.analytics.insights(null, 5 * day, now)
            assertEquals(1, activity.trades)
            assertEquals(2, activity.tradedAssets)
            val fee = activity.fees.single()
            assertEquals(ExactInteger.parse("100000"), fee.atomicUnits)
            assertEquals(0, fee.value!!.compareTo(ExactDecimal.parse("20")))
            assertEquals(listOf("Exchange", "Cold wallet"), activity.accounts.map { it.label })
            assertEquals(listOf(5, 2), activity.accounts.map { it.activities })
        }
    }

    @Test fun aCoinMissingAFlowPriceIsLeftOutAndShortPeriodsAreNotAnnualised() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            record(app)
            app.transactions.saveTransaction(draft("late", TransactionType.WITHDRAWAL, btc, "0.001", 320))
            // BTC has no price for the late withdrawal, so BTC is left out of the start, end and flows alike; the rest
            // of the portfolio is still measured.
            val partial = app.analytics.moneyWeightedReturn(null, 5 * day, now)
            assertEquals(1, partial.leftOutAssets)
            assertTrue(partial.status != ReturnStatus.MISSING_PRICES)
            assertTrue(partial.startValue != null && partial.endValue != null && partial.gain != null)
            val month = app.analytics.moneyWeightedReturn(null, now - 30 * day, now)
            assertNull(month.annualReturn)
        }
    }

    @Test fun anAcceptedLinkBetweenScopedAccountsIsNotAFlow() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            app.transactions.saveTransaction(draft("deposit", TransactionType.DEPOSIT, usd, "1000", 10))
            app.transactions.saveTransaction(draft("buy", TransactionType.BUY, btc, "0.1", 10, "10000"))
            // The arrival has no price on its day: as two flows BTC would be left out of the return.
            app.transactions.saveTransaction(draft("out", TransactionType.WITHDRAWAL, btc, "0.05", 100))
            app.transactions.saveTransaction(draft("in", TransactionType.TRANSFER, btc, "0.05", 150, account = b, incoming = true))
            assertEquals(1, app.analytics.moneyWeightedReturn(null, 5 * day, now).leftOutAssets)
            app.transactions.setDecision(ActivityId("out"), ActivityId("in"), LinkState.ACCEPTED)
            val linked = app.analytics.moneyWeightedReturn(null, 5 * day, now)
            assertEquals(ReturnStatus.OK, linked.status)
            assertEquals(0, linked.leftOutAssets)
            assertEquals(0, linked.contributions!!.compareTo(ExactDecimal.parse("1000")))
            assertEquals(0, linked.withdrawals!!.compareTo(ExactDecimal.parse("0")))
        }
    }

    @Test fun anExchangeCurrencyDepositIsWorthItsAmount() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val fiat = app.exchanges.assets("bybit", setOf("USD")).getValue("USD")
            app.transactions.saveTransaction(draft("cash", TransactionType.DEPOSIT, fiat, "500", 10))
            val result = app.analytics.moneyWeightedReturn(null, 5 * day, now)
            assertEquals(0, result.contributions!!.compareTo(ExactDecimal.parse("500")))
        }
    }

    /** Buying a coin the return leaves out moves the money spent out of what is measured; it is not a loss. */
    @Test fun buyingALeftOutCoinIsNotALoss() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val eur = AssetId("EUR")
            app.transactions.saveTransaction(draft("deposit", TransactionType.DEPOSIT, usd, "1000", 290))
            // EUR has no USD price, so it is left out; the 110 USD paid for it leaves the measured coins.
            app.transactions.saveTransaction(draft("buy", TransactionType.BUY, eur, "100", 300, "1.1"))
            val result = app.analytics.moneyWeightedReturn(null, 280 * day, now)
            assertEquals(1, result.leftOutAssets)
            assertEquals(0, result.gain!!.compareTo(ExactDecimal.parse("0")))
            assertEquals(0, result.withdrawals!!.compareTo(ExactDecimal.parse("110")))
        }
    }

    /** An on-chain swap records its legs as transfers of a trade activity: they are a trade, not money in and out. */
    @Test fun aSwapsTransferLegsAreNotFlows() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            app.transactions.saveTransaction(draft("deposit", TransactionType.DEPOSIT, usd, "1000", 10))
            app.transactions.saveManualActivity(
                io.valroot.core.ledger.Activity(
                    ActivityId("swap"),
                    100 * day,
                    "swap",
                    io.valroot.core.ledger.ActivityKind.TRADE,
                    io.valroot.core.ledger.ActivityStatus.CONFIRMED,
                    listOf(
                        io.valroot.core.ledger.Movement(
                            io.valroot.core.model
                                .EventId("swap:usd"),
                            a,
                            io.valroot.core.model
                                .AtomicAmount(
                                    usd,
                                    io.valroot.core.model.ExactInteger
                                        .parse("-100000"),
                                ),
                            io.valroot.core.ledger.MovementRole.TRANSFER,
                        ),
                        io.valroot.core.ledger.Movement(
                            io.valroot.core.model
                                .EventId("swap:btc"),
                            a,
                            io.valroot.core.model
                                .AtomicAmount(
                                    btc,
                                    io.valroot.core.model.ExactInteger
                                        .parse("5000000"),
                                ),
                            io.valroot.core.ledger.MovementRole.TRANSFER,
                        ),
                    ),
                    emptySet(),
                    null,
                    true,
                ),
            )
            val result = app.analytics.moneyWeightedReturn(null, 5 * day, now)
            assertEquals(0, result.contributions!!.compareTo(ExactDecimal.parse("1000")))
            assertEquals(0, result.withdrawals!!.compareTo(ExactDecimal.parse("0")))
        }
    }

    /** A hidden token is out of the start and end values, so receiving and holding it is neither money in nor a loss. */
    @Test fun aHiddenTokenIsNotAFlow() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val spam = AssetId("eip155:1/erc20:0x" + "b".repeat(40))
            app.assets.register(
                io.valroot.core.market.AssetDefinition(
                    spam,
                    io.valroot.core.market.AssetIdentity(
                        io.valroot.core.market.AssetKind.TOKEN,
                        io.valroot.core.model
                            .NetworkId("eip155:1"),
                        "0x" + "b".repeat(40),
                    ),
                ),
                io.valroot.core.market
                    .AssetMetadata("SPAM", "Unknown token", 0, 0),
            )
            // Priced at every point, but without a coin link it starts hidden.
            listOf(290 * day, now - 3_600_000L).forEach { at ->
                app.prices.recordManual(PriceObservation("spam$at", Price(spam, usd, ExactDecimal.parse("1")), null, at, at, true))
            }
            app.transactions.saveTransaction(draft("deposit", TransactionType.DEPOSIT, usd, "1000", 290))
            app.transactions.saveTransaction(draft("spam", TransactionType.DEPOSIT, spam, "100", 300))
            assertTrue(spam in app.assets.hidden())
            val result = app.analytics.moneyWeightedReturn(null, 280 * day, now)
            assertEquals(0, result.contributions!!.compareTo(ExactDecimal.parse("1000")))
            assertEquals(0, result.gain!!.compareTo(ExactDecimal.parse("0")))
            assertEquals(0, result.leftOutAssets, "hidden is not the same as unpriced")
        }
    }
}
