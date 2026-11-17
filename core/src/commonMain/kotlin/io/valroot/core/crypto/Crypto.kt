package io.valroot.core.crypto

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom

/**
 * The few cryptographic primitives Valroot uses, on the platform's own implementation (the JDK on Android and desktop,
 * CryptoKit or CommonCrypto on Apple). Formats built on them (backups, the lock verifier, exchange signatures) live in
 * common code, so every platform reads and writes the same bytes.
 */
object Crypto {
    private val provider = CryptographyProvider.Default

    /** [count] bytes from the platform's secure random generator. */
    fun randomBytes(count: Int): ByteArray = CryptographyRandom.nextBytes(count)

    fun sha256(bytes: ByteArray): ByteArray = provider.get(SHA256).hasher().hashBlocking(bytes)

    fun hmacSha256(
        key: ByteArray,
        message: ByteArray,
    ): ByteArray =
        provider
            .get(HMAC)
            .keyDecoder(SHA256)
            .decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, key)
            .signatureGenerator()
            .generateSignatureBlocking(message)

    /**
     * PBKDF2-HMAC-SHA256 of [password], encoded as UTF-8 the way the JDK's `PBKDF2WithHmacSHA256` does (an unpaired
     * surrogate becomes `?`), so keys match what earlier versions derived.
     */
    fun pbkdf2Sha256(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        bytes: Int = 32,
    ): ByteArray {
        val encoded = utf8(password)
        try {
            return provider
                .get(PBKDF2)
                .secretDerivation(SHA256, iterations, (bytes * 8).bits, salt)
                .deriveSecretToByteArrayBlocking(encoded)
        } finally {
            encoded.fill(0)
        }
    }

    /** AES-GCM with a 128-bit tag and the caller's [nonce]: the ciphertext followed by the tag. */
    @OptIn(DelicateCryptographyApi::class)
    fun aesGcmSeal(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        plain: ByteArray,
    ): ByteArray = aesKey(key).cipher().encryptWithIvBlocking(nonce, plain, associatedData)

    /** Opens what [aesGcmSeal] produced; throws [AuthenticationFailed] when the key, nonce, data or tag do not match. */
    @OptIn(DelicateCryptographyApi::class)
    fun aesGcmOpen(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        sealed: ByteArray,
    ): ByteArray =
        try {
            aesKey(key).cipher().decryptWithIvBlocking(nonce, sealed, associatedData)
        } catch (failure: Exception) {
            throw AuthenticationFailed(failure)
        }

    /** Whether [a] and [b] are equal, in time that does not depend on where they differ. */
    fun constantTimeEquals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean {
        if (a.size != b.size) return false
        var difference = 0
        for (i in a.indices) difference = difference or (a[i].toInt() xor b[i].toInt())
        return difference == 0
    }

    private fun aesKey(key: ByteArray) = provider.get(AES.GCM).keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)

    private fun utf8(chars: CharArray): ByteArray {
        val out = ByteArray(chars.size * 3)
        var size = 0
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            val code: Int =
                when {
                    c.isHighSurrogate() && i + 1 < chars.size && chars[i + 1].isLowSurrogate() -> {
                        i++
                        0x10000 + ((c.code - 0xD800) shl 10) + (chars[i].code - 0xDC00)
                    }
                    c.isSurrogate() -> '?'.code
                    else -> c.code
                }
            when {
                code < 0x80 -> out[size++] = code.toByte()
                code < 0x800 -> {
                    out[size++] = (0xC0 or (code shr 6)).toByte()
                    out[size++] = (0x80 or (code and 0x3F)).toByte()
                }
                code < 0x10000 -> {
                    out[size++] = (0xE0 or (code shr 12)).toByte()
                    out[size++] = (0x80 or ((code shr 6) and 0x3F)).toByte()
                    out[size++] = (0x80 or (code and 0x3F)).toByte()
                }
                else -> {
                    out[size++] = (0xF0 or (code shr 18)).toByte()
                    out[size++] = (0x80 or ((code shr 12) and 0x3F)).toByte()
                    out[size++] = (0x80 or ((code shr 6) and 0x3F)).toByte()
                    out[size++] = (0x80 or (code and 0x3F)).toByte()
                }
            }
            i++
        }
        return out.copyOf(size).also { out.fill(0) }
    }
}

/** An AES-GCM open that failed: wrong key, or data that was changed or damaged. */
class AuthenticationFailed(
    cause: Throwable,
) : Exception("Authentication failed", cause)
