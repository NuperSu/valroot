package io.valroot.core.market

import io.valroot.core.model.ExactDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One market candle: open, high, low and close traded prices of [openMillis]..[closeMillis]. A [forming] candle has not
 * closed yet: its close is the latest price, and [closeMillis] is the time of that price.
 */
data class MarketCandle(
    val openMillis: Long,
    val closeMillis: Long,
    val open: ExactDecimal,
    val high: ExactDecimal,
    val low: ExactDecimal,
    val close: ExactDecimal,
    val forming: Boolean = false,
)

/** Candles of one coin for a chart range, with where they come from and the length of each candle. */
data class CandleSeries(
    val source: String,
    val intervalMillis: Long,
    val candles: List<MarketCandle>,
)

/** The coin chart ranges candles are loaded for, with how far back each reaches. */
enum class CandleRange(
    val label: String,
    val spanMillis: Long,
) {
    HOUR("1H", 3_600_000L),
    DAY("1D", 86_400_000L),
    WEEK("1W", 7 * 86_400_000L),
    MONTH("1M", 30 * 86_400_000L),
    QUARTER("3M", 90 * 86_400_000L),
    YEAR("1Y", 365 * 86_400_000L),
    ;

    companion object {
        fun of(label: String): CandleRange? = entries.firstOrNull { it.label == label }
    }
}

/**
 * CoinGecko's OHLC candles. It picks the candle length from the days requested: 30 minutes for 1–2 days, 4 hours for
 * 3–30 days and 4 days beyond. Each row is `[close time, open, high, low, close]`.
 */
object CoingeckoCandles {
    const val SOURCE = "CoinGecko"

    /** Days requested per range; null for ranges shorter than CoinGecko's shortest candle. */
    fun days(range: CandleRange): Int? =
        when (range) {
            CandleRange.HOUR -> null
            CandleRange.DAY -> 1
            CandleRange.WEEK -> 7
            CandleRange.MONTH -> 30
            CandleRange.QUARTER -> 90
            CandleRange.YEAR -> 365
        }

    /**
     * Shorter candle ranges that cover the candle still forming after the last closed one, longest first: CoinGecko
     * lists closed candles only, so a 4-day candle can be up to four days behind.
     */
    fun finerDays(days: Int): List<Int> =
        when {
            days <= 2 -> emptyList()
            days <= 30 -> listOf(1)
            else -> listOf(7, 1)
        }

    /**
     * The candle forming after [lastClose], combined from shorter closed candles that started at or after it: the first
     * open, the highest high, the lowest low and the latest close. Null when none started yet.
     */
    fun forming(
        lastClose: Long,
        shorter: List<MarketCandle>,
    ): MarketCandle? {
        val pieces = shorter.filter { it.openMillis >= lastClose }
        if (pieces.isEmpty()) return null
        return MarketCandle(
            lastClose,
            pieces.last().closeMillis,
            pieces.first().open,
            pieces.maxOf { it.high },
            pieces.minOf { it.low },
            pieces.last().close,
            forming = true,
        )
    }

    fun intervalMillis(days: Int): Long =
        when {
            days <= 2 -> 30 * 60_000L
            days <= 30 -> 4 * 3_600_000L
            else -> 4 * 86_400_000L
        }

    fun url(
        baseUrl: String,
        coinId: String,
        currency: String,
        days: Int,
    ): String {
        require(coinId.matches(COIN_ID) && currency.matches(CURRENCY))
        return "$baseUrl/coins/$coinId/ohlc?vs_currency=${currency.lowercase()}&days=$days"
    }

    fun parse(
        payload: String,
        intervalMillis: Long,
    ): List<MarketCandle> {
        val rows = Json.parseToJsonElement(payload).jsonArray
        var previous = Long.MIN_VALUE
        return rows.map { row ->
            val cells = row.jsonArray
            require(cells.size == 5)
            val close = cells[0].jsonPrimitive.content.toLong()
            require(close > previous)
            previous = close
            val (open, high, low, last) = (1..4).map { price(cells[it]) }
            requireConsistent(open, high, low, last)
            MarketCandle(close - intervalMillis, close, open, high, low, last)
        }
    }

    private val COIN_ID = Regex("[a-z0-9-]{1,100}")
    private val CURRENCY = Regex("[A-Za-z]{2,10}")
}

/**
 * CoinGecko candle responses kept for [MAX_AGE_MILLIS] in this process, by coin, currency and days. Ranges share them:
 * the 7-day response is both the 1W candles and the 4-hour pieces of a forming 4-day candle, so switching ranges rarely
 * needs a request, which matters with CoinGecko's few keyless requests a minute.
 */
internal object CoingeckoCandleCache {
    const val MAX_AGE_MILLIS = 3 * 60_000L
    private const val MAX_ENTRIES = 64
    private val entries = LinkedHashMap<String, Pair<Long, List<MarketCandle>>>()
    private val lock = kotlinx.atomicfu.locks.SynchronizedObject()

    fun get(
        key: String,
        now: Long,
    ): List<MarketCandle>? =
        kotlinx.atomicfu.locks.synchronized(lock) { entries[key]?.takeIf { now - it.first in 0 until MAX_AGE_MILLIS }?.second }

    fun put(
        key: String,
        now: Long,
        candles: List<MarketCandle>,
    ) = kotlinx.atomicfu.locks.synchronized(lock) {
        entries.remove(key)
        entries[key] = now to candles
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    fun clear() = kotlinx.atomicfu.locks.synchronized(lock) { entries.clear() }
}

/**
 * Kraken's public OHLC candles for one pair. Rows are `[open time (s), open, high, low, close, vwap, volume, count]`;
 * the last row is the candle still forming.
 */
object KrakenCandles {
    const val SOURCE = "Kraken"

    /** Candle length in minutes per range, so each range shows a few dozen candles. */
    fun intervalMinutes(range: CandleRange): Int =
        when (range) {
            CandleRange.HOUR -> 1
            CandleRange.DAY -> 30
            CandleRange.WEEK -> 240
            CandleRange.MONTH -> 1440
            CandleRange.QUARTER -> 1440
            CandleRange.YEAR -> 10080
        }

    fun url(
        pair: KrakenPair,
        intervalMinutes: Int,
        sinceMillis: Long,
    ): String = "https://api.kraken.com/0/public/OHLC?pair=${pair.restName}&interval=$intervalMinutes&since=${sinceMillis / 1000}"

    fun parse(
        payload: String,
        intervalMinutes: Int,
    ): List<MarketCandle> {
        val interval = intervalMinutes * 60_000L
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root.getValue("error").jsonArray.isEmpty())
        val rows =
            root
                .getValue("result")
                .jsonObject.entries
                .single { it.key != "last" }
                .value as JsonArray
        var previous = Long.MIN_VALUE
        return rows.mapIndexed { index, row ->
            val cells = row.jsonArray
            require(cells.size == 8)
            val open = cells[0].jsonPrimitive.content.toLong() * 1000
            require(open > previous)
            previous = open
            val (first, high, low, close) = (1..4).map { price(cells[it]) }
            requireConsistent(first, high, low, close)
            MarketCandle(open, open + interval, first, high, low, close, forming = index == rows.lastIndex)
        }
    }
}

/** A positive price: a JSON number (CoinGecko, possibly with an exponent) or a decimal string (Kraken). */
private fun price(cell: kotlinx.serialization.json.JsonElement): ExactDecimal {
    val text = (cell as JsonPrimitive).content
    require(text.length <= 80)
    val value = if (cell.isString) ExactDecimal.parse(text) else CoingeckoPrices.parseJsonNumber(text)
    return value.also { require(it.coefficient.sign > 0) }
}

private fun requireConsistent(
    open: ExactDecimal,
    high: ExactDecimal,
    low: ExactDecimal,
    close: ExactDecimal,
) {
    require(low <= open && low <= close && high >= open && high >= close && low <= high)
}
