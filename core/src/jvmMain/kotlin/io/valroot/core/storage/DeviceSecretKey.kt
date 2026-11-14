package io.valroot.core.storage

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Where the desktop keeps its key for API secrets. Until [use] is called the key exists only in this process, so
 * secrets saved then cannot be read after a restart; the desktop app calls [use] before it opens the database.
 */
object DeviceSecretKeyFile {
    private const val KEY_BYTES = 32
    private var file: Path? = null
    private var cached: SecretKey? = null

    /** Uses the key in [path], created with owner-only access when missing. */
    @Synchronized
    fun use(path: Path) {
        file = path
        cached = null
    }

    /** Back to a key held only in this process (tests). */
    @Synchronized
    internal fun useMemory() {
        file = null
        cached = null
    }

    @Synchronized
    internal fun key(): SecretKey = cached ?: SecretKeySpec(file?.let(::readOrCreate) ?: random(), "AES").also { cached = it }

    private fun random() = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)

    private fun readOrCreate(path: Path): ByteArray {
        if (!Files.exists(path)) {
            val directory = path.toAbsolutePath().parent
            Files.createDirectories(directory)
            runCatching { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")) }
            val temporary = Files.createTempFile(directory, ".device-secret", ".tmp")
            try {
                runCatching { Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------")) }
                Files.write(temporary, random())
                // A link never replaces an existing file: when another process created the key meanwhile, its key wins.
                try {
                    Files.createLink(path, temporary)
                } catch (_: FileAlreadyExistsException) {
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
        val bytes = Files.readAllBytes(path)
        check(bytes.size == KEY_BYTES) { "The device secret key file is damaged" }
        return bytes
    }
}

internal actual fun deviceSecretKey(): SecretKey = DeviceSecretKeyFile.key()
