package io.valroot.core.backup

import io.valroot.core.crypto.AuthenticationFailed
import io.valroot.core.crypto.Crypto

/**
 * Password-based streaming encryption around the backup body. The envelope is described in `docs/backup-format.md`:
 * a versioned header with the KDF parameters, then authenticated chunks whose nonces carry a counter and a final-chunk
 * flag, so a wrong password, tampering, reordering and truncation are all rejected.
 */
object BackupEncryption {
    private val magic = "VLRBK".encodeToByteArray()

    /** Backups made before the app was renamed from Polion; still decrypted. */
    private val legacyMagic = "PLNBK".encodeToByteArray()
    private const val VERSION: Byte = 3
    private const val KDF_PBKDF2_SHA256: Byte = 1
    private const val ITERATIONS = 600_000
    private const val HEADER = 5 + 1 + 1 + 4 + 16 + 7
    private const val CHUNK = 64 * 1024
    private const val TAG = 16

    private fun key(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray {
        require(password.isNotEmpty())
        return Crypto.pbkdf2Sha256(password, salt, iterations)
    }

    private fun nonce(
        prefix: ByteArray,
        counter: Int,
        last: Boolean,
    ): ByteArray = prefix + int(counter) + byteArrayOf(if (last) 1 else 0)

    private fun int(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun int(
        bytes: ByteArray,
        at: Int,
    ) = ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or
        ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)

    /** Returns a sink that encrypts into [output]; [EncryptingSink.close] writes the final chunk and must be called. */
    fun encrypt(
        output: ByteSink,
        password: CharArray,
    ): EncryptingSink {
        val salt = Crypto.randomBytes(16)
        val prefix = Crypto.randomBytes(7)
        val header = magic + byteArrayOf(VERSION, KDF_PBKDF2_SHA256) + int(ITERATIONS) + salt + prefix
        check(header.size == HEADER)
        val key = key(password, salt, ITERATIONS)
        output.write(header)
        return object : EncryptingSink {
            private val buffer = ByteArray(CHUNK)
            private var filled = 0
            private var counter = 0
            private var closed = false

            private fun emit(last: Boolean) {
                check(counter != Int.MAX_VALUE) { "Backup is too large" }
                val sealed = Crypto.aesGcmSeal(key, nonce(prefix, counter, last), header, buffer.copyOf(filled))
                output.write(int(sealed.size))
                output.write(sealed)
                counter++
                filled = 0
            }

            override fun write(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ) {
                check(!closed)
                var at = offset
                val end = offset + length
                while (at < end) {
                    // A full chunk is sealed only once more data arrives, so the final chunk is always flagged as last.
                    if (filled == CHUNK) emit(last = false)
                    val count = minOf(CHUNK - filled, end - at)
                    bytes.copyInto(buffer, filled, at, at + count)
                    filled += count
                    at += count
                }
            }

            override fun close() {
                if (closed) return
                closed = true
                emit(last = true)
            }
        }
    }

    /** Returns the decrypted body of [input]; a wrong password or damaged file fails with [IllegalArgumentException]. */
    fun decrypt(
        input: ByteSource,
        password: CharArray,
    ): ByteSource {
        val header = ByteArray(HEADER)
        // A file shorter than the header is not a backup, rather than a damaged one.
        require(runCatching { input.readFully(header) }.getOrDefault(false)) { "This is not a Valroot backup" }
        require(
            header.copyOfRange(0, 5).let { it.contentEquals(magic) || it.contentEquals(legacyMagic) },
        ) { "This is not a Valroot backup" }
        require(header[5] == VERSION) { "This backup was made by an unsupported Valroot version" }
        require(header[6] == KDF_PBKDF2_SHA256) { "Unsupported backup key derivation" }
        val iterations = int(header, 7)
        require(iterations in 100_000..10_000_000) { "Unsupported backup key derivation" }
        val salt = header.copyOfRange(11, 27)
        val prefix = header.copyOfRange(27, 34)
        val key = key(password, salt, iterations)
        return object : ByteSource {
            private var plain = ByteArray(0)
            private var position = 0
            private var counter = 0
            private var finished = false
            private var nextLength: Int? = readLength()

            private fun readLength(): Int? {
                val bytes = ByteArray(4)
                if (!input.readFully(bytes)) return null
                return int(bytes, 0).also { require(it in TAG..CHUNK + TAG) { "Damaged backup" } }
            }

            private fun nextChunk() {
                val length = requireNotNull(nextLength) { "Backup ends unexpectedly" }
                val sealed = ByteArray(length)
                require(input.readFully(sealed)) { "Backup ends unexpectedly" }
                nextLength = readLength()
                val last = nextLength == null
                plain =
                    try {
                        Crypto.aesGcmOpen(key, nonce(prefix, counter, last), header, sealed)
                    } catch (_: AuthenticationFailed) {
                        throw IllegalArgumentException(if (counter == 0) "Wrong password or damaged backup" else "Damaged backup")
                    }
                position = 0
                counter++
                finished = last
            }

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                while (position == plain.size) {
                    if (finished) return -1
                    nextChunk()
                }
                val count = minOf(length, plain.size - position)
                plain.copyInto(buffer, offset, position, position + count)
                position += count
                return count
            }
        }
    }
}
