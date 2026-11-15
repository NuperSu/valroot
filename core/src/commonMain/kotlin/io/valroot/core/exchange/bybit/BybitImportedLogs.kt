package io.valroot.core.exchange.bybit

import io.valroot.core.exchange.ExchangeLedgerBuilder
import io.valroot.core.exchange.ExchangeRow
import io.valroot.core.exchange.ImportOutcome
import io.valroot.core.exchange.ImportedEvents
import io.valroot.core.exchange.imports.EventMatcher
import io.valroot.core.exchange.imports.EventShape
import io.valroot.core.exchange.imports.MatchKind
import io.valroot.core.exchange.imports.MatchOutcome
import io.valroot.core.exchange.imports.RecordKind
import io.valroot.core.exchange.imports.RecordStatus
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.ExactDecimal
import io.valroot.core.model.ExactInteger
import io.valroot.core.model.SourceRecordId
import kotlin.math.abs

/** Reconstructs wallet logs before Bybit's wallet opening calculation, matching API rows by balance and change. */
internal class BybitImportedLogs(
    rows: List<ExchangeRow>,
) {
    val records = rows.filter { it.stream == ImportedEvents.STREAM }.map { it to ImportedEvents.parse(it.payload) }
    private val logRecords = records.filter { it.second.details["bybitWalletLog"] != null }
    private val matches = mutableMapOf<String, SourceRecordId>()
    private val matchedApi = mutableSetOf<String>()

    private val decisions = ImportedEvents.decisions(rows)
    private val blocked = mutableMapOf<String, ImportOutcome>()

    private fun canonical(value: ExactDecimal): ExactDecimal {
        val digits = value.coefficient.toString()
        val zeros = minOf(value.scale, digits.takeLastWhile { it == '0' }.length)
        return if (value.coefficient.sign == 0) zero else ExactDecimal(ExactInteger.parse(digits.dropLast(zeros)), value.scale - zeros)
    }

    private fun shape(
        id: String,
        coin: String,
        change: ExactDecimal,
        balance: ExactDecimal,
        at: Long,
    ) = EventShape(
        id,
        MatchKind.OTHER,
        at,
        mapOf("$coin:change" to canonical(change), "$coin:balance" to canonical(balance)),
    )

    private fun <T> merge(
        wallet: String,
        api: List<Pair<T, SourceRecordId>>,
        apiShape: (T, String) -> EventShape,
        build: (ExchangeRow, ImportedEvents.Stored) -> T,
    ): List<Pair<T, SourceRecordId>> {
        val all = api.toMutableList()
        logRecords.filter { it.second.wallet == wallet }.groupBy { it.second.report }.toSortedMap().values.forEach { report ->
            val candidates = all.associateBy { it.second.value }
            val existing = all.map { (entry, source) -> apiShape(entry, source.value) }
            val shapes =
                report.map { (row, record) ->
                    val coin = record.legs.first().coin
                    val change = record.details["change"]?.let(ExactDecimal::parse) ?: record.legs.single().amount
                    shape(row.key, coin, change, ExactDecimal.parse(record.details.getValue("balanceAfter")), record.atMillis)
                }
            val outcomes = EventMatcher.match(shapes, existing, { 1000L }, decisions)
            report.forEach { (row, record) ->
                when (val outcome = outcomes[row.key]) {
                    is MatchOutcome.Same -> {
                        matches[row.key] = candidates.getValue(outcome.existing).second
                        matchedApi += row.key
                    }
                    MatchOutcome.New -> {
                        val source = SourceRecordId(row.source)
                        matches[row.key] = source
                        all += build(row, record) to source
                    }
                    is MatchOutcome.Ambiguous ->
                        blocked[row.key] =
                            ImportOutcome.Ambiguous(outcome.group, outcome.records, outcome.candidates)
                    is MatchOutcome.Excluded -> blocked[row.key] = ImportOutcome.Excluded(outcome.group)
                    null -> Unit
                }
            }
        }
        return all
    }

    fun funding(api: List<Pair<BybitFundingEntry, SourceRecordId>>): List<Pair<BybitFundingEntry, SourceRecordId>> =
        merge(
            BybitDecoder.FUND,
            api,
            { entry, id -> shape(id, entry.currency, entry.change, entry.balanceAfter, entry.atMillis) },
        ) { row, record ->
            val leg = record.legs.single()
            BybitFundingEntry(
                "file:${row.key}",
                leg.coin,
                leg.amount,
                ExactDecimal.parse(record.details.getValue("balanceAfter")),
                record.atMillis,
                record.details.getValue("operation"),
                record.details.getValue("description"),
            )
        }

    fun unified(api: List<Pair<BybitLogEntry, SourceRecordId>>): List<Pair<BybitLogEntry, SourceRecordId>> =
        merge(
            BybitDecoder.UNIFIED,
            api,
            { entry, id -> shape(id, entry.currency, entry.change, entry.balanceAfter, entry.atMillis) },
        ) { row, record ->
            val details = record.details
            BybitLogEntry(
                "file:${row.key}",
                details.getValue("operation"),
                "spot",
                details.getValue("contract"),
                record.legs.first().coin,
                ExactDecimal.parse(details.getValue("change")),
                ExactDecimal.parse(details.getValue("cashFlow")),
                ExactDecimal.parse(details.getValue("funding")),
                ExactDecimal.parse(details.getValue("fee")),
                ExactDecimal.parse(details.getValue("balanceAfter")),
                "file:${record.atMillis}:${details.getValue("contract")}",
                "",
                record.atMillis,
            )
        }

    /** On-chain histories are evidence only; only a matching log produces a movement. */
    fun deposits(): List<Pair<BybitDeposit, SourceRecordId>> =
        records
            .filter {
                it.second.details["bybitTransferEvidence"] != null &&
                    it.second.kind == RecordKind.DEPOSIT
            }.map { (row, r) ->
                BybitDeposit(
                    "file:${row.key}",
                    r.legs.single().coin,
                    r.details["network"].orEmpty(),
                    r.legs.single().amount,
                    r.details["txid"].orEmpty(),
                    if (r.status ==
                        RecordStatus.COMPLETED
                    ) {
                        3
                    } else {
                        0
                    },
                    r.atMillis,
                ) to
                    SourceRecordId(row.source)
            }

    fun withdrawals(): List<Pair<BybitWithdrawal, SourceRecordId>> =
        records
            .filter {
                it.second.details["bybitTransferEvidence"] != null &&
                    it.second.kind == RecordKind.WITHDRAWAL
            }.map { (row, r) ->
                BybitWithdrawal(
                    "file:${row.key}",
                    r.legs.single().coin,
                    r.details["network"].orEmpty(),
                    negate(r.legs.single().amount),
                    zero,
                    r.details["txid"].orEmpty(),
                    if (r.status ==
                        RecordStatus.COMPLETED
                    ) {
                        "success"
                    } else {
                        "Fail"
                    },
                    r.atMillis,
                    true,
                ) to
                    SourceRecordId(row.source)
            }

    /** Pre-upgrade Spot has its own opening and trade groups; paired Funding transfers cancel within the account. */
    fun spot(
        ledger: ExchangeLedgerBuilder,
        funding: List<Pair<BybitFundingEntry, SourceRecordId>>,
    ): Set<String> {
        val spot = logRecords.filter { it.second.wallet == "SPOT" }
        val pairedFunding = mutableSetOf<String>()
        val internalSpot = mutableSetOf<String>()
        spot.filter { it.second.kind == RecordKind.INTERNAL_TRANSFER }.sortedBy { it.second.atMillis }.forEach { (row, r) ->
            val leg = r.legs.single()
            val match =
                funding
                    .filter {
                        it.first.id !in pairedFunding &&
                            "spot account" in it.first.description.lowercase() &&
                            it.first.currency == leg.coin &&
                            (it.first.change + leg.amount).coefficient.sign == 0
                    }.minByOrNull { abs(it.first.atMillis - r.atMillis) }
                    ?.takeIf {
                        abs(
                            it.first.atMillis - r.atMillis,
                        ) <=
                            600_000
                    }
            if (match != null) {
                pairedFunding += match.first.id
                internalSpot += row.key
            }
        }
        spot
            .groupBy { (row, r) ->
                if (r.kind in
                    setOf(RecordKind.TRADE, RecordKind.CONVERT)
                ) {
                    "trade:${r.atMillis}"
                } else {
                    "row:${row.key}"
                }
            }.forEach { (key, group) ->
                val active = group.filter { it.first.key !in internalSpot }
                if (active.isEmpty()) return@forEach
                ledger.build("spot:$key", active.minOf { it.second.atMillis }, active.map { SourceRecordId(it.first.source) }.toSet()) {
                    active.forEach { (_, r) ->
                        r.legs.forEach { leg ->
                            if (leg.role ==
                                MovementRole.FEE
                            ) {
                                fee(leg.coin, negate(leg.amount))
                            } else {
                                add(leg.coin, leg.amount, leg.role)
                            }
                        }
                    }
                    when (active.first().second.kind) {
                        RecordKind.DEPOSIT -> ActivityKind.DEPOSIT
                        RecordKind.WITHDRAWAL -> ActivityKind.TRANSFER
                        else -> null
                    }
                }
            }
        return pairedFunding
    }

    fun openings(
        ledger: ExchangeLedgerBuilder,
        logs: List<Pair<BybitLogEntry, SourceRecordId>>,
        funding: List<Pair<BybitFundingEntry, SourceRecordId>>,
        includeCurrentWallets: Boolean,
    ) {
        data class Row(
            val wallet: String,
            val coin: String,
            val change: ExactDecimal,
            val balance: ExactDecimal,
            val at: Long,
            val source: SourceRecordId,
        )
        val entries =
            logRecords.filter { it.second.wallet == "SPOT" }.map { (row, r) ->
                Row(
                    "SPOT",
                    r.legs.single().coin,
                    r.legs.single().amount,
                    ExactDecimal.parse(r.details.getValue("balanceAfter")),
                    r.atMillis,
                    SourceRecordId(row.source),
                )
            } +
                if (includeCurrentWallets && logRecords.isNotEmpty()) {
                    logs.map { (e, s) -> Row(BybitDecoder.UNIFIED, e.currency, e.change, e.balanceAfter, e.atMillis, s) } +
                        funding.map { (e, s) -> Row(BybitDecoder.FUND, e.currency, e.change, e.balanceAfter, e.atMillis, s) }
                } else {
                    emptyList()
                }
        entries.groupBy { it.wallet to it.coin }.forEach { (key, group) ->
            val earliest = group.filter { it.at == group.minOf { r -> r.at } }
            val first =
                earliest.firstOrNull { candidate ->
                    earliest.none { other ->
                        other !== candidate &&
                            other.balance.compareTo(candidate.balance - candidate.change) == 0
                    }
                }
                    ?: earliest.first()
            val amount = first.balance - first.change
            ledger.build("opening:${key.first}:${key.second}", first.at - 1, setOf(first.source)) {
                add(first.coin, amount, MovementRole.TRANSFER)
                null
            }
        }
    }

    fun outcomes(ledger: ExchangeLedgerBuilder) {
        logRecords.forEach { (row, record) ->
            blocked[row.key]?.let {
                ledger.imports[row.key] = it
                return@forEach
            }
            val source = matches[row.key] ?: SourceRecordId(row.source)
            val activity = ledger.activities.firstOrNull { source in it.sources && !it.id.value.contains(":opening:") }
            if (activity != null) {
                ledger.addSource(activity.id, SourceRecordId(row.source))
                ledger.importedKinds[activity.id] = ImportedEvents.matchKind(record)
                ledger.imports[row.key] = if (row.key in matchedApi) ImportOutcome.Same(activity.id) else ImportOutcome.New(activity.id)
            } else if (ImportedEvents.parse(row.payload).moves) {
                ledger.imports[row.key] = ImportOutcome.CoinPending
            }
        }
    }

    fun genericRows(rows: List<ExchangeRow>) =
        rows.filter { row ->
            row.stream != ImportedEvents.STREAM ||
                ImportedEvents.parse(row.payload).details.let { it["bybitWalletLog"] == null && it["bybitTransferEvidence"] == null }
        }

    private fun negate(value: ExactDecimal) = ExactDecimal(-value.coefficient, value.scale)

    private val zero = ExactDecimal.parse("0")
}
