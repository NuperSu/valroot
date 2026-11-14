package io.valroot.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.valroot.core.application.AssetSort
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.model.AssetId
import io.valroot.core.onboarding.OnboardingAction
import io.valroot.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PhaseTwoControlsTest {
    @Test
    fun marketCoinUsesGlobalAccountingAndRestoresEarlierPortfolioScope() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { host ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.demo.seed()
                app.accounts.savePortfolio(
                    io.valroot.core.ownership
                        .Portfolio(
                            io.valroot.core.model
                                .PortfolioId("second"),
                            "Second test",
                            "USD",
                        ),
                )
                runDesktopComposeUiTest(width = 600, height = 900) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("model-PORTFOLIO").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("model-PORTFOLIO").performClick()
                    onNodeWithText("All portfolios").performClick()
                    onNodeWithText("Second test").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Second test").fetchSemanticsNodes().size == 1 }
                    onNodeWithTag("model-MAIN").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("BTC").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("BTC").performScrollTo().performClick()
                    onNodeWithText("All portfolios").assertIsDisplayed()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("coin-accounting-ready").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("93250.00 USD", substring = true).performScrollTo().assertIsDisplayed()
                    onNodeWithText("All portfolios").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("model-PORTFOLIO").performClick()
                    onNodeWithText("Second test").assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun modeledCurrencySortFiltersAndExplanationUseRealWorkflows() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { host ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.demo.seed()
                runDesktopComposeUiTest(width = 390, height = 844) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("model-PORTFOLIO").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("model-PORTFOLIO").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("coin-BTC").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("✧  What's going on?").performScrollTo().performClick()
                    onNodeWithText("Portfolio value").assertIsDisplayed()
                    onNodeWithText("Close").performClick()
                    onNodeWithText("Highest Holdings").performScrollTo().performClick()
                    onNodeWithText("name").performClick()
                    waitUntil(timeoutMillis = 10_000) { app.settings.assetList().sort == AssetSort.NAME }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Name").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("☷").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Hide empty: false").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("Hide empty: false").performClick()
                    waitUntil(timeoutMillis = 10_000) { app.settings.assetList().hideEmpty }
                    // Other currencies are listed as planned and cannot be chosen while prices are in US dollars only.
                    onNodeWithText("USD").performScrollTo().performClick()
                    onNodeWithTag("currency-EUR").assertIsNotEnabled()
                    onNodeWithTag("currency-USD").performClick()
                    assertEquals(AssetId("USD"), app.snapshot().currency)
                    assertTrue(PortfolioApplication(db) { 1_800_000_000_000L }.settings.assetList().hideEmpty)
                }
            }
        }
    }
}
