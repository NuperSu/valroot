package io.valroot.core.exchange

import io.valroot.core.exchange.imports.EventMatcher
import io.valroot.core.exchange.imports.EventShape
import io.valroot.core.exchange.imports.EvidenceRole
import io.valroot.core.exchange.imports.GroupDecision
import io.valroot.core.exchange.imports.ImportedRecord
import io.valroot.core.exchange.imports.MatchKind
import io.valroot.core.exchange.imports.MatchOutcome
import io.valroot.core.exchange.imports.ProductFamily
import io.valroot.core.exchange.imports.RecordKind
import io.valroot.core.exchange.imports.RecordLeg
import io.valroot.core.exchange.imports.RecordStatus
import io.valroot.core.ledger.Activity
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.ActivityId
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.SourceRecordId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Imported export records in an account's saved rows ([STREAM]), and how decoders settle them with API records.
 *
 * Every decode matches the imported events against the activities the API records gave ([EventMatcher]): a match
 * adds the file as a further source of that activity, an unmatched event becomes an activity of its own, and an
 * ambiguous or excluded group adds nothing until the user decides. Because every refresh decodes all rows again,
 * importing before or after the API reads the same events gives the same history.
 */
object ImportedEvents {
    const val STREAM = "file"

    /** Rows holding the user's decisions for ambiguous groups: key the group, payload the [GroupDecision] name. */
    const val DECISIONS = "file-decision"

    /** Whether [row] belongs to imports (an imported event or a decision) rather than to the exchange's API records. */
    fun owns(row: ExchangeRow): Boolean = row.stream == STREAM || row.stream == DECISIONS

    /** Coins the account's imported events move, so they get assets like the API records' coins. */
    fun coins(rows: List<ExchangeRow>): Set<String> =
        rows.filter { it.stream == STREAM }.flatMap { row -> parse(row.payload).legs.map { it.coin } }.toSet()

    /** The user's decisions for ambiguous groups, by group key. */
    fun decisions(rows: List<ExchangeRow>): Map<String, GroupDecision> =
        rows
            .filter { it.stream == DECISIONS }
            .mapNotNull { row -> runCatching { GroupDecision.valueOf(row.payload) }.getOrNull()?.let { row.key to it } }
            .toMap()

    /** Time windows within which equal events of a kind may be the same: file times have seconds; transfers settle later. */
    fun tolerance(kind: MatchKind): Long =
        when (kind) {
            MatchKind.IN, MatchKind.OUT, MatchKind.REWARD -> 86_400_000L
            else -> 1_999L
        }

    /** The saved payload of [record]: everything decoding needs, without its cells (kept with the import's source rows). */
    fun payload(record: ImportedRecord): String =
        JsonObject(
            mapOf(
                "report" to JsonPrimitive(record.report),
                "family" to JsonPrimitive(record.family.name),
                "role" to JsonPrimitive(record.role.name),
                "kind" to JsonPrimitive(record.kind.name),
                "status" to JsonPrimitive(record.status.name),
                "at" to JsonPrimitive(record.atMillis),
                "legs" to
                    JsonArray(
                        record.legs.map {
                            JsonArray(listOf(JsonPrimitive(it.coin), JsonPrimitive(it.amount.toString()), JsonPrimitive(it.role.name)))
                        },
                    ),
                "wallet" to JsonPrimitive(record.wallet),
                "namespace" to JsonPrimitive(record.namespace),
                "id" to JsonPrimitive(record.nativeId),
                "details" to JsonObject(record.details.mapValues { JsonPrimitive(it.value) }),
            ),
        ).toString()

    /** A saved imported record, as decoding reads it back. */
    data class Stored(
        val report: String,
        val family: ProductFamily,
        val role: EvidenceRole,
        val kind: RecordKind,
        val status: RecordStatus,
        val atMillis: Long,
        val legs: List<RecordLeg>,
        val wallet: String?,
        val namespace: String?,
        val nativeId: String?,
        val details: Map<String, String>,
    ) {
        val moves: Boolean
            get() =
                role == EvidenceRole.PRIMARY &&
                    status == RecordStatus.COMPLETED &&
                    legs.isNotEmpty() &&
                    kind !in
                    setOf(
                        RecordKind.ORDER,
                        RecordKind.SNAPSHOT,
                        RecordKind.DERIVATIVE,
                        RecordKind.UNKNOWN,
                        RecordKind.INTERNAL_TRANSFER,
                    )
    }

    fun parse(payload: String): Stored {
        val o = Json.parseToJsonElement(payload).jsonObject

        fun text(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return Stored(
            report = text("report")!!,
            family = ProductFamily.valueOf(text("family")!!),
            role = EvidenceRole.valueOf(text("role")!!),
            kind = RecordKind.valueOf(text("kind")!!),
            status = RecordStatus.valueOf(text("status")!!),
            atMillis =
                o
                    .getValue("at")
                    .jsonPrimitive.content
                    .toLong(),
            legs =
                o.getValue("legs").jsonArray.map { leg ->
                    val cells = leg.jsonArray.map { it.jsonPrimitive.content }
                    RecordLeg(cells[0], ExactDecimal.parse(cells[1]), MovementRole.valueOf(cells[2]))
                },
            wallet = text("wallet"),
            namespace = text("namespace"),
            nativeId = text("id"),
            details = (o["details"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content },
        )
    }

    /** The match kind of an imported record. */
    fun matchKind(record: Stored): MatchKind =
        when (record.kind) {
            RecordKind.TRADE, RecordKind.CONVERT -> MatchKind.TRADE
            RecordKind.DEPOSIT, RecordKind.CARD_REFUND -> MatchKind.IN
            RecordKind.WITHDRAWAL, RecordKind.CARD_PAYMENT, RecordKind.PAYMENT -> MatchKind.OUT
            RecordKind.FUTURES_IN -> MatchKind.FUTURES_IN
            RecordKind.FUTURES_OUT -> MatchKind.FUTURES_OUT
            RecordKind.REWARD -> MatchKind.REWARD
            RecordKind.FEE -> MatchKind.FEE
            else -> MatchKind.OTHER
        }

    /** The match kind of an activity an API record gave. */
    private fun matchKind(
        activity: Activity,
        principal: Map<String, ExactDecimal>,
    ): MatchKind =
        when {
            ":futures:" in activity.id.value && principal.values.all { it.coefficient.sign < 0 } -> MatchKind.FUTURES_IN
            ":futures:" in activity.id.value && principal.values.all { it.coefficient.sign > 0 } -> MatchKind.FUTURES_OUT
            activity.kind == ActivityKind.TRADE -> MatchKind.TRADE
            activity.kind == ActivityKind.REWARD -> MatchKind.REWARD
            principal.isEmpty() -> MatchKind.FEE
            principal.values.all { it.coefficient.sign > 0 } -> MatchKind.IN
            principal.values.all { it.coefficient.sign < 0 } -> MatchKind.OUT
            else -> MatchKind.OTHER
        }

    /**
     * Adds the account's imported events to [ledger], which already holds the activities of its API records. Call before
     * the decoder fills balances, so openings follow the imported history.
     *
     * Detailed records (fills, deposit and withdrawal histories) settle first, each against the API activities. Statement
     * rows then settle against everything detailed: a second's trade and convert rows together against the trades of
     * that second, net of fees, and every other statement row on its own by its net change.
     */
    internal fun apply(
        ledger: ExchangeLedgerBuilder,
        rows: List<ExchangeRow>,
    ) {
        val imported = rows.filter { it.stream == STREAM }.map { it to parse(it.payload) }.filter { it.second.moves }
        if (imported.isEmpty()) return
        val decisions = decisions(rows)
        val (statement, detailed) = imported.partition { it.second.details["statement"] == "1" }
        // One report at a time, so a report's records also settle against those of reports settled before it.
        detailed
            .groupBy { it.second.report }
            .toSortedMap()
            .values
            .forEach { settle(ledger, it, decisions, net = false) }
        val (trades, others) = statement.partition { it.second.kind == RecordKind.TRADE || it.second.kind == RecordKind.CONVERT }
        settleTradeSeconds(ledger, trades)
        settle(ledger, others, decisions, net = true)
    }

    /** One movement total per asset, in atomic units; fees count when [net]. */
    private fun totals(
        activity: Activity,
        net: Boolean,
    ): Map<String, ExactDecimal> =
        activity.movements
            .filter { net || it.role != MovementRole.FEE }
            .groupBy { it.amount.assetId.value }
            .mapValues { (_, list) -> ExactDecimal(list.fold(ExactInteger.ZERO) { sum, m -> sum + m.amount.atomicUnits }, 0) }
            .filterValues { it.coefficient.sign != 0 }

    /** A record's legs per asset in atomic units; null while a coin has no asset or an amount is finer than its decimals. */
    private fun totals(
        ledger: ExchangeLedgerBuilder,
        record: Stored,
        net: Boolean,
    ): Map<String, ExactDecimal>? {
        val sums = mutableMapOf<String, ExactInteger>()
        record.legs.filter { net || it.role != MovementRole.FEE }.forEach { leg ->
            val asset = ledger.assetOf(leg.coin) ?: return null
            val atomic = ledger.atomicOf(leg.coin, leg.amount) ?: return null
            sums[asset.value] = (sums[asset.value] ?: ExactInteger.ZERO) + atomic
        }
        return sums.mapValues { ExactDecimal(it.value, 0) }.filterValues { it.coefficient.sign != 0 }
    }

    /** Matches [records] against the ledger's activities and builds the new ones. */
    private fun settle(
        ledger: ExchangeLedgerBuilder,
        records: List<Pair<ExchangeRow, Stored>>,
        decisions: Map<String, GroupDecision>,
        net: Boolean,
    ) {
        if (records.isEmpty()) return
        val existing =
            ledger.activities.map { activity ->
                val ids = setOfNotNull(ledger.transfers[activity.id]?.txId?.let { "txid:${it.lowercase()}" })
                EventShape(
                    activity.id.value,
                    ledger.importedKinds[activity.id] ?: matchKind(activity, totals(activity, net = false)),
                    activity.occurredAtEpochMillis,
                    totals(activity, net),
                    ids,
                )
            }
        val shapes =
            records.mapNotNull { (row, record) ->
                val principal =
                    totals(ledger, record, net) ?: run {
                        ledger.imports[row.key] = ImportOutcome.CoinPending
                        return@mapNotNull null
                    }
                val ids = setOfNotNull(record.details["txid"]?.takeIf { it.isNotBlank() }?.let { "txid:${it.lowercase()}" })
                EventShape(row.key, matchKind(record), record.atMillis, principal, ids)
            }
        val outcomes = EventMatcher.match(shapes, existing, ::tolerance, decisions)
        records.forEach { (row, record) ->
            when (val outcome = outcomes[row.key]) {
                is MatchOutcome.Same -> {
                    ledger.addSource(ActivityId(outcome.existing), SourceRecordId(row.source))
                    ledger.imports[row.key] = ImportOutcome.Same(ActivityId(outcome.existing))
                }
                MatchOutcome.New -> build(ledger, row, record)
                is MatchOutcome.Ambiguous ->
                    ledger.imports[row.key] =
                        ImportOutcome.Ambiguous(outcome.group, outcome.records, outcome.candidates)
                is MatchOutcome.Excluded -> ledger.imports[row.key] = ImportOutcome.Excluded(outcome.group)
                null -> Unit
            }
        }
    }

    /**
     * Statement trade, convert and dust rows of each second, together, against all trades of that second: equal net
     * changes make the statement further evidence; no trades make the rows the trade; anything else is reported and
     * left out, so a trade is never counted twice.
     */
    private fun settleTradeSeconds(
        ledger: ExchangeLedgerBuilder,
        records: List<Pair<ExchangeRow, Stored>>,
    ) {
        records.groupBy { it.second.atMillis }.toSortedMap().forEach { (second, group) ->
            val trades =
                ledger.activities.filter {
                    it.kind == ActivityKind.TRADE && it.occurredAtEpochMillis in second until second + 1_000
                }
            if (trades.isEmpty()) {
                group.forEach { (row, record) -> build(ledger, row, record) }
                return@forEach
            }
            if (group.any { (_, record) -> totals(ledger, record, net = true) == null }) {
                group.forEach { (row, _) -> ledger.imports[row.key] = ImportOutcome.CoinPending }
                return@forEach
            }
            val statementTotals = mutableMapOf<String, ExactInteger>()
            group.forEach { (_, record) ->
                val totals = totals(ledger, record, net = true) ?: return@forEach
                totals.forEach { (asset, amount) ->
                    statementTotals[asset] =
                        (statementTotals[asset] ?: ExactInteger.ZERO) + amount.coefficient
                }
            }
            val tradeTotals = mutableMapOf<String, ExactInteger>()
            trades.forEach { trade ->
                totals(trade, net = true).forEach { (asset, amount) ->
                    tradeTotals[asset] =
                        (tradeTotals[asset] ?: ExactInteger.ZERO) + amount.coefficient
                }
            }
            if (statementTotals.filterValues { it.sign != 0 } == tradeTotals.filterValues { it.sign != 0 }) {
                group.forEach { (row, _) ->
                    trades.forEach { ledger.addSource(it.id, SourceRecordId(row.source)) }
                    ledger.imports[row.key] = ImportOutcome.Same(trades.first().id)
                }
            } else {
                group.forEach { (row, _) -> ledger.imports[row.key] = ImportOutcome.Conflict }
                ledger.failures +=
                    "A statement differs from the trades at ${kotlin.time.Instant.fromEpochMilliseconds(
                        second,
                    )}; those statement rows were left out"
            }
        }
    }

    private fun build(
        ledger: ExchangeLedgerBuilder,
        row: ExchangeRow,
        record: Stored,
    ) {
        val id =
            ledger.build("$STREAM:${row.key}", record.atMillis, setOf(SourceRecordId(row.source))) {
                record.legs.forEach { leg ->
                    if (leg.role ==
                        MovementRole.FEE
                    ) {
                        fee(leg.coin, ExactDecimal(-leg.amount.coefficient, leg.amount.scale))
                    } else {
                        add(leg.coin, leg.amount, leg.role)
                    }
                }
                activityKind(record.kind)
            }
        if (id != null) ledger.importedKinds[id] = matchKind(record)
        ledger.imports[row.key] = id?.let { ImportOutcome.New(it) } ?: ImportOutcome.CoinPending
    }

    private fun activityKind(kind: RecordKind): ActivityKind =
        when (kind) {
            RecordKind.TRADE, RecordKind.CONVERT -> ActivityKind.TRADE
            RecordKind.DEPOSIT, RecordKind.CARD_REFUND -> ActivityKind.DEPOSIT
            RecordKind.WITHDRAWAL, RecordKind.FUTURES_IN, RecordKind.FUTURES_OUT -> ActivityKind.TRANSFER
            RecordKind.CARD_PAYMENT, RecordKind.PAYMENT -> ActivityKind.PAYMENT
            RecordKind.REWARD -> ActivityKind.REWARD
            else -> ActivityKind.OTHER
        }
}
