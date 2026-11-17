package io.valroot.ui.host

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.valroot.core.application.DEFAULT_LOCK_DELAY_MILLIS
import io.valroot.core.application.PortfolioApplication
import io.valroot.core.backup.LockPassword
import io.valroot.ui.UnlockResult

/** Where a host keeps the privacy-lock password verifier: the Android Keystore, a sealed file on desktop. */
interface LockVerifierStore {
    val hasLock: Boolean

    fun read(): String

    fun write(verifier: String)

    fun remove()

    /** Rewrites a verifier kept in an older form; does nothing when it is current. */
    fun migrate(verifier: String) {}
}

/**
 * The privacy lock every host shares: the locked state the screens show, the password verifier, the delay before an app
 * that left locks, and recovery by erasing local data. It protects the screen, not the database.
 *
 * Verifier work runs through [secretWork] and database work through [dataWork]; results return through [main]. A host
 * with fingerprint or face unlock clears the lock with [unlocked] and hears of the lock's removal through [onRemoved].
 */
class PrivacyLock(
    private val store: LockVerifierStore,
    private val threads: HostThreads,
    private val elapsedMillis: () -> Long,
    private val application: () -> PortfolioApplication?,
    /** Names the verifier storage in failure messages, such as "Secure storage". */
    private val storeName: String,
    /** Runs after an erase on the data thread: the erase turns automatic refresh off, which the host's schedule follows. */
    private val afterErase: (PortfolioApplication) -> Unit,
    private val onErased: () -> Unit,
    private val onRemoved: () -> Unit = {},
) {
    val locked: MutableState<Boolean> = mutableStateOf(store.hasLock)
    val hasLock: Boolean get() = store.hasLock
    private var leftAt: Long? = null

    /** The lock delay; kept in memory so leaving and returning never waits on the database. */
    var delayMillis: Long = DEFAULT_LOCK_DELAY_MILLIS

    fun setLockDelay(millis: Long) {
        delayMillis = millis
        threads.data { runCatching { application()?.settings?.setLockDelay(millis) } }
    }

    /**
     * The app went to the background or lost focus: lock now, or remember when, depending on the delay. While [choosing]
     * a file the picker covers the app, so it only remembers when and [returned] applies the delay.
     */
    fun left(choosing: Boolean = false) {
        if (delayMillis == 0L && !choosing) lock() else leftAt = elapsedMillis()
    }

    /** The app came back: lock if it was away at least as long as the delay. */
    fun returned() {
        val away = leftAt?.let { elapsedMillis() - it }
        leftAt = null
        if (away != null && away >= delayMillis) lock()
    }

    fun lock() {
        if (store.hasLock) locked.value = true
    }

    /** Clears the lock after the host verified the user another way, such as a fingerprint. Main thread. */
    fun unlocked() {
        locked.value = false
    }

    fun unlock(
        password: String,
        onResult: (UnlockResult) -> Unit,
    ) {
        threads.secret {
            val result =
                runCatching {
                    val verifier = store.read()
                    if (LockPassword.verify(password, verifier)) {
                        store.migrate(verifier)
                        UnlockResult(true)
                    } else {
                        UnlockResult(false, "Incorrect password")
                    }
                }.getOrElse { UnlockResult(false, "Secure privacy lock storage is unavailable or damaged") }
            threads.main {
                if (result.success) locked.value = false
                onResult(result)
            }
        }
    }

    fun setLockPassword(
        password: String,
        onResult: (String) -> Unit,
    ) {
        threads.secret {
            val success =
                runCatching {
                    check(!store.hasLock)
                    store.write(LockPassword.create(password))
                }.isSuccess
            threads.main {
                onResult(
                    when {
                        success -> "Privacy lock enabled"
                        password.length < 8 -> "Use a password of at least 8 characters"
                        else -> "$storeName unavailable; privacy lock was not changed"
                    },
                )
            }
        }
    }

    fun removeLockPassword(onResult: (String) -> Unit) {
        if (locked.value) {
            onResult("Unlock first")
            return
        }
        threads.secret {
            val success = runCatching { store.remove() }.isSuccess
            threads.main {
                if (success) onRemoved()
                onResult(if (success) "Privacy lock removed" else "$storeName unavailable; privacy lock was not removed")
            }
        }
    }

    /**
     * Recovery for a forgotten lock password or a lost lock key: erases this device's Valroot data, turns automatic
     * refresh off and removes the lock.
     */
    fun eraseAndRemoveLock(onResult: (String) -> Unit) {
        threads.data {
            val success =
                runCatching {
                    application()?.let { app ->
                        app.backup.wipe()
                        app.prices.setAutomaticRefresh(false)
                        afterErase(app)
                    }
                    store.remove()
                }.isSuccess
            threads.main {
                if (success) {
                    onRemoved()
                    locked.value = false
                    onErased()
                }
                onResult(if (success) "Local data erased and privacy lock removed" else "Could not finish erasing; retry")
            }
        }
    }
}

/**
 * How shared host workflows reach a host's threads: [data] runs database work in turn with the screens' queries,
 * [secret] runs slow verifier and key work off the database thread, and [main] returns to the UI thread.
 */
interface HostThreads {
    fun data(block: () -> Unit)

    fun secret(block: () -> Unit)

    fun main(block: () -> Unit)
}
