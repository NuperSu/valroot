package io.valroot.android

import androidx.compose.runtime.MutableState
import io.valroot.ui.BiometricUnlock
import io.valroot.ui.UnlockResult
import io.valroot.ui.host.PrivacyLock
import kotlinx.coroutines.flow.update

/**
 * The shared [PrivacyLock] with Android's verifier storage (an Android Keystore key) and fingerprint unlock, which only
 * Android has. The lock delay counts time asleep, so a phone left locked overnight locks the app on return.
 */
internal class AndroidLock(
    private val state: AndroidState,
    sync: AndroidSync,
) {
    private val lockStore = SecureLockStore(state.preferences)
    private val biometrics = BiometricLock(state.activity, state.preferences)
    private val shared =
        PrivacyLock(
            store = lockStore,
            threads = state.threads,
            elapsedMillis = android.os.SystemClock::elapsedRealtime,
            application = { state.app.value },
            storeName = "Secure storage",
            afterErase = { sync.reconcileRefreshPreferences(it) },
            onErased = { state.dataRevision.update { it + 1 } },
            onRemoved = { biometrics.disable() },
        )

    val locked: MutableState<Boolean> get() = shared.locked
    val hasLock: Boolean get() = shared.hasLock

    var delayMillis: Long
        get() = shared.delayMillis
        set(value) {
            shared.delayMillis = value
        }

    /** True while the fingerprint prompt is showing, so the app does not lock behind it. */
    val isPrompting: Boolean get() = biometrics.prompting

    fun biometricUnlock(): BiometricUnlock = biometrics.status(lockStore.hasLock)

    fun setBiometricUnlock(
        enabled: Boolean,
        onResult: (String) -> Unit,
    ) {
        if (!enabled) {
            biometrics.disable()
            onResult("Fingerprint unlock off")
        } else if (!lockStore.hasLock || locked.value) {
            onResult("Turn on Passcode Lock first")
        } else {
            biometrics.enable { error ->
                onResult(if (error == null) "Fingerprint unlock on" else error.ifEmpty { "Fingerprint unlock not changed" })
            }
        }
    }

    fun unlockWithBiometrics(onResult: (UnlockResult) -> Unit) {
        if (biometricUnlock() != BiometricUnlock.ON) {
            onResult(UnlockResult(false, "Fingerprint unlock is off. Unlock with your password."))
            return
        }
        biometrics.unlock { error ->
            if (error == null) shared.unlocked()
            // An empty message means the user chose the password instead; nothing to report.
            onResult(UnlockResult(error == null, error?.ifEmpty { null }))
        }
    }

    fun setLockDelay(millis: Long) = shared.setLockDelay(millis)

    /**
     * The app went to the background: lock now, or remember when, depending on the delay. While [choosing] a file the
     * picker covers the app; with no delay that locks on every return from a picker, since leaving the app through it
     * cannot be told apart.
     */
    fun left(choosing: Boolean = false) = shared.left(choosing)

    fun returned() = shared.returned()

    fun lock() = shared.lock()

    fun removeLockPassword(onResult: (String) -> Unit) = shared.removeLockPassword(onResult)

    fun eraseAndRemoveLock(onResult: (String) -> Unit) = shared.eraseAndRemoveLock(onResult)

    fun unlock(
        password: String,
        onResult: (UnlockResult) -> Unit,
    ) = shared.unlock(password, onResult)

    fun setLockPassword(
        password: String,
        onResult: (String) -> Unit,
    ) = shared.setLockPassword(password, onResult)
}
