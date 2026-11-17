package io.valroot.core.crypto

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [Crypto] must produce exactly what the JDK did before it, so existing backups, verifiers and signatures still work. */
class CryptoCompatibilityTest {
    private val salt = ByteArray(16) { it.toByte() }

    @Test fun pbkdf2MatchesTheJdkForEveryKindOfPassword() {
        for (password in listOf("correct horse", "пароль-Ünïcødé", "emoji 🔐 key", "lone \uD800 surrogate", "")) {
            val jdk =
                if (password.isEmpty()) {
                    null
                } else {
                    SecretKeyFactory
                        .getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(PBEKeySpec(password.toCharArray(), salt, 1_000, 256))
                        .encoded
                }
            val ours = Crypto.pbkdf2Sha256(password.toCharArray(), salt, 1_000)
            if (jdk != null) assertContentEquals(jdk, ours, "password \"$password\"")
        }
    }

    @Test fun aesGcmMatchesTheJdkAndRejectsTampering() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val nonce = ByteArray(12) { (it + 3).toByte() }
        val aad = "header".encodeToByteArray()
        val plain = ByteArray(70_000) { (it % 251).toByte() }
        val jdk =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(plain)
            }
        val sealed = Crypto.aesGcmSeal(key, nonce, aad, plain)
        assertContentEquals(jdk, sealed)
        assertContentEquals(plain, Crypto.aesGcmOpen(key, nonce, aad, sealed))
        sealed[10] = (sealed[10].toInt() xor 1).toByte()
        assertFailsWith<AuthenticationFailed> { Crypto.aesGcmOpen(key, nonce, aad, sealed) }
        assertFailsWith<AuthenticationFailed> { Crypto.aesGcmOpen(key, nonce, "other".encodeToByteArray(), jdk) }
    }

    @Test fun hmacAndSha256MatchTheJdk() {
        val key = "secret".encodeToByteArray()
        val message = "timestamp=1&recvWindow=5000".encodeToByteArray()
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)
        assertContentEquals(mac, Crypto.hmacSha256(key, message))
        assertContentEquals(MessageDigest.getInstance("SHA-256").digest(message), Crypto.sha256(message))
    }

    @Test fun aLockVerifierMadeByTheJdkStillVerifies() {
        val salt = ByteArray(16) { (it * 5).toByte() }
        val derived =
            SecretKeyFactory
                .getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec("ключ-password".toCharArray(), salt, 210_000, 256))
                .encoded
        val verifier =
            java.util.Base64
                .getEncoder()
                .encodeToString(salt) + ":" +
                java.util.Base64
                    .getEncoder()
                    .encodeToString(derived)
        assertTrue(
            io.valroot.core.backup.LockPassword
                .verify("ключ-password", verifier),
        )
        assertFalse(
            io.valroot.core.backup.LockPassword
                .verify("ключ-passwore", verifier),
        )
    }

    @Test fun aBackupChunkOpensWithTheJdkUsingTheDocumentedLayout() {
        val out = java.io.ByteArrayOutputStream()
        val sink =
            io.valroot.core.backup.BackupEncryption.encrypt(
                io.valroot.core.backup
                    .ByteSink { bytes, offset, length -> out.write(bytes, offset, length) },
                "пароль".toCharArray(),
            )
        val body = "VALROOT BACKUP BODY".encodeToByteArray()
        sink.write(body, 0, body.size)
        sink.close()
        val file = out.toByteArray()
        // Header: magic(5) version(1) kdf(1) iterations(4) salt(16) nonce prefix(7); then length(4) + sealed chunk.
        val header = file.copyOfRange(0, 34)
        val iterations =
            java.nio.ByteBuffer
                .wrap(header, 7, 4)
                .int
        val key =
            SecretKeyFactory
                .getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec("пароль".toCharArray(), header.copyOfRange(11, 27), iterations, 256))
                .encoded
        val length =
            java.nio.ByteBuffer
                .wrap(file, 34, 4)
                .int
        val nonce = header.copyOfRange(27, 34) + byteArrayOf(0, 0, 0, 0, 1)
        val plain =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(header)
                doFinal(file, 38, length)
            }
        assertContentEquals(body, plain)
    }

    @Test fun constantTimeEqualsComparesContent() {
        assertTrue(Crypto.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(Crypto.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertFalse(Crypto.constantTimeEquals(byteArrayOf(1), byteArrayOf(1, 2)))
    }
}
