package com.monostr.tips.watcher

import com.monostr.monero.Address
import com.monostr.monero.hexToBytes
import com.monostr.monero.toHex
import com.monostr.tips.FakeSigner
import com.monostr.tips.Kinds
import com.monostr.tips.assertThrowsSuspend
import com.monostr.tips.event.EventId
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WatcherClientTest {
    private val server = MockWebServer()
    private val signer = FakeSigner("a".repeat(64))
    private var now = 1_700_000_000L
    private lateinit var client: WatcherClient

    private val infoJson = """{"pubkey":"${"d".repeat(64)}","relays":["wss://relay.monostr.com"],"network":"stagenet","height":1234567,"version":"0.1.0"}"""

    @BeforeEach
    fun start() {
        server.start()
        client = WatcherClient(server.url("/").toString(), signer, clock = { now })
    }

    @AfterEach
    fun stop() = server.shutdown()

    @Test
    fun `info parses the response and hits GET v1 info`() = runTest {
        server.enqueue(MockResponse().setBody(infoJson).addHeader("Content-Type", "application/json"))
        val info = client.info()
        assertEquals(WatcherInfo("d".repeat(64), listOf("wss://relay.monostr.com"), "stagenet", 1234567, "0.1.0"), info)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1/info", req.path)
    }

    @Test
    fun `info is cached for 24h and refreshed afterwards`() = runTest {
        server.enqueue(MockResponse().setBody(infoJson))
        server.enqueue(MockResponse().setBody(infoJson.replace("1234567", "1234999")))
        assertEquals(1234567, client.info().height)
        now += WatcherClient.INFO_MAX_AGE - 1
        assertEquals(1234567, client.info().height)
        assertEquals(1, server.requestCount)
        now += 1
        assertEquals(1234999, client.info().height)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `info ignores unknown fields`() = runTest {
        server.enqueue(MockResponse().setBody(infoJson.dropLast(1) + ""","future":true}"""))
        assertEquals("0.1.0", client.info().version)
    }

    @Test
    fun `http error with json body maps to Http with server message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"too many requests"}"""))
        val e = assertThrowsSuspend<WatcherException.Http> { client.info() }
        assertEquals(429, e.status)
        assertEquals("too many requests", e.message)
    }

    @Test
    fun `http error without json maps to Http with status text`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("<html>boom</html>"))
        val e = assertThrowsSuspend<WatcherException.Http> { client.info() }
        assertEquals(500, e.status)
        assertEquals("HTTP 500", e.message)
    }

    @Test
    fun `invalid json maps to Protocol`() = runTest {
        server.enqueue(MockResponse().setBody("""{"pubkey":"x"}"""))
        assertThrowsSuspend<WatcherException.Protocol> { client.info() }
    }

    @Test
    fun `IO failure while reading the body maps to Network`() = runTest {
        // Body stays under MAX_BODY_BYTES so the size cap does not pre-empt this: the point here
        // is a genuine IOException from a connection dropped mid-body, not the cap from finding 3.
        server.enqueue(
            MockResponse()
                .setBody("x".repeat(40_000))
                .throttleBody(1024, 10, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val e = assertThrowsSuspend<WatcherException.Network> { client.info() }
        assertTrue(e.cause is java.io.IOException)
    }

    @Test
    fun `info hits v1 info beneath a base url path`() = runTest {
        val pathClient = WatcherClient(server.url("/api/").toString(), signer, clock = { now })
        server.enqueue(MockResponse().setBody(infoJson).addHeader("Content-Type", "application/json"))
        pathClient.info()
        val req = server.takeRequest()
        assertEquals("/api/v1/info", req.path)
    }

    @Test
    fun `response body over the cap maps to Protocol`() = runTest {
        val oversized = "{" + "x".repeat((64 * 1024).toInt())
        server.enqueue(MockResponse().setBody(oversized))
        assertThrowsSuspend<WatcherException.Protocol> { client.info() }
    }

    @Test
    fun `response body just under the cap still parses`() = runTest {
        val padding = " ".repeat((64 * 1024) - infoJson.toByteArray(Charsets.UTF_8).size - 1)
        server.enqueue(MockResponse().setBody(padding + infoJson))
        val info = client.info()
        assertEquals("0.1.0", info.version)
    }

    @Test
    fun `unreachable server maps to Network`() = runTest {
        val deadServer = MockWebServer().also { it.start() }
        val url = deadServer.url("/").toString()
        deadServer.shutdown()
        val dead = WatcherClient(url, signer, clock = { now })
        val e = assertThrowsSuspend<WatcherException.Network> { dead.info() }
        assertTrue(e.cause is java.io.IOException)
    }

    private val mainnet = "42nsXK8WbVGTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnQdF28r3"
    private val viewKey = "cea3c5dfea43f31197bd4b7166da59f90af44b4afac2b0732d9fcbe2b7fa0c06"

    @Test
    fun `register posts address and view key with a NIP-98 header and returns the watcher pubkey`() = runTest {
        server.enqueue(MockResponse().setBody("""{"watcher_pubkey":"${"d".repeat(64)}"}"""))
        val pubkey = client.register(Address.parse(mainnet), viewKey.hexToBytes())
        assertEquals("d".repeat(64), pubkey)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/accounts", req.path)
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = req.body.readUtf8()
        assertEquals("""{"address":"$mainnet","view_key":"$viewKey"}""", body)

        val auth = Nip98.decode(req.getHeader("Authorization")!!)!!
        assertEquals(Kinds.HTTP_AUTH, auth.kind)
        assertEquals(signer.pubkey, auth.pubkey)
        assertEquals(now, auth.createdAt)
        assertEquals(server.url("/v1/accounts").toString(), auth.firstTagValue("u"))
        assertEquals("POST", auth.firstTagValue("method"))
        assertEquals(EventId.sha256(body.toByteArray()).toHex(), auth.firstTagValue("payload"))
    }

    @Test
    fun `register refuses a view key that does not match the address without contacting the watcher`() = runTest {
        val wrongKey = "0000000000000000000000000000000000000000000000000000000000000001".hexToBytes()
        val e = assertThrowsSuspend<IllegalArgumentException> { client.register(Address.parse(mainnet), wrongKey) }
        assertEquals(0, server.requestCount)
        assertTrue(!e.message!!.contains("0000000000000000000000000000000000000000000000000000000000000001"))
    }

    @Test
    fun `register rejects a malformed watcher pubkey as Protocol`() = runTest {
        server.enqueue(MockResponse().setBody("""{"watcher_pubkey":"XYZ"}"""))
        assertThrowsSuspend<WatcherException.Protocol> { client.register(Address.parse(mainnet), viewKey.hexToBytes()) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `register maps 400 to Http with server message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"subaddresses are not supported"}"""))
        val e = assertThrowsSuspend<WatcherException.Http> { client.register(Address.parse(mainnet), viewKey.hexToBytes()) }
        assertEquals(400, e.status)
        assertEquals("subaddresses are not supported", e.message)
    }

    @Test
    fun `register does not follow a redirect to another host`() = runTest {
        val redirectTarget = MockWebServer()
        redirectTarget.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(307).addHeader("Location", redirectTarget.url("/v1/accounts").toString()),
            )
            val e = assertThrowsSuspend<WatcherException.Http> { client.register(Address.parse(mainnet), viewKey.hexToBytes()) }
            assertEquals(307, e.status)
            assertEquals(0, redirectTarget.requestCount)
        } finally {
            redirectTarget.shutdown()
        }
    }

    @Test
    fun `register over cleartext to a non-loopback host is rejected before any network access`() = runTest {
        val insecureClient = WatcherClient("http://10.0.0.1:1/", signer, clock = { now })
        val e = assertThrowsSuspend<IllegalArgumentException> {
            insecureClient.register(Address.parse(mainnet), viewKey.hexToBytes())
        }
        assertTrue(e.message!!.contains("https"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `unregister sends an authenticated DELETE and accepts 204`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        client.unregister()
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/v1/accounts", req.path)
        val auth = Nip98.decode(req.getHeader("Authorization")!!)!!
        assertEquals("DELETE", auth.firstTagValue("method"))
        assertEquals(server.url("/v1/accounts").toString(), auth.firstTagValue("u"))
        assertNull(auth.firstTagValue("payload"))
    }
}
