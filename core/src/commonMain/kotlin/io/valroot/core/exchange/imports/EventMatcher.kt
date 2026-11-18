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

/** Matches economic events using shared identifiers, then eligible time-and-identifier pairs. */
object EventMatcher {
    fun match(
        records: List<EventShape>,
        existing: List<EventShape>,
        tolerance: (MatchKind) -> Long,
        decisions: Map<String, GroupDecision> = emptyMap(),
    ): Map<String, MatchOutcome> {
        val outcomes = mutableMapOf<String, MatchOutcome>()
        val used = mutableSetOf<String>()
        val order = compareBy<EventShape> { it.atMillis }.thenBy { it.id }
        val byId = mutableMapOf<String, MutableList<EventShape>>()
        existing.sortedWith(order).forEach { event -> event.ids.forEach { byId.getOrPut(it) { mutableListOf() } += event } }
        val remaining =
            records.sortedWith(order).filter { record ->
                val event =
                    record.ids
                        .sorted()
                        .flatMap { byId[it].orEmpty() }
                        .distinctBy { it.id }
                        .sortedWith(order)
                        .firstOrNull {
                            it.kind == record.kind && it.principal == record.principal && it.id !in used && !conflicts(record, it)
                        }
                if (event != null) {
                    outcomes[record.id] = MatchOutcome.Same(event.id)
                    used += event.id
                }
                event == null
            }
        val open = existing.filter { it.id !in used }
        remaining.groupBy { it.kind to it.principal }.forEach { (signature, sameShape) ->
            val window = tolerance(signature.first)
            val candidates = open.filter { it.kind == signature.first && it.principal == signature.second }
            clusters(sameShape, candidates, window).forEach { (group, events) ->
                // Saved decisions can name the full time cluster. Keep those decisions when eligible edges split it.
                val legacyKey = groupKey(group)
                val legacyDecision = decisions[legacyKey]
                val orderedRecords = group.sortedWith(order)
                val orderedEvents = events.sortedWith(order)
                val graph = Candidates(orderedRecords, orderedEvents, window)
                components(graph).forEach { (recordIndexes, eventIndexes) ->
                    val component = recordIndexes.map { orderedRecords[it] }
                    if (eventIndexes.isEmpty()) {
                        component.forEach { outcomes[it.id] = MatchOutcome.New }
                        return@forEach
                    }
                    val key = groupKey(component)
                    val decision = decisions[key] ?: legacyDecision
                    val decisionKey =
                        if (decisions.containsKey(key)) {
                            key
                        } else if (legacyDecision != null) {
                            legacyKey
                        } else {
                            key
                        }
                    val pairs =
                        if (decision == GroupDecision.EXCLUDED || decision == GroupDecision.DISTINCT) {
                            emptyMap()
                        } else {
                            pairs(recordIndexes, graph)
                        }
                    when {
                        decision == GroupDecision.EXCLUDED ->
                            component.forEach { outcomes[it.id] = MatchOutcome.Excluded(decisionKey) }
                        decision == GroupDecision.DISTINCT -> component.forEach { outcomes[it.id] = MatchOutcome.New }
                        pairs.size == component.size || decision == GroupDecision.SAME ->
                            recordIndexes.forEach { record ->
                                outcomes[orderedRecords[record].id] =
                                    pairs[record]?.let { MatchOutcome.Same(orderedEvents[it].id) } ?: MatchOutcome.New
                            }
                        else -> component.forEach { outcomes[it.id] = MatchOutcome.Ambiguous(key, component.size, eventIndexes.size) }
                    }
                }
            }
        }
        return outcomes
    }

    private fun within(
        a: Long,
        b: Long,
        window: Long,
    ): Boolean = window >= 0 && if (a >= b) a.toULong() - b.toULong() <= window.toULong() else b.toULong() - a.toULong() <= window.toULong()

    /** Eligible neighbors are enumerated on demand, keeping dense equal-fill groups linear in memory. */
    private class Candidates(
        val records: List<EventShape>,
        val events: List<EventShape>,
        val window: Long,
    ) {
        private val recordKinds = records.flatMap { it.ids }.map { it.substringBefore(':') }.toSet()
        val unrestricted = events.none { event -> event.ids.any { it.substringBefore(':') in recordKinds } }
        val ranges = records.map { range(it.atMillis, events) }

        fun events(record: Int): Sequence<Int> =
            ranges[record].asSequence().filter { unrestricted || !conflicts(records[record], events[it]) }

        fun records(event: Int): Sequence<Int> =
            range(events[event].atMillis, records).asSequence().filter { unrestricted || !conflicts(records[it], events[event]) }

        private fun range(
            at: Long,
            shapes: List<EventShape>,
        ): IntRange {
            fun bound(after: Boolean): Int {
                var low = 0
                var high = shapes.size
                while (low < high) {
                    val mid = low + (high - low) / 2
                    val time = shapes[mid].atMillis
                    val before = if (after) time <= at || within(time, at, window) else time < at && !within(time, at, window)
                    if (before) low = mid + 1 else high = mid
                }
                return low
            }
            if (window < 0) return IntRange.EMPTY
            return bound(false) until bound(true)
        }
    }

    /** Connected components of eligible record/event edges; an isolated record forms its own component. */
    private fun components(graph: Candidates): List<Pair<List<Int>, List<Int>>> {
        val result = mutableListOf<Pair<List<Int>, List<Int>>>()
        if (graph.unrestricted) {
            var records = mutableListOf<Int>()
            var firstEvent = -1
            var lastEvent = -1

            fun finish() {
                if (records.isNotEmpty()) result += records.toList() to (firstEvent..lastEvent).toList()
                records = mutableListOf()
            }
            graph.ranges.forEachIndexed { record, range ->
                if (range.isEmpty()) {
                    result += listOf(record) to emptyList()
                } else {
                    if (records.isNotEmpty() && range.first > lastEvent) finish()
                    if (records.isEmpty()) firstEvent = range.first
                    lastEvent = range.last
                    records += record
                }
            }
            finish()
            return result
        }
        val seenRecords = BooleanArray(graph.records.size)
        val seenEvents = BooleanArray(graph.events.size)
        graph.records.indices.forEach { start ->
            if (seenRecords[start]) return@forEach
            val records = mutableListOf(start)
            val events = mutableListOf<Int>()
            seenRecords[start] = true
            var next = 0
            while (next < records.size) {
                graph.events(records[next++]).forEach { event ->
                    if (!seenEvents[event]) {
                        seenEvents[event] = true
                        events += event
                        graph.records(event).forEach { record ->
                            if (!seenRecords[record]) {
                                seenRecords[record] = true
                                records += record
                            }
                        }
                    }
                }
            }
            result += records.sorted() to events.sorted()
        }
        return result
    }

    /** Deterministic maximum matching using iterative augmenting paths, without recursion. */
    private fun pairs(
        records: List<Int>,
        graph: Candidates,
    ): Map<Int, Int> {
        if (graph.unrestricted) {
            val pairs = mutableMapOf<Int, Int>()
            var next = 0
            records.forEach { record ->
                val range = graph.ranges[record]
                next = maxOf(next, range.first)
                if (next <= range.last) pairs[record] = next++
            }
            return pairs
        }
        val owner = mutableMapOf<Int, Int>()
        val assigned = mutableMapOf<Int, Int>()
        records.forEach { start ->
            val free = graph.events(start).firstOrNull { it !in owner }
            if (free != null) {
                owner[free] = start
                assigned[start] = free
                return@forEach
            }
            val previous = mutableMapOf<Int, Int>()
            val seenRecords = mutableSetOf(start)
            val queue = mutableListOf(start)
            var next = 0
            var end = -1
            while (next < queue.size && end < 0) {
                val record = queue[next++]
                for (event in graph.events(record)) {
                    if (event in previous) continue
                    previous[event] = record
                    if (event !in owner) {
                        end = event
                        break
                    }
                    val occupiedBy = owner.getValue(event)
                    if (seenRecords.add(occupiedBy)) queue += occupiedBy
                }
            }
            while (end >= 0) {
                val record = previous.getValue(end)
                val old = assigned[record] ?: -1
                owner[end] = record
                assigned[record] = end
                end = old
            }
        }
        return assigned
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
            if (result.isEmpty() || !within(shape.atMillis, last, window)) result += mutableListOf<EventShape>() to mutableListOf()
            if (isRecord) result.last().first += shape else result.last().second += shape
            last = shape.atMillis
        }
        return result.filter { it.first.isNotEmpty() }
    }
}
