package io.valroot.desktop

import androidx.compose.runtime.MutableState
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.storage.BundledSqliteDatabase
import io.valroot.core.storage.readBoundedText
import io.valroot.ui.host.BackupWorkflow
import io.valroot.ui.host.JvmDatabaseFiles
import io.valroot.ui.host.RestoreHost
import kotlinx.coroutines.flow.update
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import javax.swing.SwingUtilities

/**
 * File import and export: saves generated files and encrypted backups, reads text files, and restores a backup into a
 * staging database before it replaces the open one. [reconcileRefreshPreferences] and [reconcileNotificationChecks] run
 * after a successful swap, since restored data can change both schedules.
 */
internal class DesktopBackups(
    private val state: DesktopState,
    private val reconcileRefreshPreferences: () -> Unit,
    private val reconcileNotificationChecks: () -> Unit,
) {
    private val files = BackupFiles()
    private val app: MutableState<PortfolioApplication> get() = state.app

    fun saveFile(
        name: String,
        bytes: ByteArray,
        onResult: (String) -> Unit,
    ) {
        SwingUtilities.invokeLater {
            runCatching {
                files.save(
                    name,
                    bytes,
                )
            }.fold({ onResult(if (it) "File saved" else "Cancelled") }, { onResult("Could not save file") })
        }
    }

    fun openTextFile(
        maxChars: Int,
        onResult: (name: String, text: String?) -> Unit,
    ) {
        SwingUtilities.invokeLater {
            val path = runCatching { files.chooseImport() }.getOrNull()
            if (path == null) {
                onResult("", null)
                return@invokeLater
            }
            state.executor.execute {
                val text = runCatching { readBoundedText(Files.newInputStream(path), maxChars) }.getOrNull()
                SwingUtilities.invokeLater { onResult(path.fileName.toString(), text) }
            }
        }
    }

    fun saveBackup(
        password: String,
        onResult: (String) -> Unit,
    ) {
        SwingUtilities.invokeLater {
            val path = runCatching { files.chooseBackupDestination() }.getOrNull()
            if (path == null) {
                onResult("Backup was not saved")
                return@invokeLater
            }
            workflow.save(
                password,
                { export ->
                    // Written beside the destination and moved into place, so a failed export never leaves a partial backup.
                    val temp = Files.createTempFile(path.toAbsolutePath().parent, ".valroot-backup-", ".tmp")
                    try {
                        Files.newOutputStream(temp).buffered().use { out -> export { b, o, l -> out.write(b, o, l) } }
                        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        Files.deleteIfExists(temp)
                    }
                },
                onResult,
            )
        }
    }

    fun restoreBackup(
        password: String,
        onResult: (String) -> Unit,
    ) {
        SwingUtilities.invokeLater {
            val path = runCatching { files.chooseBackup() }.getOrNull()
            when {
                path == null -> onResult("No backup selected")
                // Some native dialogs ignore the filter.
                !path.fileName.toString().endsWith(".valroot", ignoreCase = true) -> onResult("Choose a Valroot backup (a .valroot file)")
                else -> restoreBackup(path, password, onResult)
            }
        }
    }

    /** Restores [path] on the sync thread, so it never overlaps a sync; the database work runs on [DesktopState.executor]. */
    internal fun restoreBackup(
        path: Path,
        password: String,
        onResult: (String) -> Unit,
    ) = workflow.restore(
        password,
        { task, released -> state.syncQueue.tryStart(onReleased = released, task = task) },
        { read -> Files.newInputStream(path).buffered().use { input -> read { b, o, l -> input.read(b, o, l) } } },
        onResult,
    )

    private val restoreHost =
        object : RestoreHost {
            override val files = JvmDatabaseFiles(state.file)

            override fun <T> onData(task: () -> T): T =
                try {
                    state.executor
                        .submit(
                            Callable {
                                check(!state.closing.get()) { "Valroot closed during the restore" }
                                task()
                            },
                        ).get()
                } catch (failure: ExecutionException) {
                    throw failure.cause as? Exception ?: failure
                }

            override fun current(): PortfolioApplication = app.value

            override fun pauseBackgroundWork(app: PortfolioApplication) {
                state.priceRestorePaused = true
            }

            override fun resumeBackgroundWork() {
                state.priceRestorePaused = false
            }

            override fun <T> underWriteGate(block: () -> T): T = synchronized(state.priceGate) { block() }

            override fun closeLive() {
                state.closeSyncConnection()
                state.db.close()
            }

            override fun reopenLive() {
                state.db = BundledSqliteDatabase(state.file.toString())
            }

            override fun publishFresh() {
                val replacement = PortfolioApplication(state.db, System::currentTimeMillis)
                SwingUtilities.invokeAndWait {
                    app.value = replacement
                    state.dataRevision.update { it + 1 }
                }
            }

            override fun afterRestore(app: PortfolioApplication) {
                // Restored data can turn alerts on or off, so the check schedule follows it.
                runCatching { reconcileNotificationChecks() }
                reconcileRefreshPreferences()
            }
        }

    private val workflow = BackupWorkflow(restoreHost, state.threads, state.backupWork)
}
