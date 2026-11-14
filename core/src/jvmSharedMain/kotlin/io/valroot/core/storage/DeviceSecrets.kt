package io.valroot.core.storage

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** This device's AES key for API secrets: the Android Keystore on Android, a key file on desktop. */
internal expect fun deviceSecretKey(): SecretKey

/** AES-256-GCM; the cipher chooses each nonce, which the Android Keystore requires. */
actual object DeviceSecrets {
    private const val NONCE = 12
    private const val TAG_BITS = 128

    actual fun seal(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deviceSecretKey())
        val nonce = cipher.iv
        check(nonce.size == NONCE) { "Unexpected nonce size" }
        return SEALED_SECRET + Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plain.encodeToByteArray()))
    }

    actual fun open(sealed: String): String? =
        try {
            val bytes = Base64.getDecoder().decode(sealed.removePrefix(SEALED_SECRET))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deviceSecretKey(), GCMParameterSpec(TAG_BITS, bytes, 0, NONCE))
            cipher.doFinal(bytes, NONCE, bytes.size - NONCE).decodeToString()
        } catch (_: Exception) {
            null
        }
}
