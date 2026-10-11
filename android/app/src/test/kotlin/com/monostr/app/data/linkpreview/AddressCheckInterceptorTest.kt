package com.monostr.app.data.linkpreview

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

/** Security review: preview pictures go through their own client, checked at every connection (redirects included). */
class AddressCheckInterceptorTest {
    private val a = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    private val b = MockWebServer().apply { start(InetAddress.getByName("127.0.0.2"), 0) }

    @AfterEach fun stop() { a.shutdown(); b.shutdown() }

    private fun client(policy: AddressPolicy) = OkHttpClient.Builder().addNetworkInterceptor(AddressCheckInterceptor(policy)).build()

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

    @Test
    fun `an allowed address loads`() {
        a.enqueue(MockResponse().setBody("img"))
        client(AddressPolicy.ANY).newCall(Request.Builder().url(a.url("/i.png")).build()).execute().use { assertEquals("img", it.body!!.string()) }
    }
}
