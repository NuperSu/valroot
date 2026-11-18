package io.valroot.desktop

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Another Valroot window already has this data folder open. */
internal class DataFolderInUse : IllegalStateException("Valroot is already open. Use that window, or close it first.")

/**
 * An exclusive operating-system lock on the data folder, held for as long as one window uses it. A second process
 * (or a second host in this one) cannot open the database beside it, so a restore never replaces the file under a
 * connection it cannot close. The lock is released when [close] runs or the process ends.
 */
internal class DesktopInstanceLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        const val FILE_NAME = "instance.lock"

        /** Takes the lock on [directory], or throws [DataFolderInUse] when another window holds it. */
        fun acquire(directory: Path): DesktopInstanceLock {
            val channel =
                FileChannel.open(directory.resolve(FILE_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock =
                try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
            if (lock == null) {
                channel.close()
                throw DataFolderInUse()
            }
            return DesktopInstanceLock(channel, lock)
        }
    }
}
