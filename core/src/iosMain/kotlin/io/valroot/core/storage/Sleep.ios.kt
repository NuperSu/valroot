package io.valroot.core.storage

internal actual fun sleepMillis(millis: Long) {
    platform.posix.usleep((millis * 1_000).toUInt())
}
