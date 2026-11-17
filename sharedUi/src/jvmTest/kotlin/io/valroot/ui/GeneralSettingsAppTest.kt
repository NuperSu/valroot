package io.valroot.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.valroot.core.application.LandingPage
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.onboarding.OnboardingAction
import io.valroot.core.storage.BundledSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class GeneralSettingsAppTest {
    @Test
    fun savedLandingAndChartApplyOnLaunch() {
        BundledSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { host ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.demo.seed()
                app.settings.setLandingPage(LandingPage.PORTFOLIO)
                app.settings.setShowPortfolioChart(false)
                runDesktopComposeUiTest(width = 600, height = 900) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("portfolio-stats").fetchSemanticsNodes().isNotEmpty() }
                    // Prices are in US dollars only, so there is no second currency to show.
                    onNodeWithTag("portfolio-secondary-balance").assertDoesNotExist()
                    onNodeWithTag("portfolio-chart").assertDoesNotExist()
                    openSettings()
                    openSettingsRow("settings-portfolio")
                    openSettingsRow("settings-show-chart")
                    waitUntil(timeoutMillis = 10_000) { app.settings.general().showPortfolioChart }
                    onNodeWithTag("settings-back").performClick()
                    onNodeWithTag("model-PORTFOLIO").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("portfolio-chart").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(LandingPage.PORTFOLIO, app.settings.general().landing)
                }
            }
        }
    }
}
