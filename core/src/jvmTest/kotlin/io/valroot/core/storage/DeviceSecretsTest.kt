package io.valroot.core.storage

import io.valroot.core.application.PortfolioApplication
import io.valroot.core.exchange.ExchangeKind
import io.valroot.core.market.MarketFeedRepository
import io.valroot.core.model.AccountId
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceSecretsTest {
    private val account = AccountId("a")

    @AfterTest fun memoryKey() = DeviceSecretKeyFile.useMemory()

    @Test
    fun apiSecretsAreStoredSealedAndReadBack() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 0L }
            app.exchanges.setKey(ExchangeKind.BYBIT, account, "KEYKEYKEYKEY", "SECRETSECRETSECRET")
            app.evm.setAlchemyApiKey("alchemy-key-0123456789")
            val stored = MarketFeedRepository(db)
            for (key in listOf("bybit_key:a", "bybit_secret:a", "alchemy_api_key")) {
                val value = stored.deviceSetting(key)!!
                assertTrue(value.startsWith(SEALED_SECRET), key)
                assertFalse("KEYKEY" in value || "SECRETSECRET" in value || "alchemy-key" in value)
            }
            assertEquals("KEYKEYKEYKEY" to "SECRETSECRETSECRET", app.exchanges.key(ExchangeKind.BYBIT, account))
            assertEquals("alchemy-key-0123456789", app.evm.alchemyApiKey())
        }
    }

    @Test
    fun aPlaintextSecretIsSealedWhenReadAndAnUnreadableOneCountsAsMissing() {
        BundledSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 0L }
            val stored = MarketFeedRepository(db)
            stored.putDeviceSetting("coingecko_api_key", "CG-plain-0123456789")
            assertEquals("CG-plain-0123456789", app.market.coingeckoApiKey())
            assertTrue(stored.deviceSetting("coingecko_api_key")!!.startsWith(SEALED_SECRET))
            assertEquals("CG-plain-0123456789", app.market.coingeckoApiKey())
            // Sealed with another device's key: asked for again rather than failing.
            stored.putDeviceSetting("bybit_key:a", SEALED_SECRET + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
            stored.putDeviceSetting("bybit_secret:a", DeviceSecrets.seal("SECRETSECRETSECRET"))
            assertNull(app.exchanges.key(ExchangeKind.BYBIT, account))
            assertFalse(app.exchanges.hasKey(ExchangeKind.BYBIT, account))
        }
    }

    @Test
    fun theDesktopKeyFileIsOwnerOnlyAndKeepsSecretsReadableAcrossRestarts() {
        val directory = Files.createTempDirectory("valroot-secret-key")
        try {
            val file = directory.resolve("config").resolve("device-secret.key")
            DeviceSecretKeyFile.use(file)
            val sealed = DeviceSecrets.seal("SECRETSECRETSECRET")
            assertEquals(32, Files.size(file))
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
            // A restart reads the same key; another key cannot open the value.
            DeviceSecretKeyFile.use(file)
            assertEquals("SECRETSECRETSECRET", DeviceSecrets.open(sealed))
            DeviceSecretKeyFile.useMemory()
            assertNull(DeviceSecrets.open(sealed))
            Files.write(file, ByteArray(5))
            DeviceSecretKeyFile.use(file)
            assertFailsWith<IllegalStateException> { DeviceSecrets.seal("x") }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
