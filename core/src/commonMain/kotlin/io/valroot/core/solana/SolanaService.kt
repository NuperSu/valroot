package io.valroot.core.solana

import io.valroot.core.application.AppContext
import io.valroot.core.application.AppService
import io.valroot.core.ledger.ActivityStatus
import io.valroot.core.market.AssetDefinition
import io.valroot.core.market.AssetIdentity
import io.valroot.core.market.AssetKind
import io.valroot.core.market.AssetMetadata
import io.valroot.core.model.AccountId
import io.valroot.core.model.SourceRecordId
import io.valroot.core.storage.RawObservation
import io.valroot.core.storage.sha256
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * Solana addresses: the Helius key and consent, raw-first history with ordered replay, mint classification, coverage
 * and balance checks.
 *
 * Every transaction is stored raw before anything is derived from it. Activities are a deterministic replay of a
 * wallet's stored transactions in chain order ([SolanaDecoder.replay]); a page's raw records, its stream cursor and the
 * activities it changes commit together, so an interrupted refresh resumes from the last committed page.
 */
class SolanaService internal constructor(
    ctx: AppContext,
) : AppService(ctx) {
    private val repository get() = solanaRepository

    /** The Helius API key for Solana history; a device setting, not part of backups. */
    fun heliusApiKey(): String? = secrets.get("helius_api_key")

    fun setHeliusApiKey(key: String?) {
        val trimmed = key?.trim()?.takeIf { it.isNotEmpty() }
        require(trimmed == null || trimmed.matches(Regex("[A-Za-z0-9-]{20,100}"))) { "That does not look like a Helius API key" }
        secrets.put("helius_api_key", trimmed)
    }

    /** Solana addresses are sent to Helius only after this consent. */
    fun consent(): Boolean = setting("solana_consent") == "true"

    fun setConsent(accepted: Boolean) = putSetting("solana_consent", accepted.toString())

    /** Enabled Solana address targets with their accounts. */
    fun targets(): List<Pair<AccountId, String>> =
        ownership
            .load()
            .targets
            .filter { it.enabled && it.identity.networkId == SolanaIds.NETWORK_ID && it.identity.kind == "address" }
            .map { it.accountId to it.identity.canonicalKey }

    fun state(address: String): SolanaWalletState = repository.state(address)

    fun stream(
        wallet: String,
        stream: String = wallet,
    ): SolanaStream = repository.stream(wallet, stream)

    /** Starts history again from the provider; stored transactions stay and are deduplicated by signature. */
    fun restartHistory(wallet: String) =
        db.transaction {
            repository.restartStreams(wallet)
            repository.clearSnapshot(wallet)
        }

    /** The stored transactions of [wallet], parsed, in chain order. */
    fun transactions(wallet: String): List<SolanaTransaction> =
        repository.payloads(wallet).map(SolanaTransaction::parse).sortedWith(SolanaDecoder.CHAIN_ORDER)

    /** Classified mints: NFTs are left out of activities, unknown mints are tracked as fungible and asked about again. */
    fun mints(): Map<String, SolanaMintInfo> = repository.mints()

    fun saveMints(infos: List<SolanaMintInfo>) = db.transaction { repository.saveMints(infos, now()) }

    /**
     * Stores one history page of [stream] raw, moves the stream's cursor (and watermark, when the page ends the scan),
     * and projects [wallet]'s activities, all in one database transaction. [stored] are the wallet's transactions
     * already stored, in chain order; [previous] is the replay of them, continued when the page only adds later ones.
     * Returns the wallet's transactions with the page's and the new replay.
     */
    fun applyPage(
        account: AccountId,
        wallet: String,
        stream: SolanaStream,
        entries: List<JsonObject>,
        stored: List<SolanaTransaction>,
        previous: SolanaDecoder.Replay?,
    ): Pair<List<SolanaTransaction>, SolanaDecoder.Replay> {
        requireTarget(account, wallet)
        val parsed = entries.map { it to SolanaTransaction.parse(it) }
        val retrieved = Instant.fromEpochMilliseconds(now()).toString()
        val known = stored.mapTo(mutableSetOf()) { it.signature }
        val added = parsed.filter { it.second.signature !in known }.map { it.second }
        val all = (stored + added).sortedWith(SolanaDecoder.CHAIN_ORDER)
        lateinit var replay: SolanaDecoder.Replay
        db.transaction {
            parsed.forEach { (entry, transaction) ->
                val payload = entry.toString()
                val record =
                    store.ingestInTransaction(
                        RawObservation(
                            "${SolanaIds.TRANSACTION_PREFIX}${transaction.signature}",
                            SolanaIds.NETWORK_ID.value,
                            transaction.signature,
                            SolanaIds.NETWORK_ID.value,
                            "helius",
                            retrieved,
                            payload,
                            PAYLOAD_VERSION,
                            sha256(payload),
                            "address=$wallet",
                        ),
                    )
                repository.index(wallet, transaction, record.value)
            }
            repository.saveStream(stream)
            replay = projectInTransaction(account, wallet, all, previous?.let { it to added })
        }
        invalidate()
        return all to replay
    }

    /** Replays every stored transaction of [wallet] and saves the activities that changed; obsolete ones are superseded. */
    fun project(
        account: AccountId,
        wallet: String,
        stored: List<SolanaTransaction> = transactions(wallet),
    ): SolanaDecoder.Replay {
        requireTarget(account, wallet)
        lateinit var replay: SolanaDecoder.Replay
        db.transaction { replay = projectInTransaction(account, wallet, stored, null) }
        invalidate()
        return replay
    }

    /**
     * Replays [all] for [wallet], continuing [resume] (a replay and the transactions added after it) when possible.
     * A full replay supersedes the wallet's transaction activities it no longer produces, except those at or after a
     * deferred slot, which are not projected yet.
     */
    private fun projectInTransaction(
        account: AccountId,
        wallet: String,
        all: List<SolanaTransaction>,
        resume: Pair<SolanaDecoder.Replay, List<SolanaTransaction>>?,
    ): SolanaDecoder.Replay {
        val sources = mutableMapOf<String, SourceRecordId>()

        fun source(transaction: SolanaTransaction) =
            sources.getOrPut(transaction.signature) { SourceRecordId("${SolanaIds.TRANSACTION_PREFIX}${transaction.signature}") }
        val nfts = repository.mints().filterValues { it.kind == SolanaMintKind.NFT }.keys
        val continued =
            resume
                ?.takeIf { (replay, added) -> SolanaDecoder.canContinue(replay, added, nfts) }
                ?.let { (replay, added) -> SolanaDecoder.replay(wallet, account, added, nfts, replay, ::source) }
        val replay = continued ?: SolanaDecoder.replay(wallet, account, all, nfts, null, ::source)
        val fresh = if (continued != null) replay.activities.drop(resume!!.first.activities.size) else replay.activities
        registerAssets(all, replay)
        fresh.forEach { activity -> if (activities.currentById(activity.id) != activity) activities.saveInTransaction(activity) }
        if (continued == null) {
            val ids = replay.activities.mapTo(mutableSetOf()) { it.id }
            val deferred = replay.deferredFromSlot
            activities
                .current()
                .filter { it.id !in ids && SolanaIds.signatureOf(it.id.value) != null && it.id.value.endsWith(":$wallet") }
                .filter { it.status == ActivityStatus.CONFIRMED || it.status == ActivityStatus.PENDING }
                .filter { deferred == null || slotOf(it.orderingKey) < deferred }
                .forEach { activities.saveInTransaction(it.copy(status = ActivityStatus.SUPERSEDED)) }
        }
        return replay
    }

    private fun slotOf(orderingKey: String): Long = orderingKey.split(':').getOrNull(1)?.toLongOrNull() ?: Long.MAX_VALUE

    /** Registers SOL and every token the replay moves, with decimals from the node and names from mint metadata. */
    private fun registerAssets(
        transactions: List<SolanaTransaction>,
        replay: SolanaDecoder.Replay,
    ) {
        val known = market.assetIds().toSet()
        val used =
            replay.activities
                .flatMap { it.movements }
                .map { it.amount.assetId }
                .toSet()
        if (SolanaIds.NATIVE in used && SolanaIds.NATIVE !in known) {
            market.registerAssetInTransaction(
                AssetDefinition(SolanaIds.NATIVE, AssetIdentity(AssetKind.NATIVE, SolanaIds.NETWORK_ID, "native")),
                AssetMetadata("SOL", "Solana", SolanaIds.NATIVE_DECIMALS, 0),
            )
        }
        val missing = used.filter { it !in known }.mapNotNull { asset -> SolanaIds.mintOf(asset)?.let { asset to it } }
        if (missing.isEmpty()) return
        val decimals =
            transactions
                .flatMap { it.preTokens + it.postTokens }
                .associate { it.mint to it.decimals }
        val infos = repository.mints()
        missing.forEach { (asset, mint) ->
            val info = infos[mint]
            val short = "${mint.take(4)}…${mint.takeLast(4)}"
            market.registerAssetInTransaction(
                AssetDefinition(asset, AssetIdentity(AssetKind.TOKEN, SolanaIds.NETWORK_ID, mint)),
                AssetMetadata(info?.symbol ?: short, info?.name ?: "Solana token $short", decimals[mint] ?: 0, 0),
            )
        }
    }

    /** The account of every open token account [wallet] owns as of the latest stored history. */
    fun openAccounts(
        wallet: String,
        stored: List<SolanaTransaction>,
    ): List<String> = SolanaBalances.openAccounts(wallet, SolanaBalances.at(stored, Long.MAX_VALUE))

    fun saveSnapshot(
        wallet: String,
        batches: List<SolanaAccountBatch>,
    ) = db.transaction { repository.saveSnapshot(wallet, batches) }

    fun snapshot(wallet: String): List<SolanaAccountBatch> = repository.snapshot(wallet)

    /**
     * Compares the saved snapshot with stored history, each batch at its own slot, and the replayed activities with the
     * accounts they come from at the latest batch's slot. The check stays pending while any
     * stream has not been read through a batch's slot; a finished check clears the snapshot so the next refresh takes
     * a new one. Returns the recorded state.
     */
    fun checkBalances(
        wallet: String,
        stored: List<SolanaTransaction>,
        replay: SolanaDecoder.Replay,
        streams: List<SolanaStream> = repository.streams(wallet),
    ): SolanaWalletState {
        val batches = repository.snapshot(wallet)
        val current = repository.state(wallet)
        if (batches.isEmpty()) return current
        val readThrough = streams.minOfOrNull { it.scannedThrough ?: -1 } ?: -1
        val state =
            if (streams.isEmpty() || batches.any { it.slot > readThrough }) {
                current.copy(balanceState = PENDING)
            } else {
                // Each batch against history at its own slot, then the ledger against the accounts at the latest one.
                val latest = batches.maxOf { it.slot }
                val differences =
                    batches.flatMap { SolanaBalances.differences(wallet, it, SolanaBalances.at(stored, it.slot)) } +
                        SolanaBalances.unexplained(
                            wallet,
                            SolanaBalances.at(stored, latest),
                            replay.activities.filter { slotOf(it.orderingKey) <= latest },
                            replay.ignoredMints,
                        )
                current.copy(
                    balanceState = if (differences.isEmpty()) MATCHED else DIFFERENT,
                    balanceDifference = differences.take(MAX_DIFFERENCES).joinToString("; ").ifEmpty { null },
                )
            }
        db.transaction {
            repository.saveState(state)
            if (state.balanceState != PENDING) repository.clearSnapshot(wallet)
        }
        return state
    }

    /** Records a finished refresh of [wallet] with what its replay could not determine. */
    fun recordSuccess(
        wallet: String,
        stored: List<SolanaTransaction>,
        replay: SolanaDecoder.Replay,
    ) {
        val mints = repository.mints()
        val held =
            replay.activities
                .flatMap { it.movements }
                .mapNotNull { SolanaIds.mintOf(it.amount.assetId) }
                .toSet()
        val touched = stored.flatMap { it.preTokens + it.postTokens }.filter { it.owner == wallet }.mapTo(mutableSetOf()) { it.mint }
        val gaps = replay.gaps.groupBy { it.reason }

        fun tokens(reason: SolanaGapReason) =
            gaps[reason]
                .orEmpty()
                .mapNotNull { it.asset }
                .distinct()
                .size

        fun plural(
            count: Int,
            noun: String,
        ) = "$count $noun${if (count == 1) "" else "s"}"
        val notes =
            listOfNotNull(
                gaps[SolanaGapReason.TOKEN_BALANCES_UNAVAILABLE]?.let {
                    "${plural(it.size, "transaction")} without recorded token balances; their token changes are not counted"
                },
                tokens(SolanaGapReason.TRANSFER_FEE_UNKNOWN).takeIf { it > 0 }?.let {
                    "${plural(it, "token")} with transfer fees that could not be determined"
                },
                tokens(SolanaGapReason.UNSUPPORTED_EXTENSION).takeIf { it > 0 }?.let {
                    "${plural(it, "token")} using features Valroot does not interpret"
                },
                touched.count { mints[it]?.kind == SolanaMintKind.NFT }.takeIf { it > 0 }?.let {
                    "${plural(it, "NFT")} not tracked"
                },
                held.count { it !in mints }.takeIf { it > 0 }?.let { "${plural(it, "token")} not yet classified" },
                replay.deferredFromSlot?.let { "History from slot $it waits until its transaction order is known" },
            )
        db.transaction {
            repository.saveState(
                repository
                    .state(
                        wallet,
                    ).copy(lastSuccessMillis = now(), error = null, coverage = notes.joinToString(". ").ifEmpty { null }),
            )
        }
    }

    fun recordError(
        wallet: String,
        message: String,
    ) = db.transaction { repository.saveState(repository.state(wallet).copy(error = message)) }

    private fun requireTarget(
        account: AccountId,
        wallet: String,
    ) {
        require(consent()) { "Solana consent required" }
        require(targets().any { it.first == account && it.second == wallet }) { "Unknown Solana address" }
    }

    companion object {
        const val PAYLOAD_VERSION = "helius-jsonParsed-1"
        const val MATCHED = "matched"
        const val PENDING = "pending"
        const val DIFFERENT = "different"
        private const val MAX_DIFFERENCES = 10
    }
}
