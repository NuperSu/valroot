package io.valroot.ui.host

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.backup.ByteArraySource
import io.valroot.core.storage.BundledSqliteDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupWorkflowTest {
    private val directory: Path = Files.createTempDirectory("valroot-restore")
    private val file: Path = directory.resolve("portfolio.sqlite")

    private object Immediate : HostThreads {
        override fun data(block: () -> Unit) = block()

        override fun secret(block: () -> Unit) = block()

        override fun main(block: () -> Unit) = block()
    }

    private inner class Host : RestoreHost {
        var db = BundledSqliteDatabase(file.toString())
        var app = PortfolioApplication(db) { 1_700_000_000_000 }
        var paused = false
        var rescheduled = 0
        override val files = JvmDatabaseFiles(file)

        override fun <T> onData(task: () -> T): T = task()

        override fun current() = app

        override fun pauseBackgroundWork(app: PortfolioApplication) {
            paused = true
        }

        override fun resumeBackgroundWork() {
            paused = false
        }

        override fun <T> underWriteGate(block: () -> T): T = block()

        override fun closeLive() = db.close()

        override fun reopenLive() {
            db = BundledSqliteDatabase(file.toString())
        }

        override fun publishFresh() {
            app = PortfolioApplication(db) { 1_700_000_000_000 }
        }

        override fun afterRestore(app: PortfolioApplication) {
            rescheduled++
        }
    }

    private val host = Host()
    private val progress = MutableStateFlow<String?>(null)
    private val workflow = BackupWorkflow(host, Immediate, progress)

    @AfterTest fun cleanUp() {
        runCatching { host.db.close() }
        directory.toFile().deleteRecursively()
    }

    private fun backupOfDemoData(password: String): ByteArray {
        host.app.demo.seed()
        val archive = host.app.backup.export(password.toCharArray())
        host.app.backup.wipe()
        assertTrue(
            host.app
                .snapshot()
                .activities
                .isEmpty(),
        )
        return archive
    }

    private fun restore(
        archive: ByteArray,
        password: String,
        exclusive: (() -> Unit, () -> Unit) -> Boolean = { task, released ->
            task()
            released()
            true
        },
    ): String {
        var message = ""
        workflow.restore(password, exclusive, { read -> read(ByteArraySource(archive)) }) { message = it }
        return message
    }

    @Test fun aRestoreReplacesTheDataAndReschedules() {
        val archive = backupOfDemoData("backup password")
        assertEquals("Backup restored", restore(archive, "backup password"))
        assertTrue(
            host.app
                .snapshot()
                .activities
                .isNotEmpty(),
        )
        assertEquals(1, host.rescheduled)
        assertFalse(host.paused)
        assertNull(progress.value)
        assertEquals(listOf("portfolio.sqlite"), directory.toFile().list()!!.filter { !it.endsWith("-wal") && !it.endsWith("-shm") })
    }

    @Test fun aWrongPasswordLeavesTheLiveDataAndExplainsWhy() {
        val archive = backupOfDemoData("backup password")
        host.app.demo.seed()
        assertEquals("Wrong password or damaged backup", restore(archive, "other password"))
        assertTrue(
            host.app
                .snapshot()
                .activities
                .isNotEmpty(),
        )
        assertEquals(0, host.rescheduled)
        assertFalse(host.paused)
    }

    @Test fun anotherConnectionStillWritingStopsTheSwap() {
        val archive = backupOfDemoData("backup password")
        // A second connection keeps the write-ahead log alive after the live one closes.
        BundledSqliteDatabase(file.toString()).use { other ->
            other.execute("INSERT OR REPLACE INTO application_settings VALUES ('held', 'x')")
            assertEquals("Valroot is still writing; retry the restore", restore(archive, "backup password"))
        }
        assertTrue(
            host.app
                .snapshot()
                .activities
                .isEmpty(),
            "the live data is unchanged and usable again",
        )
    }

    @Test fun aBusySyncDefersTheRestore() {
        val archive = backupOfDemoData("backup password")
        assertEquals("Wait for the active sync to finish, then restore", restore(archive, "backup password") { _, _ -> false })
        assertNull(progress.value)
    }
}
