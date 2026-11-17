package io.valroot.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.storage.BundledSqliteDatabase

/**
 * State shared by [AndroidHost] and its helpers: the database file and its connection, the background executor and
 * main-thread handler, the file pickers and the notification permission launcher, and the flag that keeps callbacks away
 * from a closed activity. Helpers receive it through their constructors.
 */
internal class AndroidState(
    val activity: ComponentActivity,
) {
    /** Database and workflow work runs here; a long Bitcoin scan uses [RefreshWorker.syncExecutor] instead. */
    val executor = RefreshWorker.executor
    val main = Handler(Looper.getMainLooper())

    /** Lock-verifier work, kept off [executor] so unlocking never waits behind database work. */
    val secretExecutor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Shared workflows' threads: database work on [executor], verifier work on [secretExecutor], results on [main]. */
    val threads =
        object : io.valroot.ui.host.HostThreads {
            override fun data(block: () -> Unit) = executor.execute(block)

            override fun secret(block: () -> Unit) = secretExecutor.execute(block)

            override fun main(block: () -> Unit) {
                // A closed activity's screens are gone; their callbacks are dropped.
                this@AndroidState.main.post { if (!closed) block() }
            }
        }
    val file = activity.getDatabasePath("portfolio.sqlite").toPath()
    val preferences = activity.getSharedPreferences("privacy", 0)
    val dataRevision = RefreshWorker.dataRevision
    val syncStatus = RefreshWorker.syncStatus
    val priceRefreshStatus = AutomaticPriceWorker.status
    val app = mutableStateOf<PortfolioApplication?>(null)
    val openFailed = mutableStateOf(false)

    /** A backup handed to the app ("Open with"): its name for the restore dialog, and the document. Main thread only. */
    val openedBackup = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    var openedBackupUri: android.net.Uri? = null

    /** What a running backup save or restore is doing, for the progress dialog. */
    val backupWork = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Opened on [executor]; a restore replaces it. */
    lateinit var db: BundledSqliteDatabase

    /** Whether the database has been opened. */
    val dbOpen: Boolean get() = ::db.isInitialized

    /** Set in [AndroidHost.close]. Callbacks posted to the main thread check it and do nothing once it is set. */
    @Volatile var closed = false

    /** Storage Access Framework pickers for exports and backups. */
    val files = BackupFiles(activity)
    val notificationPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** True on Android 13 and later when the notification permission has not been granted. */
    fun notificationPermissionMissing(): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
}
