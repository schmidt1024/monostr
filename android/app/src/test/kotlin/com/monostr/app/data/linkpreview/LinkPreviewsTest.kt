package com.monostr.app.data.linkpreview

import com.monostr.app.net.FakeSocks5
import com.monostr.app.net.dropAllConnections
import com.monostr.app.net.gatedOkHttp
import com.monostr.nostr.net.NetworkGate
import com.monostr.nostr.net.NetworkMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class LinkPreviewsTest {
    @TempDir lateinit var dir: File
    private val p = LinkPreview("https://a.example/", "Title", "https://a.example/i.png", true)
    private var clock = 1_000_000L

    private fun repo(calls: AtomicInteger, answer: LinkPreview? = p, gate: CompletableDeferred<Unit>? = null) =
        LinkPreviews({ calls.incrementAndGet(); gate?.await(); answer }, File(dir, "lp"), now = { clock })

    @Test
    fun `Tor not ready is no miss, the next call fetches again`() = runBlocking {
        var ready = false
        val r = LinkPreviews({ if (!ready) throw com.monostr.app.net.TorNotReadyException() else p }, File(dir, "lp"), now = { clock })
        assertNull(r.get("https://a.example/"))
        ready = true
        assertEquals(p, r.get("https://a.example/"))
    }

    private val page = """<head><meta property="og:title" content="Hello"></head>"""

    /** The real fetcher on a gated client in Tor mode, ready; [socks] carries or refuses the connection. */
    private fun torPreviews(socks: Int, gate: NetworkGate = NetworkGate(pickPorts = { socks to 1 }).apply { set(torMode()) }, totalMs: Long = 8_000): Pair<LinkPreviews, OkHttpClient> {
        val http = gatedOkHttp(gate) { true }
        return LinkPreviews(LinkPreviewFetcher(http, "ua", AddressPolicy.PUBLIC_ONLY, totalMs = totalMs)::fetch, File(dir, "lp"), now = { clock }) to http
    }

    private fun remembered() = File(dir, "lp").listFiles()?.size ?: 0

    @Test
    fun `Tor mode, a refusing proxy leaves no miss behind`() = runBlocking {
        FakeSocks5().use { refusing ->
            val (r, _) = torPreviews(refusing.port)
            assertNull(r.get("http://preview.example/"))
            assertEquals(0, remembered())
            assertNull(r.get("http://preview.example/"))
            assertEquals(2, refusing.asks.size, "asked again, nothing remembered")
        }
    }

    @Test
    fun `Tor mode, a stopped proxy leaves no miss behind`() = runBlocking {
        val dead = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertNull(torPreviews(dead).first.get("http://preview.example/"))
        assertEquals(0, remembered())
    }

    @Test
    fun `Tor mode, a timeout leaves no miss behind and the page loads later`() = runBlocking {
        val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            FakeSocks5(server.port).use { socks ->
                server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(page).setHeadersDelay(1, TimeUnit.SECONDS))
                assertNull(torPreviews(socks.port, totalMs = 200).first.get("http://preview.example/"))
                assertEquals(0, remembered())
                server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(page))
                assertEquals("Hello", torPreviews(socks.port).first.get("http://preview.example/")?.title)
            }
        } finally {
            server.shutdown()
        }
    }

    private suspend fun cancelledByDrop(afterDrop: NetworkGate.() -> Unit, beforeDrop: NetworkGate.() -> Unit = {}) {
        val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            FakeSocks5(server.port).use { socks ->
                val gate = NetworkGate(pickPorts = { socks.port to 1 }).apply { set(torMode()) }
                val (r, http) = torPreviews(socks.port, gate)
                server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(page).setHeadersDelay(2, TimeUnit.SECONDS))
                coroutineScope {
                    val preview = async(Dispatchers.IO) { r.get("http://preview.example/") }
                    server.takeRequest(3, TimeUnit.SECONDS)
                    gate.beforeDrop()
                    http.dropAllConnections()
                    gate.afterDrop()
                    assertNull(preview.await())
                }
                assertEquals(0, remembered())
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `Tor mode, cancelled by dropAllConnections leaves no miss behind`() = runBlocking {
        cancelledByDrop(afterDrop = {})
    }

    @Test
    fun `switching Tor off, a fetch it cancels leaves no miss behind either`() = runBlocking {
        cancelledByDrop(afterDrop = {}, beforeDrop = { set(NetworkMode.Direct) })
    }

    @Test
    fun `Tor mode, a page that answers 404 stays a miss`() = runBlocking {
        val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            FakeSocks5(server.port).use { socks ->
                server.enqueue(MockResponse().setResponseCode(404))
                val (r, _) = torPreviews(socks.port)
                assertNull(r.get("http://preview.example/"))
                assertEquals(1, remembered())
                assertNull(r.get("http://preview.example/"))
                assertEquals(1, server.requestCount)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `Tor mode, a page without metadata stays a miss`() = runBlocking {
        val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            FakeSocks5(server.port).use { socks ->
                server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("nothing"))
                assertNull(torPreviews(socks.port).first.get("http://preview.example/"))
                assertEquals(1, remembered())
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `Direct, a refused connection stays a remembered miss, as ever`() = runBlocking {
        val dead = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val http = gatedOkHttp(NetworkGate().apply { set(NetworkMode.Direct) }) { false }
        val r = LinkPreviews(LinkPreviewFetcher(http, "ua", AddressPolicy.ANY)::fetch, File(dir, "lp"), now = { clock })
        assertNull(r.get("http://127.0.0.1:$dead/"))
        assertEquals(1, remembered())
    }

    @Test
    fun `memory hit after the first fetch`() = runBlocking {
        val calls = AtomicInteger()
        val r = repo(calls)
        assertNull(r.cached("https://a.example/"))
        assertEquals(p, r.get("https://a.example/"))
        assertEquals(p, r.cached("https://a.example/"))
        assertEquals(p, r.get("https://a.example/"))
        assertEquals(1, calls.get())
    }

    @Test
    fun `disk hit survives a new repository and expires after seven days`() = runBlocking {
        val calls = AtomicInteger()
        repo(calls).get("https://a.example/")
        val second = repo(calls)
        assertEquals(p, second.get("https://a.example/"))
        assertEquals(1, calls.get())
        clock += LinkPreviews.HIT_TTL_MS + 1
        repo(calls).get("https://a.example/")
        assertEquals(2, calls.get())
    }

    @Test
    fun `a miss is remembered for one day`() = runBlocking {
        val calls = AtomicInteger()
        assertNull(repo(calls, answer = null).get("https://none.example/"))
        assertNull(repo(calls, answer = null).get("https://none.example/"))
        assertEquals(1, calls.get())
        clock += LinkPreviews.MISS_TTL_MS + 1
        repo(calls, answer = null).get("https://none.example/")
        assertEquals(2, calls.get())
    }

    @Test
    fun `two callers share one request`() = runBlocking {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val r = repo(calls, gate = gate)
        val both = listOf(async { r.get("https://a.example/") }, async { r.get("https://a.example/") })
        gate.complete(Unit)
        assertEquals(listOf(p, p), both.awaitAll())
        assertEquals(1, calls.get())
    }

    @Test
    fun `a title and image without newlines round-trip, a preview without image too`() = runBlocking {
        val noImage = LinkPreview("https://b.example/x?y=1", "T with | pipe", null, false)
        val calls = AtomicInteger()
        LinkPreviews({ calls.incrementAndGet(); noImage }, File(dir, "lp"), now = { clock }).get("https://b.example/x?y=1")
        assertEquals(noImage, LinkPreviews({ error("no fetch") }, File(dir, "lp"), now = { clock }).get("https://b.example/x?y=1"))
    }

    @Test
    fun `clear empties memory and disk`() = runBlocking {
        val calls = AtomicInteger()
        val r = repo(calls)
        r.get("https://a.example/")
        r.clear()
        assertNull(r.cached("https://a.example/"))
        assertTrue(File(dir, "lp").listFiles().isNullOrEmpty())
        r.get("https://a.example/")
        assertEquals(2, calls.get())
    }

    @Test
    fun `a corrupt cache file is treated as absent`() = runBlocking {
        val calls = AtomicInteger()
        repo(calls).get("https://a.example/")
        File(dir, "lp").listFiles()!!.forEach { it.writeText("garbage") }
        repo(calls).get("https://a.example/")
        assertEquals(2, calls.get())
    }

    @Test
    fun `when the last caller leaves, its fetch is cancelled and a queued one never starts`() = runBlocking {
        // review I1: scrolling away or switching the setting off must stop requests, not just stop waiting
        val started = java.util.Collections.synchronizedList(mutableListOf<String>())
        val cancelled = java.util.Collections.synchronizedList(mutableListOf<String>())
        val never = CompletableDeferred<Unit>()
        val r = LinkPreviews({ u -> started += u; try { never.await(); p } finally { if (!never.isCompleted) cancelled += u } }, File(dir, "lp"), now = { clock }, parallel = 1)
        val first = launch(kotlinx.coroutines.Dispatchers.Default) { r.get("https://one.example/") }
        while (started.isEmpty()) kotlinx.coroutines.delay(5)
        val second = launch(kotlinx.coroutines.Dispatchers.Default) { r.get("https://two.example/") }
        kotlinx.coroutines.delay(100)
        second.cancelAndJoin()
        first.cancelAndJoin()
        kotlinx.coroutines.delay(100)
        assertEquals(listOf("https://one.example/"), started.toList())
        assertEquals(listOf("https://one.example/"), cancelled.toList())
        assertNull(r.cached("https://one.example/"))
    }

    @Test
    fun `clear during a fetch keeps its result out of memory and disk`() = runBlocking {
        // review I2: the next account must not inherit a preview that was in flight at logout
        val gate = CompletableDeferred<Unit>()
        val r = LinkPreviews({ gate.await(); p }, File(dir, "lp"), now = { clock })
        val waiting = async(kotlinx.coroutines.Dispatchers.Default) { r.get("https://a.example/") }
        kotlinx.coroutines.delay(100)
        r.clear()
        gate.complete(Unit)
        runCatching { waiting.await() }
        kotlinx.coroutines.delay(100)
        assertNull(r.cached("https://a.example/"))
        assertTrue(File(dir, "lp").listFiles().isNullOrEmpty())
    }

    @Test
    fun `clear empties memory even when its caller is already cancelled`() = runBlocking {
        // security review: a cancelled logout must not leave the previous account's previews behind
        val calls = AtomicInteger()
        val r = repo(calls)
        r.get("https://a.example/")
        val job = launch { coroutineContext[kotlinx.coroutines.Job]!!.cancel(); runCatching { r.clear() } }
        job.join()
        assertNull(r.cached("https://a.example/"))
    }
}
