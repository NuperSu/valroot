package io.valroot.core.application

import io.valroot.core.market.MarketFeedRepository
import io.valroot.core.storage.DeviceSecrets
import io.valroot.core.storage.SEALED_SECRET

/**
 * API keys and secrets: device settings (never in backups) sealed with this device's key. A value this device cannot
 * open reads as missing, so the app asks for it again.
 */
internal class SecretSettings(
    private val settings: MarketFeedRepository,
) {
    fun get(key: String): String? {
        val stored = settings.deviceSetting(key) ?: return null
        return if (stored.startsWith(SEALED_SECRET)) DeviceSecrets.open(stored) else null
    }

    fun put(
        key: String,
        value: String?,
    ) = settings.putDeviceSetting(key, value?.let(DeviceSecrets::seal))
}
