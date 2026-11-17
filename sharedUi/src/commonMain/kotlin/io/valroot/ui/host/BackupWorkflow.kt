package io.valroot.ui.host

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.backup.ByteSink
import io.valroot.core.backup.ByteSource
import io.valroot.core.storage.BundledSqliteDatabase
import kotlinx.coroutines.flow.MutableStateFlow

/** File operations a restore needs beside the live database file. */
interface DatabaseFiles {
    /** Creates an empty file beside the live database for the restored copy and returns its path. */
    fun createStaging(): String

    /** Forces [path]'s contents to storage. */
    fun flush(path: String)

    /** Whether the live database's write-ahead log is missing or empty, so no connection still holds writes. */
    fun walIsEmpty(): Boolean

    /** Atomically replaces the live database file with [staging]. */
    fun replaceLive(staging: String)

    fun delete(path: String)
}

/** What a host provides around a backup restore. Every method except [onData] and [publishFresh] runs on the data thread. */
interface RestoreHost {
    val files: DatabaseFiles

    /** Runs [task] on the host's database thread and waits for it; fails once the host has closed. */
    fun <T> onData(task: () -> T): T

    /** The application the screens hold now. */
    fun current(): PortfolioApplication

    /** Stops background price, market and notification work from starting or writing until [resumeBackgroundWork]. */
    fun pauseBackgroundWork(app: PortfolioApplication)

    fun resumeBackgroundWork()

    /** Runs [block] holding the gate background work takes before it reads or writes the database. */
    fun <T> underWriteGate(block: () -> T): T

    /** Closes every connection the host holds to the live database file. */
    fun closeLive()

    /** Opens the live database file again. */
    fun reopenLive()

    /** Builds an application on the live connection and hands it to the screens, waiting until they hold it. */
    fun publishFresh()

    /** Restored settings decide the host's schedules (automatic refresh, notification checks); throws when they fail. */
    fun afterRestore(app: PortfolioApplication)
}

/**
 * Backup save and restore, shared by every host. A restore decrypts into a staging file, and replaces the live database
 * only after the backup validated, with background work paused and every live connection closed. [progress] holds what
 * is running for the progress dialog.
 */
class BackupWorkflow(
    private val host: RestoreHost,
    private val threads: HostThreads,
    private val progress: MutableStateFlow<String?>,
) {
    /** Exports an encrypted backup through [write], which opens the destination and hands it a sink. Main thread. */
    fun save(
        password: String,
        write: ((ByteSink) -> Unit) -> Unit,
        onResult: (String) -> Unit,
    ) {
        progress.value = "Saving backup…"
        threads.data {
            val secret = password.toCharArray()
            val message =
                try {
                    write { sink -> host.current().backup.export(secret, sink) }
                    "Backup saved"
                } catch (_: Exception) {
                    "Could not save backup"
                } finally {
                    secret.fill('\u0000')
                }
            threads.main {
                progress.value = null
                onResult(message)
            }
        }
    }

    /**
     * Restores the backup [open] reads. [exclusive] runs the restore on a thread of its own, apart from the data thread
     * and never beside a sync, or returns false when it cannot start now. Main thread.
     */
    fun restore(
        password: String,
        exclusive: (task: () -> Unit) -> Boolean,
        open: ((ByteSource) -> Unit) -> Unit,
        onResult: (String) -> Unit,
    ) {
        progress.value = "Restoring backup…"
        val accepted =
            exclusive {
                val message = restoreNow(password, open)
                threads.main {
                    progress.value = null
                    onResult(message)
                }
            }
        if (!accepted) {
            progress.value = null
            onResult("Wait for the active sync to finish, then restore")
        }
    }

    /** The restore itself, on the exclusive thread; returns the message for the user. */
    internal fun restoreNow(
        password: String,
        open: ((ByteSource) -> Unit) -> Unit,
    ): String {
        val files = host.files
        var staging: String? = null
        var result = "Could not restore backup"
        var swapped = false
        var reopened = false
        var paused = false
        var ready = false
        try {
            staging = files.createStaging()
            BundledSqliteDatabase(staging).use { stagingDb ->
                host.onData {
                    open { source -> host.current().backup.restoreToStaging(stagingDb, source, password.toCharArray()) }
                }
            }
            files.flush(staging)
            paused = true
            host.onData {
                host.pauseBackgroundWork(host.current())
                host.current().prices.invalidateAutomaticRequests()
            }
            host.onData {
                host.underWriteGate {
                    host.closeLive()
                    try {
                        // Another connection still holding a write-ahead log would replay it into the restored file.
                        check(files.walIsEmpty()) { STILL_WRITING }
                        files.replaceLive(staging)
                        swapped = true
                    } finally {
                        host.reopenLive()
                        reopened = true
                    }
                    host.publishFresh()
                    ready = true
                }
            }
            result = "Backup restored"
        } catch (failure: Exception) {
            // Validation messages (wrong password, damaged or unsupported backup) explain a rejected file.
            if (!swapped && failure is IllegalArgumentException) result = failure.message ?: result
            if (!swapped && failure is IllegalStateException && failure.message == STILL_WRITING) result = STILL_WRITING
            if (swapped) result = "Backup restored; reopen the app to load it"
            if (reopened && !ready) runCatching { host.onData { host.publishFresh() } }
        } finally {
            if (paused) {
                host.resumeBackgroundWork()
                if (swapped && ready && runCatching { host.onData { host.afterRestore(host.current()) } }.isFailure) {
                    result = "Backup restored; automatic price scheduling could not be updated"
                }
            }
            staging?.let { runCatching { files.delete(it) } }
        }
        return result
    }

    private companion object {
        const val STILL_WRITING = "Valroot is still writing; retry the restore"
    }
}
