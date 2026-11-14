package io.valroot.core.exchange.bybit

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.application.SourceRefreshOptions
import io.valroot.core.connectors.Capability
import io.valroot.core.connectors.Coverage
import io.valroot.core.connectors.IntegrationManifest
import io.valroot.core.exchange.ExchangeBalance
import io.valroot.core.exchange.ExchangeConnector
import io.valroot.core.exchange.ExchangeContract
import io.valroot.core.exchange.ExchangeHistory
import io.valroot.core.exchange.ExchangeKeyInfo
import io.valroot.core.exchange.ExchangeKind
import io.valroot.core.exchange.ExchangeProvider
import io.valroot.core.exchange.ExchangeRow
import io.valroot.core.exchange.ExchangeSnapshot
import io.valroot.core.exchange.ExchangeTransport
import io.valroot.core.exchange.RequestBudget
import io.valroot.core.exchange.StreamWindows
import io.valroot.core.model.AccountId
import io.valroot.core.model.NetworkId

/** Bybit accounts are tracked by their user ID on this network. */
val BYBIT_NETWORK: NetworkId = ExchangeKind.BYBIT.network

const val BYBIT_EXCHANGE = "bybit"

/** Bybit accounts through a read-only API key: balances, both balance logs, deposits, withdrawals and converts. */
val BYBIT_MANIFEST =
    IntegrationManifest(
        "bybit-api",
        setOf(BYBIT_NETWORK),
        setOf("uid"),
        setOf(Capability.BALANCE, Capability.HISTORY, Capability.DECODING),
        requiresAuthentication = true,
        coverage = Coverage.PARTIAL,
    )

/** One history stream: its request window, how far back Bybit keeps it, and when reading back may stop early. */
private data class BybitStream(
    val name: String,
    val windowMillis: Long,
    val retentionMillis: Long,
    /** Stop reading back after this many windows in a row without a record; null reads back to [retentionMillis]. */
    val stopAfterEmpty: Int?,
)

/**
 * Refreshes every tracked Bybit account through [get], which signs and sends a request with the account's API key.
 *
 * Each refresh checks the key (it must be read-only and belong to the tracked account), reads the balances, then each
 * history stream: forward from where it stopped (one day of overlap, so late records are caught), then further back
 * until Bybit's retention ends or the account's start is passed. At most [MAX_REQUESTS] requests are made per account
 * and refresh; the rest continues next time. Every record is saved as it arrives, so an interrupted refresh resumes.
 * Symbols not matched to a coin yet get their token contracts from Bybit's coin information, which CoinGecko's
 * contract map turns into coins. Then the account's activities are derived again from all saved records
 * ([BybitDecoder]).
 */
class BybitConnector(
    private val get: (account: AccountId, request: BybitRequest) -> String,
) : ExchangeConnector(ExchangeKind.BYBIT) {
    override val manifest = BYBIT_MANIFEST

    override fun sync(
        app: PortfolioApplication,
        account: AccountId,
        uid: String,
        options: SourceRefreshOptions,
    ): Boolean {
        val key = Bybit.parseKeyInfo(get(account, Bybit.keyInfo()))
        require(key.uid == uid) { "This API key belongs to another Bybit account" }
        require(key.readOnly) { "This API key can trade or withdraw; Valroot accepts only read-only keys" }
        val now = app.clockMillis()
        val balances =
            (if (key.unified) Bybit.parseUnifiedBalance(get(account, Bybit.unifiedBalance())) else emptyList()) +
                Bybit.parseFundingBalance(get(account, Bybit.fundingBalance())) +
                Bybit.parseOverview(get(account, Bybit.overview()))
        val budget = RequestBudget<BybitRequest>(MAX_REQUESTS) { get(account, it) }
        val streams =
            listOfNotNull(
                BybitStream(BybitDecoder.LOG, Bybit.LOG_WINDOW_MILLIS, Bybit.LOG_RETENTION_MILLIS, null).takeIf { key.unified },
                // The Funding history can be quiet for months while deposits and withdrawals happened, so it is read
                // back fully, until Bybit refuses older windows.
                BybitStream(BybitDecoder.FUNDING, Bybit.LOG_WINDOW_MILLIS, MAX_RETENTION_MILLIS, null),
                BybitStream(BybitDecoder.DEPOSITS, Bybit.RECORD_WINDOW_MILLIS, MAX_RETENTION_MILLIS, 12),
                BybitStream(BybitDecoder.WITHDRAWALS, Bybit.RECORD_WINDOW_MILLIS, MAX_RETENTION_MILLIS, 12),
            )
        var unfinished = false
        streams.forEach { stream ->
            options.onProgress("Bybit · ${STREAM_LABELS.getValue(stream.name)}")
            val windows = StreamWindows(stream.name, stream.windowMillis, now - stream.retentionMillis, stream.stopAfterEmpty)
            val complete =
                readStream(
                    app,
                    account,
                    windows,
                    now,
                    { !budget.spent },
                ) { from, to -> readWindow(app, account, uid, stream.name, from, to, budget) }
            if (!complete) unfinished = true
        }
        if (!readConverts(app, account, uid, budget)) unfinished = true
        // Symbols not matched to a coin yet: Bybit's token contracts let CoinGecko's contract map name the coin.
        val held = balances.filter { it.amount.coefficient.sign != 0 }.map { it.coin }
        val unmatched = app.exchanges.unmatchedSymbols(BYBIT_EXCHANGE, app.exchanges.rowSymbols(ExchangeKind.BYBIT, account) + held)
        val contracts = mutableMapOf<String, List<ExchangeContract>>()
        unmatched.sorted().take(MAX_COIN_LOOKUPS).forEach { symbol ->
            if (!budget.spent) contracts += Bybit.parseCoinContracts(budget.send(Bybit.coin(symbol)))
        }
        val covered = app.exchanges.streams(account).filterKeys { it == BybitDecoder.LOG || it == BybitDecoder.FUNDING }
        val coveredFrom =
            covered
                .map { (stream, state) ->
                    (
                        if (stream ==
                            BybitDecoder.LOG
                        ) {
                            BybitDecoder.UNIFIED
                        } else {
                            BybitDecoder.FUND
                        }
                    ) to state.fromMillis
                }.toMap()
        val snapshot =
            ExchangeSnapshot(
                now,
                balances.map { ExchangeBalance(it.accountType, it.coin, it.amount) },
                contracts = contracts,
                coveredFrom = coveredFrom,
                keyExpiresAtMillis = key.expiresAtMillis,
            )
        app.exchanges.applyHistory(ExchangeKind.BYBIT, account, uid, snapshot)
        return unfinished
    }

    /**
     * Reads every page of one window and saves each page's records; returns how many there were, or null when the
     * budget ran out. The next page is kept in a cursor while the window is read, so a refresh stopped by the budget or
     * a failure resumes the window there.
     */
    private fun readWindow(
        app: PortfolioApplication,
        account: AccountId,
        uid: String,
        stream: String,
        from: Long,
        to: Long,
        budget: RequestBudget<BybitRequest>,
    ): Int? {
        val pageKey = WINDOW_CURSOR + stream
        val resumed =
            app.exchanges.cursors(account)[pageKey]?.split('|', limit = 3)?.takeIf {
                it.size == 3 &&
                    it[0] == "$from" &&
                    it[1] == "$to"
            }
        var cursor: String? = resumed?.get(2)
        // A resumed window had records on its earlier pages.
        var count = if (resumed != null) 1 else 0
        do {
            if (budget.spent) return null
            val request = budget::send
            val (page, next) =
                when (stream) {
                    BybitDecoder.LOG ->
                        Bybit.parseTransactionLog(request(Bybit.transactionLog(from, to, cursor))).let { page ->
                            page.items.map { ExchangeRow(stream, it.key, it.atMillis, it.raw, "") } to page.next
                        }
                    BybitDecoder.FUNDING ->
                        Bybit.parseFundingHistory(request(Bybit.fundingHistory(from, to, cursor))).let { page ->
                            page.items.map { ExchangeRow(stream, it.id, it.atMillis, it.raw, "") } to page.next
                        }
                    BybitDecoder.DEPOSITS ->
                        Bybit.parseDeposits(request(Bybit.deposits(from, to, cursor))).let { page ->
                            page.items.map { ExchangeRow(stream, it.id, it.atMillis, it.raw, "") } to page.next
                        }
                    else ->
                        Bybit.parseWithdrawals(request(Bybit.withdrawals(from, to, cursor))).let { page ->
                            page.items.map { ExchangeRow(stream, it.id, it.atMillis, it.raw, "") } to page.next
                        }
                }
            app.exchanges.saveRows(ExchangeKind.BYBIT, account, uid, page, BybitDecoder.VERSION)
            count += page.size
            cursor = next.takeIf { page.isNotEmpty() && it != cursor }
            cursor?.let { app.exchanges.saveCursor(account, pageKey, "$from|$to|$it") }
        } while (cursor != null)
        app.exchanges.saveCursor(account, pageKey, "")
        return count
    }

    /**
     * Convert history has no time filter: pages are read newest first. New converts are read until a page holds only
     * known ones; until the history has been read to its last page once, reading then continues from the page saved in
     * [CONVERTS_CURSOR], so an interrupted refresh resumes there. Returns false when older pages remain.
     */
    private fun readConverts(
        app: PortfolioApplication,
        account: AccountId,
        uid: String,
        budget: RequestBudget<BybitRequest>,
    ): Boolean {
        val known = app.exchanges.rowKeys(account, BybitDecoder.CONVERTS)
        val saved = app.exchanges.cursors(account)[CONVERTS_CURSOR]
        val readToEnd = saved == CONVERTS_DONE
        // New converts push older ones to later pages, so resuming at a saved page may read some again but skips none.
        var resumeAt = saved?.toIntOrNull() ?: 1
        var page = 1
        var reads = 0
        while (reads < MAX_CONVERT_PAGES && !budget.spent) {
            val converts = Bybit.parseConverts(budget.send(Bybit.converts(page)))
            reads++
            app.exchanges.saveRows(
                ExchangeKind.BYBIT,
                account,
                uid,
                converts.map {
                    ExchangeRow(BybitDecoder.CONVERTS, it.id, it.atMillis, it.raw, "")
                },
                BybitDecoder.VERSION,
            )
            if (converts.size < CONVERT_PAGE) {
                app.exchanges.saveCursor(account, CONVERTS_CURSOR, CONVERTS_DONE)
                return true
            }
            if (readToEnd && converts.all { it.id in known }) return true
            if (!readToEnd) {
                resumeAt = maxOf(resumeAt, page + 1)
                app.exchanges.saveCursor(account, CONVERTS_CURSOR, "$resumeAt")
            }
            page = if (!readToEnd && converts.all { it.id in known }) resumeAt else page + 1
        }
        return false
    }

    companion object {
        /** Requests per account and refresh; a first refresh of two years of history takes a few refreshes. */
        const val MAX_REQUESTS = 400
        const val MAX_RETENTION_MILLIS = 5 * 365 * Bybit.DAY_MILLIS
        const val MAX_CONVERT_PAGES = 50
        const val CONVERT_PAGE = 100

        /** Next convert page to read until the history has been read to its end, then [CONVERTS_DONE]. */
        const val CONVERTS_CURSOR = "converts-page"
        const val CONVERTS_DONE = "done"

        /** Window and next page of a stream's window the budget stopped, as `from|to|cursor`. */
        const val WINDOW_CURSOR = "window:"

        /** Coins whose contracts are looked up per refresh; the rest follow on later refreshes. */
        const val MAX_COIN_LOOKUPS = 50
        private val STREAM_LABELS =
            mapOf(
                BybitDecoder.LOG to "trading history",
                BybitDecoder.FUNDING to "funding history",
                BybitDecoder.DEPOSITS to "deposits",
                BybitDecoder.WITHDRAWALS to "withdrawals",
            )
    }
}

/** Bybit: Unified and Funding wallets with both balance logs; keys report their permissions and expiry. */
object BybitProvider : ExchangeProvider<BybitRequest> {
    override val kind = ExchangeKind.BYBIT
    override val manifest = BYBIT_MANIFEST
    override val history: ExchangeHistory = BybitDecoder

    override fun connector(transport: ExchangeTransport<BybitRequest>) = BybitConnector(transport::get)

    override fun checkKey(send: (BybitRequest) -> String): ExchangeKeyInfo =
        Bybit.parseKeyInfo(send(Bybit.keyInfo())).let { ExchangeKeyInfo(it.uid, it.readOnly, it.expiresAtMillis) }
}
