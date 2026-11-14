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

/**
 * Market candles of one coin: [series] is the latest loaded, which while [loading] may still be the previous range's, so
 * the chart keeps candles instead of falling back to its line. [message] says why none could be loaded.
 */
internal class CoinCandlesState(
    val series: CandleSeries?,
    val message: String?,
    val loading: Boolean,
)

/** Candles loaded less than this long ago are shown again without a request. */
internal const val CANDLES_REUSE_MILLIS = 60_000L

private class LoadedCandles(
    val series: CandleSeries,
    val atMillis: Long,
)

/**
 * Loads candles of [coinId] for [range] while [active]. Each range's candles are kept while the coin page is open: a
 * range shown again appears at once and is loaded again in the background when older than [CANDLES_REUSE_MILLIS].
 * While a range loads, the previous candles stay on screen.
 */
@Composable
internal fun rememberCoinCandles(
    host: PlatformActions,
    coinId: String?,
    range: CandleRange?,
    currency: AssetId,
    active: Boolean,
    now: () -> Long = {
        kotlin.time.Clock.System
            .now()
            .toEpochMilliseconds()
    },
): CoinCandlesState {
    val loaded = remember(coinId, currency) { androidx.compose.runtime.mutableStateMapOf<CandleRange, LoadedCandles>() }
    var shown by remember(coinId, currency) { mutableStateOf<CandleSeries?>(null) }
    var message by remember(coinId, range, currency) { mutableStateOf<String?>(null) }
    var loading by remember(coinId, currency) { mutableStateOf(false) }
    LaunchedEffect(host, coinId, range, currency, active) {
        if (coinId == null || range == null || !active) return@LaunchedEffect
        val saved = loaded[range]
        if (saved != null) {
            shown = saved.series
            if (now() - saved.atMillis < CANDLES_REUSE_MILLIS) return@LaunchedEffect
        }
        loading = true
        val result =
            try {
                suspendCancellableCoroutine { continuation ->
                    host.fetchCandles(coinId, range) { result -> if (continuation.isActive) continuation.resume(result) }
                }
            } finally {
                loading = false
            }
        when (result) {
            is CandleResult.Loaded -> {
                loaded[range] = LoadedCandles(result.series, now())
                shown = result.series
                message = null
            }
            is CandleResult.Unavailable ->
                if (saved == null) {
                    // Another range's candles would mislabel this one: the chart shows its line and why.
                    shown = null
                    message = result.message
                }
        }
    }
    return CoinCandlesState(shown, message, loading)
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
                it.forming,
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
    if (state.loading) {
        "Loading candles…"
    } else {
        state.series?.let { "${it.source} · ${candleLength(it.intervalMillis)} candles" } ?: state.message
    }

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
