package io.valroot.core.evm

import io.valroot.core.application.ActivityFilter
import io.valroot.core.application.BitcoinConnector
import io.valroot.core.application.DEFAULT_ENDPOINT
import io.valroot.core.application.SourceRefreshOptions
import io.valroot.core.application.SourceRegistry
import io.valroot.core.bitcoin.EsploraTransport
import io.valroot.core.ledger.ActivityKind
import io.valroot.core.ledger.MovementRole
import io.valroot.core.model.ExactInteger
import io.valroot.core.storage.BundledSqliteDatabase
import io.valroot.core.sync.JobState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EvmConnectorTest : EvmConnectorTestFixture() {
    @Test fun historyFeesGapsAndSkipping() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = FakeAlchemy()
            val connector = EvmConnector(provider.post)
            // Discovery finds Base among the networks.
            assertEquals(
                listOf(JobState.COMPLETED),
                connector.refresh(app, SourceRefreshOptions()).jobs,
                app.evm.chainStates(me).toString(),
            )
            assertEquals(listOf(chain), app.evm.chainStates(me).map { it.chainId })

            val activities =
                app.transactions.entries(ActivityFilter()).associateBy {
                    it.activity.id.value
                        .substringAfter("$chain:")
                        .substringBefore(':')
                }
            assertEquals(ActivityKind.DEPOSIT, activities.getValue(h(1)).activity.kind)
            // Sent 1 ETH: gas 500 plus the 100 wei L1 data fee.
            val sent = activities.getValue(h(4)).activity.movements
            assertEquals(listOf("-1000000000000000000", "-600"), sent.map { it.amount.atomicUnits.toString() })
            assertEquals(MovementRole.FEE, sent.last().role)
            // A swap listed only through its token transfer still gets its fee from the receipt.
            val swap = activities.getValue(h(7)).activity.movements
            assertEquals(setOf("-2500000", "-50"), swap.map { it.amount.atomicUnits.toString() }.toSet())
            // The poisoning transfer was paid for by the scammer: no fee for this address.
            assertTrue(
                activities
                    .getValue(h(8))
                    .activity.movements
                    .none { it.role == MovementRole.FEE },
            )
            assertEquals(
                "250",
                activities
                    .getValue(h(6))
                    .activity.movements
                    .single()
                    .amount.atomicUnits
                    .toString(),
            )
            assertEquals(
                EvmAssets.nft(chain, punk, evmQuantity(huge)),
                activities
                    .getValue(h(9))
                    .activity.movements
                    .single()
                    .amount.assetId,
            )

            // A failed call Alchemy did not list was found by its nonce: its fee counts, with the search as its source.
            val found = activities.getValue(h(20)).activity
            assertEquals(listOf("-300" to MovementRole.FEE), found.movements.map { it.amount.atomicUnits.toString() to it.role })
            // 3 ETH − 1 ETH − 600 − 50 + 250 − 300 matches Alchemy, with nothing missing.
            val state = app.evm.chainStates(me).single()
            assertNull(state.balanceDifference)
            assertNull(state.coverage)
            assertEquals(
                mapOf(2L to h(20)),
                app.evm
                    .recovered()
                    .nonces(me, chain)
                    .mapValues { it.value?.hash },
            )
            assertEquals(setOf(EvmAssets.token(chain, fakeUsdc), EvmAssets.nft(chain, punk, evmQuantity(huge))), app.assets.hidden())

            // Unchanged: newest transfer, newest contract transfer and the sent count only.
            provider.requests.clear()
            connector.refresh(app, SourceRefreshOptions())
            assertEquals(4, provider.requests.size)

            // A nonce no transaction in its block uses (a delegation authorization, say) is recorded as not found once, and
            // the unexplained difference becomes an adjustment after the last activity, so the balance matches.
            provider.sentCount = 4
            provider.nativeBalance = "1999999999999999000"
            connector.refresh(app, SourceRefreshOptions())
            val after = app.evm.chainStates(me).single()
            assertNull(after.balanceDifference)
            assertEquals("1 sent transaction could not be found on the chain; their fees are not counted", after.coverage)
            assertEquals(null, app.evm.recovered().nonces(me, chain)[3L])
            provider.requests.clear()
            connector.refresh(app, SourceRefreshOptions())
            assertTrue(provider.requests.none { it.contains("eth_getBlockByNumber") })
            val adjustment =
                app
                    .snapshot()
                    .activities
                    .map { it.activity }
                    .single { it.id.value.startsWith("evm-adjust:") }
            assertEquals(
                "-300",
                adjustment.movements
                    .single()
                    .amount.atomicUnits
                    .toString(),
            )
        }
    }

    @Test fun contractPaymentsAreRecoveredFromOneInchTracesWhereAlchemyDoesNotListThem() {
        BundledSqliteDatabase(":memory:").use { db ->
            slug = "bnb-mainnet"
            val app = setUp(db)
            val provider = FakeAlchemy()
            // Only the deposit, the swap's token side and the address-poisoning transfer are listed; Alchemy lists no
            // contract payments on BNB Smart Chain.
            provider.receipts.remove(h(20))
            provider.sentByNonce.removeAt(2)
            provider.sentCount = 2
            // 3 − 1 − 600 − 50 + 250 (swap proceeds) + 1000 (paid in someone else's transaction).
            provider.nativeBalance = "2000000000000000600"
            val payer = "0x4444444444444444444444444444444444444444"
            val traces =
                mapOf(
                    h(7) to
                        """{"transactionTrace":{"txHash":"${h(7)}","type":"CALL","from":"$me","to":"$router","value":"0x0",""" +
                        """"status":"RETURNED","calls":[""" +
                        """{"type":"CALL","from":"$router","to":"$me","value":"0xfa","status":"NO_CONTRACT","success":1},""" +
                        // A delegate call carries the caller's value without moving it.
                        """{"type":"DELEGATECALL","from":"$router","to":"$me","value":"0x3e7","status":"RETURNED","success":1},""" +
                        // A payment inside a reverted call never happened.
                        """{"type":"CALL","from":"$router","to":"$router","value":"0x0","status":"REVERTED","success":0,"calls":[""" +
                        """{"type":"CALL","from":"$router","to":"$me","value":"0x7","status":"NO_CONTRACT","success":1}]}]}}""",
                    h(30) to
                        """{"transactionTrace":{"txHash":"${h(30)}","type":"CALL","from":"$other","to":"$payer","value":"0x0",""" +
                        """"status":"RETURNED","calls":[{"type":"CALL","from":"$payer","to":"$me","value":"0x3e8","status":"NO_CONTRACT","success":1}]}}""",
                )
            val oneInchRequests = mutableListOf<String>()
            val oneInch = { path: String ->
                oneInchRequests += path
                when {
                    path.startsWith("/history/") ->
                        oneInchHistory(
                            oneInchEvent(30, other, action("Native", payer, me, "1000")),
                            oneInchEvent(7, me, action("ERC20", me, router, "2500000", usdc), action("Native", router, me, "250")),
                            // A plain transfer from the sender is listed by Alchemy and needs no trace.
                            oneInchEvent(1, other, action("Native", other, me, "3000000000000000000")),
                        )
                    else -> traces.getValue(path.substringAfter("/tx-hash/"))
                }
            }
            val connector = EvmConnector(provider.post, oneInch)
            app.evm.addChains(me, setOf(56L))
            app.evm.recordDiscovery(me)
            assertEquals(listOf(JobState.COMPLETED), connector.refresh(app, SourceRefreshOptions()).jobs)
            val activities =
                app.transactions.entries(ActivityFilter()).associate {
                    it.activity.id.value
                        .substringAfter("56:")
                        .substringBefore(':') to it.activity
                }
            // The swap's proceeds join the swap itself.
            val swap = activities.getValue(h(7))
            assertEquals(ActivityKind.TRADE, swap.kind)
            assertTrue(swap.movements.any { it.amount.assetId == EvmAssets.native(56) && it.amount.atomicUnits.toString() == "250" })
            // A payment in a transaction Alchemy never listed is its own deposit.
            assertEquals(ActivityKind.DEPOSIT, activities.getValue(h(30)).kind)
            // Balances match with no adjustment, and the chain reports no gap.
            val state = app.evm.chainStates(me).single()
            assertNull(state.balanceDifference)
            assertNull(state.coverage)
            assertTrue(
                app.snapshot().activities.none {
                    it.activity.id.value
                        .startsWith("evm-adjust:")
                },
            )
            assertEquals(listOf("/history/", "/traces/", "/traces/"), oneInchRequests.map { it.substring(0, it.indexOf('/', 1) + 1) })

            // Unchanged and checked recently: 1inch is not asked again, and nothing is traced twice.
            oneInchRequests.clear()
            connector.refresh(app, SourceRefreshOptions())
            assertTrue(oneInchRequests.isEmpty())

            // A payout cached before a failed full history read is applied on the next refresh.
            app.evm.recovered().saveListing(me, 56, OneInchListing(0, emptySet()))
            var retryRequests = 0
            val retryConnector =
                EvmConnector(provider.post) { path ->
                    retryRequests++
                    if (path.startsWith("/history/")) {
                        oneInchHistory(oneInchEvent(31, other, action("Native", payer, me, "1000")))
                    } else {
                        traces.getValue(h(30)).replace(h(30), h(31))
                    }
                }
            provider.failFullHistory = true
            assertEquals(listOf(JobState.FAILED), retryConnector.refresh(app, SourceRefreshOptions()).jobs)
            assertNull(
                app.evm
                    .chainStates(me)
                    .single()
                    .head,
            )
            assertTrue(h(31) in app.evm.recovered().traces(me, 56))
            provider.failFullHistory = false
            retryRequests = 0
            assertEquals(listOf(JobState.COMPLETED), retryConnector.refresh(app, SourceRefreshOptions()).jobs)
            assertEquals(0, retryRequests)
            assertTrue(
                app.transactions.entries(ActivityFilter()).any {
                    it.activity.id.value
                        .contains(h(31))
                },
            )

            app.evm.recovered().saveListing(me, 56, OneInchListing(0, emptySet()))
            val traceRequests = mutableListOf<String>()
            var failTrace = true
            val interrupted =
                EvmConnector(provider.post) { path ->
                    if (path.startsWith("/history/")) {
                        oneInchHistory(
                            oneInchEvent(32, other, action("Native", payer, me, "1000")),
                            oneInchEvent(33, other, action("Native", payer, me, "1000")),
                        )
                    } else {
                        val hash = path.substringAfter("/tx-hash/")
                        traceRequests += hash
                        check(!failTrace || hash != h(33)) { "Trace unavailable" }
                        traces.getValue(h(30)).replace(h(30), hash)
                    }
                }
            assertEquals(listOf(JobState.FAILED), interrupted.refresh(app, SourceRefreshOptions()).jobs)
            assertNull(
                app.evm
                    .chainStates(me)
                    .single()
                    .head,
            )
            assertTrue(h(32) in app.evm.recovered().traces(me, 56))
            failTrace = false
            assertEquals(listOf(JobState.COMPLETED), interrupted.refresh(app, SourceRefreshOptions()).jobs)
            assertEquals(listOf(h(32), h(33), h(33)), traceRequests)
            assertTrue(
                app.transactions.entries(ActivityFilter()).any {
                    it.activity.id.value
                        .contains(h(32))
                },
            )

            // Without a key the chain says what is missing.
            EvmConnector(provider.post).refresh(app, SourceRefreshOptions(recoverHistory = true))
            assertEquals(
                "Payments made by contracts are not listed on this chain; a 1inch key recovers them",
                app.evm
                    .chainStates(me)
                    .single { it.chainId == 56L }
                    .coverage,
            )
            slug = "base-mainnet"
        }
    }

    @Test fun tokensOneInchLeavesOutStartHidden() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = FakeAlchemy()
            var listed = emptyList<String>()
            val oneInch = { path: String ->
                require(path.startsWith("/history/"))
                oneInchHistory(*listed.map { oneInchEvent(5, other, action("ERC20", other, me, "1", it)) }.toTypedArray())
            }
            app.evm.addChains(me, setOf(chain))
            app.evm.recordDiscovery(me)
            EvmConnector(provider.post, oneInch).refresh(app, SourceRefreshOptions())
            val usdcAsset = EvmAssets.token(chain, usdc)
            // USD Coin has a CoinGecko coin, but 1inch's history of the address leaves it out.
            assertTrue(usdcAsset in app.assets.hidden())
            // The user's choice wins.
            app.assets.setShown(usdcAsset, true)
            assertTrue(usdcAsset !in app.assets.hidden())
            app.evm.recovered().saveListing(me, chain, OneInchListing(1_800_000_000_000L, setOf(usdc)))
            BundledSqliteDatabase(":memory:").use { other ->
                val fresh = setUp(other)
                fresh.evm.addChains(me, setOf(chain))
                listed = listOf(usdc)
                EvmConnector(FakeAlchemy().post, oneInch).refresh(fresh, SourceRefreshOptions())
                assertTrue(usdcAsset !in fresh.assets.hidden())
            }
        }
    }

    @Test fun aFailedOrPartialNetworkCheckStillRefreshesAndIsRepeated() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = FakeAlchemy()
            val connector = EvmConnector(provider.post)
            // The check fails outright: the refresh completes with a note and checks again next time.
            provider.tokensAnswer = """{"error":{"message":"busy"}}"""
            val failed = connector.refresh(app, SourceRefreshOptions())
            assertTrue(failed.notes.any { "could not be checked" in it }, failed.notes.toString())
            assertTrue(app.evm.discoveryDue(me))
            // Some networks are not checked: what was found is added, the address keeps refreshing, and the check repeats.
            provider.tokensAnswer = """{"data":{"tokens":[]},"error":{"partialErrors":[{"network":"eth-mainnet","state":"timeout"}]}}"""
            val partial = connector.refresh(app, SourceRefreshOptions())
            assertEquals(listOf(JobState.COMPLETED), partial.jobs)
            assertEquals(listOf(chain), app.evm.chainStates(me).map { it.chainId })
            assertTrue(partial.notes.any { "Some networks could not be checked" in it }, partial.notes.toString())
            assertTrue(app.evm.discoveryDue(me))
            provider.tokensAnswer = null
            connector.refresh(app, SourceRefreshOptions())
            kotlin.test.assertFalse(app.evm.discoveryDue(me))
        }
    }

    @Test fun aFailingNetworkGroupKeepsTheChainsOtherGroupsFound() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = FakeAlchemy().apply { failOtherGroups = true }
            val outcome = EvmConnector(provider.post).refresh(app, SourceRefreshOptions())
            assertEquals(listOf(chain), app.evm.chainStates(me).map { it.chainId })
            assertTrue(outcome.notes.any { "Some networks could not be checked" in it }, outcome.notes.toString())
            assertTrue(app.evm.discoveryDue(me))
        }
    }

    @Test fun theRegistryRoutesEvmAddressesToTheEvmConnector() {
        val evm = EvmConnector({ _, _ -> error("not called") })
        val registry = SourceRegistry(listOf(BitcoinConnector(EsploraTransport { error("not called") }, DEFAULT_ENDPOINT), evm))
        assertSame(evm, registry.forNetwork(EVM_FAMILY))
    }

    @Test fun aFailedBalanceCheckKeepsTheHeadSoItIsRepeated() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = FakeAlchemy()
            val connector = EvmConnector(provider.post)
            provider.failBalances = true
            assertEquals(listOf(JobState.FAILED), connector.refresh(app, SourceRefreshOptions()).jobs)
            assertNull(
                app.evm
                    .chainStates(me)
                    .single()
                    .head,
                "history is saved but the chain is not settled",
            )
            provider.failBalances = false
            assertEquals(listOf(JobState.COMPLETED), connector.refresh(app, SourceRefreshOptions()).jobs)
            val state = app.evm.chainStates(me).single()
            assertNull(state.error)
            assertTrue(state.head != null)
        }
    }

    @Test fun incomingTransactionsAreOrderedByTheirBlockPosition() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = FakeAlchemy()
            EvmConnector(provider.post).refresh(app, SourceRefreshOptions())

            fun key(n: Int) =
                app.transactions
                    .entries(ActivityFilter())
                    .single {
                        it.activity.id.value
                            .contains(h(n))
                    }.activity.orderingKey
            // Someone else sent h(5) (a token) and h(1) (plain coin): positions come from their receipts, not log indexes.
            assertTrue(key(5).endsWith(":0000000001:${h(5)}"), key(5))
            assertTrue(key(1).endsWith(":0000000001:${h(1)}"), key(1))
            // Blocks 101 to 109 are within 256 of the head (200): their positions are not saved, since they may still move.
            assertTrue(
                app.evm
                    .recovered()
                    .positions(me, chain)
                    .isEmpty(),
            )
            provider.latestBlock = 1_000
            EvmConnector(provider.post).refresh(app, SourceRefreshOptions(recoverHistory = true))
            assertEquals(105L to 1, app.evm.recovered().positions(me, chain)[h(5)])
            assertEquals(101L to 1, app.evm.recovered().positions(me, chain)[h(1)])
        }
    }

    @Test fun aForcedRecoveryForgetsRecoveredTransactionsNoLongerInTheirBlock() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = FakeAlchemy()
            val connector = EvmConnector(provider.post)
            connector.refresh(app, SourceRefreshOptions())
            // A saved result whose transaction is now in another block (its receipt says block 177, not 50).
            app.evm.recovered().saveNonce(
                me,
                chain,
                9,
                EvmTransaction(h(77), 50, 0, 1_768_003_250_000, me, other, ExactInteger.ZERO, ExactInteger.ZERO, true),
            )
            connector.refresh(app, SourceRefreshOptions(recoverHistory = true))
            assertEquals(
                setOf(2L),
                app.evm
                    .recovered()
                    .nonces(me, chain)
                    .keys,
            )
        }
    }

    @Test fun anOlderDecoderVersionIsReadAgainWithTheSameRawRecords() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = FakeAlchemy()
            val connector = EvmConnector(provider.post)
            connector.refresh(app, SourceRefreshOptions())
            // History decoded before an upgrade: its raw records are byte for byte what this version writes.
            db.execute("UPDATE ledger_activities SET decoder='evm-1' WHERE id LIKE 'eip155:%'")
            db.execute("UPDATE raw_revisions SET payload_version='evm-2' WHERE record_id LIKE 'eip155:%'")
            assertTrue(app.evm.decodedByEarlierVersion(me, chain))
            assertEquals(listOf(JobState.COMPLETED), connector.refresh(app, SourceRefreshOptions()).jobs)
            assertTrue(!app.evm.decodedByEarlierVersion(me, chain))
            assertEquals(
                listOf("evm-2"),
                db.query("SELECT DISTINCT payload_version FROM raw_revisions WHERE record_id LIKE 'eip155:%'").map { it.single() },
            )
        }
    }

    @Test fun aSupersededActivityOfAnOlderDecoderDoesNotForceFullReads() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = FakeAlchemy()
            val connector = EvmConnector(provider.post)
            connector.refresh(app, SourceRefreshOptions())
            // An activity decoded by the earlier version and later superseded stays behind in the ledger.
            db.execute("UPDATE ledger_activities SET decoder='evm-1', status='SUPERSEDED' WHERE id=?", listOf("eip155:$chain:${h(5)}:$me"))
            assertTrue(!app.evm.decodedByEarlierVersion(me, chain))
            provider.requests.clear()
            connector.refresh(app, SourceRefreshOptions())
            assertEquals(4, provider.requests.size, "only the unchanged-head check")
        }
    }

    @Test fun aFailedBalanceCheckAfterAnAcceptedHeadIsNotSkippedLater() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = FakeAlchemy()
            val connector = EvmConnector(provider.post)
            connector.refresh(app, SourceRefreshOptions())
            assertTrue(
                app.evm
                    .chainStates(me)
                    .single()
                    .head != null,
            )
            // A full read of the same head (here a forced one) whose balance check fails leaves no accepted head.
            provider.failBalances = true
            assertEquals(listOf(JobState.FAILED), connector.refresh(app, SourceRefreshOptions(recoverHistory = true)).jobs)
            assertNull(
                app.evm
                    .chainStates(me)
                    .single()
                    .head,
            )
            provider.failBalances = false
            provider.requests.clear()
            assertEquals(listOf(JobState.COMPLETED), connector.refresh(app, SourceRefreshOptions()).jobs)
            assertTrue(provider.requests.size > 4, "the chain is read and checked again, not skipped")
            assertNull(
                app.evm
                    .chainStates(me)
                    .single()
                    .error,
            )
        }
    }

    @Test fun aForcedRecoveryNeverPublishesAnOrphanedTrace() {
        BundledSqliteDatabase(":memory:").use { db ->
            slug = "bnb-mainnet"
            val app = setUp(db)
            val provider = FakeAlchemy()
            app.evm.addChains(me, setOf(56L))
            app.evm.recordDiscovery(me)
            // A contract payment traced in block 50; the transaction is now in block 188, so the trace is stale.
            app.evm.recovered().saveTrace(
                me,
                56,
                h(88),
                listOf(EvmInternalTransfer(h(88), 50, 0, 1_768_003_250_000, other, me, ExactInteger.parse("77"), true)),
            )
            EvmConnector(provider.post).refresh(app, SourceRefreshOptions(recoverHistory = true))
            assertTrue(
                app.transactions.entries(ActivityFilter()).none {
                    it.activity.id.value
                        .contains(h(88))
                },
            )
            assertTrue(h(88) !in app.evm.recovered().traces(me, 56))
            slug = "base-mainnet"
        }
    }

    @Test fun sameHeightReceiptChangesTriggerAFullRead() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = FakeAlchemy()
            val connector = EvmConnector(provider.post)
            connector.refresh(app, SourceRefreshOptions())
            val before =
                app.evm
                    .chainStates(me)
                    .single()
                    .head
            provider.blockHash = h(901)
            provider.receiptPosition = 3
            provider.requests.clear()
            assertEquals(listOf(JobState.COMPLETED), connector.refresh(app, SourceRefreshOptions()).jobs)
            assertTrue(
                app.evm
                    .chainStates(me)
                    .single()
                    .head != before,
            )
            assertTrue(provider.requests.size > 4)
            assertTrue(
                app.transactions
                    .entries(ActivityFilter())
                    .single {
                        it.activity.id.value
                            .contains(h(9))
                    }.activity.orderingKey
                    .endsWith(":0000000003:${h(9)}"),
            )
        }
    }

    @Test fun partialInternalHeadProbeCannotSettleTheChain() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = FakeAlchemy()
            val connector = EvmConnector(provider.post)
            connector.refresh(app, SourceRefreshOptions())
            val before =
                app.evm
                    .chainStates(me)
                    .single()
                    .head
            provider.internalProbeError = true
            assertEquals(listOf(JobState.FAILED), connector.refresh(app, SourceRefreshOptions()).jobs)
            assertEquals(
                before,
                app.evm
                    .chainStates(me)
                    .single()
                    .head,
            )
            assertEquals(listOf(JobState.FAILED), connector.refresh(app, SourceRefreshOptions(recoverHistory = true)).jobs)
            assertNull(
                app.evm
                    .chainStates(me)
                    .single()
                    .head,
            )
        }
    }

    @Test fun legacyRawVersionsIncludeNativeAdjustmentsOnRetry() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = FakeAlchemy().apply { nativeBalance = "123" }
            val connector = EvmConnector(provider.post)
            assertEquals(listOf(JobState.COMPLETED), connector.refresh(app, SourceRefreshOptions()).jobs)
            assertTrue(db.query("SELECT 1 FROM raw_revisions WHERE record_id LIKE 'evm-adjust:%'").isNotEmpty())
            db.execute("UPDATE raw_revisions SET payload_version='evm-2'")
            assertEquals(listOf(JobState.COMPLETED), connector.refresh(app, SourceRefreshOptions(recoverHistory = true)).jobs)
            assertEquals(listOf("evm-2"), db.query("SELECT DISTINCT payload_version FROM raw_revisions").map { it.single() })
        }
    }

    @Test fun unchangedHeadBalanceFailureForRebasingTokensClearsAcceptedHead() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = FakeAlchemy()
            val connector = EvmConnector(provider.post)
            connector.refresh(app, SourceRefreshOptions())
            // A recorded difference schedules another balance check even while the transfer head stays unchanged.
            app.evm.checkBalances(
                app.evm
                    .targets()
                    .single()
                    .first,
                me,
                chain,
                ExactInteger.ZERO,
                emptyList(),
            )
            provider.failBalances = true
            assertEquals(listOf(JobState.FAILED), connector.refresh(app, SourceRefreshOptions()).jobs)
            assertNull(
                app.evm
                    .chainStates(me)
                    .single()
                    .head,
            )
            provider.failBalances = false
            provider.requests.clear()
            assertEquals(listOf(JobState.COMPLETED), connector.refresh(app, SourceRefreshOptions()).jobs)
            assertTrue(provider.requests.size > 4)
        }
    }

    @Test fun forcedRecoveryKeepsDeepPositiveResultsAndDropsRecentOrNegativeCaches() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = FakeAlchemy().apply { latestBlock = 1_000 }
            val recovered = app.evm.recovered()
            val deep =
                EvmTransaction(
                    h(20),
                    16,
                    1,
                    1_768_003_250_000,
                    me,
                    router,
                    ExactInteger.ZERO,
                    ExactInteger.parse("300"),
                    false,
                    NONCE_SOURCE,
                )
            recovered.saveNonce(me, chain, 2, deep)
            recovered.saveNonce(me, chain, 99, null)
            recovered.saveTrace(me, chain, h(90), emptyList())
            recovered.saveTrace(
                me,
                chain,
                h(1_000),
                listOf(
                    EvmInternalTransfer(h(1_000), 1_100, 0, 1_768_003_250_000, other, me, ExactInteger.parse("77"), true),
                ),
            )
            recovered.savePosition(me, chain, h(5), 105, 7)
            assertEquals(
                listOf(JobState.COMPLETED),
                EvmConnector(provider.post).refresh(app, SourceRefreshOptions(recoverHistory = true)).jobs,
            )
            assertEquals(deep, recovered.nonces(me, chain)[2])
            assertTrue(99L !in recovered.nonces(me, chain))
            assertTrue(recovered.traces(me, chain).isEmpty())
            assertEquals(105L to 1, recovered.positions(me, chain)[h(5)])
        }
    }

    @Test fun partialRevalidationBatchLeavesCachesIntactAndHeadUnsettled() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = setUp(db)
            val provider = FakeAlchemy()
            EvmConnector(provider.post).refresh(app, SourceRefreshOptions())
            val recovered = app.evm.recovered()
            recovered.saveNonce(me, chain, 77, null)
            recovered.saveTrace(me, chain, h(88), emptyList())
            val connector =
                EvmConnector({ url, body ->
                    if (body.contains(h(88))) "[]" else provider.post(url, body)
                })
            assertEquals(listOf(JobState.FAILED), connector.refresh(app, SourceRefreshOptions(recoverHistory = true)).jobs)
            assertNull(
                app.evm
                    .chainStates(me)
                    .single()
                    .head,
            )
            assertTrue(77L in recovered.nonces(me, chain))
            assertTrue(h(88) in recovered.traces(me, chain))
        }
    }
}
