package io.valroot.core.exchange.imports

import io.valroot.core.model.ExactDecimal
import io.valroot.core.storage.sha256

/** The economic kinds events are compared within; a trade never matches a deposit. */
enum class MatchKind { TRADE, IN, OUT, FUTURES_IN, FUTURES_OUT, REWARD, FEE, OTHER }

/**
 * An economic event reduced to what matching compares: its kind, time, principal amounts by coin (signed; fees left
 * out, since sources report them differently) and identifiers such as `txid:0x…` or `trade:BTCUSDT:123`, each
 * qualified by its namespace so identifiers of different kinds never collide.
 */
data class EventShape(
    val id: String,
    val kind: MatchKind,
    val atMillis: Long,
    val principal: Map<String, ExactDecimal>,
    val ids: Set<String> = emptySet(),
)

/** What the user decided for an ambiguous group. */
enum class GroupDecision { SAME, DISTINCT, EXCLUDED }

/** How one imported record relates to the events the account already has. */
sealed interface MatchOutcome {
    /** A new economic event. */
    data object New : MatchOutcome

    /** The same economic event as [existing]; the record becomes supporting evidence for it. */
    data class Same(
        val existing: String,
    ) : MatchOutcome

    /** Part of a group the user must decide; nothing in it is imported until then. */
    data class Ambiguous(
        val group: String,
        val records: Int,
        val candidates: Int,
    ) : MatchOutcome

    /** Excluded by the user's decision for its group. */
    data class Excluded(
        val group: String,
    ) : MatchOutcome
}

/**
 * Matches imported records to existing events as economic events, never as rows.
 *
 * - A shared identifier decides at once.
 * - Otherwise records and events of the same kind with the same principal amounts, within the kind's [tolerance] of
 *   each other, form a group. When the group has at least as many existing events as records, records pair with events
 *   in time order. With more records than events the file holds something the account does not, and either could be
 *   true (a repeated identical fill, or an export of the same event twice), so the group waits for a decision.
 * - Records without candidates are new; identical records stay distinct events.
 */
object EventMatcher {
    fun match(
        records: List<EventShape>,
        existing: List<EventShape>,
        tolerance: (MatchKind) -> Long,
        decisions: Map<String, GroupDecision> = emptyMap(),
    ): Map<String, MatchOutcome> {
        val outcomes = mutableMapOf<String, MatchOutcome>()
        val used = mutableSetOf<String>()
        val byId = mutableMapOf<String, EventShape>()
        existing.forEach { event -> event.ids.forEach { byId.putIfAbsent(it, event) } }
        val remaining =
            records.filter { record ->
                val event = record.ids.firstNotNullOfOrNull { byId[it] }?.takeIf { it.kind == record.kind && it.id !in used }
                if (event != null) {
                    outcomes[record.id] = MatchOutcome.Same(event.id)
                    used += event.id
                }
                event == null
            }
        val open = existing.filter { it.id !in used }
        remaining
            .groupBy { it.kind to it.principal }
            .forEach { (signature, sameShape) ->
                val window = tolerance(signature.first)
                val candidates = open.filter { it.kind == signature.first && it.principal == signature.second }
                clusters(sameShape, candidates, window).forEach { (group, events) ->
                    val key = groupKey(group)
                    val decision = decisions[key]
                    when {
                        decision == GroupDecision.EXCLUDED -> group.forEach { outcomes[it.id] = MatchOutcome.Excluded(key) }
                        decision == GroupDecision.DISTINCT || events.isEmpty() -> group.forEach { outcomes[it.id] = MatchOutcome.New }
                        events.size >= group.size || decision == GroupDecision.SAME -> {
                            val sortedEvents = events.sortedBy { it.atMillis }
                            group.sortedBy { it.atMillis }.forEachIndexed { index, record ->
                                outcomes[record.id] = sortedEvents.getOrNull(index)?.let { MatchOutcome.Same(it.id) } ?: MatchOutcome.New
                            }
                        }
                        else -> group.forEach { outcomes[it.id] = MatchOutcome.Ambiguous(key, group.size, events.size) }
                    }
                }
            }
        return outcomes
    }

    /** A stable key for a group of records, saved with the user's decision. */
    fun groupKey(group: List<EventShape>): String = "group:" + sha256(group.map { it.id }.sorted().joinToString("\n")).take(32)

    /**
     * Records and events of one shape split into clusters: items whose times lie within [window] of a neighbour belong
     * together, so a busy day of equal fills stays one decision and fills a week apart stay separate.
     */
    private fun clusters(
        records: List<EventShape>,
        events: List<EventShape>,
        window: Long,
    ): List<Pair<List<EventShape>, List<EventShape>>> {
        val items = (records.map { it to true } + events.map { it to false }).sortedBy { it.first.atMillis }
        val result = mutableListOf<Pair<MutableList<EventShape>, MutableList<EventShape>>>()
        var last = Long.MIN_VALUE
        items.forEach { (shape, isRecord) ->
            if (result.isEmpty() || shape.atMillis - last > window) result += mutableListOf<EventShape>() to mutableListOf()
            if (isRecord) result.last().first += shape else result.last().second += shape
            last = shape.atMillis
        }
        return result.filter { it.first.isNotEmpty() }
    }
}
