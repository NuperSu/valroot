package io.valroot.core.exchange.imports

import io.valroot.core.exchange.ImportedEvents

/** Economic identity within one statement report; occurrence ordinals keep equal rows distinct. */
internal object StatementIdentity {
    const val CANONICAL_KEY = "canonicalKey"

    fun of(record: ImportedRecord): String? =
        of(
            ImportedEvents.Stored(
                record.report,
                record.family,
                record.role,
                record.kind,
                record.status,
                record.atMillis,
                record.legs,
                record.wallet,
                record.namespace,
                record.nativeId,
                record.details,
            ),
        )

    fun of(record: ImportedEvents.Stored): String? {
        if (record.details["statement"] != "1" || record.details["rows"] == null) return null
        val grouped = record.kind in setOf(RecordKind.TRADE, RecordKind.CONVERT, RecordKind.WITHDRAWAL)
        return listOf(
            record.report,
            record.family.name,
            record.role.name,
            record.kind.name,
            record.status.name,
            record.atMillis.toString(),
            record.legs
                .map { "${it.coin}=${it.amount}/${it.role}" }
                .sorted()
                .joinToString(";"),
            record.wallet.orEmpty(),
            record.namespace.orEmpty(),
            record.nativeId.orEmpty(),
            record.details.entries
                .filter { it.key != "rows" && it.key != CANONICAL_KEY && (!grouped || it.key != "operation") }
                .sortedBy { it.key }
                .joinToString(";") { "${it.key}=${it.value}" },
        ).joinToString("|")
    }
}
