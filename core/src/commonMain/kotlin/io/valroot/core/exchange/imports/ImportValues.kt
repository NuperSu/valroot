package io.valroot.core.exchange.imports

import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import io.valroot.core.storage.sha256

/** Exact numbers and identities of export cells. */
object ImportValues {
    private val NUMBER = Regex("""([+-]?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d{1,3}))?""")

    /** The exact value of a number cell (`-0.5`, `1.5e-05`, `+3`, `05.`); a blank or non-number cell fails with [label]. */
    fun decimal(
        text: String,
        label: String,
    ): ExactDecimal {
        val trimmed = text.trim()
        val match = NUMBER.matchEntire(trimmed)
        val (sign, whole, fraction, exponent) = match?.destructured ?: throw ImportFileException("\"$text\" in $label is not a number")
        if (whole.isEmpty() && fraction.isEmpty()) throw ImportFileException("\"$text\" in $label is not a number")
        val digits = (whole + fraction).trimStart('0').ifEmpty { "0" }
        val scale = fraction.length - (exponent.toIntOrNull() ?: 0)
        val signed = if (sign == "-") "-$digits" else digits
        return if (scale >= 0) {
            ExactDecimal(ExactInteger.parse(signed), scale)
        } else {
            ExactDecimal(ExactInteger.parse(signed + "0".repeat(-scale)), 0)
        }
    }

    private val COIN = Regex("[A-Za-z][A-Za-z0-9]*")

    private fun isNumber(text: String) =
        NUMBER.matchEntire(text)?.let { it.groupValues[2].isNotEmpty() || it.groupValues[3].isNotEmpty() } == true

    /** Like [decimal], but null for a blank cell or a dash. */
    fun optionalDecimal(
        text: String,
        label: String,
    ): ExactDecimal? = text.trim().takeUnless { it.isEmpty() || it == "--" || it == "-" }?.let { decimal(it, label) }

    /**
     * An amount written with its coin, such as `0.5BTC` or `10.51INCH`. The coin is the longest of [coins] the text ends
     * with (so a coin starting with a digit is not read as part of the number); without one, the letters after the
     * number. Fails with [label] when the text is not an amount and coin.
     */
    fun amountAndCoin(
        text: String,
        coins: Collection<String>,
        label: String,
    ): Pair<ExactDecimal, String> {
        val trimmed = text.trim()
        val coin =
            coins
                .filter { it.isNotEmpty() && trimmed.endsWith(it) && isNumber(trimmed.dropLast(it.length)) }
                .maxByOrNull { it.length }
                ?: trimmed.indices
                    .firstOrNull { at -> isNumber(trimmed.take(at)) && COIN.matches(trimmed.drop(at)) }
                    ?.let { trimmed.drop(it) }
                ?: throw ImportFileException("\"$text\" in $label is not an amount with its coin")
        return decimal(trimmed.dropLast(coin.length), label) to coin
    }

    /**
     * Keys identifying records by content within one report kind: equal records get the ordinals 0, 1, 2… in the order
     * given, which within a report is the file's order. The same rows in another file, in another order or under another
     * name get the same keys; identical rows stay distinct records.
     */
    fun keys(
        namespace: String,
        contents: List<String>,
    ): List<String> {
        val seen = mutableMapOf<String, Int>()
        return contents.map { content ->
            val hash = sha256("$namespace\n$content").take(32)
            val ordinal = seen.getOrElse(hash) { 0 }
            seen[hash] = ordinal + 1
            "$namespace:$hash:$ordinal"
        }
    }
}
