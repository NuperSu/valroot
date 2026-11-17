package io.valroot.core.backup

import io.valroot.core.crypto.Crypto
import kotlin.io.encoding.Base64

/** A password verifier for the privacy screen; it does not encrypt the database. */
object LockPassword {
    fun create(password: String): String {
        require(password.length >= 8)
        val salt = Crypto.randomBytes(16)
        return Base64.encode(salt) + ":" + Base64.encode(derive(password, salt))
    }

    fun verify(
        password: String,
        verifier: String,
    ): Boolean =
        runCatching {
            val fields = verifier.split(':')
            require(fields.size == 2)
            val salt = Base64.decode(fields[0])
            val expected = Base64.decode(fields[1])
            require(salt.size == 16 && expected.size == 32)
            Crypto.constantTimeEquals(expected, derive(password, salt))
        }.getOrDefault(false)

    private fun derive(
        password: String,
        salt: ByteArray,
    ): ByteArray {
        val chars = password.toCharArray()
        try {
            return Crypto.pbkdf2Sha256(chars, salt, 210_000)
        } finally {
            chars.fill('\u0000')
        }
    }
}
