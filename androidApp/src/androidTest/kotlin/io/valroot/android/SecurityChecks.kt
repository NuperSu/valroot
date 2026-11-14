package io.valroot.android

import android.content.Context
import io.valroot.core.backup.LockPassword
import io.valroot.core.bitcoin.bitcoinMainnetAddress
import io.valroot.core.bitcoin.bitcoinWallet
import io.valroot.core.bitcoin.descriptorChecksum

/** Keystore-backed lock storage, lock password verification and public Bitcoin descriptor derivation. */
internal fun securityChecks(context: Context): List<ReliabilityCheck> =
    listOf<ReliabilityCheck>(
        "secureLockRoundTripAndTamper" to {
            val alias = "io.valroot.test." + java.util.UUID.randomUUID()
            val preferences = context.getSharedPreferences(alias, 0)
            try {
                val store = SecureLockStore(preferences, alias)
                val verifier = LockPassword.create("fixture-password")
                store.write(verifier)
                check(store.hasLock && store.read() == verifier)
                val bytes =
                    java.util.Base64
                        .getDecoder()
                        .decode(preferences.getString("secure_verifier_v1", null))
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                check(
                    preferences
                        .edit()
                        .putString(
                            "secure_verifier_v1",
                            java.util.Base64
                                .getEncoder()
                                .encodeToString(bytes),
                        ).commit(),
                )
                check(runCatching { store.read() }.isFailure)
            } finally {
                preferences.edit().clear().commit()
                java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                    load(null)
                    deleteEntry(alias)
                }
            }
        },
        // Uses the app's own Keystore key (the one the app seals with), so the key is kept afterwards.
        "apiSecretsSealedWithKeystore" to {
            withTemporaryDatabase(context.cacheDir) { file ->
                io.valroot.core.storage.AndroidSqliteDatabase(file.path).use { db ->
                    val app = io.valroot.core.application.PortfolioApplication(db, System::currentTimeMillis)
                    val account = io.valroot.core.model.AccountId("reliability")
                    val kind = io.valroot.core.exchange.ExchangeKind.BYBIT
                    app.exchanges.setKey(kind, account, "KEYKEYKEYKEY", "SECRETSECRETSECRET")
                    val stored = io.valroot.core.market.MarketFeedRepository(db)
                    val sealed = checkNotNull(stored.deviceSetting("bybit_secret:reliability"))
                    check(sealed.startsWith(io.valroot.core.storage.SEALED_SECRET) && "SECRETSECRET" !in sealed)
                    check(app.exchanges.key(kind, account) == "KEYKEYKEYKEY" to "SECRETSECRETSECRET")
                    // A value saved before sealing is sealed when read.
                    stored.putDeviceSetting("alchemy_api_key", "alchemy-plain-0123456789")
                    check(app.evm.alchemyApiKey() == "alchemy-plain-0123456789")
                    check(stored.deviceSetting("alchemy_api_key")!!.startsWith(io.valroot.core.storage.SEALED_SECRET))
                    // A damaged value reads as missing.
                    val bytes = java.util.Base64.getDecoder().decode(sealed.removePrefix(io.valroot.core.storage.SEALED_SECRET))
                    bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                    stored.putDeviceSetting(
                        "bybit_secret:reliability",
                        io.valroot.core.storage.SEALED_SECRET + java.util.Base64.getEncoder().encodeToString(bytes),
                    )
                    check(!app.exchanges.hasKey(kind, account))
                }
            }
        },
        "bitcoinPublicWalletDerivation" to {
            // BIP 84 account zpub serialized with mainnet xpub version bytes.
            val xpub =
                "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvP" +
                    "EbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V"
            val wallet = bitcoinWallet(xpub)
            check(wallet.address(0, 0) == "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu")
            check(wallet.address(0, 1) == "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g")
            check(wallet.address(1, 0) == "bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el")
            check(bitcoinWallet("pkh($xpub/0/*)").address(0, 0) == "1JaUQDVNRdhfNsVncGkXedaPSM5Gc54Hso")
            check(bitcoinWallet("sh(wpkh($xpub/0/*))").address(0, 0) == "3GtVZYzsKF6Feikdjd4bDyPdAiyeHANY9b")
            check(descriptorChecksum("raw(deadbeef)") == "89f8spxm")
            check(bitcoinMainnetAddress(wallet.address(0, 0)) == wallet.address(0, 0))
            check(runCatching { bitcoinWallet("wpkh($xpub/0/*)#aaaaaaaa") }.isFailure)
            check(runCatching { bitcoinMainnetAddress("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyq") }.isFailure)
        },
        "passwordVerification" to {
            val verifier = LockPassword.create("fixture-password")
            check(LockPassword.verify("fixture-password", verifier))
            check(!LockPassword.verify("wrong-password", verifier))
            check(!LockPassword.verify("fixture-password", "corrupt"))
        },
    )
