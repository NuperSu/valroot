package io.valroot.ui.host

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.storage.BundledSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivacyLockTest {
    private class MemoryStore : LockVerifierStore {
        var verifier: String? = null
        var failing = false
        override val hasLock: Boolean get() = verifier != null

        override fun read(): String = checkNotNull(verifier)

        override fun write(verifier: String) {
            check(!failing)
            this.verifier = verifier
        }

        override fun remove() {
            check(!failing)
            verifier = null
        }
    }

    private object Immediate : HostThreads {
        override fun data(block: () -> Unit) = block()

        override fun secret(block: () -> Unit) = block()

        override fun main(block: () -> Unit) = block()
    }

    private var now = 0L
    private val store = MemoryStore()
    private var removed = 0
    private var erased = 0

    private fun lock(app: PortfolioApplication? = null) =
        PrivacyLock(
            store = store,
            threads = Immediate,
            elapsedMillis = { now },
            application = { app },
            storeName = "Test storage",
            afterErase = {},
            onErased = { erased++ },
            onRemoved = { removed++ },
        )

    @Test fun aPasswordLocksAndUnlocksOnlyWithTheRightPassword() {
        val lock = lock()
        var message: String? = null
        lock.setLockPassword("short") { message = it }
        assertEquals("Use a password of at least 8 characters", message)
        lock.setLockPassword("long enough") { message = it }
        assertEquals("Privacy lock enabled", message)
        lock.lock()
        assertTrue(lock.locked.value)
        var result: io.valroot.ui.UnlockResult? = null
        lock.unlock("wrong password") { result = it }
        assertEquals("Incorrect password", result?.message)
        assertTrue(lock.locked.value)
        lock.unlock("long enough") { result = it }
        assertTrue(result!!.success)
        assertFalse(lock.locked.value)
    }

    @Test fun leavingLocksOnlyAfterTheDelay() {
        store.verifier =
            io.valroot.core.backup.LockPassword
                .create("long enough")
        val lock = lock().also { it.locked.value = false }
        lock.delayMillis = 60_000
        lock.left()
        now += 59_999
        lock.returned()
        assertFalse(lock.locked.value)
        lock.left()
        now += 60_000
        lock.returned()
        assertTrue(lock.locked.value)
        lock.locked.value = false
        lock.delayMillis = 0
        lock.left(choosing = true)
        assertFalse(lock.locked.value, "a file picker covering the app does not lock at once")
        lock.left()
        assertTrue(lock.locked.value)
    }

    @Test fun removingNeedsAnUnlockedScreenAndReportsStorageFailure() {
        store.verifier =
            io.valroot.core.backup.LockPassword
                .create("long enough")
        val lock = lock()
        var message: String? = null
        lock.removeLockPassword { message = it }
        assertEquals("Unlock first", message)
        lock.locked.value = false
        store.failing = true
        lock.removeLockPassword { message = it }
        assertEquals("Test storage unavailable; privacy lock was not removed", message)
        store.failing = false
        lock.removeLockPassword { message = it }
        assertEquals("Privacy lock removed", message)
        assertEquals(1, removed)
        assertFalse(lock.hasLock)
    }

    @Test fun eraseWipesDataTurnsRefreshOffAndRemovesTheLock() {
        val db = BundledSqliteDatabase(":memory:")
        val app = PortfolioApplication(db) { 1_700_000_000_000 }
        app.demo.seed()
        app.prices.setAutomaticRefresh(true)
        store.verifier =
            io.valroot.core.backup.LockPassword
                .create("long enough")
        val lock = lock(app)
        var message: String? = null
        lock.eraseAndRemoveLock { message = it }
        assertEquals("Local data erased and privacy lock removed", message)
        assertTrue(app.snapshot().activities.isEmpty())
        assertFalse(app.prices.automaticConsent() && app.settings.automaticRefresh() == true)
        assertNull(store.verifier)
        assertFalse(lock.locked.value)
        assertEquals(1, erased)
        db.close()
    }
}
