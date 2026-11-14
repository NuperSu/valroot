package io.valroot.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class BackupWorkDialogTest {
    @Test fun aRunningRestoreShowsProgressUntilItEnds() {
        ModelWorkspaceRestorationTest.RestorationHost().use { delegate ->
            val work = MutableStateFlow<String?>(null)
            val host =
                object : PlatformActions by delegate {
                    override val backupWork = work
                }
            runDesktopComposeUiTest(width = 390, height = 844) {
                setContent { ValrootTheme(AppearanceTheme.DARK) { BackupWorkDialog(host) } }
                onNodeWithTag("backup-work").assertDoesNotExist()
                work.value = "Restoring backup…"
                waitForIdle()
                onNodeWithText("Restoring backup…").assertIsDisplayed()
                work.value = null
                waitForIdle()
                onNodeWithTag("backup-work").assertDoesNotExist()
            }
        }
    }
}
