package io.valroot.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.valroot.core.market.CandleRange
import io.valroot.core.market.CandleSeries
import io.valroot.core.model.AssetId
import io.valroot.ui.model.Candle
import io.valroot.ui.model.ChartData
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Market candles of one coin and range: [series] once loaded, or [message] when none could be loaded. */
internal class CoinCandlesState(
    val series: CandleSeries?,
    val message: String?,
    val loading: Boolean,
)

/** Loads candles of [coinId] for [range] while [active]; a new coin, range or currency loads again. */
@Composable
internal fun rememberCoinCandles(
    host: PlatformActions,
    coinId: String?,
    range: CandleRange?,
    currency: AssetId,
    active: Boolean,
): CoinCandlesState {
    var series by remember(coinId, range, currency) { mutableStateOf<CandleSeries?>(null) }
    var message by remember(coinId, range, currency) { mutableStateOf<String?>(null) }
    var loading by remember(coinId, range, currency) { mutableStateOf(false) }
    LaunchedEffect(host, coinId, range, currency, active) {
        if (coinId == null || range == null || !active || series != null) return@LaunchedEffect
        loading = true
        val result =
            suspendCancellableCoroutine { continuation ->
                host.fetchCandles(coinId, range) { result -> if (continuation.isActive) continuation.resume(result) }
            }
        loading = false
        when (result) {
            is CandleResult.Loaded -> {
                series = result.series
                message = null
            }
            is CandleResult.Unavailable -> message = result.message
        }
    }
    return CoinCandlesState(series, message, loading)
}

/** Chart data drawing [series] as candles, with each candle's close for the line. */
internal fun candleChartData(
    series: CandleSeries,
    currency: AssetId,
): ChartData {
    val candles =
        series.candles.map {
            Candle(
                it.open.toString().toDouble(),
                it.high.toString().toDouble(),
                it.low.toString().toDouble(),
                it.close.toString().toDouble(),
                it.openMillis,
                it.closeMillis,
            )
        }
    val span = series.candles.last().closeMillis - series.candles.first().openMillis
    return ChartData(
        candles.map { it.close },
        candles.map { it.end },
        { sparklinePrice(it, currency) },
        { chartTime(it, span) },
        candles = candles,
    )
}

/** The line under a candle chart: where the candles come from and how long each is, or why there are none. */
internal fun candleNote(state: CoinCandlesState): String? =
    state.series?.let { "${it.source} · ${candleLength(it.intervalMillis)} candles" }
        ?: state.message
        ?: if (state.loading) "Loading candles…" else null

private fun candleLength(millis: Long): String {
    val minutes = millis / 60_000
    return when {
        minutes < 60 -> "$minutes-minute"
        minutes < 1440 -> "${minutes / 60}-hour"
        minutes % 10080 == 0L -> if (minutes == 10080L) "weekly" else "${minutes / 10080}-week"
        minutes == 1440L -> "daily"
        else -> "${minutes / 1440}-day"
    }
}
