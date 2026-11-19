package com.monostr.app.net

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException

/** Security review (backlog 97): a stranger's watcher name that resolves to the LAN or loopback is never connected to. */
class PublicOnlyDnsTest {
    private fun addr(vararg b: Int) = InetAddress.getByAddress(b.map { it.toByte() }.toByteArray())

    private val resolver = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = when (hostname) {
            "loop.example" -> listOf(addr(127, 0, 0, 1))
            "lan.example" -> listOf(addr(192, 168, 1, 10))
            "mixed.example" -> listOf(addr(10, 0, 0, 1), addr(93, 184, 216, 34))
            else -> throw UnknownHostException(hostname)
        }
    }

    @Test
    fun `a name resolving to loopback or the LAN is refused`() {
        val dns = PublicOnlyDns(resolver)
        assertThrows<UnknownHostException> { dns.lookup("loop.example") }
        assertThrows<UnknownHostException> { dns.lookup("lan.example") }
    }

    @Test
    fun `only the public addresses of a name are kept`() {
        assertEquals(listOf(addr(93, 184, 216, 34)), PublicOnlyDns(resolver).lookup("mixed.example"))
    }

    @Test
    fun `the derived client sends nothing to a name that resolves to loopback`() {
        val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            server.enqueue(MockResponse().setBody("{}"))
            val client = OkHttpClient.Builder().dns(resolver).build().publicAddressesOnly()
            assertThrows<IOException> { client.newCall(Request.Builder().url("http://loop.example:${server.port}/v1/info").build()).execute().close() }
            // a literal skips the lookup: the connection check refuses it
            assertThrows<IOException> { client.newCall(Request.Builder().url(server.url("/v1/info")).build()).execute().close() }
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
