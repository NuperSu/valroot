package io.valroot.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.valroot.core.application.LandingPage
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.onboarding.OnboardingAction
import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.ui.model.Candle
import io.valroot.ui.model.ChartData
import io.valroot.ui.model.ChartStyle
import io.valroot.ui.model.ModelInteractiveChart
import io.valroot.ui.model.chartPercent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class InteractiveChartTest {
    private val data =
        ChartData(
            listOf(100.0, 110.0, null, 90.0, 120.0, 130.0, 125.0, 140.0),
            (0L until 8L).map { it * 3_600_000L },
            { "$${it.toInt()}" },
            { "t${it / 3_600_000L}" },
        )

    /** Two market candles: 100 → 110 (high 115, low 95), then 110 → 140 (high 150, low 105). */
    private val withCandles =
        data.copy(
            values = listOf(110.0, 140.0),
            times = listOf(3_600_000L, 7_200_000L),
            candles =
                listOf(
                    Candle(100.0, 115.0, 95.0, 110.0, 0L, 3_600_000L),
                    Candle(110.0, 150.0, 105.0, 140.0, 3_600_000L, 7_200_000L),
                ),
        )

    @Test fun percentagesCompareWithTheFirstPrice() {
        assertEquals("+40.00%", chartPercent(100.0, 140.0))
        assertEquals("-10.00%", chartPercent(100.0, 90.0))
        assertEquals("—", chartPercent(null, 1.0))
    }

    @Test fun candlesShowTheMarketsOpenHighLowAndClose() {
        runDesktopComposeUiTest(width = 400, height = 300) {
            setContent {
                ValrootTheme(AppearanceTheme.DARK) {
                    ModelInteractiveChart(withCandles, ChartStyle.CANDLES, Modifier.fillMaxWidth().height(200.dp))
                }
            }
            onNodeWithTag("interactive-chart").performMouseInput {
                moveTo(
                    androidx.compose.ui.geometry
                        .Offset(width - 1f, height / 2f),
                )
            }
            waitForIdle()
            onNodeWithText("O $110  C $140").assertExists()
            onNodeWithText("H $150  L $105").assertExists()
            // The change is measured from the first candle's open.
            onNodeWithText("+40.00%", substring = true).assertExists()
        }
    }

    @Test fun withoutMarketCandlesTheCandleChoiceDrawsTheLine() {
        runDesktopComposeUiTest(width = 400, height = 300) {
            setContent {
                ValrootTheme(AppearanceTheme.DARK) {
                    ModelInteractiveChart(data, ChartStyle.CANDLES, Modifier.fillMaxWidth().height(200.dp))
                }
            }
            onNodeWithTag("interactive-chart").performMouseInput {
                moveTo(
                    androidx.compose.ui.geometry
                        .Offset(width - 1f, height / 2f),
                )
            }
            waitForIdle()
            onNodeWithText("$140 · +40.00%").assertExists()
            onNodeWithText("O ", substring = true).assertDoesNotExist()
        }
    }

    @Test fun hoverAndTouchShowValueChangeAndTime() {
        runDesktopComposeUiTest(width = 400, height = 300) {
            setContent {
                ValrootTheme(AppearanceTheme.DARK) {
                    ModelInteractiveChart(data, ChartStyle.LINE, Modifier.fillMaxWidth().height(200.dp))
                }
            }
            onNodeWithTag("chart-tooltip").assertDoesNotExist()
            onNodeWithTag("interactive-chart").performMouseInput {
                moveTo(
                    androidx.compose.ui.geometry
                        .Offset(width - 1f, height / 2f),
                )
            }
            waitForIdle()
            onNodeWithText("$140 · +40.00%").assertExists()
            onNodeWithText("t7").assertExists()
            onNodeWithTag("interactive-chart").performMouseInput { exit() }
            waitForIdle()
            onNodeWithTag("chart-tooltip").assertDoesNotExist()
            onNodeWithTag("interactive-chart").performTouchInput {
                down(
                    androidx.compose.ui.geometry
                        .Offset(1f, height / 2f),
                )
            }
            waitForIdle()
            onNodeWithText("$100 · 0.00%").assertExists()
            onNodeWithTag("interactive-chart").performTouchInput { up() }
            waitForIdle()
            onNodeWithTag("chart-tooltip").assertDoesNotExist()
        }
    }

    @Test fun hiddenAmountsKeepPercentagesInTheTooltip() {
        runDesktopComposeUiTest(width = 400, height = 300) {
            setContent {
                ValrootTheme(AppearanceTheme.DARK) {
                    ModelInteractiveChart(withCandles.copy(hideValues = true), ChartStyle.CANDLES, Modifier.fillMaxWidth().height(200.dp))
                }
            }
            assertTrue(onAllNodesWithText("$", substring = true).fetchSemanticsNodes().isEmpty())
            onNodeWithTag("interactive-chart").performMouseInput {
                moveTo(
                    androidx.compose.ui.geometry
                        .Offset(width - 1f, height / 2f),
                )
            }
            waitForIdle()
            onNodeWithText("O *****", substring = true).assertExists()
            onNodeWithText("+40.00%", substring = true).assertExists()
        }
    }

    @Test fun portfolioShowsPerformanceAllocationAndOnlyALine() {
        JvmSqliteDatabase(":memory:").use { db ->
            ModelWorkspaceRestorationTest.RestorationHost().use { host ->
                val app = PortfolioApplication(db) { 1_800_000_000_000L }
                app.settings.updateOnboarding(OnboardingAction.Skip)
                app.demo.seed()
                app.settings.setLandingPage(LandingPage.PORTFOLIO)
                runDesktopComposeUiTest(width = 600, height = 1600) {
                    setContent { ValrootApp(app, host) }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("portfolio-stats").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("Cost basis").assertExists()
                    onNodeWithTag("portfolio-allocation").performScrollTo()
                    onNodeWithText("100.0%").assertExists()
                    // A portfolio's value is not traded, so it offers no candles.
                    onNodeWithTag("chart-style-candles").assertDoesNotExist()
                    assertTrue(onAllNodesWithText("Portfolio value change includes", substring = true).fetchSemanticsNodes().isEmpty())
                }
            }
        }
    }
}
