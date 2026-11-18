package io.valroot.core.evm

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.SourceConnector
import io.valroot.core.application.SourceRefreshOptions
import io.valroot.core.application.SourceRefreshOutcome
import io.valroot.core.connectors.Capability
import io.valroot.core.connectors.Coverage
import io.valroot.core.connectors.IntegrationManifest
import io.valroot.core.model.AccountId
import io.valroot.core.model.ExactInteger
import io.valroot.core.sync.JobState

/** EVM addresses on the networks Alchemy serves: discovery of used chains, history with decoding, balance checks. */
val EVM_MANIFEST =
    IntegrationManifest(
        "evm-alchemy",
        setOf(EVM_FAMILY),
        setOf("address"),
        setOf(Capability.DISCOVERY, Capability.HISTORY, Capability.DECODING, Capability.BALANCE),
        requiresAuthentication = true,
        coverage = Coverage.PARTIAL,
    )

/**
 * Refreshes every tracked EVM address through Alchemy.
 *
 * - **Discovery:** balances and newest transfers per group of five networks find the chains an address has used, on the
 *   first refresh and then weekly; chains the user chose are kept.
 * - **Skip check per chain:** the newest transfer (plus the newest contract-made transfer where listed) and the count of
 *   transactions the address has sent form the head. Nothing else is read while it is unchanged.
 * - **Full read:** every transfer page, then receipts for transactions the address sent, which give their fee
 *   (including any L1 data fee) and status.
 * - **Unlisted sent transactions:** Alchemy lists transfers, so a sent transaction that moved nothing (for example a
 *   failed call) is not listed. Each nonce no listed transaction uses is found by binary search over the address's
 *   historical transaction count, then read from its block and receipt, at most [MAX_NONCE_SEARCHES] per refresh.
 * - **Contract payments:** where Alchemy does not list contract-made transfers, and a 1inch key is set, 1inch's history
 *   names the transactions in which a contract paid the address, and each one's 1inch trace gives the exact payments.
 *   1inch's own fees and amounts are not used. Without a key, the chain records that such payments are not listed.
 * - **Token listing:** the tokens 1inch's history moves are saved, so tokens it leaves out as scams start hidden.
 *
 * Everything recovered is saved, so each lookup happens once. 1inch is asked again after [ONE_INCH_RECHECK_MILLIS]
 * even when Alchemy shows nothing new, because a contract can pay the address in someone else's transaction.
 *
 * A failing chain is recorded and the others continue. [post] sends a JSON body to a URL whose `{key}` placeholder the
 * host replaces with the API key; [oneInch] requests a path on [OneInch.BASE_URL] with the 1inch key, or is null
 * without one.
 */
class EvmConnector(
    private val post: (url: String, body: String) -> String,
    private val oneInch: ((path: String) -> String)? = null,
) : SourceConnector {
    override val manifest = EVM_MANIFEST

    override fun refresh(
        app: PortfolioApplication,
        options: SourceRefreshOptions,
    ): SourceRefreshOutcome {
        val targets = app.evm.targets()
        if (targets.isEmpty()) return SourceRefreshOutcome(manifest.id, emptyList())
        require(app.evm.consent()) { "EVM consent required" }
        val jobs = mutableListOf<JobState>()
        val notes = mutableListOf<String>()
        targets.forEach { (account, address) ->
            // Chains the address has started to use are added on the first refresh and then weekly; chosen chains stay.
            // A failed or partial check adds what it found and is repeated next refresh; the address's chains still refresh.
            if (options.recoverHistory || app.evm.discoveryDue(address)) {
                try {
                    val (chains, complete) = discover(address)
                    app.evm.addChains(address, chains)
                    if (complete) {
                        app.evm.recordDiscovery(address)
                    } else {
                        notes += "Some networks could not be checked for ${address.take(10)}…; they are checked again next refresh"
                    }
                } catch (cancelled: io.valroot.core.application.RefreshCancelled) {
                    throw cancelled
                } catch (failure: Exception) {
                    notes += "Networks for ${address.take(10)}… could not be checked; they are checked again next refresh"
                }
            }
            val states = app.evm.chainStates(address)
            if (states.isEmpty()) notes += "No activity found yet for ${address.take(10)}…"
            states.forEachIndexed { index, state ->
                options.onProgress("${Alchemy.network(state.chainId)?.name ?: "Chain ${state.chainId}"} · ${index + 1}/${states.size}")
                jobs +=
                    try {
                        requireNotNull(Alchemy.network(state.chainId)) { "This chain is not available from Alchemy" }
                        refreshChain(app, account, address, state, force = options.recoverHistory)
                        JobState.COMPLETED
                    } catch (cancelled: io.valroot.core.application.RefreshCancelled) {
                        throw cancelled
                    } catch (failure: Exception) {
                        app.evm.recordChainError(address, state.chainId, failure.message ?: "Could not refresh this chain")
                        JobState.FAILED
                    }
            }
        }
        return SourceRefreshOutcome(manifest.id, jobs, notes)
    }

    /**
     * Chains with a balance or any transfer, checked five networks per request; false when Alchemy could not check some
     * network, whose chain may then be missing. A group that fails leaves the chains other groups found.
     */
    fun discover(address: String): Pair<Set<Long>, Boolean> {
        var complete = true
        val chains =
            Alchemy.NETWORKS
                .map { it.chainId }
                .chunked(Alchemy.MAX_NETWORKS)
                .flatMap { group ->
                    try {
                        val (balances, balanceErrors) =
                            Alchemy.parseTokenBalances(
                                post(Alchemy.tokensUrl(), Alchemy.tokensBody(address, group)),
                            )
                        val transfers =
                            Alchemy.parseTransfers(
                                post(Alchemy.transfersUrl(), Alchemy.transfersBody(address, group, maxCount = 1)),
                            )
                        if (balanceErrors.isNotEmpty() || transfers.errors.isNotEmpty()) complete = false
                        val slugs =
                            balances.filter { it.second.value.sign > 0 }.map { it.first } +
                                transfers.transactions.map { it.first } +
                                transfers.tokens.map { it.first }
                        group.filter { chain -> Alchemy.network(chain)!!.slug in slugs }
                    } catch (cancelled: io.valroot.core.application.RefreshCancelled) {
                        throw cancelled
                    } catch (_: Exception) {
                        complete = false
                        emptyList()
                    }
                }.toSet()
        return chains to complete
    }

    private fun refreshChain(
        app: PortfolioApplication,
        account: AccountId,
        address: String,
        state: EvmChainState,
        force: Boolean,
    ) {
        val chain = state.chainId
        val network = Alchemy.network(chain)!!
        val newest = Alchemy.parseTransfers(post(Alchemy.transfersUrl(), Alchemy.transfersBody(address, listOf(chain), maxCount = 1)))
        failOnErrors(newest)
        val newestInternal =
            if (network.internalTransfers) {
                Alchemy.parseTransfers(
                    post(Alchemy.transfersUrl(), Alchemy.transfersBody(address, listOf(chain), maxCount = 1, internal = true)),
                )
            } else {
                null
            }
        val sent = Alchemy.parseNonce(post(Alchemy.rpcUrl(chain), Alchemy.nonceBody(address)))
        val head =
            listOf(
                (newest.transactions.map { it.second.hash } + newest.tokens.map { "${it.second.transactionHash}:${it.second.logIndex}" })
                    .firstOrNull(),
                newestInternal
                    ?.internal
                    ?.firstOrNull()
                    ?.second
                    ?.transactionHash,
                "n$sent",
            ).joinToString("|") { it ?: "-" }
        val recovered = app.evm.recovered()
        // A forced recovery first drops saved traces and nonce results whose transactions left the chain, before any of
        // them is read into this refresh.
        if (force) revalidateRecovered(app, address, chain)
        val recheck =
            oneInch != null &&
                chain in OneInch.CHAINS &&
                (force || recovered.listing(address, chain)?.let { app.clockMillis() - it.atMillis >= ONE_INCH_RECHECK_MILLIS } ?: true)
        var checkedOneInch = false
        // Activities decoded by an earlier decoder version are read again in full, so they follow the current rules.
        if (!force && head == state.head && !app.evm.decodedByEarlierVersion(address, chain)) {
            val paid = recheck && readOneInch(app, address, chain, network.internalTransfers)
            checkedOneInch = recheck
            // A found contract payment changes the history. So does a gap a full read can now close: sent transactions
            // Alchemy did not list, or contract payments once a 1inch key is set.
            val closable =
                state.coverage?.let { note ->
                    UNLISTED_NOTE in note || (oneInch != null && chain in OneInch.CHAINS && CONTRACT_NOTE in note)
                } == true
            if (!paid && !closable) {
                // A rebasing token's balance changes without a transfer, so it is read on every refresh; a difference
                // left by an earlier check is settled too.
                if (state.balanceDifference != null || app.evm.holdsRebasing(account, chain)) checkBalances(app, account, address, chain)
                app.evm.recordChainSuccess(address, chain, head)
                return
            }
        }
        // Until this read and its balance check finish, the chain has no accepted head: a failure anywhere below makes
        // the next refresh read it in full again instead of skipping it as unchanged.
        app.evm.clearChainHead(address, chain)

        val transactions = mutableListOf<EvmTransaction>()
        val internal = mutableListOf<EvmInternalTransfer>()
        val tokens = mutableListOf<EvmTokenTransfer>()
        pages(address, chain, internal = false).forEach { page ->
            transactions += page.transactions.map { it.second }
            tokens += page.tokens.map { it.second }
        }
        if (network.internalTransfers) {
            pages(address, chain, internal = true).forEach { page ->
                internal += page.internal.map { it.second }
            }
        } else {
            if (recheck && !checkedOneInch) readOneInch(app, address, chain, internalListed = false)
            internal += recovered.traces(address, chain).values.flatten()
        }
        if (network.internalTransfers && recheck && !checkedOneInch) readOneInch(app, address, chain, internalListed = true)

        // Receipts for every transaction the address may have sent: fee, status and the actual sender.
        val candidates =
            (transactions.filter { it.from == address }.map { it.hash } + tokens.filter { it.from == address }.map { it.transactionHash })
                .distinct()
        val receipts =
            candidates
                .chunked(Alchemy.RECEIPT_BATCH)
                .flatMap { batch -> Alchemy.parseReceipts(post(Alchemy.rpcUrl(chain), Alchemy.receiptsBody(batch))) }
                .filter { it.from == address }
                .associateBy { it.hash }
        val listed = transactions.associateBy { it.hash }
        val found = recoverSentTransactions(app, address, chain, sent, receipts.keys).filter { it.hash !in listed && it.hash !in receipts }
        val withFees =
            transactions.map { tx ->
                receipts[tx.hash]?.let { tx.copy(fee = it.fee, success = it.success, position = it.position) } ?: tx
            } +
                receipts.values.filter { it.hash !in listed }.map { receipt ->
                    // A sent transaction listed only through its token transfers: zero native value, fee from the receipt.
                    val token = tokens.first { it.transactionHash == receipt.hash }
                    EvmTransaction(
                        receipt.hash,
                        receipt.blockNumber,
                        receipt.position,
                        token.timestampMillis,
                        address,
                        token.contract,
                        ExactInteger.ZERO,
                        receipt.fee,
                        receipt.success,
                    )
                } + found
        val positions = positions(app, address, chain, withFees, internal, tokens)
        // The head is saved only after the balance check below, so a failed check is repeated next refresh.
        app.evm.applyChainHistory(account, address, chain, EvmDecoder.group(chain, withFees, internal, tokens, positions), head = null)

        val searched = recovered.nonces(address, chain)
        val unresolved = searched.count { it.value == null }
        val pending = (sent - receipts.size - found.size - unresolved).coerceAtLeast(0)

        fun count(n: Long) = "$n sent transaction${if (n == 1L) "" else "s"}"
        app.evm.recordCoverage(
            address,
            chain,
            listOfNotNull(
                if (pending > 0) "${count(pending)} $UNLISTED_NOTE and still to be searched for; their fees are not counted yet" else null,
                if (unresolved > 0) "${count(unresolved.toLong())} could not be found on the chain; their fees are not counted" else null,
                when {
                    network.internalTransfers -> null
                    oneInch != null && chain in OneInch.CHAINS -> null
                    chain in OneInch.CHAINS -> "$CONTRACT_NOTE; a 1inch key recovers them"
                    else -> CONTRACT_NOTE
                },
            ).joinToString(". ").ifEmpty { null },
        )
        checkBalances(app, account, address, chain)
        app.evm.recordChainSuccess(address, chain, head)
    }

    /**
     * Block positions of transactions the address did not send (incoming coin, token and contract transfers), from
     * their receipts. A position is saved only once its block is [REORG_DEPTH] blocks deep, so a reorganisation that
     * puts the transaction elsewhere at the same height cannot leave a stale position behind; newer ones are read on
     * every full read. A transaction without a receipt keeps an unknown position.
     */
    private fun positions(
        app: PortfolioApplication,
        address: String,
        chain: Long,
        transactions: List<EvmTransaction>,
        internal: List<EvmInternalTransfer>,
        tokens: List<EvmTokenTransfer>,
    ): Map<String, Int> {
        val recovered = app.evm.recovered()
        val known = transactions.filter { it.position != null }.map { it.hash }.toSet()
        val blocks =
            (
                transactions.mapNotNull { tx -> tx.blockNumber?.takeIf { tx.position == null }?.let { tx.hash to it } } +
                    tokens.map { it.transactionHash to it.blockNumber } +
                    internal.map { it.transactionHash to it.blockNumber }
            ).toMap() - known
        if (blocks.isEmpty()) return emptyMap()
        val settledThrough = Alchemy.parseQuantity(post(Alchemy.rpcUrl(chain), Alchemy.blockNumberBody())) - REORG_DEPTH
        val saved =
            recovered.positions(address, chain).filter { (hash, saved) ->
                blocks[hash] == saved.first &&
                    saved.first <= settledThrough
            }
        val positions = saved.mapValues { it.value.second }.toMutableMap()
        blocks.keys.filter { it !in saved }.chunked(Alchemy.RECEIPT_BATCH).forEach { batch ->
            Alchemy.parseReceiptsByHash(post(Alchemy.rpcUrl(chain), Alchemy.receiptsBody(batch)), batch).forEach { (hash, receipt) ->
                if (receipt == null || receipt.blockNumber != blocks[hash]) return@forEach
                positions[hash] = receipt.position
                if (receipt.blockNumber <=
                    settledThrough
                ) {
                    recovered.savePosition(address, chain, hash, receipt.blockNumber, receipt.position)
                }
            }
        }
        return positions
    }

    /**
     * Checks saved nonce results and contract traces against current receipts: one whose transaction has no receipt or
     * is now in another block is forgotten, so this read finds it again.
     */
    private fun revalidateRecovered(
        app: PortfolioApplication,
        address: String,
        chain: Long,
    ) {
        val recovered = app.evm.recovered()
        val nonces = recovered.nonces(address, chain).mapNotNull { (nonce, tx) -> tx?.let { Triple(it.hash, it.blockNumber, "n$nonce") } }
        val traces =
            recovered.traces(address, chain).mapNotNull { (hash, transfers) ->
                transfers.firstOrNull()?.let { Triple(hash, it.blockNumber, "t$hash") }
            }
        val saved = nonces + traces
        val receipts =
            saved
                .map { it.first }
                .distinct()
                .chunked(Alchemy.RECEIPT_BATCH)
                .flatMap { batch -> Alchemy.parseReceiptsByHash(post(Alchemy.rpcUrl(chain), Alchemy.receiptsBody(batch)), batch).entries }
                .associate { it.key to it.value }
        saved.forEach { (hash, block, item) ->
            if (receipts[hash]?.blockNumber != block) {
                if (item.startsWith("n")) {
                    recovered.forget(address, chain, "nonce", item.drop(1))
                } else {
                    recovered.forget(address, chain, "trace", hash)
                }
            }
        }
    }

    /**
     * Reads the provider's balances, follows rebasing tokens and the native coin to them, and records any remaining
     * difference.
     */
    private fun checkBalances(
        app: PortfolioApplication,
        account: AccountId,
        address: String,
        chain: Long,
    ) {
        val (balances, errors) = Alchemy.parseTokenBalances(post(Alchemy.tokensUrl(), Alchemy.tokensBody(address, listOf(chain))))
        require(errors.isEmpty()) { "Alchemy could not report balances on this chain" }
        val tokens = balances.map { it.second }
        app.evm.reconcileRebasingBalances(account, address, chain, tokens)
        val native = tokens.firstOrNull { it.standard == EvmTokenStandard.NATIVE }?.value ?: ExactInteger.ZERO
        app.evm.reconcileNativeBalance(account, address, chain, native)
        app.evm.checkBalances(account, address, chain, native, tokens)
    }

    /**
     * Reads 1inch's history of the address on the chain: saves the token contracts it lists and, where Alchemy does not
     * list contract payments, traces each new transaction in which 1inch reports one. Returns whether a trace found a
     * payment. The listing is dated only once every such transaction is traced, so an interrupted run resumes.
     */
    private fun readOneInch(
        app: PortfolioApplication,
        address: String,
        chain: Long,
        internalListed: Boolean,
    ): Boolean {
        val fetch = oneInch ?: return false
        val recovered = app.evm.recovered()
        val events = mutableListOf<OneInchEvent>()
        var before: Long? = null
        do {
            require(events.size < OneInch.HISTORY_LIMIT * OneInch.MAX_HISTORY_PAGES) { "1inch history is too long" }
            val page = OneInch.parseHistory(fetch(OneInch.historyPath(address, chain, before)), address)
            events += page
            before = page.minOfOrNull { it.timestampMillis }?.minus(1)
        } while (page.size >= OneInch.HISTORY_LIMIT)
        val traced = recovered.traces(address, chain)
        val due = if (internalListed) emptyList() else events.filter { it.contractPayment && it.hash !in traced }.distinctBy { it.hash }
        var paid = false
        due.take(MAX_TRACES).forEach { event ->
            val transfers =
                OneInch.parseTrace(
                    fetch(OneInch.tracePath(chain, event.blockNumber, event.hash)),
                    event.hash,
                    event.blockNumber,
                    event.timestampMillis,
                    address,
                )
            recovered.saveTrace(address, chain, event.hash, transfers)
            if (transfers.any { it.success }) paid = true
        }
        val complete = due.size <= MAX_TRACES
        recovered.saveListing(
            address,
            chain,
            OneInchListing(if (complete) app.clockMillis() else 0L, events.flatMap { it.contracts }.toSet()),
        )
        return paid
    }

    /**
     * Finds sent transactions Alchemy did not list by their nonce: the block where the address's transaction count
     * passes the nonce holds the transaction, and its receipt gives fee and status. Results, including nonces no
     * transaction in that block uses (such as a delegation authorization), are saved. Returns every found transaction.
     */
    private fun recoverSentTransactions(
        app: PortfolioApplication,
        address: String,
        chain: Long,
        sent: Long,
        known: Set<String>,
    ): List<EvmTransaction> {
        val recovered = app.evm.recovered()
        val searched = recovered.nonces(address, chain)
        val gap = sent - known.size - searched.size
        if (gap > 0) {
            val nonces =
                known
                    .toList()
                    .chunked(Alchemy.RECEIPT_BATCH)
                    .flatMap { batch -> Alchemy.parseNonces(post(Alchemy.rpcUrl(chain), Alchemy.transactionsBody(batch))).values }
                    .toSet()
            val missing = (0 until sent).filter { it !in nonces && it !in searched }.take(MAX_NONCE_SEARCHES)
            if (missing.isNotEmpty()) {
                val latest = Alchemy.parseQuantity(post(Alchemy.rpcUrl(chain), Alchemy.blockNumberBody()))
                missing.forEach { nonce ->
                    // The count at block `low` is at most the nonce and at `high` above it.
                    var low = 0L
                    var high = latest
                    while (high - low > 1) {
                        val middle = low + (high - low) / 2
                        if (Alchemy.parseQuantity(post(Alchemy.rpcUrl(chain), Alchemy.nonceAtBody(address, middle))) > nonce) {
                            high = middle
                        } else {
                            low = middle
                        }
                    }
                    val transaction =
                        Alchemy.parseBlockTransaction(post(Alchemy.rpcUrl(chain), Alchemy.blockBody(high)), address, nonce)?.let { tx ->
                            val receipt = Alchemy.parseReceipts(post(Alchemy.rpcUrl(chain), Alchemy.receiptsBody(listOf(tx.hash)))).single()
                            tx.copy(fee = receipt.fee, success = receipt.success, position = receipt.position, source = NONCE_SOURCE)
                        }
                    recovered.saveNonce(address, chain, nonce, transaction)
                }
            }
        }
        return recovered.nonces(address, chain).values.filterNotNull()
    }

    private fun pages(
        address: String,
        chain: Long,
        internal: Boolean,
    ): List<AlchemyTransfers> {
        val result = mutableListOf<AlchemyTransfers>()
        var key: String? = null
        do {
            require(result.size < MAX_PAGES) { "History is longer than $MAX_PAGES pages" }
            val page =
                Alchemy.parseTransfers(
                    post(Alchemy.transfersUrl(), Alchemy.transfersBody(address, listOf(chain), key, internal = internal)),
                )
            failOnErrors(page)
            result += page
            key = page.pageKey
        } while (key != null)
        return result
    }

    /** A timeout or error on the chain means the history is incomplete, so nothing is applied. */
    private fun failOnErrors(page: AlchemyTransfers) {
        page.errors.firstOrNull()?.let { throw IllegalStateException("Alchemy could not read this chain (${it.state})") }
    }

    companion object {
        const val MAX_PAGES = 400

        /** Blocks below the chain head after which a saved transaction position is trusted not to move. */
        const val REORG_DEPTH = 256L

        /** Nonces searched per chain and refresh; each search takes about 30 small requests. */
        const val MAX_NONCE_SEARCHES = 3

        /** Transactions traced per chain and refresh; the rest follow on the next refresh. */
        const val MAX_TRACES = 100

        /** 1inch is asked again after this long even when Alchemy shows nothing new. */
        const val ONE_INCH_RECHECK_MILLIS = 6 * 3_600_000L

        /** Coverage note text for sent transactions a full read can still search for. */
        const val UNLISTED_NOTE = "not listed by Alchemy"

        /** Coverage note text for contract payments Alchemy does not list on a chain. */
        const val CONTRACT_NOTE = "Payments made by contracts are not listed on this chain"
    }
}
