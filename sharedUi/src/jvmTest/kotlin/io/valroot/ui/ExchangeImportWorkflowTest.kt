package io.valroot.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.exchange.ExchangeKind
import io.valroot.core.exchange.imports.ExportCsv
import io.valroot.core.exchange.imports.ImportBudget
import io.valroot.core.exchange.imports.ImportStatus
import io.valroot.core.exchange.imports.ImportTable
import io.valroot.core.model.AccountId
import io.valroot.core.model.OwnerId
import io.valroot.core.model.TrackingTargetId
import io.valroot.core.ownership.Account
import io.valroot.core.ownership.AccountKind
import io.valroot.core.ownership.Owner
import io.valroot.core.ownership.TargetIdentity
import io.valroot.core.ownership.TargetRole
import io.valroot.core.ownership.TrackingTarget
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ExchangeImportWorkflowTest {
    @Test
    fun selectingDepositAssigningAccountCommittingAndUndoingPreservesReceipt() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { delegate ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.snapshot()
                val owner = OwnerId("synthetic-owner")
                val account = AccountId("synthetic-binance")
                app.accounts.saveOwner(Owner(owner, "Test owner"))
                app.accounts.saveAccountAndTarget(
                    Account(account, owner, "Synthetic exchange", AccountKind.ACCOUNT),
                    TrackingTarget(
                        TrackingTargetId("synthetic-target"),
                        account,
                        TargetIdentity(ExchangeKind.BINANCE.network, "uid", "123456"),
                        TargetRole.LEAF,
                        "Synthetic exchange",
                    ),
                )
                val snapshot = app.snapshot()
                val table =
                    ImportTable(
                        "synthetic-deposit(UTC).csv",
                        "synthetic-hash",
                        null,
                        null,
                        ExportCsv.read(
                            "Time,Coin,Network,Amount,Address,TXID,Status\n" +
                                "2026-01-01 12:00:00,BTC,BTC,0.25,test-address,test-tx,Completed\n",
                            ImportBudget(),
                        ),
                    )
                val host =
                    object : PlatformActions by delegate {
                        override fun openExchangeExports(onResult: (List<ImportTable>?, String?) -> Unit) {
                            onResult(listOf(table), null)
                        }
                    }
                var changed = 0
                runDesktopComposeUiTest(width = 900, height = 1200) {
                    setContent {
                        ValrootTheme(AppearanceTheme.LIGHT) {
                            ExchangeImportDialog(app, host, snapshot, {}, { changed++ })
                        }
                    }
                    onNodeWithTag("exchange-import-files").performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        runCatching { onNodeWithTag("exchange-import-commit").assertIsNotEnabled() }.isSuccess
                    }
                    onNodeWithText("Synthetic exchange · 123456").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        runCatching { onNodeWithTag("exchange-import-commit").assertIsEnabled() }.isSuccess
                    }
                    onNodeWithText("Assigned to Synthetic exchange").assertExists()
                    onNodeWithTag("exchange-import-commit").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        runCatching { onNodeWithText("Undo import").assertExists() }.isSuccess
                    }
                    onNodeWithTag("exchange-import-commit").assertDoesNotExist()
                    onNodeWithText("View source rows").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        runCatching { onNodeWithTag("exchange-source-row-0").assertExists() }.isSuccess
                    }
                    onNodeWithText("Original rows: 2").assertExists()
                    onNodeWithText("Report: binance.deposits").assertExists()
                    onNodeWithText("Account: Synthetic exchange").assertExists()
                    onNodeWithText("Outcome: new").assertExists()
                    onNodeWithText("Hide source rows").performScrollTo().performClick()
                    onNodeWithText("Undo import").performScrollTo().performClick()
                    onNodeWithText("Confirm undo").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        runCatching { onNodeWithText("Undone").assertExists() }.isSuccess
                    }
                }
                assertEquals(2, changed)
                val receipt = app.exchangeImports.receipts().single()
                assertNotNull(receipt.undoneAtMillis)
                assertEquals(listOf(account), receipt.accounts)
                assertEquals("synthetic-deposit(UTC).csv", receipt.files.single().name)
                assertEquals(1, receipt.counts[ImportStatus.NEW])
                assertTrue(app.snapshot().activities.none { it.activity.status == io.valroot.core.ledger.ActivityStatus.CONFIRMED })
                assertEquals(
                    "Synthetic exchange",
                    app
                        .snapshot()
                        .ownership.accounts
                        .single()
                        .label,
                )
            }
        }
    }

    @Test
    fun unsupportedFilesCannotCommitAndRestoringDropsPendingSelection() {
        JvmSqliteDatabase(":memory:").use { firstDb ->
            JvmSqliteDatabase(":memory:").use { restoredDb ->
                ModelWorkspaceRestorationTest.RestorationHost().use { delegate ->
                    val first = PortfolioApplication(firstDb) { 1_800_000_000_000L }
                    val restored = PortfolioApplication(restoredDb) { 1_800_000_000_000L }
                    val firstSnapshot = first.snapshot()
                    val restoredSnapshot = restored.snapshot()
                    val current = mutableStateOf(first)
                    var answer: ((List<ImportTable>?, String?) -> Unit)? = null
                    val host =
                        object : PlatformActions by delegate {
                            override fun openExchangeExports(onResult: (List<ImportTable>?, String?) -> Unit) {
                                answer = onResult
                            }
                        }
                    runDesktopComposeUiTest(width = 900, height = 1200) {
                        setContent {
                            ValrootTheme(AppearanceTheme.LIGHT) {
                                ExchangeImportDialog(
                                    current.value,
                                    host,
                                    if (current.value ===
                                        first
                                    ) {
                                        firstSnapshot
                                    } else {
                                        restoredSnapshot
                                    },
                                    {},
                                    {},
                                )
                            }
                        }
                        onNodeWithTag("exchange-import-files").performClick()
                        runOnIdle { answer!!(listOf(ImportTable("unknown.csv", "synthetic", null, null, emptyList())), null) }
                        waitUntil(timeoutMillis = 10_000) {
                            runCatching { onNodeWithTag("exchange-import-commit").assertIsNotEnabled() }.isSuccess
                        }
                        onNodeWithText("Not a supported export").assertExists()
                        onNodeWithTag("exchange-import-files").performClick()
                        val oldAnswer = answer!!
                        runOnIdle { current.value = restored }
                        waitForIdle()
                        runOnIdle { oldAnswer(listOf(ImportTable("old.csv", "synthetic", null, null, emptyList())), null) }
                        waitForIdle()
                        onNodeWithText("old.csv").assertDoesNotExist()
                        onNodeWithTag("exchange-import-commit").assertDoesNotExist()
                        onNodeWithTag("exchange-import-files").assertIsEnabled()
                    }
                }
            }
        }
    }
}
