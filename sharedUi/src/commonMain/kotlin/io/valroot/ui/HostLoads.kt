package io.valroot.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Runs [block] on the host's database dispatcher; null when it fails, so a screen can show that. Cancellation passes. */
internal suspend fun <T> PlatformActions.loadOrNull(block: () -> T): T? =
    try {
        withContext(dispatcher) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
