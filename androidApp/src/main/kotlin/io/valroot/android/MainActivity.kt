package io.valroot.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import io.valroot.ui.ValrootApp
import io.valroot.ui.ValrootStartScreen
import io.valroot.ui.valrootThemeIsDark
import kotlinx.coroutines.flow.update

class MainActivity : ComponentActivity() {
    private var host: AndroidHost? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val opened = runCatching { AndroidHost(this) }.getOrNull()
        val startTheme = opened?.savedTheme
        val systemDark =
            (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        styleSystemBars(this, valrootThemeIsDark(startTheme, systemDark))
        host = opened
        if (savedInstanceState == null) {
            receive(intent)
        } else {
            // A recreated activity has a new host; the backup opened before it is handed over again.
            savedInstanceState.getString(PENDING_BACKUP)?.let { host?.receive(it.toUri()) }
        }
        setContent {
            if (opened == null) {
                ValrootStartScreen(themeKey = null, failed = true, message = "")
            } else {
                val app = opened.app.value
                if (app != null) {
                    ValrootApp(app, opened, opened.locked.value)
                } else {
                    val status by opened.syncStatus.collectAsState()
                    ValrootStartScreen(startTheme, opened.openFailed.value, status.message.takeIf { status.phase == io.valroot.ui.SyncPhase.SYNCING }.orEmpty())
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        host?.pendingBackup?.let { outState.putString(PENDING_BACKUP, it.toString()) }
    }

    /** A backup opened with Valroot from a file manager or another app. */
    private fun receive(intent: Intent?) {
        val uri = intent?.data?.takeIf { intent.action == Intent.ACTION_VIEW } ?: return
        host?.receive(uri)
    }

    override fun onStart() {
        super.onStart()
        host?.returned()
        RefreshWorker.dataRevision.update { it + 1 }
        // Runs in the background and does nothing unless an alert or notification setting is on.
        host?.checkNotifications()
    }

    override fun onStop() {
        host?.left()
        super.onStop()
    }

    override fun onDestroy() {
        host?.close()
        super.onDestroy()
    }

    private companion object {
        const val PENDING_BACKUP = "pending-backup"
    }
}
