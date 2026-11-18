package io.valroot.core.exchange.imports

import io.valroot.core.model.ExactDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class EventMatcherTest {
    @Test fun differentChainsRejectSharedHashAndSavedDecisions() {
        val imported = EventShape("import", MatchKind.IN, 1000, mapOf("USDT" to ExactDecimal.parse("1")), setOf("txid:0xabc"), "eip155:1")
        val api = imported.copy(id = "api", chain = "eip155:56")
        val decisions = mapOf(EventMatcher.groupKey(listOf(imported)) to GroupDecision.SAME)
        assertEquals(MatchOutcome.New, EventMatcher.match(listOf(imported), listOf(api), { 1000 }, decisions)[imported.id])
        assertEquals(
            MatchOutcome.Same(api.id),
            EventMatcher.match(listOf(imported), listOf(api.copy(chain = imported.chain)), {
                1000
            })[imported.id],
        )
    }

    @Test fun differentChainsRejectTimeFallbackWithoutIdentifiers() {
        val imported = EventShape("import", MatchKind.IN, 1000, mapOf("USDT" to ExactDecimal.parse("1")), chain = "eip155:1")
        val api = imported.copy(id = "api", chain = "eip155:56")
        assertEquals(MatchOutcome.New, EventMatcher.match(listOf(imported), listOf(api), { 1000 })[imported.id])
    }

    private fun d(text: String) = ExactDecimal.parse(text)

    @Test fun numbersAndAmountsWithCoinsAreExact() {
        assertEquals(d("-0.5"), ImportValues.decimal(" -0.5 ", "Amount"))
        assertEquals(d("0.000015"), ImportValues.decimal("1.5e-05", "Amount"))
        assertEquals(d("5"), ImportValues.decimal("05.", "Amount"))
        assertEquals(d("0.25"), ImportValues.decimal(".25", "Amount"))
        assertEquals(d("1200"), ImportValues.decimal("1.2E3", "Amount"))
        assertEquals(null, ImportValues.optionalDecimal("--", "Fee"))
        assertFailsWith<ImportFileException> { ImportValues.decimal("1,616.86", "Amount") }
        assertFailsWith<ImportFileException> { ImportValues.decimal("", "Amount") }
        assertEquals(d("0.4995") to "BTC", ImportValues.amountAndCoin("0.4995BTC", listOf("BTC", "USDT"), "Executed"))
        // A coin starting with a digit is found among the pair's coins, not read as part of the number.
        assertEquals(d("10.5") to "1INCH", ImportValues.amountAndCoin("10.51INCH", listOf("1INCH", "USDT"), "Executed"))
        assertEquals(d("0.000015") to "BNB", ImportValues.amountAndCoin("1.5e-05BNB", emptyList(), "Fee"))
        assertFailsWith<ImportFileException> { ImportValues.amountAndCoin("BNB", emptyList(), "Fee") }
    }

    @Test fun keysDependOnContentAndKeepIdenticalRowsDistinct() {
        val first = ImportValues.keys("binance-fills", listOf("a", "b", "a"))
        val reordered = ImportValues.keys("binance-fills", listOf("b", "a", "a"))
        assertEquals(first.toSet(), reordered.toSet())
        assertEquals(3, first.toSet().size)
        assertEquals(first[0].substringBeforeLast(':'), first[2].substringBeforeLast(':'))
    }

    private fun trade(
        id: String,
        at: Long,
        qty: String = "1",
        ids: Set<String> = emptySet(),
    ) = EventShape(id, MatchKind.TRADE, at, mapOf("BTC" to d(qty), "USDT" to d("-" + qty + "0000")), ids)

    private val tolerance: (MatchKind) -> Long = { if (it == MatchKind.TRADE) 1_999L else 86_400_000L }

    @Test fun sharedIdentifiersDecideFirstAndEqualFillsPairInTimeOrder() {
        val existing = listOf(trade("api-1", 1_000), trade("api-2", 1_400), trade("api-3", 9_000, ids = setOf("trade:BTCUSDT:7")))
        val outcomes =
            EventMatcher.match(
                listOf(trade("f-1", 1_000), trade("f-2", 1_000), trade("f-3", 50_000, ids = setOf("trade:BTCUSDT:7"))),
                existing,
                tolerance,
            )
        assertEquals(MatchOutcome.Same("api-1"), outcomes["f-1"])
        assertEquals(MatchOutcome.Same("api-2"), outcomes["f-2"])
        assertEquals(MatchOutcome.Same("api-3"), outcomes["f-3"])
    }

    @Test fun moreRecordsThanEventsWaitForADecisionAndOthersProceed() {
        val existing = listOf(trade("api-1", 1_000))
        val records = listOf(trade("f-1", 1_000), trade("f-2", 1_000), trade("f-3", 5_000_000, qty = "2"))
        val outcomes = EventMatcher.match(records, existing, tolerance)
        val ambiguous = assertIs<MatchOutcome.Ambiguous>(outcomes["f-1"])
        assertEquals(2, ambiguous.records)
        assertEquals(1, ambiguous.candidates)
        assertEquals(ambiguous, outcomes["f-2"])
        assertEquals(MatchOutcome.New, outcomes["f-3"])

        val group = ambiguous.group
        val same = EventMatcher.match(records, existing, tolerance, mapOf(group to GroupDecision.SAME))
        assertEquals(listOf(MatchOutcome.Same("api-1"), MatchOutcome.New), listOf(same["f-1"], same["f-2"]))
        val distinct = EventMatcher.match(records, existing, tolerance, mapOf(group to GroupDecision.DISTINCT))
        assertEquals(listOf(MatchOutcome.New, MatchOutcome.New), listOf(distinct["f-1"], distinct["f-2"]))
        val excluded = EventMatcher.match(records, existing, tolerance, mapOf(group to GroupDecision.EXCLUDED))
        assertEquals(MatchOutcome.Excluded(group), excluded["f-1"])
    }

    @Test fun differentKindsAmountsAndDistantTimesNeverMatch() {
        val deposit = EventShape("api-d", MatchKind.IN, 1_000, mapOf("USDT" to d("100")), setOf("txid:0xabc"))
        val withdrawal = EventShape("f-w", MatchKind.OUT, 1_000, mapOf("USDT" to d("-100")), setOf("txid:0xabc"))
        val later = EventShape("f-d", MatchKind.IN, 1_000 + 2 * 86_400_000L, mapOf("USDT" to d("100")))
        val other = EventShape("f-d2", MatchKind.IN, 1_500, mapOf("USDT" to d("100.01")))
        val outcomes = EventMatcher.match(listOf(withdrawal, later, other), listOf(deposit), tolerance)
        assertEquals(
            listOf(MatchOutcome.New, MatchOutcome.New, MatchOutcome.New),
            listOf(outcomes["f-w"], outcomes["f-d"], outcomes["f-d2"]),
        )
    }

    @Test fun twoDepositsInOneTransactionStayTwoEvents() {
        fun deposit(
            id: String,
            eth: String,
        ) = EventShape(id, MatchKind.IN, 1_000, mapOf("ETH" to d(eth)), setOf("txid:0xabc"))
        // The account already has the 1 ETH deposit; the file also holds a 2 ETH deposit paid by the same transaction.
        val outcomes = EventMatcher.match(listOf(deposit("f-1", "1"), deposit("f-2", "2")), listOf(deposit("api-1", "1")), tolerance)
        assertEquals(MatchOutcome.Same("api-1"), outcomes["f-1"])
        assertEquals(MatchOutcome.New, outcomes["f-2"])
        // With both already recorded, each record finds its own, whichever is listed first.
        val both = EventMatcher.match(listOf(deposit("f-2", "2")), listOf(deposit("api-1", "1"), deposit("api-2", "2")), tolerance)
        assertEquals(MatchOutcome.Same("api-2"), both["f-2"])
    }

    @Test fun equalAmountsWithDifferentTransactionIdsAreDifferentEvents() {
        fun deposit(
            id: String,
            txid: String?,
            at: Long = 1_000,
        ) = EventShape(id, MatchKind.IN, at, mapOf("ETH" to d("1")), setOfNotNull(txid?.let { "txid:$it" }))
        val existing = listOf(deposit("api-1", "0xaaa"))
        assertEquals(MatchOutcome.New, EventMatcher.match(listOf(deposit("f-1", "0xbbb")), existing, tolerance)["f-1"])
        // Without a transaction ID on one side, equal amounts and times still pair.
        assertEquals(MatchOutcome.Same("api-1"), EventMatcher.match(listOf(deposit("f-2", null)), existing, tolerance)["f-2"])
        // Pairing in time order skips an event a record's ID contradicts: 0xbbb cannot take the 0xaaa deposit.
        val two = listOf(deposit("api-1", "0xaaa", 1_000), deposit("api-2", null, 2_000))
        val outcomes = EventMatcher.match(listOf(deposit("f-3", "0xbbb", 900), deposit("f-5", null, 950)), two, tolerance)
        assertEquals(MatchOutcome.Same("api-2"), outcomes["f-3"])
        assertEquals(MatchOutcome.Same("api-1"), outcomes["f-5"])
    }

    @Test fun aRecordWithoutAnIdLeavesTheOnlyCompatibleEventToTheRecordThatNeedsIt() {
        fun deposit(
            id: String,
            txid: String?,
            at: Long,
        ) = EventShape(id, MatchKind.IN, at, mapOf("ETH" to d("1")), setOfNotNull(txid?.let { "txid:$it" }))
        // f-2 (0xccc) contradicts api-2 (0xaaa), so it can only be api-1; f-1 without an ID can be either.
        val events = listOf(deposit("api-1", null, 1_000), deposit("api-2", "0xaaa", 2_000))
        val outcomes = EventMatcher.match(listOf(deposit("f-1", null, 900), deposit("f-2", "0xccc", 950)), events, tolerance)
        assertEquals(MatchOutcome.Same("api-2"), outcomes["f-1"])
        assertEquals(MatchOutcome.Same("api-1"), outcomes["f-2"])
    }

    @Test fun aTimeClusterCannotPairEventsOutsideEachRecordsWindow() {
        val records = listOf(trade("f-1", 0), trade("f-2", 6_000))
        val events = listOf(trade("api-1", 1_000), trade("bridge", 2_999), trade("api-2", 4_998))
        val outcomes = EventMatcher.match(records, events, tolerance)
        assertEquals(MatchOutcome.Same("api-1"), outcomes["f-1"])
        assertEquals(MatchOutcome.Same("api-2"), outcomes["f-2"])
        assertEquals(outcomes, EventMatcher.match(records.reversed(), events.reversed(), tolerance))
    }

    @Test fun recordsWithoutEligibleCandidatesAreNewEvenInsideABusyCluster() {
        val records = listOf(trade("isolated", 0), trade("near", 1_999))
        val events = listOf(trade("event", 3_998))
        val outcomes = EventMatcher.match(records, events, tolerance)
        assertEquals(MatchOutcome.New, outcomes["isolated"])
        assertEquals(MatchOutcome.Same("event"), outcomes["near"])
    }

    @Test fun countsAloneCannotProveACompleteMatching() {
        val records = listOf(trade("f-1", 0), trade("f-2", 0), trade("f-3", 10))
        val events = listOf(trade("e-1", 0), trade("e-2", 20), trade("e-3", 20))
        val outcomes = EventMatcher.match(records, events, { 10L })
        val ambiguous = assertIs<MatchOutcome.Ambiguous>(outcomes["f-1"])
        assertEquals(ambiguous, outcomes["f-2"])
        assertEquals(ambiguous, outcomes["f-3"])
        val same = EventMatcher.match(records, events, { 10L }, mapOf(ambiguous.group to GroupDecision.SAME))
        assertEquals(2, same.values.count { it is MatchOutcome.Same })
        assertEquals(1, same.values.count { it == MatchOutcome.New })
    }

    @Test fun identifierIncompatibleSubgroupsDoNotShareAmbiguity() {
        val records = listOf(trade("a-1", 0), trade("a-2", 0), trade("b", 6_000))
        val events = listOf(trade("a", 1_000), trade("bridge", 2_999), trade("b-event", 4_998))
        val outcomes = EventMatcher.match(records, events, tolerance)
        val ambiguous = assertIs<MatchOutcome.Ambiguous>(outcomes["a-1"])
        assertEquals(2, ambiguous.records)
        assertEquals(1, ambiguous.candidates)
        assertEquals(MatchOutcome.Same("b-event"), outcomes["b"])
        val legacyKey = EventMatcher.groupKey(records)
        val excluded = EventMatcher.match(records, events, tolerance, mapOf(legacyKey to GroupDecision.EXCLUDED))
        assertEquals(MatchOutcome.Excluded(legacyKey), excluded["a-1"])
        assertEquals(MatchOutcome.Excluded(legacyKey), excluded["b"])
    }

    @Test fun aSharedIdentifierDoesNotOverrideAnotherContradictingIdentifier() {
        val record = trade("file", 0, ids = setOf("trade:shared", "txid:first"))
        val event = trade("api", 0, ids = setOf("trade:shared", "txid:other"))
        assertEquals(MatchOutcome.New, EventMatcher.match(listOf(record), listOf(event), tolerance)["file"])
    }

    @Test fun aLargeAmbiguousComponentMatchesDeterministicallyWithoutRecursion() {
        val records = (0 until 1_000).map { trade("f-$it", 0) }
        val events = (0 until 999).map { trade("api-$it", 0) }
        val key = EventMatcher.groupKey(records)
        val decisions = mapOf(key to GroupDecision.SAME)
        val result = EventMatcher.match(records, events, tolerance, decisions)
        assertEquals(999, result.values.count { it is MatchOutcome.Same })
        assertEquals(1, result.values.count { it == MatchOutcome.New })
        assertEquals(result, EventMatcher.match(records.reversed(), events.reversed(), tolerance, decisions))
    }
}
