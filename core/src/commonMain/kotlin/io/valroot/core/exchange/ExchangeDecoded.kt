package io.valroot.core.exchange

import io.valroot.core.ledger.Activity
import io.valroot.core.model.ActivityId

/** An on-chain transfer behind an exchange activity: the coin's chain as the exchange names it, and the transaction ID. */
data class ExchangeTransfer(
    val chain: String,
    val txId: String,
)

data class ExchangeDecoded(
    val activities: List<Activity>,
    /** Activities that move coins on chain, by activity. */
    val transfers: Map<ActivityId, ExchangeTransfer>,
    /** Symbols without a coin; records that move them wait until the user chooses one. */
    val unresolved: Set<String>,
    /** Records that could not be represented, such as amounts finer than the asset's decimals. */
    val failures: List<String>,
    /** What became of each imported record, by its key. */
    val imports: Map<String, ImportOutcome> = emptyMap(),
)

/** What a decode did with one imported record. */
sealed interface ImportOutcome {
    /** Became an activity of its own. */
    data class New(
        val activity: io.valroot.core.model.ActivityId,
    ) : ImportOutcome

    /** The same event as an activity other records gave; the record is further evidence for it. */
    data class Same(
        val activity: io.valroot.core.model.ActivityId,
    ) : ImportOutcome

    /** Waits for the user's decision on its group. */
    data class Ambiguous(
        val group: String,
        val records: Int,
        val candidates: Int,
    ) : ImportOutcome

    /** Left out by the user's decision. */
    data class Excluded(
        val group: String,
    ) : ImportOutcome

    /** A statement row that disagrees with the detailed records of its moment; left out and reported. */
    data object Conflict : ImportOutcome

    /** Moves a coin not matched to an asset yet. */
    data object CoinPending : ImportOutcome
}
