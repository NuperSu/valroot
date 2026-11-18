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
import io.valroot.core.model.OwnerId
import io.valroot.core.model.Rational
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.storage.BundledSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountingCompletenessTest {
    @Test fun anUnclassifiedDisposalCountsAtMarketValueAndAClassificationReplacesIt() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            val owner = OwnerId("owner")
            val account = AccountId("account")
            app.accounts.saveOwner(Owner(owner, "Owner"))
            app.accounts.saveAccount(Account(account, owner, "Account", AccountKind.ACCOUNT))

            fun entry(
                id: String,
                at: Long,
                units: String,
                value: String?,
            ) {
                val movement = EventId(id)
                val quantity = ExactInteger.parse(units)
                app.transactions.saveManualActivity(
                    Activity(
                        ActivityId(id),
                        at,
                        id,
                        ActivityKind.OTHER,
                        ActivityStatus.CONFIRMED,
                        listOf(Movement(movement, account, AtomicAmount(AssetId("bitcoin:btc"), quantity), MovementRole.TRANSFER)),
                        emptySet(),
                        null,
                        true,
                    ),
                )
                if (value != null) {
                    val quote = QuoteValue(AssetId("USD"), Rational.of(value))
                    app.accounting.saveTreatments(
                        ActivityId(id),
                        listOf(
                            if (quantity.sign >
                                0
                            ) {
                                Treatment.Acquire(movement, quote)
                            } else {
                                Treatment.Dispose(movement, quote)
                            },
                        ),
                    )
                }
            }
            entry("buy1", 1, "1", "10")
            entry("sell1", 2, "-1", null)
            entry("buy2", 3, "1", "100")
            entry("sell2", 4, "-1", "200")
            // Unclassified, sell1 is sold at its market value; with no saved price its proceeds and gain stay unknown,
            // but the inventory is complete.
            val defaults = app.accounting.report()
            assertTrue(defaults.missingTreatments.isEmpty())
            assertTrue(ActivityId("sell1") in defaults.automaticTreatments)
            assertTrue(defaults.report.lots.isEmpty())
            assertEquals(
                null,
                defaults.report.disposals
                    .first { it.activityId == ActivityId("sell1") }
                    .gain,
            )
            app.accounting.saveTreatments(ActivityId("sell1"), listOf(Treatment.Dispose(EventId("sell1"), null)))
            val complete = app.accounting.report()
            assertTrue(complete.report.lots.isEmpty())
            assertEquals(
                Rational.of("100"),
                complete.report.disposals
                    .last()
                    .basis,
            )
            assertEquals(
                Rational.of("100"),
                complete.report.disposals
                    .last()
                    .gain,
            )
            db.execute("UPDATE accounting_treatments SET quote_asset=NULL WHERE activity_id='buy1'")
            assertEquals(listOf(ActivityId("buy1")), app.accounting.report().invalidTreatments)
            assertTrue(app.accounting.treatments(ActivityId("buy1")).isEmpty())
            app.accounting.saveTreatments(
                ActivityId("buy1"),
                listOf(Treatment.Acquire(EventId("buy1"), QuoteValue(AssetId("USD"), Rational.of("10")))),
            )
            assertTrue(
                app.accounting
                    .report()
                    .invalidTreatments
                    .isEmpty(),
            )
        }
    }

    /** An unclassified receipt is bought, and an unclassified send sold, at the saved market price of their moment. */
    @Test fun unclassifiedReceiptsAndSendsTakeTheirMarketValue() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 10_000_000L }
            val owner = OwnerId("owner")
            val account = AccountId("account")
            val btc = AssetId("bitcoin:btc")
            app.accounts.saveOwner(Owner(owner, "Owner"))
            app.accounts.saveAccount(Account(account, owner, "Account", AccountKind.ACCOUNT))
            app.assets.ensureDefaults()
            io.valroot.core.market.MarketRepository(db).apply {
                listOf(1_000_000L to "20000", 5_000_000L to "30000").forEach { (at, usd) ->
                    recordPrice(
                        io.valroot.core.market.PriceObservation(
                            "p$at",
                            io.valroot.core.model
                                .Price(
                                    btc,
                                    AssetId("USD"),
                                    io.valroot.core.model.ExactDecimal
                                        .parse(usd),
                                ),
                            io.valroot.core.model
                                .ProviderId("coingecko"),
                            at,
                            at,
                        ),
                    )
                }
            }

            fun move(
                id: String,
                at: Long,
                sats: String,
            ) = app.transactions.saveManualActivity(
                Activity(
                    ActivityId(id),
                    at,
                    id,
                    ActivityKind.OTHER,
                    ActivityStatus.CONFIRMED,
                    listOf(Movement(EventId(id), account, AtomicAmount(btc, ExactInteger.parse(sats)), MovementRole.TRANSFER)),
                    emptySet(),
                    null,
                    true,
                ),
            )
            move("receive", 1_000_000L, "100000000")
            move("send", 5_000_000L, "-50000000")
            val view = app.accounting.report()
            assertTrue(view.missingTreatments.isEmpty())
            assertTrue(ActivityId("receive") in view.estimatedBasis && ActivityId("send") in view.estimatedBasis)
            // 1 BTC at 20,000 USD; half sold at 30,000 USD: proceeds 15,000, basis 10,000, gain 5,000.
            val sale = view.report.disposals.single()
            assertEquals(Rational.of("15000"), sale.proceeds)
            assertEquals(Rational.of("10000"), sale.basis)
            assertEquals(Rational.of("5000"), sale.gain)
            assertEquals(
                Rational.of("10000"),
                view.report.lots
                    .single()
                    .basis,
            )
        }
    }

    /**
     * A receipt recorded before its linked withdrawal (an exchange records completion after the block) and sold in
     * between still sells the transferred lot: the lots move at the earlier side.
     */
    @Test fun aReceiptBeforeItsLinkedWithdrawalCanBeSoldWithItsBasis() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 10_000L }
            val owner = OwnerId("owner")
            val exchange = AccountId("exchange")
            val wallet = AccountId("wallet")
            val btc = AssetId("bitcoin:btc")
            val usd = AssetId("USD")
            app.accounts.saveOwner(Owner(owner, "Owner"))
            app.accounts.saveAccount(Account(exchange, owner, "Exchange", AccountKind.ACCOUNT))
            app.accounts.saveAccount(Account(wallet, owner, "Wallet", AccountKind.WALLET))

            fun move(
                id: String,
                at: Long,
                account: AccountId,
                units: String,
                treatment: Treatment,
            ) {
                app.transactions.saveManualActivity(
                    Activity(
                        ActivityId(id),
                        at,
                        id,
                        ActivityKind.OTHER,
                        ActivityStatus.CONFIRMED,
                        listOf(Movement(EventId(id), account, AtomicAmount(btc, ExactInteger.parse(units)), MovementRole.TRANSFER)),
                        emptySet(),
                        null,
                        true,
                    ),
                )
                app.accounting.saveTreatments(ActivityId(id), listOf(treatment))
            }
            move("buy", 1_000, exchange, "100", Treatment.Acquire(EventId("buy"), QuoteValue(usd, Rational.of("10"))))
            move("withdraw", 5_000, exchange, "-100", Treatment.Dispose(EventId("withdraw"), null))
            move("receive", 4_000, wallet, "100", Treatment.Acquire(EventId("receive"), null))
            move("sell", 4_500, wallet, "-100", Treatment.Dispose(EventId("sell"), QuoteValue(usd, Rational.of("25"))))
            app.transactions.setDecision(ActivityId("withdraw"), ActivityId("receive"), io.valroot.core.reconcile.LinkState.ACCEPTED)
            val view = app.accounting.report()
            val sale = view.report.disposals.single { it.activityId == ActivityId("sell") }
            assertEquals(Rational.of("10"), sale.basis)
            assertEquals(Rational.of("15"), sale.gain)
            assertTrue(view.report.lots.isEmpty(), "no lot is left behind in either account")
        }
    }
}
