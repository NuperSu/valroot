package io.valroot.ui.host

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** [DatabaseFiles] for the live database at [live] on a JVM host (Android or desktop). */
class JvmDatabaseFiles(
    private val live: Path,
) : DatabaseFiles {
    override fun createStaging(): String = Files.createTempFile(live.parent, "restore-", ".sqlite").toString()

    override fun flush(path: String) = FileChannel.open(Path.of(path), StandardOpenOption.WRITE).use { it.force(true) }

    override fun walIsEmpty(): Boolean =
        io.valroot.core.storage
            .walIsEmpty(live)

    override fun replaceLive(staging: String) {
        Files.move(Path.of(staging), live, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Deletes [path] with any write-ahead log and shared-memory file SQLite left beside it. */
    override fun delete(path: String) {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) Files.deleteIfExists(Path.of(path + suffix))
    }
}
