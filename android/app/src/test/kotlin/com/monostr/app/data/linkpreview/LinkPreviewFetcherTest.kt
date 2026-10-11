package com.monostr.app.data.linkpreview

import org.junit.jupiter.api.Assertions.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LinkPreviewFetcherTest {
    private val server = MockWebServer().apply { start() }
    private val fetcher = LinkPreviewFetcher(OkHttpClient(), "monostr/test (+https://monostr.com)", AddressPolicy.ANY)
    private val head = """<html><head><meta property="og:title" content="Hello"><meta property="og:image" content="/i.png"></head><body>"""

    @AfterEach fun stop() = server.shutdown()

    private fun html(body: String, type: String = "text/html; charset=utf-8") = MockResponse().setHeader("Content-Type", type).setBody(body)

    @Test
    fun `happy path sends the documented headers and parses the head`() = runBlocking {
        server.enqueue(html(head))
        val p = fetcher.fetch(server.url("/page").toString())!!
        assertEquals("Hello", p.title)
        assertEquals(server.url("/i.png").toString(), p.imageUrl)
        val req = server.takeRequest()
        assertEquals("monostr/test (+https://monostr.com)", req.getHeader("User-Agent"))
        assertEquals("text/html,application/xhtml+xml", req.getHeader("Accept"))
        assertEquals("en", req.getHeader("Accept-Language"))
        assertNull(req.getHeader("Referer"))
        assertNull(req.getHeader("Cookie"))
    }

    @Test
    fun `body larger than the limit still yields the title`() = runBlocking {
        // a head that never closes, followed by 1 MB of filler
        server.enqueue(html("<head><title>Big</title>" + "x".repeat(1_000_000)))
        assertEquals("Big", fetcher.fetch(server.url("/big").toString())!!.title)
    }

    @Test
    fun `non-html, errors and missing titles give nothing`() = runBlocking {
        server.enqueue(html(head, type = "image/png"))
        server.enqueue(html(head).setResponseCode(404))
        server.enqueue(html("<head></head>"))
        server.enqueue(html(head, type = "application/json"))
        repeat(4) { assertNull(fetcher.fetch(server.url("/x$it").toString())) }
    }

    @Test
    fun `xhtml is accepted`() = runBlocking {
        server.enqueue(html(head, type = "application/xhtml+xml"))
        assertEquals("Hello", fetcher.fetch(server.url("/x").toString())!!.title)
    }

    @Test
    fun `redirects are followed up to five times and the final address is the preview url`() = runBlocking {
        for (i in 1..5) server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/hop$i"))
        server.enqueue(html(head))
        val p = fetcher.fetch(server.url("/start").toString())!!
        assertEquals(server.url("/hop5").toString(), p.url)
    }

    @Test
    fun `a sixth redirect is refused`() = runBlocking {
        for (i in 1..6) server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/hop$i"))
        server.enqueue(html(head))
        assertNull(fetcher.fetch(server.url("/start").toString()))
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `redirect to a private address is refused`() = runBlocking {
        val strict = LinkPreviewFetcher(OkHttpClient(), "ua", AddressPolicy.PUBLIC_ONLY)
        // the start page itself is on loopback, so the strict fetcher must not even send the first request
        // (nothing is enqueued for it: a queued answer would be served to the next request below)
        assertNull(strict.fetch(server.url("/x").toString()))
        assertEquals(0, server.requestCount)
        // a permissive first hop that redirects to a private literal is stopped before the second request
        val firstHopOnly = LinkPreviewFetcher(OkHttpClient(), "ua", AddressPolicy { it.isLoopbackAddress })
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://192.168.1.1/admin"))
        assertNull(firstHopOnly.fetch(server.url("/r").toString()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `latin-1 title from the content-type header`() = runBlocking {
        val bytes = "<head><title>Gr\u00fc\u00dfe</title></head>".toByteArray(Charsets.ISO_8859_1)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=ISO-8859-1").setBody(Buffer().write(bytes)))
        assertEquals("Grüße", fetcher.fetch(server.url("/l").toString())!!.title)
    }

    @Test
    fun `latin-1 title from the meta charset when the header has none`() = runBlocking {
        val bytes = "<head><meta charset=\"iso-8859-1\"><title>Gr\u00fc\u00dfe</title></head>".toByteArray(Charsets.ISO_8859_1)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(Buffer().write(bytes)))
        assertEquals("Grüße", fetcher.fetch(server.url("/m").toString())!!.title)
    }

    @Test
    fun `non-http schemes and garbage give nothing without a request`() = runBlocking {
        assertNull(fetcher.fetch("ftp://example.org/x"))
        assertNull(fetcher.fetch("not a url"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `with a proxy the target host is still checked before anything is sent`() = runBlocking {
        // the proxy resolves the host itself, so OkHttp never asks our Dns; the fetcher must check first
        val intranet = object : okhttp3.Dns {
            override fun lookup(hostname: String) =
                if (hostname == "intranet.example") listOf(java.net.InetAddress.getByName("192.168.1.10")) else okhttp3.Dns.SYSTEM.lookup(hostname)
        }
        val viaProxy = OkHttpClient.Builder().proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(server.hostName, server.port))).build()
        server.enqueue(html(head))
        assertNull(LinkPreviewFetcher(viaProxy, "ua", AddressPolicy.PUBLIC_ONLY, dns = intranet).fetch("http://intranet.example/admin"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the time budget covers all redirects together`() = runBlocking {
        // review I4: 8 s per hop made 6 hops 48 s; the budget is for the whole fetch
        val quick = LinkPreviewFetcher(OkHttpClient(), "ua", AddressPolicy.ANY, totalMs = 1_500)
        for (i in 1..4) server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/hop$i").setHeadersDelay(600, java.util.concurrent.TimeUnit.MILLISECONDS))
        server.enqueue(html(head))
        val t0 = System.nanoTime()
        assertNull(quick.fetch(server.url("/start").toString()))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 2_500, "took $ms ms")
    }

    @Test
    fun `a page that trickles past the budget still gives what was read`() = runBlocking {
        val quick = LinkPreviewFetcher(OkHttpClient(), "ua", AddressPolicy.ANY, totalMs = 1_000)
        server.enqueue(html("<head><title>Slow</title>" + "x".repeat(50_000)).throttleBody(1024, 200, java.util.concurrent.TimeUnit.MILLISECONDS))
        assertEquals("Slow", quick.fetch(server.url("/slow").toString())?.title)
    }

    @Test
    fun `a picture on a private address is dropped, the title stays`() = runBlocking {
        // review I5: the image loader has no address policy, so the fetcher only keeps pictures it may contact
        val intranet = object : okhttp3.Dns {
            override fun lookup(hostname: String) =
                if (hostname == "nas.example") listOf(java.net.InetAddress.getByName("192.168.1.20")) else okhttp3.Dns.SYSTEM.lookup(hostname)
        }
        val loopbackOnly = LinkPreviewFetcher(OkHttpClient(), "ua", AddressPolicy { it.isLoopbackAddress }, dns = intranet)
        server.enqueue(html("""<head><meta property="og:title" content="A"><meta property="og:image" content="http://192.168.1.1/cgi-bin/reboot"></head>"""))
        server.enqueue(html("""<head><meta property="og:title" content="B"><meta property="og:image" content="http://nas.example/x.png"></head>"""))
        server.enqueue(html("""<head><meta property="og:title" content="C"><meta property="og:image" content="/ok.png"></head>"""))
        val a = loopbackOnly.fetch(server.url("/a").toString())!!
        assertEquals("A", a.title); assertNull(a.imageUrl)
        assertNull(loopbackOnly.fetch(server.url("/b").toString())!!.imageUrl)
        assertEquals(server.url("/ok.png").toString(), loopbackOnly.fetch(server.url("/c").toString())!!.imageUrl)
    }

    @Test
    fun `pages labelled latin-1 are read as windows-1252`() = runBlocking {
        // browsers do the same: smart quotes and the euro sign live in 0x80-0x9F
        val bytes = byteArrayOf(*"<head><title>".toByteArray(), 0x93.toByte(), 'x'.code.toByte(), 0x94.toByte(), ' '.code.toByte(), 0x80.toByte(), *"</title></head>".toByteArray())
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=ISO-8859-1").setBody(Buffer().write(bytes)))
        assertEquals("“x” €", fetcher.fetch(server.url("/w").toString())!!.title)
    }
}
