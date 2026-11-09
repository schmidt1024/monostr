package com.monostr.app.data.linkpreview

import com.monostr.app.net.FakeSocks5
import com.monostr.app.net.gatedOkHttp
import com.monostr.nostr.net.NetworkGate
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/** Security review: preview pictures go through their own client, checked at every connection (redirects included). */
class AddressCheckInterceptorTest {
    private val a = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    private val b = MockWebServer().apply { start(InetAddress.getByName("127.0.0.2"), 0) }

    @AfterEach fun stop() { a.shutdown(); b.shutdown() }

    private fun client(policy: AddressPolicy) = OkHttpClient.Builder().addNetworkInterceptor(AddressCheckInterceptor(policy, Dns.SYSTEM)).build()

    @Test
    fun `a refused address gets no request`() {
        a.enqueue(MockResponse().setBody("img"))
        assertThrows<IOException> { client(AddressPolicy.PUBLIC_ONLY).newCall(Request.Builder().url(a.url("/i.png")).build()).execute().close() }
        assertEquals(0, a.requestCount)
    }

    @Test
    fun `every redirect hop is checked`() {
        a.enqueue(MockResponse().setResponseCode(302).setHeader("Location", b.url("/i.png").toString()))
        b.enqueue(MockResponse().setBody("img"))
        val onlyA = client { it.hostAddress == "127.0.0.1" }
        assertThrows<IOException> { onlyA.newCall(Request.Builder().url(a.url("/start")).build()).execute().close() }
        assertEquals(1, a.requestCount)
        assertEquals(0, b.requestCount)
    }

    private fun torClient(socks: FakeSocks5, policy: AddressPolicy = AddressPolicy.PUBLIC_ONLY) =
        gatedOkHttp(NetworkGate(pickPorts = { socks.port to 1 }).apply { set(torMode()) }) { true }
            .newBuilder().addNetworkInterceptor(AddressCheckInterceptor(policy, Dns.SYSTEM)).build()

    @Test
    fun `a LAN literal is refused in Tor mode`() {
        FakeSocks5(a.port).use { socks ->
            a.enqueue(MockResponse().setBody("img"))
            for (url in listOf("http://192.168.1.10/i.png", "http://127.0.0.1/i.png", "http://[fd00::1]/i.png", "http://localhost/i.png", "http://127.1/i.png", "http://2130706433/i.png", "http://0x7f.0.0.1/i.png", "http://999.1.1.1/i.png", "http://nas.local/i.png", "http://nas/i.png", "http://a.b.c.1/", "http://1a.0.0.1/")) {
                assertThrows<IOException>(url) { torClient(socks).newCall(Request.Builder().url(url).build()).execute().close() }
            }
            assertEquals(0, a.requestCount)
        }
    }

    @Test
    fun `a name in Tor mode loads through the proxy without a local lookup`() {
        FakeSocks5(a.port).use { socks ->
            a.enqueue(MockResponse().setBody("img"))
            // the name exists nowhere: the old local check could only fail here
            torClient(socks).newCall(Request.Builder().url("http://pictures.example/i.png").build()).execute().use { assertEquals("img", it.body!!.string()) }
            assertEquals(listOf("pictures.example"), socks.asks.map { it.host })
        }
    }

    @Test
    fun `a redirect to a LAN literal is refused in Tor mode`() {
        FakeSocks5(a.port).use { socks ->
            a.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://192.168.1.10/i.png"))
            a.enqueue(MockResponse().setBody("img"))
            assertThrows<IOException> { torClient(socks).newCall(Request.Builder().url("http://pictures.example/i.png").build()).execute().close() }
            assertEquals(1, a.requestCount)
        }
    }

    @Test
    fun `behind an HTTP proxy the Dns it is given decides`() {
        val asked = mutableListOf<String>()
        val intranet = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> { asked += hostname; return listOf(InetAddress.getByName("192.168.1.10")) }
        }
        val viaProxy = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(a.hostName, a.port)))
            .addNetworkInterceptor(AddressCheckInterceptor(AddressPolicy.PUBLIC_ONLY, intranet)).build()
        assertThrows<IOException> { viaProxy.newCall(Request.Builder().url("http://nas.example/i.png").build()).execute().close() }
        assertEquals(listOf("nas.example"), asked)
        assertEquals(0, a.requestCount)
    }

    @Test
    fun `an allowed address loads`() {
        a.enqueue(MockResponse().setBody("img"))
        client(AddressPolicy.ANY).newCall(Request.Builder().url(a.url("/i.png")).build()).execute().use { assertEquals("img", it.body!!.string()) }
    }
}
