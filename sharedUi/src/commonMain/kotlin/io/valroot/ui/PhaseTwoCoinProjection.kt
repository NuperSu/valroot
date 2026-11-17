package io.valroot.ui

import io.valroot.core.application.AccountingView
import io.valroot.core.application.AssetValuation
import io.valroot.core.model.Rational
import io.valroot.ui.model.ModelMetric

internal fun coinAccountingMetrics(
    view: AccountingView?,
    asset: AssetValuation,
): List<ModelMetric> = coinAccountingMetrics(view, listOf(asset))

/**
 * Gains and cost basis of [held], the coin's assets the page totals (one per chain it is held on). Scoped accounting
 * evidence supplies figures only when treatments and acquisition basis permit them.
 */
internal fun coinAccountingMetrics(
    view: AccountingView?,
    held: List<AssetValuation>,
): List<ModelMetric> {
    val assets = held.map { it.asset }.toSet()
    val heldAssets = held.filter { it.amount.atomicUnits.sign != 0 }.map { it.asset }
    val uncertain = view == null || view.missingTreatments.isNotEmpty() || view.invalidTreatments.isNotEmpty()

    fun total(values: List<Rational?>): Rational? =
        if (uncertain || values.any { it == null }) null else values.filterNotNull().fold(Rational.ZERO) { a, b -> a + b }
    val lots =
        view
            ?.report
            ?.lots
            .orEmpty()
            .filter { it.asset in assets }
    val valuations =
        view
            ?.report
            ?.valuations
            .orEmpty()
            .filter { it.lot.asset in assets }
    val basis = if (heldAssets.any { a -> lots.none { it.asset == a } }) null else total(lots.map { it.basis })
    val unrealized =
        if (heldAssets.any { a ->
                valuations.none { it.lot.asset == a }
            }
        ) {
            null
        } else {
            total(valuations.map { it.unrealizedGain })
        }
    val realized =
        total(
            view
                ?.report
                ?.disposals
                .orEmpty()
                .filter { it.asset in assets }
                .map { it.gain },
        )
    val gains = if (unrealized == null || realized == null) null else unrealized + realized

    fun figure(value: Rational?): String = value?.let { "${it.displayAmount()} ${view?.report?.quoteAsset?.value}" } ?: "—"
    return listOf(
        ModelMetric("Total Gains", figure(gains)),
        ModelMetric("Total Cost Basis", figure(basis)),
        ModelMetric("Unrealized Gain", figure(unrealized)),
        ModelMetric("Realized Gain", figure(realized)),
    )
}
