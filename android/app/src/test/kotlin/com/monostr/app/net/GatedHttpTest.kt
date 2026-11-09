package com.monostr.app.net

import com.monostr.nostr.net.NetworkGate
import com.monostr.nostr.net.NetworkMode
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Spec 2026-11-06 Tor 4 and 7.2: with the switch on, HTTP goes through the SOCKS port or not at all. */
class GatedHttpTest {
    private val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    private val closing = mutableListOf<AutoCloseable>()

    @AfterEach fun stop() { closing.forEach { it.close() }; server.shutdown() }

    private fun deadPort() = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    private fun socks(relay: Boolean = true) = FakeSocks5(if (relay) server.port else null).also { closing += it }

    private fun torGate(port: Int) = NetworkGate(pickPorts = { port to deadPort() }).apply { set(torMode()) }

    private fun get(url: String) = Request.Builder().url(url).build()

    private fun OkHttpClient.body(url: String) = newCall(get(url)).execute().use { it.body!!.string() }

    @Test
    fun `direct mode goes direct`() {
        val client = gatedOkHttp(NetworkGate().apply { set(NetworkMode.Direct) }) { false }
        server.enqueue(MockResponse().setBody("ok"))
        assertEquals("ok", client.body(server.url("/a").toString()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `direct mode never looks up an onion name, a request for one fails before any lookup or socket`() {
        val client = gatedOkHttp(NetworkGate().apply { set(NetworkMode.Direct) }) { false }
        listOf("abc.onion", "ABC.Onion.", "sub.abcdefghijklmnop.onion").forEach { host ->
            val e = assertThrows<java.net.UnknownHostException>(host) { client.dns.lookup(host) }
            assertTrue(e.message!!.contains("needs Tor"), "${e.message} (the system resolver was asked)")
        }
        val e = assertThrows<java.net.UnknownHostException> { client.body("http://abcdefghijklmnop.onion/x") }
        assertTrue(e.message!!.contains("needs Tor"), e.message)
        // a name that merely has onion in it is an ordinary name: the system resolver is asked, as it always was
        val ordinary = assertThrows<java.net.UnknownHostException> { client.dns.lookup("onion.invalid") }
        assertFalse(ordinary.message.orEmpty().contains("needs Tor"))
    }

    @Test
    fun `the shared client keeps its timeouts`() {
        val client = gatedOkHttp(NetworkGate()) { false }
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(15_000, client.readTimeoutMillis)
    }

    @Test
    fun `tor mode not ready throws TorNotReadyException and the server sees no request`() {
        val s = socks()
        val client = gatedOkHttp(torGate(s.port)) { false }
        assertThrows<TorNotReadyException> { client.body(server.url("/a").toString()) }
        assertEquals(0, server.requestCount)
        assertTrue(s.asks.isEmpty(), "not even the proxy is asked")
    }

    @Test
    fun `a derived client is gated as well`() {
        val client = gatedOkHttp(torGate(deadPort())) { false }.newBuilder().followRedirects(false).readTimeout(1, TimeUnit.MINUTES).build()
        assertThrows<TorNotReadyException> { client.body(server.url("/a").toString()) }
        assertEquals(0, server.requestCount)
    }

    private fun running(client: OkHttpClient, url: String): CompletableFuture<String> {
        val result = CompletableFuture<String>()
        thread(isDaemon = true) {
            try {
                result.complete(client.body(url))
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return result
    }

    @Test
    fun `during Unknown no request is sent, once Direct is known it goes out`() {
        val gate = NetworkGate()
        server.enqueue(MockResponse().setBody("ok"))
        val call = running(gatedOkHttp(gate) { false }, server.url("/a").toString())
        Thread.sleep(300)
        assertFalse(call.isDone, "waits for the mode")
        assertEquals(0, server.requestCount)
        gate.set(NetworkMode.Direct)
        assertEquals("ok", call.get(5, TimeUnit.SECONDS))
    }

    /** What the rest of the chain is told; answered here, so no route is needed. */
    private fun connectTimeoutSeen(client: okhttp3.OkHttpClient): Int {
        var seen = -1
        client.newBuilder().addInterceptor { chain ->
            seen = chain.connectTimeoutMillis()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(204).message("").body("".toResponseBody()).build()
        }.build().newCall(okhttp3.Request.Builder().url("http://relay.example/a").build()).execute().close()
        return seen
    }

    @Test
    fun `the connect timeout has room for a circuit in Tor, and stays as it was in Direct`() {
        assertEquals(30_000, connectTimeoutSeen(gatedOkHttp(torGate(deadPort())) { true }))
        assertEquals(10_000, connectTimeoutSeen(gatedOkHttp(NetworkGate().apply { set(NetworkMode.Direct) }) { false }))
    }

    @Test
    fun `Tor stops being ready behind the first look - the port hears nothing and the call fails typed`() {
        FakeSocks5().use { squatter ->
            val gate = torGate(squatter.port)
            var looks = 0
            // the application interceptor passes, the route is chosen after it
            val client = gatedOkHttp(gate) { looks++ == 0 }
            assertThrows<TorNotReadyException> { client.body("http://relay.example/a") }
            assertEquals(emptyList<Any>(), squatter.asks.toList())
        }
    }

    @Test
    fun `during Unknown no request is sent, Tor not ready then fails typed`() {
        val gate = NetworkGate(pickPorts = { deadPort() to deadPort() })
        val call = running(gatedOkHttp(gate) { false }, server.url("/a").toString())
        Thread.sleep(300)
        assertFalse(call.isDone, "waits for the mode")
        gate.set(gate.torMode())
        val e = runCatching { call.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
        assertTrue(e?.cause is TorNotReadyException, "got $e")
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a mode that stays Unknown fails typed in the end, nothing sent`() {
        val client = gatedOkHttp(NetworkGate(), unknownWaitMs = 200) { false }
        assertThrows<TorNotReadyException> { client.body(server.url("/a").toString()) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cancelling a call that waits for the mode ends it`() {
        val call = gatedOkHttp(NetworkGate()) { false }.newCall(get(server.url("/a").toString()))
        val result = CompletableFuture<Throwable?>()
        thread(isDaemon = true) { result.complete(runCatching { call.execute().close() }.exceptionOrNull()) }
        Thread.sleep(150)
        call.cancel()
        assertTrue(result.get(3, TimeUnit.SECONDS) is IOException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `tor mode asks SOCKS with the host name`() {
        val s = socks()
        val client = gatedOkHttp(torGate(s.port)) { true }
        server.enqueue(MockResponse().setBody("through tor"))
        assertEquals("through tor", client.body("http://x.example/a"))
        assertEquals(listOf(FakeSocks5.Ask(3, "x.example", 80)), s.asks.toList())
        assertEquals("x.example", server.takeRequest().getHeader("Host"))
    }

    @Test
    fun `a redirect in tor mode stays on the proxy`() {
        val s = socks()
        val client = gatedOkHttp(torGate(s.port)) { true }
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://y.example/b"))
        server.enqueue(MockResponse().setBody("there"))
        assertEquals("there", client.body("http://x.example/a"))
        assertEquals(listOf("x.example", "y.example"), s.asks.map { it.host })
    }

    @Test
    fun `an IP literal in tor mode goes to the proxy too`() {
        val s = socks(relay = false)
        val client = gatedOkHttp(torGate(s.port)) { true }
        assertThrows<IOException> { client.body("http://127.0.0.1:${server.port}/a") }
        assertEquals("127.0.0.1", s.asks.first().host)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a stopped proxy fails, no direct retry`() {
        val client = gatedOkHttp(torGate(deadPort())) { true }
        assertThrows<IOException> { client.body(server.url("/a").toString()) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a proxy that refuses fails, no direct retry`() {
        val s = socks(relay = false)
        val client = gatedOkHttp(torGate(s.port)) { true }
        assertThrows<IOException> { client.body(server.url("/a").toString()) }
        assertEquals(1, s.asks.size)
        assertEquals(0, server.requestCount)
    }

    /** OkHttp skips the selector and connects directly when java.net.URI finds no host: the JDK's finds none in an underscore name. */
    @Test
    fun `a host java URI cannot parse does not go direct in tor mode`() {
        val s = socks()
        val client = gatedOkHttp(torGate(s.port)) { true }
        assertThrows<TorNotReadyException> { client.body("http://foo_bar.example/a") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a redirect to such a host does not go direct either`() {
        val s = socks()
        val client = gatedOkHttp(torGate(s.port)) { true }
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://foo_bar.example/b"))
        assertThrows<TorNotReadyException> { client.body("http://x.example/a") }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `such a host still works in direct mode as far as DNS allows`() {
        val client = gatedOkHttp(NetworkGate().apply { set(NetworkMode.Direct) }) { false }
        val e = assertThrows<IOException> { client.body("http://foo_bar.invalid/a") }
        assertFalse(e is TorNotReadyException, "got $e")
    }

    @Test
    fun `name lookups and direct sockets exist only in Direct`() {
        val tor = gatedOkHttp(torGate(deadPort())) { true }
        assertThrows<TorNotReadyException> { tor.dns.lookup("x.example") }
        assertThrows<TorNotReadyException> { tor.socketFactory.createSocket() }
        val unknown = gatedOkHttp(NetworkGate(), unknownWaitMs = 50) { false }
        assertThrows<TorNotReadyException> { unknown.dns.lookup("x.example") }
        assertThrows<TorNotReadyException> { unknown.socketFactory.createSocket() }
        val direct = gatedOkHttp(NetworkGate().apply { set(NetworkMode.Direct) }) { false }
        direct.socketFactory.createSocket().close()
        assertTrue(direct.dns.lookup("127.0.0.1").isNotEmpty())
    }

    @Test
    fun `a direct connection left in the pool is not used after switching to Tor`() {
        val gate = NetworkGate(pickPorts = { 0 to 0 }).apply { set(NetworkMode.Direct) }
        val s = socks()
        val client = gatedOkHttp(gate) { true }
        server.enqueue(MockResponse().setBody("direct"))
        val url = server.url("/a").toString()
        assertEquals("direct", client.body(url))
        assertEquals(1, client.connectionPool.connectionCount())

        gate.set(NetworkMode.Tor(s.port, deadPort()))
        server.enqueue(MockResponse().setBody("through tor"))
        assertThrows<TorNotReadyException> { client.body(url) }
        assertEquals(1, server.requestCount, "nothing on the old connection")

        // the refused connection is closed, so the next call really goes through the proxy
        assertEquals("through tor", client.body(url))
        assertEquals(listOf(server.hostName), s.asks.map { it.host })
    }

    @Test
    fun `dropAllConnections empties the pool`() {
        val client = gatedOkHttp(NetworkGate().apply { set(NetworkMode.Direct) }) { false }
        server.enqueue(MockResponse().setBody("ok"))
        assertEquals("ok", client.body(server.url("/a").toString()))
        assertEquals(1, client.connectionPool.connectionCount())
        client.dropAllConnections()
        assertEquals(0, client.connectionPool.connectionCount())
    }

    @Test
    fun `dropAllConnections cancels running calls, those of derived clients too`() {
        val client = gatedOkHttp(NetworkGate().apply { set(NetworkMode.Direct) }) { false }
        val derived = client.newBuilder().readTimeout(1, TimeUnit.MINUTES).build()
        server.enqueue(MockResponse().setBody("late").setBodyDelay(2, TimeUnit.SECONDS))
        val done = CompletableFuture<Throwable?>()
        derived.newCall(get(server.url("/a").toString())).enqueue(object : Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { done.complete(e) }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                done.complete(runCatching { response.use { it.body!!.string() } }.exceptionOrNull())
            }
        })
        server.takeRequest(3, TimeUnit.SECONDS)
        client.dropAllConnections()
        assertTrue(done.get(1, TimeUnit.SECONDS) is IOException)
    }
}
