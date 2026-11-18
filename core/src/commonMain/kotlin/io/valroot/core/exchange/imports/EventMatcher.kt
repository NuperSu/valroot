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
 * - A shared identifier decides at once between events of the same kind and principal amounts; with other amounts it
 *   is a different event in the same transaction (for example two deposits paid by one transaction).
 * - Otherwise records and events of the same kind with the same principal amounts, within the kind's [tolerance] of
 *   each other, form a group; an event whose identifiers contradict a record's ([conflicts]) never pairs with it.
 *   When the group has at least as many existing events as records, records pair with events in time order, as many
 *   as identifiers allow ([pairs]). With more records than events the file holds something the account does not, and either could be
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
        val byId = mutableMapOf<String, MutableList<EventShape>>()
        existing.forEach { event -> event.ids.forEach { byId.getOrPut(it) { mutableListOf() } += event } }
        val remaining =
            records.filter { record ->
                // One on-chain transaction can carry several distinct deposits, so a shared identifier decides only
                // between events of the same kind and the same amounts.
                val event =
                    record.ids
                        .flatMap { byId[it].orEmpty() }
                        .firstOrNull { it.kind == record.kind && it.principal == record.principal && it.id !in used }
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
                clusters(sameShape, candidates, window).forEach { (group, clustered) ->
                    val key = groupKey(group)
                    val decision = decisions[key]
                    // An event whose identifiers contradict every record's is a different event, however equal its amounts.
                    val events = clustered.filter { event -> group.any { !conflicts(it, event) } }
                    when {
                        decision == GroupDecision.EXCLUDED -> group.forEach { outcomes[it.id] = MatchOutcome.Excluded(key) }
                        decision == GroupDecision.DISTINCT || events.isEmpty() -> group.forEach { outcomes[it.id] = MatchOutcome.New }
                        events.size >= group.size || decision == GroupDecision.SAME -> {
                            val pairs = pairs(group, events)
                            group.forEach { record ->
                                outcomes[record.id] =
                                    pairs[record.id]?.let { MatchOutcome.Same(it) } ?: MatchOutcome.New
                            }
                        }
                        else -> group.forEach { outcomes[it.id] = MatchOutcome.Ambiguous(key, group.size, events.size) }
                    }
                }
            }
        return outcomes
    }

    /**
     * Pairs [records] with [events] of one group, by record ID to event ID. Without contradicting identifiers they pair in
     * time order. Otherwise as many pair as possible: a record first takes the earliest free event it does not
     * contradict, and when none is free an earlier pairing moves to another event to make room (an augmenting path).
     */
    private fun pairs(
        records: List<EventShape>,
        events: List<EventShape>,
    ): Map<String, String> {
        val inOrder = records.sortedBy { it.atMillis }
        val candidates = events.sortedBy { it.atMillis }
        val owner = arrayOfNulls<Int>(candidates.size)

        fun place(
            record: Int,
            seen: MutableSet<Int>,
        ): Boolean {
            val allowed = candidates.indices.filter { !conflicts(inOrder[record], candidates[it]) && it !in seen }
            allowed.firstOrNull { owner[it] == null }?.let {
                owner[it] = record
                return true
            }
            allowed.forEach { event ->
                seen += event
                if (place(owner[event]!!, seen)) {
                    owner[event] = record
                    return true
                }
            }
            return false
        }
        inOrder.indices.forEach { place(it, mutableSetOf()) }
        return owner.withIndex().mapNotNull { (event, record) -> record?.let { inOrder[it].id to candidates[event].id } }.toMap()
    }

    /**
     * Whether [a] and [b] name different events: both carry identifiers of one kind (such as `txid:`) and share none of
     * that kind. Identifiers of a kind only one side has say nothing.
     */
    fun conflicts(
        a: EventShape,
        b: EventShape,
    ): Boolean {
        fun byKind(ids: Set<String>) = ids.groupBy { it.substringBefore(':') }
        val left = byKind(a.ids)
        val right = byKind(b.ids)
        return left.keys.intersect(right.keys).any { kind -> left.getValue(kind).intersect(right.getValue(kind).toSet()).isEmpty() }
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
