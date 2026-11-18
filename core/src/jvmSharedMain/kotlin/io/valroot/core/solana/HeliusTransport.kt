package io.valroot.core.solana

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI

private const val RATE_LIMIT_MESSAGE = "Helius's request limit was reached. Try again shortly."

/**
 * Spaces Helius requests across the process: RPC methods at least 120 ms apart (under the free plan's 10 per second)
 * and DAS asset lookups 500 ms apart (its 2 per second), with a cooldown after a rate limit.
 */
class HeliusPacer(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private var lastRequestAt: Long? = null
    private var lastAssetRequestAt: Long? = null
    private var rateLimitedUntil: Long? = null

    @Synchronized
    fun await(
        assets: Boolean,
        beforeRequest: () -> Unit,
    ) {
        if ((rateLimitedUntil ?: 0L) - clock() > 0) throw IllegalStateException(RATE_LIMIT_MESSAGE)
        val waits =
            listOfNotNull(
                lastRequestAt?.let { RPC_INTERVAL_MILLIS - (clock() - it) },
                if (assets) lastAssetRequestAt?.let { ASSET_INTERVAL_MILLIS - (clock() - it) } else null,
            )
        waits.maxOrNull()?.takeIf { it > 0 }?.let(sleep)
        beforeRequest()
        lastRequestAt = clock()
        if (assets) lastAssetRequestAt = lastRequestAt
    }

    @Synchronized
    fun rateLimited(delayMillis: Long) {
        rateLimitedUntil = clock() + delayMillis
    }

    private companion object {
        const val RPC_INTERVAL_MILLIS = 120L
        const val ASSET_INTERVAL_MILLIS = 500L
    }
}

/**
 * POSTs JSON-RPC bodies built by [Helius] to Helius's mainnet endpoint. Only [Helius.METHODS] are sent; the key from
 * [apiKey] goes into the query string and appears in no message. [beforeRequest] runs before each request and may
 * throw to stop a refresh that was cancelled.
 */
class HeliusTransport(
    private val apiKey: () -> String?,
    private val beforeRequest: () -> Unit = {},
    private val openConnection: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection },
    private val pacer: HeliusPacer = sharedPacer,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private companion object {
        val sharedPacer = HeliusPacer()
        val API_KEY = Regex("[A-Za-z0-9-]{20,100}")
        const val MISSING_KEY_MESSAGE = "Add a free Helius API key in Settings › API Keys."
        const val INVALID_KEY_MESSAGE = "The Helius API key is not valid. Replace it in Settings › API Keys."
        const val REJECTED_KEY_MESSAGE = "Helius rejected the API key. Replace it in Settings › API Keys."
        const val MAX_REQUEST_BYTES = 256 * 1024
        const val MAX_RESPONSE_BYTES = 32 * 1024 * 1024
        const val MAX_RETRY_AFTER_MILLIS = 60_000L
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 60_000
    }

    fun post(body: String): String {
        val method = Helius.methodOf(body)
        require(method in Helius.METHODS) { "Unsupported Helius request" }
        val payload = body.toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_REQUEST_BYTES) { "Helius request is too large." }
        val key = apiKey()?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException(MISSING_KEY_MESSAGE)
        check(key.matches(API_KEY)) { INVALID_KEY_MESSAGE }
        pacer.await(method == Helius.ASSETS, beforeRequest)
        // Failure messages from the connection layer may contain the keyed URL, so only fixed messages leave this function.
        val connection =
            try {
                openConnection("${Helius.URL}?api-key=$key")
            } catch (_: Exception) {
                throw IllegalStateException("Could not reach Helius.")
            }
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            when (connection.responseCode) {
                429 -> {
                    pacer.rateLimited(retryAfterMillis(connection))
                    throw IllegalStateException(RATE_LIMIT_MESSAGE)
                }
                401, 403 -> throw IllegalStateException(REJECTED_KEY_MESSAGE)
                !in 200..299 -> throw IllegalStateException("Helius is unavailable.")
            }
            return connection.inputStream.use { readCapped(it) }
        } catch (_: SocketTimeoutException) {
            throw IllegalStateException("Helius did not respond in time.")
        } catch (_: IOException) {
            throw IllegalStateException("Could not reach Helius.")
        } finally {
            connection.disconnect()
        }
    }

    private fun readCapped(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(output.size() + count <= MAX_RESPONSE_BYTES) { "Helius returned a response that is too large." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    /** Retry-After in seconds or as an HTTP date, capped at one minute; a missing or unreadable value uses the cap. */
    private fun retryAfterMillis(connection: HttpURLConnection): Long {
        val value = connection.getHeaderField("Retry-After")?.trim()
        val delay =
            if (value != null && value.isNotEmpty() && value.all { it in '0'..'9' }) {
                minOf(value.toLongOrNull() ?: MAX_RETRY_AFTER_MILLIS / 1000, MAX_RETRY_AFTER_MILLIS / 1000) * 1000
            } else {
                val at = connection.getHeaderFieldDate("Retry-After", -1)
                if (at < 0) MAX_RETRY_AFTER_MILLIS else at - wallClock()
            }
        return delay.coerceIn(0L, MAX_RETRY_AFTER_MILLIS)
    }
}
