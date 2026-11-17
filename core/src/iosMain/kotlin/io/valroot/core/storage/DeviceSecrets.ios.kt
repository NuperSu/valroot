@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.valroot.core.storage

import io.valroot.core.crypto.Crypto
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy
import kotlin.io.encoding.Base64

/**
 * AES-256-GCM under a random key kept in the iOS Keychain, readable only on this device and after its first unlock (so
 * background refresh can use it). The sealed form matches Android and desktop: the nonce, then the ciphertext and tag.
 */
actual object DeviceSecrets {
    private const val NONCE = 12
    private const val SERVICE = "io.valroot.device-secrets"
    private const val ACCOUNT = "device-key-v1"
    private val lock = SynchronizedObject()

    actual fun seal(plain: String): String {
        val nonce = Crypto.randomBytes(NONCE)
        return SEALED_SECRET + Base64.encode(nonce + Crypto.aesGcmSeal(key(), nonce, ByteArray(0), plain.encodeToByteArray()))
    }

    actual fun open(sealed: String): String? =
        try {
            val bytes = Base64.decode(sealed.removePrefix(SEALED_SECRET))
            Crypto
                .aesGcmOpen(key(), bytes.copyOfRange(0, NONCE), ByteArray(0), bytes.copyOfRange(NONCE, bytes.size))
                .decodeToString()
        } catch (_: Exception) {
            null
        }

    private fun key(): ByteArray =
        synchronized(lock) {
            read() ?: run {
                val status = add(Crypto.randomBytes(32))
                check(status == errSecSuccess || status == errSecDuplicateItem) { "Keychain unavailable" }
                checkNotNull(read()) { "Keychain unavailable" }
            }
        }

    private fun read(): ByteArray? =
        query(kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne) { query ->
            memScoped {
                val result = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, result.ptr)
                if (status == errSecItemNotFound) return@memScoped null
                check(status == errSecSuccess) { "Keychain unavailable" }
                (CFBridgingRelease(result.value) as NSData).toByteArray()
            }
        }

    private fun add(key: ByteArray): Int {
        val data = CFBridgingRetain(key.toNSData())
        try {
            return query(kSecValueData to data, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly) {
                SecItemAdd(it, null)
            }
        } finally {
            data?.let { CFRelease(it) }
        }
    }

    /** Runs [block] with a Keychain query for this app's key, plus [extra] attributes. */
    private fun <T> query(
        vararg extra: Pair<CFTypeRef?, CFTypeRef?>,
        block: (CFDictionaryRef?) -> T,
    ): T {
        val service = CFBridgingRetain(SERVICE)
        val account = CFBridgingRetain(ACCOUNT)
        val dictionary = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        try {
            CFDictionaryAddValue(dictionary, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(dictionary, kSecAttrService, service)
            CFDictionaryAddValue(dictionary, kSecAttrAccount, account)
            extra.forEach { (name, value) -> CFDictionaryAddValue(dictionary, name, value) }
            return block(dictionary)
        } finally {
            CFRelease(dictionary)
            service?.let { CFRelease(it) }
            account?.let { CFRelease(it) }
        }
    }

    private fun NSData.toByteArray(): ByteArray {
        val size = length.toInt()
        val bytes = ByteArray(size)
        if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
        return bytes
    }

    private fun ByteArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }
}
