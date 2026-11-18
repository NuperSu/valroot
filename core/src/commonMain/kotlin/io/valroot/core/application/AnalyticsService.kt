package io.valroot.core.application

import io.valroot.core.bitcoin.BITCOIN_MAINNET
import io.valroot.core.bitcoin.bitcoinMainnetAddress
import io.valroot.core.holdings.BalanceCoverage
import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.market.PriceStatus
import io.valroot.core.model.AccountId
import io.valroot.core.model.AssetId
import io.valroot.core.model.AtomicAmount
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.PortfolioId
import io.valroot.core.ownership.TargetRole
import io.valroot.core.storage.sha256
import kotlin.math.pow

/** Portfolio values over time: history series, analytics, money-weighted return, insights and account overviews. */
class AnalyticsService internal constructor(
    ctx: AppContext,
) : AppService(ctx) {
    fun history(
        atMillis: Long,
        portfolio: PortfolioId? = null,
    ): List<AssetValuation> {
        val selected = app.assets.scopedAccounts(ownership.load(), portfolio)
        val balance = holdings.historical(ledger().activities.map { it.activity }, atMillis, BalanceCoverage.UNKNOWN)
        return app.assets.valuations(
            balance.copy(quantities = balance.quantities.filterKeys { it.account in selected }),
            app.assets.portfolioCurrency(portfolio),
            now(),
        )
    }

    /**
     * Whether a sample at [at] is the present one, judged by the current-price rule (a quote at most two hours old) like
     * the snapshot. Earlier samples, even minutes ago, use the historical rule (a quote at most a day old): a past point
     * carries an asset's last saved price forward instead of dropping the asset when prices were saved hours apart.
     */
    private fun isCurrent(
        at: Long,
        knownAt: Long,
    ) = at >= knownAt - CURRENT_SAMPLE_SLACK_MILLIS

    /** Evaluation instant shared by financial projections and their presentation. */
    fun evaluationTimeMillis(): Long = now()

    /** Saved per-unit observations; missing points stay missing and carry no holdings or returns. */
    fun priceSeries(
        asset: AssetId,
        quote: AssetId,
        times: List<Long>,
    ): List<MarketPricePoint> {
        val knownAt = now()
        val results = market.lookupSeries(asset, quote, times, knownAt, { at -> app.prices.policy(current = isCurrent(at, knownAt)) })
        return times.zip(results) { at, result -> MarketPricePoint(at, result.status, result.observation) }
    }

    fun historySeries(
        times: List<Long>,
        portfolio: PortfolioId? = null,
    ): List<HistoryPoint> {
        val selected = app.assets.scopedAccounts(ownership.load(), portfolio)
        val entries = ledger().activities.map { it.activity }
        val quote = app.assets.portfolioCurrency(portfolio)
        val knownAt = now()
        val balances =
            holdings.historicalSeries(entries, times, BalanceCoverage.UNKNOWN).map { balance ->
                balance.copy(quantities = balance.quantities.filterKeys { it.account in selected })
            }
        // Prices and metadata for every instant are read once per asset instead of once per asset and instant.
        val instants = balances.map { it.atMillis }
        val assets = balances.flatMap { balance -> balance.quantities.keys.map { it.asset } }.toSet()
        val prices =
            (assets - quote).associateWith { asset ->
                instants
                    .zip(
                        market.lookupSeries(asset, quote, instants, knownAt, { at ->
                            app.prices.policy(current = isCurrent(at, knownAt))
                        }),
                    ).toMap()
            }
        val metadata = assets.associateWith { market.metadataHistory(it) }
        return balances.map { balance ->
            val at = balance.atMillis
            HistoryPoint(
                at,
                app.assets.valuations(
                    balance,
                    quote,
                    knownAt,
                    app.prices.policy(current = isCurrent(at, knownAt)),
                    priceAt = { asset -> prices[asset]?.get(at) },
                    metadataAt = { asset -> metadata[asset]?.firstOrNull { it.effectiveAtMillis <= at } },
                ),
            )
        }
    }

    /** Earliest confirmed activity in the selected account scope, for historical chart bounds. */
    fun firstHistoryActivity(portfolio: PortfolioId? = null): Long? {
        val selected = app.assets.scopedAccounts(ownership.load(), portfolio)
        return ledger()
            .activities
            .asSequence()
            .map { it.activity }
            .filter {
                it.status == io.valroot.core.ledger.ActivityStatus.CONFIRMED &&
                    it.movements.any { movement -> movement.accountId in selected }
            }.minOfOrNull { it.occurredAtEpochMillis }
    }

    fun report(
        atMillis: Long,
        portfolio: PortfolioId? = null,
    ): AnalyticsSnapshot {
        app.assets.ensureDefaults()
        val catalog = ownership.load()
        val selected = app.assets.scopedAccounts(catalog, portfolio)
        val quote = app.assets.portfolioCurrency(portfolio)
        val knownAt = now()
        val entries = ledger().activities.map { it.activity }
        val balance = holdings.historical(entries, atMillis, BalanceCoverage.UNKNOWN)
        val scoped = balance.copy(quantities = balance.quantities.filterKeys { it.account in selected })
        val accounts =
            catalog.accounts
                .filter { it.id in selected }
                .map { account ->
                    val view = scoped.copy(quantities = scoped.quantities.filterKeys { it.account == account.id })
                    AccountAnalytics(account.id, account.label, app.assets.valuations(view, quote, knownAt))
                }.filter { it.valuations.isNotEmpty() }
        val rows =
            entries
                .filter { it.status == io.valroot.core.ledger.ActivityStatus.CONFIRMED && it.occurredAtEpochMillis <= atMillis }
                .flatMap { activity -> activity.movements.filter { it.accountId in selected }.map { activity to it } }
        val categories =
            rows
                .groupBy { it.first.kind to it.second.amount.assetId }
                .map { (key, group) ->
                    val incoming =
                        group.filter { it.second.amount.atomicUnits.sign > 0 }.fold(io.valroot.core.model.ExactInteger.ZERO) { sum, row ->
                            sum +
                                row.second.amount.atomicUnits
                        }
                    val outgoing =
                        group.filter { it.second.amount.atomicUnits.sign < 0 }.fold(io.valroot.core.model.ExactInteger.ZERO) { sum, row ->
                            sum -
                                row.second.amount.atomicUnits
                        }
                    CategoryAnalytics(key.first, key.second, incoming, outgoing, group.map { it.first.id }.distinct().size)
                }.sortedWith(compareBy({ it.kind.name }, { it.asset.value }))
        return AnalyticsSnapshot(
            atMillis,
            quote,
            app.assets.valuations(scoped, quote, knownAt),
            accounts,
            categories,
            BalanceCoverage.UNKNOWN,
        )
    }

    /** Confirmed activities in (start, end] with a movement in [accounts], oldest first. */
    private fun activitiesBetween(
        accounts: Set<AccountId>,
        startMillis: Long,
        endMillis: Long,
    ): List<Activity> =
        ledger()
            .activities
            .map { it.activity }
            .filter {
                it.status == io.valroot.core.ledger.ActivityStatus.CONFIRMED &&
                    it.occurredAtEpochMillis > startMillis &&
                    it.occurredAtEpochMillis <= endMillis &&
                    it.movements.any { movement -> movement.accountId in accounts }
            }.sortedBy { it.occurredAtEpochMillis }

    /** Both sides of each accepted transfer link whose two movements are in [activities] and in [accounts]. */
    private fun internalTransferSides(
        activities: List<Activity>,
        accounts: Set<AccountId>,
    ): Set<Pair<io.valroot.core.model.ActivityId, io.valroot.core.model.EventId>> {
        val byId = activities.associateBy { it.id }

        fun movement(
            activity: io.valroot.core.model.ActivityId,
            event: io.valroot.core.model.EventId,
        ) = byId[activity]?.movements?.firstOrNull { it.id == event }
        return ledger()
            .transferLinks
            .filter { link ->
                val out = movement(link.outgoingActivity, link.outgoingEvent)
                val into = movement(link.incomingActivity, link.incomingEvent)
                out != null && into != null && out.accountId in accounts && into.accountId in accounts
            }.flatMap { listOf(it.outgoingActivity to it.outgoingEvent, it.incomingActivity to it.incomingEvent) }
            .toSet()
    }

    /** The value of [amount] in [quote] at [atMillis] from a fresh saved price, or null without one. */
    private fun valueAt(
        amount: AtomicAmount,
        quote: AssetId,
        atMillis: Long,
        knownAt: Long,
    ): ExactDecimal? {
        val decimals = market.metadataAt(amount.assetId, atMillis)?.decimals ?: return null
        // A currency held on an exchange is worth one of the same currency, as in holdings.
        if (amount.assetId == quote ||
            io.valroot.core.exchange.ExchangeAssets
                .currencyOf(amount.assetId) == quote
        ) {
            return amount.decimal(decimals)
        }
        val result = market.lookup(amount.assetId, quote, atMillis, knownAt, app.prices.policy(current = isCurrent(atMillis, knownAt)))
        return if (result.status == PriceStatus.FRESH) result.value(amount, decimals) else null
    }

    /**
     * Money-weighted return of [portfolio] from [startMillis] to [endMillis]. See [MoneyWeightedReturn] for what counts as
     * a contribution. An asset without a fresh price at any point the return needs (its holding at the start or end, or
     * one of its contributions or withdrawals) is left out of every part, as coins without a price are left out of
     * totals; leaving out only the unpriced points would turn its value into a false gain or loss.
     */
    fun moneyWeightedReturn(
        portfolio: PortfolioId?,
        startMillis: Long,
        endMillis: Long,
    ): MoneyWeightedReturn {
        require(endMillis > startMillis)
        app.assets.ensureDefaults()
        val quote = app.assets.portfolioCurrency(portfolio)
        val selected = app.assets.scopedAccounts(ownership.load(), portfolio)
        val knownAt = now()
        val zero = ExactDecimal.parse("0")

        /** Each held asset's value at the point, null when it has no fresh price. */
        fun holdings(point: HistoryPoint): Map<AssetId, ExactDecimal?> =
            point.valuations
                .filter { it.amount.atomicUnits.sign != 0 }
                .groupBy { it.asset }
                .mapValues { (_, values) ->
                    if (values.any { it.value == null || it.priceStatus != PriceStatus.FRESH }) {
                        null
                    } else {
                        values.fold(zero) { sum, valuation -> sum + valuation.value!! }
                    }
                }
        val (startPoint, endPoint) = historySeries(listOf(startMillis, endMillis), portfolio)
        val start = holdings(startPoint)
        val end = holdings(endPoint)

        // Transfer movements that leave or enter the scope are flows; transfers between scoped accounts cancel out,
        // within one activity or across the two sides of a transfer link inside the period. Trades are not flows,
        // including on-chain swaps, whose legs are recorded as transfers of a trade activity.
        data class Flow(
            val asset: AssetId,
            val at: Long,
            val value: ExactDecimal?,
            val incoming: Boolean,
        )
        val period = activitiesBetween(selected, startMillis, endMillis)
        val internal = internalTransferSides(period, selected)

        fun tradeLeg(
            activity: io.valroot.core.ledger.Activity,
            movement: io.valroot.core.ledger.Movement,
        ) = movement.role == io.valroot.core.ledger.MovementRole.TRADE_LEG ||
            (activity.kind == io.valroot.core.ledger.ActivityKind.TRADE && movement.role == io.valroot.core.ledger.MovementRole.TRANSFER)

        /** Each asset's net amount among [movements], valued at the activity's time. */
        fun netFlows(
            activity: io.valroot.core.ledger.Activity,
            movements: List<io.valroot.core.ledger.Movement>,
        ) = movements.groupBy { it.amount.assetId }.mapNotNull { (asset, group) ->
            val net = group.fold(io.valroot.core.model.ExactInteger.ZERO) { sum, movement -> sum + movement.amount.atomicUnits }
            if (net.sign == 0) return@mapNotNull null
            val magnitude = AtomicAmount(asset, if (net.sign < 0) -net else net)
            Flow(asset, activity.occurredAtEpochMillis, valueAt(magnitude, quote, activity.occurredAtEpochMillis, knownAt), net.sign > 0)
        }
        val flows =
            period.flatMap { activity ->
                netFlows(
                    activity,
                    activity.movements.filter {
                        it.accountId in selected &&
                            it.role == io.valroot.core.ledger.MovementRole.TRANSFER &&
                            !tradeLeg(activity, it) &&
                            (activity.id to it.id) !in internal
                    },
                )
            }
        val trades =
            period.mapNotNull { activity ->
                activity.movements
                    .filter { it.accountId in selected && tradeLeg(activity, it) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { activity to it }
            }
        // Hidden assets (unknown tokens, NFTs) are out of the start and end values, so they are left out of the flows
        // too: receiving one is not a contribution and holding it is not a loss. They are not counted as unpriced.
        val hidden =
            app.assets.hiddenAmong(
                (flows.map { it.asset } + trades.flatMap { (_, legs) -> legs.map { it.amount.assetId } }).toSet(),
            )
        val leftOut =
            (
                hidden + start.filterValues { it == null }.keys + end.filterValues { it == null }.keys +
                    flows.filter { it.value == null }.map { it.asset }
            ).toMutableSet()
        // A trade between a measured coin and a left-out one moves value across the boundary of what is measured: the
        // measured side counts as leaving (bought the left-out coin) or entering (sold it). A measured side without a
        // price then is left out too, which can reach further trades, so this repeats until nothing changes.
        var boundary: List<Flow>
        while (true) {
            boundary =
                trades.flatMap { (activity, legs) ->
                    if (legs.none { it.amount.assetId in leftOut } || legs.all { it.amount.assetId in leftOut }) {
                        emptyList()
                    } else {
                        netFlows(activity, legs.filter { it.amount.assetId !in leftOut })
                    }
                }
            val unpriced = boundary.filter { it.value == null }.map { it.asset }
            if (unpriced.isEmpty()) break
            leftOut += unpriced
        }
        val counted = flows.filter { it.asset !in leftOut } + boundary
        val startValue = start.filterKeys { it !in leftOut }.values.fold(zero) { sum, value -> sum + value!! }
        val endValue = end.filterKeys { it !in leftOut }.values.fold(zero) { sum, value -> sum + value!! }
        val contributions = counted.filter { it.incoming }.fold(zero) { sum, flow -> sum + flow.value!! }
        val withdrawals = counted.filter { !it.incoming }.fold(zero) { sum, flow -> sum + flow.value!! }
        // Measurable when at least one asset has every price it needs, or when there is nothing at all (no investment).
        val assets =
            start.keys + end.keys + flows.map { it.asset } + trades.flatMap { (_, legs) -> legs.map { it.amount.assetId } } - hidden
        val priced = assets.isEmpty() || assets.any { it !in leftOut }
        val gain = if (priced) endValue - startValue - contributions + withdrawals else null
        val year = 365.25 * 86_400_000.0
        val span = (endMillis - startMillis) / year
        val (rate, status) =
            if (!priced) {
                null to ReturnStatus.MISSING_PRICES
            } else {
                // Investor view: money put in is negative, money taken out and the final value are positive. Flows at
                // the same instant are summed exactly first, so money in and out at once cancels.
                val net = linkedMapOf<Long, ExactDecimal>()
                net[startMillis] = zero - startValue
                counted.forEach { flow ->
                    net[flow.at] = (net[flow.at] ?: zero) + (if (flow.incoming) zero - flow.value!! else flow.value!!)
                }
                net[endMillis] = (net[endMillis] ?: zero) + endValue
                InternalRate.solve(
                    net
                        .map { (at, amount) ->
                            DatedFlow((at - startMillis) / year, amount.toString().toDouble())
                        }.filter { it.amount != 0.0 },
                )
            }
        return MoneyWeightedReturn(
            startMillis,
            endMillis,
            quote,
            startValue.takeIf { priced },
            endValue.takeIf { priced },
            contributions.takeIf { priced },
            withdrawals.takeIf { priced },
            gain,
            rate?.let { (1 + it).pow(span) - 1 },
            rate?.takeIf { span >= 365.0 / 365.25 },
            status,
            (leftOut - hidden).size,
            counted.size,
        )
    }

    /** Trades, fees and account use of [portfolio] from [startMillis] to [endMillis], from confirmed activities. */
    fun insights(
        portfolio: PortfolioId?,
        startMillis: Long,
        endMillis: Long,
    ): ActivityInsights {
        app.assets.ensureDefaults()
        val catalog = ownership.load()
        val selected = app.assets.scopedAccounts(catalog, portfolio)
        val quote = app.assets.portfolioCurrency(portfolio)
        val knownAt = now()
        val activities = activitiesBetween(selected, startMillis, endMillis)
        val trades = activities.filter { it.kind == io.valroot.core.ledger.ActivityKind.TRADE }
        val tradedAssets =
            trades
                .flatMap { trade ->
                    trade.movements.filter { it.accountId in selected && it.role == io.valroot.core.ledger.MovementRole.TRADE_LEG }
                }.map { it.amount.assetId }
                .toSet()
        // Each fee is counted once, in its own asset, and valued at the time it was paid.
        val fees =
            activities
                .flatMap { activity ->
                    activity.movements
                        .filter {
                            it.accountId in selected &&
                                it.role == io.valroot.core.ledger.MovementRole.FEE &&
                                it.amount.atomicUnits.sign < 0
                        }.map { activity.occurredAtEpochMillis to it }
                }.groupBy { it.second.amount.assetId }
                .map { (asset, paid) ->
                    var value: ExactDecimal? = ExactDecimal.parse("0")
                    var unpriced = 0
                    paid.forEach { (at, movement) ->
                        val amount = AtomicAmount(asset, -movement.amount.atomicUnits)
                        val worth = valueAt(amount, quote, at, knownAt)
                        if (worth == null) unpriced++ else value = value?.plus(worth)
                    }
                    FeeTotal(
                        asset,
                        paid.fold(io.valroot.core.model.ExactInteger.ZERO) { sum, (_, movement) -> sum - movement.amount.atomicUnits },
                        market.metadataAt(asset, knownAt)?.decimals,
                        value.takeIf { unpriced == 0 },
                        unpriced,
                    )
                }.sortedBy { it.asset.value }
        val accounts =
            catalog.accounts
                .filter { it.id in selected }
                .map { account ->
                    AccountUse(
                        account.id,
                        account.label,
                        activities.count { a ->
                            a.movements.any { it.accountId == account.id }
                        },
                    )
                }.filter { it.activities > 0 }
                .sortedByDescending { it.activities }
        return ActivityInsights(trades.size, tradedAssets.size, fees, accounts)
    }

    fun accountOverview(portfolio: PortfolioId? = null): List<AccountOverview> {
        app.assets.ensureDefaults()
        val catalog = ownership.load()
        val unarchived = catalog.copy(accounts = catalog.accounts.map { it.copy(tags = it.tags - "valroot:archived") })
        val membership = catalog.portfolios.associate { it.id to app.assets.scopedAccounts(unarchived, it.id) }
        val entries = ledger().activities.map { it.activity }
        val balance = holdings.historical(entries, now(), BalanceCoverage.UNKNOWN)
        val projection = AccountHistoryProjection(db)
        val endpoints = app.bitcoin.providerEndpoints()
        val quote = app.assets.portfolioCurrency(portfolio)
        return catalog.accounts.map { account ->
            val targets = catalog.targets.filter { it.accountId == account.id && it.identity.networkId == BITCOIN_MAINNET }
            val active =
                targets
                    .filter { it.enabled && it.role == TargetRole.LEAF && it.identity.kind == "address" }
                    .map { bitcoinMainnetAddress(it.identity.canonicalKey) }
                    .toSet()
            val accepted = projection.latest(account.id)
            val discovered =
                targets.filter { it.enabled && it.role == TargetRole.WALLET }.all { wallet ->
                    targets.any { it.enabled && it.walletId == wallet.id }
                }
            AccountOverview(
                account,
                targets.filter { it.walletId == null },
                targets.count { it.walletId != null },
                catalog.portfolios.filter { account.id in membership.getValue(it.id) }.map { it.label },
                app.assets.valuations(balance.copy(quantities = balance.quantities.filterKeys { it.account == account.id }), quote, now()),
                accepted,
                discovered &&
                    active.isNotEmpty() &&
                    accepted?.addresses == active &&
                    accepted.providerSourceId == "esplora:" + sha256(app.sources.endpoint()),
                accepted?.let { endpoints[it.providerSourceId] },
            )
        }
    }

    private companion object {
        /** A chart takes "now" just before it queries, so its last sample counts as the present one within this slack. */
        const val CURRENT_SAMPLE_SLACK_MILLIS = 60_000L
    }
}
