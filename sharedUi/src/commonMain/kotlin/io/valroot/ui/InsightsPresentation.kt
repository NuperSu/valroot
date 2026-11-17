package io.valroot.ui

import androidx.compose.ui.graphics.Color
import io.valroot.core.application.AccountingView
import io.valroot.core.application.ActivityInsights
import io.valroot.core.application.AnalyticsSnapshot
import io.valroot.core.application.HistoryPoint
import io.valroot.core.application.MoneyWeightedReturn
import io.valroot.core.application.PortfolioSnapshot
import io.valroot.core.application.ReturnStatus
import io.valroot.core.market.PriceStatus
import io.valroot.core.model.AssetId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.Rational
import io.valroot.ui.model.ChartData
import io.valroot.ui.model.ModelChangeTone
import io.valroot.ui.model.ModelFeeRow
import io.valroot.ui.model.ModelGainRow
import io.valroot.ui.model.ModelInsightsState
import io.valroot.ui.model.ModelMetric
import io.valroot.ui.model.ModelReturnState
import io.valroot.ui.model.ModelSlice

/** Calculations behind Insights for one range; any part may be missing while it loads or if it failed. */
internal data class InsightsData(
    val performance: MoneyWeightedReturn?,
    val activity: ActivityInsights?,
    val analytics: AnalyticsSnapshot?,
)

private const val SLICES = 6

private fun Rational.signed(currency: AssetId) = signedMoney(this, currency)

private fun ExactDecimal.rational() = Rational.fromDecimal(this)

internal fun returnState(
    result: MoneyWeightedReturn?,
    hidden: Boolean,
): ModelReturnState {
    if (result == null) return ModelReturnState(status = "Calculating…")
    val currency = result.currency

    fun amount(value: ExactDecimal?) = (value?.let { money(it, currency) } ?: "—").hiddenIf(hidden)
    val rows =
        listOf(
            ModelMetric("Value at start", amount(result.startValue)),
            ModelMetric("Money in", amount(result.contributions)),
            ModelMetric("Money out", amount(result.withdrawals)),
            ModelMetric("Value at end", amount(result.endValue)),
            ModelMetric("Investment gain", (result.gain?.rational()?.signed(currency) ?: "—").hiddenIf(hidden)),
        )
    val leftOut =
        result.leftOutAssets.takeIf { it > 0 }?.let { count ->
            "Leaves out $count ${if (count == 1) "coin" else "coins"} without a saved price at the start, end or a transfer."
        }
    val status =
        when (result.status) {
            ReturnStatus.OK -> leftOut
            ReturnStatus.NO_INVESTMENT -> listOfNotNull("Nothing was invested during this period.", leftOut).joinToString(" ")
            ReturnStatus.MISSING_PRICES -> "No rate: no coin has a saved price at every point this period needs."
            ReturnStatus.NO_UNIQUE_RATE -> listOfNotNull("No single rate fits these deposits and withdrawals.", leftOut).joinToString(" ")
            ReturnStatus.NO_CONVERGENCE ->
                listOfNotNull(
                    "The rate could not be determined for these cash flows.",
                    leftOut,
                ).joinToString(" ")
        }
    val period = result.periodReturn
    return ModelReturnState(
        periodReturn = percentChange(period?.times(100)),
        annualReturn = result.annualReturn?.let { "${percentChange(it * 100)} a year" },
        tone =
            when {
                period == null || period == 0.0 -> ModelChangeTone.NEUTRAL
                period > 0 -> ModelChangeTone.POSITIVE
                else -> ModelChangeTone.NEGATIVE
            },
        rows = rows,
        status = status,
    )
}

internal fun insightsState(
    data: PortfolioSnapshot,
    chart: List<HistoryPoint>,
    range: String,
    ranges: List<String>,
    portfolio: String,
    insights: InsightsData?,
    accounting: AccountingView?,
    hidden: Boolean,
    worthSelected: String?,
    loading: Boolean,
): ModelInsightsState {
    val currency = data.currency
    val summary = pricingSummary(data.valuations)

    fun moneyOrHidden(value: ExactDecimal?) = (value?.let { money(it, currency) } ?: "—").hiddenIf(hidden)

    fun color(asset: AssetId) =
        identity(
            asset.value,
            data.valuations
                .find { it.asset == asset }
                ?.metadata
                ?.symbol,
            null,
        ).color
    val shares = allocation(data.valuations)
    val diversity =
        shares.take(SLICES).map { (asset, share) ->
            ModelSlice(
                asset.asset.value,
                asset.metadata?.symbol ?: asset.asset.value,
                share.toFloat(),
                moneyOrHidden(asset.value),
                color(asset.asset),
            )
        } +
            listOfNotNull(
                shares.drop(SLICES).takeIf { it.isNotEmpty() }?.let { rest ->
                    ModelSlice("other", "Other", rest.sumOf { it.second }.toFloat(), "", Color.Gray)
                },
            )
    val report = accounting?.takeIf { it.report.quoteAsset == currency }
    val uncertain = report == null || report.missingTreatments.isNotEmpty() || report.invalidTreatments.isNotEmpty()

    fun total(values: List<Rational?>): Rational? =
        if (uncertain || values.any { it == null }) null else values.filterNotNull().fold(Rational.ZERO) { a, b -> a + b }
    val gainAssets =
        (
            data.valuations.filter { it.amount.atomicUnits.sign != 0 }.map { it.asset } +
                report
                    ?.report
                    ?.disposals
                    .orEmpty()
                    .map { it.asset }
        ).distinct()
    val gains =
        gainAssets.map { asset ->
            val valuation = data.valuations.find { it.asset == asset }
            val symbol = valuation?.metadata?.symbol ?: asset.value
            val unrealized =
                total(
                    report
                        ?.report
                        ?.valuations
                        .orEmpty()
                        .filter { it.lot.asset == asset }
                        .map { it.unrealizedGain },
                )
            val realized =
                total(
                    report
                        ?.report
                        ?.disposals
                        .orEmpty()
                        .filter { it.asset == asset }
                        .map { it.gain },
                )
            ModelGainRow(
                asset.value,
                symbol,
                (valuation?.metadata?.let { formatUnits(valuation.amount.atomicUnits, it.decimals) } ?: "—").hiddenIf(hidden),
                (realized?.signed(currency) ?: "—").hiddenIf(hidden),
                (unrealized?.signed(currency) ?: "—").hiddenIf(hidden),
                tone(unrealized),
            )
        }
    val unpricedHoldings =
        insights
            ?.analytics
            ?.accounts
            .orEmpty()
            .sumOf { account -> account.valuations.count { it.value == null } }
    val location =
        insights
            ?.analytics
            ?.accounts
            .orEmpty()
            .mapNotNull { account ->
                val priced = account.valuations.mapNotNull { it.value }
                if (priced.isEmpty()) null else account to priced.fold(ExactDecimal.parse("0")) { sum, value -> sum + value }
            }.let { accounts ->
                val sum = accounts.sumOf { it.second.toString().toDouble() }
                accounts.sortedByDescending { it.second }.mapNotNull { (account, value) ->
                    if (sum <= 0) {
                        null
                    } else {
                        ModelSlice(
                            account.account.value,
                            account.label,
                            (value.toString().toDouble() / sum).toFloat(),
                            moneyOrHidden(value),
                            identity(account.account.value, account.label, null).color,
                        )
                    }
                }
            }
    val activity = insights?.activity
    val fees =
        activity?.fees.orEmpty().map { fee ->
            val symbol =
                data.valuations
                    .find { it.asset == fee.asset }
                    ?.metadata
                    ?.symbol ?: fee.asset.value
            ModelFeeRow(
                ("${fee.decimals?.let { formatUnits(fee.atomicUnits, it) } ?: fee.atomicUnits.toString()} $symbol").hiddenIf(hidden),
                if (fee.value == null) "${fee.unpriced} unpriced" else moneyOrHidden(fee.value),
            )
        }
    val feesTotal =
        activity?.fees?.takeIf { list -> list.isNotEmpty() && list.all { it.value != null } }?.let { list ->
            moneyOrHidden(list.fold(ExactDecimal.parse("0")) { sum, fee -> sum + fee.value!! })
        }
    // Asset Worth follows the history range: each asset's position value at each sampled time.
    val worthAssets = diversity.filter { it.id != "other" }
    val selectedWorth = worthSelected?.takeIf { id -> worthAssets.any { it.id == id } } ?: worthAssets.firstOrNull()?.id
    val worth =
        selectedWorth?.let { id ->
            val values =
                chart.map { point ->
                    point.valuations
                        .firstOrNull { it.asset.value == id }
                        ?.takeIf { it.priceStatus == PriceStatus.FRESH }
                        ?.value
                        ?.toString()
                        ?.toDoubleOrNull()
                }
            if (chart.size < 2 || values.all { it == null }) {
                null
            } else {
                val span = chart.last().atMillis - chart.first().atMillis
                ChartData(values, chart.map { it.atMillis }, { it.asMoney(currency) }, { chartTime(it, span) }, hidden)
            }
        }
    return ModelInsightsState(
        balance = (summary.total?.let { money(it, currency) } ?: "—").hiddenIf(hidden),
        portfolio = portfolio,
        range = range,
        ranges = ranges,
        history = portfolioChartData(chart, currency, hidden),
        performance = returnState(insights?.performance, hidden),
        diversity = diversity,
        diversityNote = if (summary.missing > 0) "${summary.missing} unpriced asset(s) are not in the total." else null,
        gains = gains,
        gainsNote =
            when {
                report == null -> "Calculating cost basis…"
                uncertain -> "Some transactions need a classification; affected figures show —."
                report.estimatedAssets.isNotEmpty() -> ESTIMATED_COST_NOTE
                else -> null
            },
        location = location,
        locationNote =
            if (unpricedHoldings > 0) "$unpricedHoldings unpriced holding(s) are not in these account values." else null,
        accountUse =
            activity?.accounts.orEmpty().map {
                ModelMetric(it.label, "${it.activities} activit${if (it.activities == 1) "y" else "ies"}")
            },
        trades = activity?.trades?.toString() ?: "—",
        tradedAssets = activity?.tradedAssets?.toString() ?: "—",
        fees = fees,
        feesTotal = feesTotal,
        worthAssets = worthAssets,
        worthSelected = selectedWorth,
        worth = worth,
        loading = loading,
    )
}
