package io.valroot.core.solana

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.RefreshCancelled
import io.valroot.core.application.SourceConnector
import io.valroot.core.application.SourceRefreshOptions
import io.valroot.core.application.SourceRefreshOutcome
import io.valroot.core.connectors.Capability
import io.valroot.core.connectors.Coverage
import io.valroot.core.connectors.IntegrationManifest
import io.valroot.core.model.AccountId
import io.valroot.core.sync.JobState

/** Solana mainnet addresses through Helius: history with decoding and balance checks. */
val SOLANA_MANIFEST =
    IntegrationManifest(
        "solana-helius",
        setOf(SolanaIds.NETWORK_ID),
        setOf("address"),
        setOf(Capability.DISCOVERY, Capability.HISTORY, Capability.DECODING, Capability.BALANCE),
        requiresAuthentication = true,
        coverage = Coverage.PARTIAL,
    )

/**
 * Refreshes every tracked Solana address through Helius, at most [maxRequests] requests per address and refresh; a
 * longer history continues on the next refresh.
 *
 * 1. **Snapshot first:** when none is saved, the address's token accounts (found by owner, plus those open in stored
 *    history) and the address itself are read in batches of at most [Helius.ACCOUNT_BATCH], each at its own slot, and
 *    saved. It stays the comparison target until history has been read through every batch's slot.
 * 2. **History:** pages continue from the stream's cursor, oldest first. Each page is stored raw and projected in one
 *    database transaction. When a scan reaches the end, the stream is read through the finalized slot taken before it.
 *    Mints are classified by their metadata before their page is projected, so NFTs are left out from the start.
 * 3. **Block times:** a transaction the node returned without one keeps its activity waiting; its block's time is
 *    asked for, at most [MAX_BLOCK_TIMES] per refresh, slots that failed before last.
 * 4. **Balance check** ([SolanaService.checkBalances]): pending until history is read and projected through the
 *    snapshot, then account by account.
 *
 * [post] sends a JSON-RPC body built by [Helius] and returns the response.
 */
class SolanaConnector(
    private val post: (body: String) -> String,
    private val maxRequests: Int = MAX_REQUESTS,
) : SourceConnector {
    override val manifest = SOLANA_MANIFEST

    override fun refresh(
        app: PortfolioApplication,
        options: SourceRefreshOptions,
    ): SourceRefreshOutcome {
        val targets = app.solana.targets()
        if (targets.isEmpty()) return SourceRefreshOutcome(manifest.id, emptyList())
        require(app.solana.consent()) { "Solana consent required" }
        val jobs = mutableListOf<JobState>()
        val notes = mutableListOf<String>()
        targets.forEachIndexed { index, (account, wallet) ->
            options.onProgress("Solana · ${index + 1}/${targets.size}")
            jobs +=
                try {
                    if (!refreshWallet(app, account, wallet, options.recoverHistory)) {
                        notes += "History of ${wallet.take(6)}… is still being read; it continues next refresh"
                    }
                    JobState.COMPLETED
                } catch (cancelled: RefreshCancelled) {
                    throw cancelled
                } catch (failure: Exception) {
                    app.solana.recordError(wallet, failure.message ?: "Could not refresh this address")
                    JobState.FAILED
                }
        }
        return SourceRefreshOutcome(manifest.id, jobs, notes)
    }

    /** Refreshes one address; false when the request budget ran out before history reached its end. */
    fun refreshWallet(
        app: PortfolioApplication,
        account: AccountId,
        wallet: String,
        recoverHistory: Boolean = false,
    ): Boolean {
        var requests = 0

        fun call(body: String): String {
            check(requests < maxRequests) { "Request budget used" }
            requests++
            return post(body)
        }
        if (recoverHistory) app.solana.restartHistory(wallet)
        val asked = mutableSetOf<String>()
        var stored = app.solana.transactions(wallet)
        classify(app, wallet, stored, asked, ::call)
        var replay = app.solana.project(account, wallet, stored)

        if (app.solana.snapshot(wallet).isEmpty()) {
            val discovered =
                SolanaOwnership.TOKEN_PROGRAM_IDS.flatMap {
                    Helius
                        .parseTokenAccounts(
                            call(Helius.tokenAccountsBody(wallet, it)),
                        ).keys
                }
            val addresses = (listOf(wallet) + discovered + app.solana.openAccounts(wallet, stored)).distinct()
            val batches =
                addresses.chunked(Helius.ACCOUNT_BATCH).map { batch ->
                    Helius.parseAccounts(call(Helius.accountsBody(batch)), batch)
                }
            app.solana.saveSnapshot(wallet, batches)
        }

        val target = Helius.parseSlot(call(Helius.slotBody()))
        var stream = app.solana.stream(wallet)
        var finished = false
        // Two requests are kept back for the page's mint lookup and one for the page itself.
        while (requests + 2 <= maxRequests) {
            val page = Helius.parseHistory(call(Helius.historyBody(wallet, stream.cursor)))
            // Entries in a version this parser does not read are stored raw by the service and reported, not decoded.
            val parsed =
                page.entries.mapNotNull {
                    try {
                        SolanaTransaction.parse(it)
                    } catch (_: UnsupportedSolanaTransaction) {
                        null
                    }
                }
            classify(app, wallet, parsed, asked, ::call)
            val last = page.entries.map(SolanaTransaction::positionOf).maxWithOrNull(compareBy({ it.slot }, { it.index ?: -1 }))
            finished = page.paginationToken == null
            stream =
                stream.copy(
                    // At the end, the next refresh continues after the newest transaction read.
                    cursor = page.paginationToken ?: last?.index?.let { "${last.slot}:$it" } ?: stream.cursor,
                    scannedThrough = if (finished) target else stream.scannedThrough,
                )
            val applied = app.solana.applyPage(account, wallet, stream, page.entries, stored, replay)
            stored = applied.first
            replay = applied.second
            if (finished) break
        }
        // Activities without a block time wait for it; the block's time is asked for, a few slots per refresh.
        // Each lookup stands alone: a found time is saved at once, and a slot that fails or has no time yet is asked
        // after the others next refresh, so one bad slot cannot hold back the rest.
        val due = app.solana.blockTimeOrder(replay.undatedSlots).take(minOf(MAX_BLOCK_TIMES, maxRequests - requests))
        var recovered = false
        due.forEach { slot ->
            val time =
                try {
                    Helius.parseBlockTime(call(Helius.blockTimeBody(slot)))
                } catch (cancelled: RefreshCancelled) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            if (time == null) {
                app.solana.recordBlockTimeAttempt(slot)
            } else {
                app.solana.saveBlockTime(slot, time)
                recovered = true
            }
        }
        if (recovered) replay = app.solana.project(account, wallet, stored)
        app.solana.checkBalances(wallet, stored, replay)
        app.solana.recordSuccess(wallet, stored, replay)
        return finished
    }

    /**
     * Classifies mints [wallet] holds in [transactions] that are not classified yet, each asked at most once per refresh.
     * Classification is best effort: without it a mint is tracked as fungible and asked about next refresh.
     */
    private fun classify(
        app: PortfolioApplication,
        wallet: String,
        transactions: List<SolanaTransaction>,
        asked: MutableSet<String>,
        call: (String) -> String,
    ) {
        val known = app.solana.mints().keys
        val mints =
            transactions
                .flatMap { it.preTokens + it.postTokens }
                .filter { (it.owner == null || it.owner == wallet) && it.mint !in SolanaIds.WRAPPED_SOL_MINTS }
                .map { it.mint }
                .distinct()
                .filter { it !in known && it !in asked }
        if (mints.isEmpty()) return
        mints.chunked(Helius.ASSET_BATCH).forEach { batch ->
            asked += batch
            try {
                app.solana.saveMints(Helius.parseAssets(call(Helius.assetsBody(batch)), batch))
            } catch (cancelled: RefreshCancelled) {
                throw cancelled
            } catch (_: Exception) {
                // Left unclassified; asked again next refresh.
            }
        }
    }

    companion object {
        /** Requests per address and refresh, about 1,000 Helius credits at most. */
        const val MAX_REQUESTS = 100

        /** Block times asked for per address and refresh. */
        const val MAX_BLOCK_TIMES = 20
    }
}
