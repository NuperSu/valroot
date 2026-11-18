package io.valroot.core.solana

import io.valroot.core.model.ExactInteger
import io.valroot.core.storage.SqliteDatabase

/** How far one history stream has been read: where the next page starts, and the finalized slot read through. */
data class SolanaStream(
    val wallet: String,
    val stream: String,
    val cursor: String?,
    /** Every transaction of the stream at or below this finalized slot is stored; null until a scan first reaches the end. */
    val scannedThrough: Long?,
)

/** Sync state of one tracked Solana address. */
data class SolanaWalletState(
    val address: String,
    val lastSuccessMillis: Long?,
    val error: String?,
    /** What is known missing or not interpreted; null when nothing is. */
    val coverage: String?,
    /** `matched`, `pending` (history not yet read through the snapshot) or `different`; null before the first check. */
    val balanceState: String?,
    val balanceDifference: String?,
)

/**
 * Raw Solana history by signature (payloads live in the raw store), which wallet each belongs to, per-stream cursors
 * and watermarks, balance snapshots, mint classifications and per-wallet state. Part of encrypted backups.
 */
class SolanaRepository(
    private val db: SqliteDatabase,
) {
    init {
        db.execute(
            "CREATE TABLE IF NOT EXISTS solana_transactions (signature TEXT PRIMARY KEY, slot INTEGER NOT NULL, position INTEGER, " +
                "record_id TEXT NOT NULL, checksum TEXT NOT NULL, quality INTEGER NOT NULL, version TEXT)",
        )
        db.execute("CREATE TABLE IF NOT EXISTS solana_block_times (slot INTEGER PRIMARY KEY, time INTEGER NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS solana_block_time_attempts (slot INTEGER PRIMARY KEY, attempts INTEGER NOT NULL)")
        db.execute(
            "CREATE TABLE IF NOT EXISTS solana_wallet_transactions (wallet TEXT NOT NULL, signature TEXT NOT NULL, " +
                "PRIMARY KEY(wallet, signature))",
        )
        db.execute(
            "CREATE TABLE IF NOT EXISTS solana_streams (wallet TEXT NOT NULL, stream TEXT NOT NULL, cursor TEXT, " +
                "scanned_through INTEGER, PRIMARY KEY(wallet, stream))",
        )
        db.execute(
            "CREATE TABLE IF NOT EXISTS solana_wallets (address TEXT PRIMARY KEY, last_success INTEGER, error TEXT, coverage TEXT, " +
                "balance_state TEXT, balance_difference TEXT)",
        )
        db.execute(
            "CREATE TABLE IF NOT EXISTS solana_snapshots (wallet TEXT NOT NULL, batch INTEGER NOT NULL, slot INTEGER NOT NULL, " +
                "accounts TEXT NOT NULL, PRIMARY KEY(wallet, batch))",
        )
        db.execute(
            "CREATE TABLE IF NOT EXISTS solana_mints (mint TEXT PRIMARY KEY, kind TEXT NOT NULL, symbol TEXT, name TEXT, checked_at INTEGER NOT NULL)",
        )
    }

    /**
     * Indexes one stored raw revision of a transaction and assigns it to [wallet]. The revision becomes the one replay
     * reads when the signature is new or when it is more complete ([quality]) than the one kept; an equally complete
     * revision never replaces it, so the choice does not depend on hashes or arrival order. [version] is null for a
     * revision this parser reads, otherwise the unsupported version. Returns true when a kept revision was replaced.
     */
    fun index(
        wallet: String,
        position: SolanaPosition,
        recordId: String,
        checksum: String,
        quality: Int,
        version: String?,
    ): Boolean {
        db.execute("INSERT OR IGNORE INTO solana_wallet_transactions VALUES (?, ?)", listOf(wallet, position.signature))
        val kept =
            db
                .query("SELECT checksum, quality FROM solana_transactions WHERE signature=?", listOf(position.signature))
                .singleOrNull()
        val values =
            listOf(
                position.slot.toString(),
                position.index?.toString(),
                recordId,
                checksum,
                quality.toString(),
                version,
                position.signature,
            )
        return when {
            kept == null -> {
                db.execute(
                    "INSERT INTO solana_transactions (slot, position, record_id, checksum, quality, version, signature) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    values,
                )
                false
            }
            kept[0] != checksum && quality > kept[1]!!.toInt() -> {
                db.execute(
                    "UPDATE solana_transactions SET slot=?, position=?, record_id=?, checksum=?, quality=?, version=? WHERE signature=?",
                    values,
                )
                true
            }
            else -> false
        }
    }

    /** The kept raw payloads of [wallet]'s transactions in a version this parser reads, one per signature. */
    fun payloads(wallet: String): List<String> =
        db
            .query(
                "SELECT r.payload FROM solana_wallet_transactions w JOIN solana_transactions t ON t.signature=w.signature " +
                    "JOIN raw_revisions r ON r.record_id=t.record_id AND r.checksum=t.checksum WHERE w.wallet=? AND t.version IS NULL " +
                    "ORDER BY t.slot, t.position, t.signature",
                listOf(wallet),
            ).map { requireNotNull(it.single()) { "Stored Solana transaction has no payload" } }

    /** How many of [wallet]'s stored transactions are in a version this parser does not read. */
    fun unsupportedCount(wallet: String): Int =
        db
            .query(
                "SELECT COUNT(*) FROM solana_wallet_transactions w JOIN solana_transactions t ON t.signature=w.signature " +
                    "WHERE w.wallet=? AND t.version IS NOT NULL",
                listOf(wallet),
            ).single()
            .single()!!
            .toInt()

    /** The first slot of [wallet]'s stored transactions in a version this parser does not read, or null. */
    fun firstUnsupportedSlot(wallet: String): Long? =
        db
            .query(
                "SELECT MIN(t.slot) FROM solana_wallet_transactions w JOIN solana_transactions t ON t.signature=w.signature " +
                    "WHERE w.wallet=? AND t.version IS NOT NULL",
                listOf(wallet),
            ).single()
            .single()
            ?.toLong()

    /** Failed block-time lookups by slot, so slots that keep failing are asked after the others. */
    fun blockTimeAttempts(): Map<Long, Int> =
        db.query("SELECT slot, attempts FROM solana_block_time_attempts").associate { it[0]!!.toLong() to it[1]!!.toInt() }

    fun recordBlockTimeAttempt(slot: Long) =
        db.execute(
            "INSERT INTO solana_block_time_attempts VALUES (?, 1) ON CONFLICT(slot) DO UPDATE SET attempts = attempts + 1",
            listOf(slot.toString()),
        )

    /** Block times recovered for transactions whose response had none, by slot. */
    fun blockTimes(): Map<Long, Long> =
        db.query("SELECT slot, time FROM solana_block_times").associate { it[0]!!.toLong() to it[1]!!.toLong() }

    fun saveBlockTime(
        slot: Long,
        timeMillis: Long,
    ) = db.execute("INSERT OR REPLACE INTO solana_block_times VALUES (?, ?)", listOf(slot.toString(), timeMillis.toString()))

    fun transactionCount(wallet: String): Int =
        db
            .query("SELECT COUNT(*) FROM solana_wallet_transactions WHERE wallet=?", listOf(wallet))
            .single()
            .single()!!
            .toInt()

    fun stream(
        wallet: String,
        stream: String,
    ): SolanaStream =
        db
            .query("SELECT cursor, scanned_through FROM solana_streams WHERE wallet=? AND stream=?", listOf(wallet, stream))
            .singleOrNull()
            ?.let { SolanaStream(wallet, stream, it[0], it[1]?.toLong()) }
            ?: SolanaStream(wallet, stream, null, null)

    fun streams(wallet: String): List<SolanaStream> =
        db
            .query("SELECT stream, cursor, scanned_through FROM solana_streams WHERE wallet=? ORDER BY stream", listOf(wallet))
            .map { SolanaStream(wallet, it[0]!!, it[1], it[2]?.toLong()) }

    fun saveStream(state: SolanaStream) =
        db.execute(
            "INSERT OR REPLACE INTO solana_streams VALUES (?, ?, ?, ?)",
            listOf(state.wallet, state.stream, state.cursor, state.scannedThrough?.toString()),
        )

    /** Forgets cursors and watermarks so history is read again from the start; stored transactions stay. */
    fun restartStreams(wallet: String) = db.execute("DELETE FROM solana_streams WHERE wallet=?", listOf(wallet))

    fun state(address: String): SolanaWalletState =
        db
            .query(
                "SELECT last_success, error, coverage, balance_state, balance_difference FROM solana_wallets WHERE address=?",
                listOf(address),
            ).singleOrNull()
            ?.let { SolanaWalletState(address, it[0]?.toLong(), it[1], it[2], it[3], it[4]) }
            ?: SolanaWalletState(address, null, null, null, null, null)

    fun saveState(state: SolanaWalletState) =
        db.execute(
            "INSERT OR REPLACE INTO solana_wallets VALUES (?, ?, ?, ?, ?, ?)",
            listOf(
                state.address,
                state.lastSuccessMillis?.toString(),
                state.error?.take(300),
                state.coverage?.take(1000),
                state.balanceState,
                state.balanceDifference?.take(1000),
            ),
        )

    /** The saved balance snapshot of [wallet]: each batch at its own slot. */
    fun snapshot(wallet: String): List<SolanaAccountBatch> =
        db
            .query("SELECT slot, accounts FROM solana_snapshots WHERE wallet=? ORDER BY batch", listOf(wallet))
            .map { SolanaAccountBatch(it[0]!!.toLong(), decodeAccounts(it[1]!!)) }

    /** Replaces the saved snapshot of [wallet]. */
    fun saveSnapshot(
        wallet: String,
        batches: List<SolanaAccountBatch>,
    ) {
        db.execute("DELETE FROM solana_snapshots WHERE wallet=?", listOf(wallet))
        batches.forEachIndexed { index, batch ->
            db.execute(
                "INSERT INTO solana_snapshots VALUES (?, ?, ?, ?)",
                listOf(wallet, index.toString(), batch.slot.toString(), encodeAccounts(batch.accounts)),
            )
        }
    }

    fun clearSnapshot(wallet: String) = db.execute("DELETE FROM solana_snapshots WHERE wallet=?", listOf(wallet))

    fun mints(): Map<String, SolanaMintInfo> =
        db
            .query("SELECT mint, kind, symbol, name FROM solana_mints")
            .associate { it[0]!! to SolanaMintInfo(it[0]!!, SolanaMintKind.valueOf(it[1]!!), it[2], it[3]) }

    /** Saves recognized classifications only, so a mint without recognizable metadata is asked about again. */
    fun saveMints(
        infos: List<SolanaMintInfo>,
        atMillis: Long,
    ) = infos.filter { it.kind != SolanaMintKind.UNKNOWN }.forEach {
        db.execute(
            "INSERT OR REPLACE INTO solana_mints VALUES (?, ?, ?, ?, ?)",
            listOf(it.mint, it.kind.name, it.symbol, it.name, atMillis.toString()),
        )
    }

    private fun encodeAccounts(accounts: Map<String, SolanaChainAccount?>): String =
        accounts.entries.joinToString("\n") { (address, account) ->
            if (account == null) {
                "$address\t-"
            } else {
                listOf(address, account.lamports.toString(), account.mint ?: "", account.owner ?: "", account.amount?.toString() ?: "")
                    .joinToString("\t")
            }
        }

    private fun decodeAccounts(text: String): Map<String, SolanaChainAccount?> =
        text.lines().filter { it.isNotEmpty() }.associate { line ->
            val parts = line.split('\t')
            parts[0] to
                if (parts[1] == "-") {
                    null
                } else {
                    SolanaChainAccount(
                        ExactInteger.parse(parts[1]),
                        parts[2].ifEmpty { null },
                        parts[3].ifEmpty { null },
                        parts[4].ifEmpty { null }?.let(ExactInteger::parse),
                    )
                }
        }
}
