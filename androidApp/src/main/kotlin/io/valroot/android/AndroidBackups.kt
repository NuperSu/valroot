package io.valroot.android

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.storage.BundledSqliteDatabase
import io.valroot.core.storage.readBoundedText
import io.valroot.core.storage.walIsEmpty
import kotlinx.coroutines.flow.update
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * File import and export: CSV text in, and encrypted backups out and back in. A restore builds the new database in a
 * staging file and replaces the live one only after it validates.
 */
internal class AndroidBackups(
    private val state: AndroidState,
    private val sync: AndroidSync,
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
                    state.backupWork.value = "Saving backup…"
                    state.executor.execute {
                        val secret = password.toCharArray()
                        val success =
                            try {
                                runCatching {
                                    state.files.write(uri) { out ->
                                        requireNotNull(state.app.value).backup.export(secret) { b, o, l -> out.write(b, o, l) }
                                    }
                                }.isSuccess
                            } finally {
                                secret.fill('\u0000')
                            }
                        state.main.post {
                            state.backupWork.value = null
                            onResult(if (success) "Backup saved" else "Could not save backup")
                        }
                    }
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

    private fun restoreBackup(
        uri: android.net.Uri,
        password: String,
        onResult: (String) -> Unit,
    ) {
        // Restore replaces the database file, so it shares the sync thread and cannot overlap a running scan. Every use
        // of the live connection runs on the database executor through [onDb], in turn with the screens' queries and
        // the activity's close, so none of them meets a connection being closed or replaced.
        state.backupWork.value = "Restoring backup…"
        RefreshWorker.syncExecutor.execute {
            var staging: java.nio.file.Path? = null
            var result = "Could not restore backup"
            var swapped = false
            var reopened = false
            var pricePaused = false
            var replacementReady = false
            try {
                staging = Files.createTempFile(state.file.parent, "restore-", ".sqlite")
                BundledSqliteDatabase(
                    staging.toString(),
                ).use { stagingDb ->
                    onDb {
                        state.files.read(uri) { input ->
                            requireNotNull(state.app.value).backup.restoreToStaging(
                                stagingDb,
                                { b, o, l -> input.read(b, o, l) },
                                password.toCharArray(),
                            )
                        }
                    }
                }
                java.nio.channels.FileChannel
                    .open(staging, java.nio.file.StandardOpenOption.WRITE)
                    .use { it.force(true) }
                AutomaticPriceGate.paused = true
                pricePaused = true
                onDb {
                    requireNotNull(state.app.value).prices.invalidateAutomaticRequests()
                    PriceHistoryWorker.invalidate()
                    // This short transaction waits for earlier price commits and invalidates their consent generation.
                    requireNotNull(state.app.value).let { it.prices.setSourceConsent(it.prices.sourceConsent()) }
                }
                onDb {
                    synchronized(AutomaticPriceGate.lock) {
                        state.db.close()
                        try {
                            // Another connection still holding a write-ahead log would replay it into the restored file.
                            check(walIsEmpty(state.file)) { "Valroot is still writing; retry the restore" }
                            Files.move(staging, state.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                            swapped = true
                        } finally {
                            state.db = BundledSqliteDatabase(state.file.toString())
                            reopened = true
                        }
                        publish(PortfolioApplication(state.db, System::currentTimeMillis))
                        replacementReady = true
                    }
                }
                result = "Backup restored"
            } catch (failure: Exception) {
                // Validation messages (wrong password, damaged or unsupported backup) explain a rejected file.
                if (!swapped && failure is IllegalArgumentException) result = failure.message ?: result
                if (swapped) result = "Backup restored; reopen the app to load it"
                if (reopened && !replacementReady) {
                    runCatching { onDb { publish(PortfolioApplication(state.db, System::currentTimeMillis)) } }
                }
            } finally {
                if (pricePaused) {
                    AutomaticPriceGate.paused = false
                    if (swapped && replacementReady) {
                        val scheduled =
                            runCatching {
                                onDb {
                                    state.app.value?.let { app ->
                                        sync.reconcileRefreshPreferences(app)
                                        // The restored settings decide whether periodic notification checks run.
                                        runCatching { NotificationWorker.schedule(state.activity, app.notifications.active()) }
                                    }
                                }
                            }
                        if (scheduled.isFailure) result = "Backup restored; automatic price scheduling could not be updated"
                    }
                }
                staging?.let { runCatching { Files.deleteIfExists(it) } }
            }
            val message = result
            state.main.post {
                state.backupWork.value = null
                if (!state.closed) onResult(message)
            }
        }
    }

    /**
     * Runs [block] on the database executor and waits for it. It fails once the activity has closed, since the close
     * that follows on the same executor releases the connection.
     */
    private fun <T> onDb(block: () -> T): T =
        try {
            state.executor
                .submit(
                    java.util.concurrent.Callable {
                        check(!state.closed) { "Valroot closed during the restore" }
                        block()
                    },
                ).get()
        } catch (failure: java.util.concurrent.ExecutionException) {
            throw failure.cause as? Exception ?: failure
        }

    /** Hands [replacement] to the screens on the main thread and waits until they hold it. */
    private fun publish(replacement: PortfolioApplication) {
        val updated = java.util.concurrent.CountDownLatch(1)
        state.main.post {
            state.app.value = replacement
            state.dataRevision.update { it + 1 }
            updated.countDown()
        }
        updated.await()
    }
}
