package io.valroot.android

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.storage.BundledSqliteDatabase
import io.valroot.core.storage.readBoundedText
import io.valroot.ui.host.BackupWorkflow
import io.valroot.ui.host.JvmDatabaseFiles
import io.valroot.ui.host.RestoreHost
import kotlinx.coroutines.flow.update

/**
 * File import and export: CSV text in, and encrypted backups out and back in. A restore builds the new database in a
 * staging file and replaces the live one only after it validates.
 */
internal class AndroidBackups(
    private val state: AndroidState,
    private val sync: AndroidSync,
    private val applyLockDelay: (Long) -> Unit,
) {
    fun saveFile(
        name: String,
        bytes: ByteArray,
        onResult: (String) -> Unit,
    ) {
        state.main.post { state.files.save(name, bytes) { onResult(if (it) "File saved" else "File was not saved") } }
    }

    /**
     * Asks for a text file, such as a CSV export, and reads it in the background. [onResult] runs on the main thread with
     * the file name and text, or with null text when the user cancelled, the file is over [maxChars] or is not UTF-8 text.
     */
    fun openTextFile(
        maxChars: Int,
        onResult: (name: String, text: String?) -> Unit,
    ) {
        state.main.post {
            state.files.chooseSource { uri ->
                if (uri == null) {
                    onResult("", null)
                } else {
                    state.executor.execute {
                        val resolver = state.activity.contentResolver
                        val name =
                            runCatching {
                                resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                    if (it.moveToFirst()) it.getString(0) else null
                                }
                            }.getOrNull() ?: "import.csv"
                        val text = runCatching { resolver.openInputStream(uri)?.let { readBoundedText(it, maxChars) } }.getOrNull()
                        state.main.post { if (!state.closed) onResult(name, text) }
                    }
                }
            }
        }
    }

    fun saveBackup(
        password: String,
        onResult: (String) -> Unit,
    ) {
        state.main.post {
            state.files.chooseDestination("valroot-backup.valroot") { uri ->
                if (uri == null) {
                    onResult("Backup was not saved")
                } else {
                    workflow.save(password, { export -> state.files.write(uri) { out -> export { b, o, l -> out.write(b, o, l) } } }, onResult)
                }
            }
        }
    }

    /**
     * Restores the opened backup, or asks for one. Called on the main thread by the restore dialog, which closes next, so
     * the opened backup is taken now.
     */
    fun restoreBackup(
        password: String,
        onResult: (String) -> Unit,
    ) {
        val opened = state.openedBackupUri
        forgetOpenedBackup()
        if (opened != null) {
            restoreBackup(opened, password, onResult)
            return
        }
        state.main.post {
            state.files.chooseSource(BACKUP_TYPES) { uri ->
                when {
                    uri == null -> onResult("No backup selected")
                    !isBackupName(state.files.displayName(uri)) -> onResult("Choose a Valroot backup (a .valroot file)")
                    else -> restoreBackup(uri, password, onResult)
                }
            }
        }
    }

    /** A file handed to the app to open: a backup opens the restore dialog; anything else is declined. Main thread. */
    fun receive(uri: android.net.Uri) {
        val name = state.files.displayName(uri)
        if (!isBackupName(name)) {
            android.widget.Toast
                .makeText(state.activity, "Valroot opens only its own backups (.valroot files)", android.widget.Toast.LENGTH_LONG)
                .show()
            return
        }
        state.openedBackupUri = uri
        state.openedBackup.value = name ?: "the opened backup"
    }

    fun forgetOpenedBackup() {
        state.openedBackupUri = null
        state.openedBackup.value = null
    }

    /**
     * Restores on the sync thread, so it never overlaps a scan. Every use of the live connection runs on the database
     * executor through [RestoreHost.onData], in turn with the screens' queries and the activity's close, so none of them
     * meets a connection being closed or replaced.
     */
    private fun restoreBackup(
        uri: android.net.Uri,
        password: String,
        onResult: (String) -> Unit,
    ) = workflow.restore(
        password,
        { task, released ->
            RefreshWorker.syncExecutor.execute {
                try {
                    task()
                } finally {
                    released()
                }
            }
            true
        },
        { read -> state.files.read(uri) { input -> read { b, o, l -> input.read(b, o, l) } } },
        onResult,
    )

    private val restoreHost =
        object : RestoreHost {
            override val files = JvmDatabaseFiles(state.file)

            /** Runs [task] on the database executor and waits; fails once the activity has closed, since its close follows. */
            override fun <T> onData(task: () -> T): T =
                try {
                    state.executor
                        .submit(
                            java.util.concurrent.Callable {
                                check(!state.closed) { "Valroot closed during the restore" }
                                task()
                            },
                        ).get()
                } catch (failure: java.util.concurrent.ExecutionException) {
                    throw failure.cause as? Exception ?: failure
                }

            override fun current(): PortfolioApplication = requireNotNull(state.app.value)

            override fun pauseBackgroundWork(app: PortfolioApplication) {
                AutomaticPriceGate.paused = true
                PriceHistoryWorker.invalidate()
                // This short transaction waits for earlier price commits and invalidates their consent generation.
                app.prices.setSourceConsent(app.prices.sourceConsent())
            }

            override fun resumeBackgroundWork() {
                AutomaticPriceGate.paused = false
            }

            override fun <T> underWriteGate(block: () -> T): T = synchronized(AutomaticPriceGate.lock) { block() }

            override fun closeLive() = state.db.close()

            override fun reopenLive() {
                state.db = BundledSqliteDatabase(state.file.toString())
            }

            /** Hands a new application to the screens on the main thread and waits until they hold it. */
            override fun publishFresh() {
                val replacement = PortfolioApplication(state.db, System::currentTimeMillis)
                val lockDelay = replacement.settings.lockDelayMillis() ?: io.valroot.core.application.DEFAULT_LOCK_DELAY_MILLIS
                val updated = java.util.concurrent.CountDownLatch(1)
                state.main.post {
                    applyLockDelay(lockDelay)
                    state.app.value = replacement
                    state.dataRevision.update { it + 1 }
                    updated.countDown()
                }
                updated.await()
            }

            override fun afterRestore(app: PortfolioApplication) {
                sync.reconcileRefreshPreferences(app)
                // The restored settings decide whether periodic notification checks run.
                runCatching { NotificationWorker.schedule(state.activity, app.notifications.active()) }
            }
        }

    private val workflow = BackupWorkflow(restoreHost, state.threads, state.backupWork)
}
