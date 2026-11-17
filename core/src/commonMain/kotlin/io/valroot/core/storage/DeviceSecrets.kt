package io.valroot.core.storage

/**
 * Encrypts API keys and secrets kept in this device's database with a key held outside it: the Android Keystore, or
 * on desktop a key file outside the data folder. A copy of the database alone does not reveal them.
 */
expect object DeviceSecrets {
    /** [plain] encrypted with this device's key, prefixed with [SEALED_SECRET]. */
    fun seal(plain: String): String

    /** The secret, or null when this device's key cannot open it (a database from another device, or a lost key). */
    fun open(sealed: String): String?
}

/** Prefix of sealed values. */
const val SEALED_SECRET = "sealed1:"
