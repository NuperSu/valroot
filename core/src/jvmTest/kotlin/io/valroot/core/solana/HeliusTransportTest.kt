package io.valroot.core.solana

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeliusTransportTest {
    private val key = "00000000-1111-2222-3333-444444444444"
    private val wallet = "11111111111111111111111111111111"
    private var now = 0L

    private class Response(
        private val code: Int,
        private val payload: String = "{}",
        private val retryAfter: String? = null,
        private val failure: IOException? = null,
    ) : HttpURLConnection(URI("https://mainnet.helius-rpc.com").toURL()) {
        val sent = ByteArrayOutputStream()

        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy() = false

        override fun getResponseCode(): Int = failure?.let { throw it } ?: code

        override fun getOutputStream() = sent

        override fun getInputStream() = payload.byteInputStream()

        override fun getHeaderField(name: String?): String? = if (name == "Retry-After") retryAfter else null
    }

    private fun pacer() = HeliusPacer(clock = { now }, sleep = { now += it })

    private fun transport(
        apiKey: String? = key,
        pacer: HeliusPacer = pacer(),
        beforeRequest: () -> Unit = {},
        open: (String) -> HttpURLConnection = { Response(200) },
    ) = HeliusTransport(apiKey = { apiKey }, beforeRequest = beforeRequest, openConnection = open, pacer = pacer)

    @Test fun sendsEveryBuiltMethodWithTheKeyOnlyInTheQueryAndNoRedirects() {
        val requested = mutableListOf<String>()
        val connections = mutableListOf<Response>()
        val transport =
            transport(open = {
                requested += it
                Response(200).also(connections::add)
            })
        val bodies =
            listOf(
                Helius.historyBody(wallet, null),
                Helius.slotBody(),
                Helius.accountsBody(listOf(wallet)),
                Helius.tokenAccountsBody(wallet, SolanaOwnership.TOKEN_PROGRAM_IDS.first()),
                Helius.assetsBody(listOf(wallet)),
            )
        bodies.forEach(transport::post)
        assertEquals(Helius.METHODS, bodies.map { Helius.methodOf(it) }.toSet())
        assertTrue(requested.all { it == "https://mainnet.helius-rpc.com/?api-key=$key" })
        assertTrue(connections.none { it.instanceFollowRedirects })
        assertTrue(connections.none { key in it.sent.toString() }, "the key is never in a body")
    }

    @Test fun rejectsOtherMethodsAndMissingOrMalformedKeys() {
        assertFailsWith<IllegalArgumentException> {
            transport().post(
                """{"jsonrpc":"2.0","id":1,"method":"sendTransaction","params":[]}""",
            )
        }
        assertFailsWith<IllegalArgumentException> { transport().post("not json") }
        val missing = assertFailsWith<IllegalStateException> { transport(apiKey = null).post(Helius.slotBody()) }
        assertTrue(missing.message!!.contains("Settings › API Keys"))
        assertFailsWith<IllegalStateException> { transport(apiKey = "bad key?&x=1").post(Helius.slotBody()) }
    }

    @Test fun failuresNeverCarryTheKey() {
        val cases =
            listOf(
                Response(401),
                Response(500),
                Response(302),
                Response(200, failure = IOException("https://mainnet.helius-rpc.com/?api-key=$key refused")),
            )
        cases.forEach { response ->
            val failure = assertFailsWith<IllegalStateException> { transport(open = { response }).post(Helius.slotBody()) }
            assertFalse(key in failure.message.orEmpty())
        }
        val unreachable =
            assertFailsWith<IllegalStateException> { transport(open = { throw IOException("…api-key=$key") }).post(Helius.slotBody()) }
        assertFalse(key in unreachable.message.orEmpty())
        // RPC errors name only their code.
        val rpc = assertFailsWith<IllegalStateException> { Helius.parseSlot("""{"error":{"code":-32602,"message":"bad $key"}}""") }
        assertEquals("Helius could not answer (error -32602).", rpc.message)
    }

    @Test fun pacesRequestsHoldsACooldownAfterARateLimitAndStopsWhenCancelled() {
        val pacer = pacer()
        val transport = transport(pacer = pacer)
        transport.post(Helius.slotBody())
        transport.post(Helius.slotBody())
        assertEquals(120L, now, "RPC requests are at least 120 ms apart")
        transport.post(Helius.assetsBody(listOf(wallet)))
        transport.post(Helius.assetsBody(listOf(wallet)))
        assertEquals(740L, now, "asset lookups are at least 500 ms apart")

        val limited = transport(pacer = pacer, open = { Response(429, retryAfter = "5") })
        assertFailsWith<IllegalStateException> { limited.post(Helius.slotBody()) }
        val cooling = assertFailsWith<IllegalStateException> { transport(pacer = pacer).post(Helius.slotBody()) }
        assertTrue(cooling.message!!.contains("limit"))
        now += 5_000
        transport(pacer = pacer).post(Helius.slotBody())

        val cancelled =
            transport(beforeRequest = {
                throw io.valroot.core.application
                    .RefreshCancelled("Cancelled")
            })
        assertFailsWith<io.valroot.core.application.RefreshCancelled> { cancelled.post(Helius.slotBody()) }
    }
}
