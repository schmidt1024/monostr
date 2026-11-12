package com.monostr.app.media

import coil3.decode.DataSource
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StaleMediaCheckTest {
    private val own = "https://media.monostr.com"
    private val pic = "$own/abc.jpg"

    private class Fixture(scope: TestScope, origins: Collection<String>, var answer: Int?) {
        val asked = mutableListOf<String>()
        val evicted = mutableListOf<Pair<String, String?>>()
        val check = StaleMediaCheck(scope, { origins }, { url -> asked += url; answer }, { url, key -> evicted += url to key })
    }

    @Test fun `only disk hits on our own hosts are candidates`() {
        val origins = StaleMediaCheck.ownOrigins("https://blossom.example.org")
        assertTrue(StaleMediaCheck.isCandidate(pic, DataSource.DISK, origins))
        assertTrue(StaleMediaCheck.isCandidate("https://blossom.example.org/x.png", DataSource.DISK, origins))
        assertTrue(StaleMediaCheck.isCandidate("HTTPS://MEDIA.MONOSTR.COM/abc.jpg", DataSource.DISK, origins))
        assertFalse(StaleMediaCheck.isCandidate(pic, DataSource.MEMORY_CACHE, origins))
        assertFalse(StaleMediaCheck.isCandidate(pic, DataSource.NETWORK, origins))
        assertFalse(StaleMediaCheck.isCandidate("https://images.pexels.com/a.jpg", DataSource.DISK, origins))
        // a longer host with our name as prefix is not ours
        assertFalse(StaleMediaCheck.isCandidate("https://media.monostr.com.evil.example/a.jpg", DataSource.DISK, origins))
        assertFalse(StaleMediaCheck.isCandidate(own, DataSource.DISK, origins))
    }

    @Test fun `own origins ignore an invalid upload server`() {
        assertEquals(setOf(own), StaleMediaCheck.ownOrigins(null))
        assertEquals(setOf(own), StaleMediaCheck.ownOrigins("ftp://x"))
    }

    @Test fun `only 404 and 410 are gone`() {
        assertTrue(StaleMediaCheck.isGone(404))
        assertTrue(StaleMediaCheck.isGone(410))
        for (code in listOf(200, 304, 403, 429, 500, 503)) assertFalse(StaleMediaCheck.isGone(code), "$code")
    }

    @Test fun `a 404 evicts once and the URL is asked once per process`() = runTest {
        val f = Fixture(this, setOf(own), 404)
        f.check.onShown(pic, DataSource.DISK, "key")
        f.check.onShown(pic, DataSource.DISK, "key")
        advanceUntilIdle()
        assertEquals(listOf(pic), f.asked)
        assertEquals(listOf(pic to "key"), f.evicted)
    }

    @Test fun `a 200 or 403 keeps the copy`() = runTest {
        val f = Fixture(this, setOf(own), 200)
        f.check.onShown(pic, DataSource.DISK, null)
        f.answer = 403
        f.check.onShown("$own/other.jpg", DataSource.DISK, null)
        advanceUntilIdle()
        assertEquals(2, f.asked.size)
        assertTrue(f.evicted.isEmpty())
    }

    @Test fun `no answer is asked again on the next disk hit`() = runTest {
        val f = Fixture(this, setOf(own), null)
        f.check.onShown(pic, DataSource.DISK, null)
        advanceUntilIdle()
        f.answer = 410
        f.check.onShown(pic, DataSource.DISK, null)
        advanceUntilIdle()
        assertEquals(listOf(pic, pic), f.asked)
        assertEquals(listOf(pic to null), f.evicted)
    }

    @Test fun `foreign hosts and memory hits are never asked`() = runTest {
        val f = Fixture(this, setOf(own), 404)
        f.check.onShown("https://images.pexels.com/a.jpg", DataSource.DISK, null)
        f.check.onShown(pic, DataSource.MEMORY_CACHE, null)
        advanceUntilIdle()
        assertTrue(f.asked.isEmpty())
    }

    @Test fun `head status reads the code without a body and maps IO failure to null`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(404))
            val http = OkHttpClient()
            assertEquals(404, StaleMediaCheck.headStatus(http, server.url("/a.jpg").toString()))
            assertEquals("HEAD", server.takeRequest().method)
        } finally {
            server.shutdown()
        }
        assertEquals(null, StaleMediaCheck.headStatus(OkHttpClient(), "http://127.0.0.1:1/a.jpg"))
    }

    @Test fun `a run asks at most its cap, and an unanswered ask does not count`() = runTest {
        val f = Fixture(this, setOf(own), null)
        f.check.onShown("$own/first.jpg", DataSource.DISK, null)
        advanceUntilIdle()
        f.answer = 200
        repeat(StaleMediaCheck.MAX_PER_RUN + 5) { f.check.onShown("$own/$it.jpg", DataSource.DISK, null) }
        advanceUntilIdle()
        assertEquals(1 + StaleMediaCheck.MAX_PER_RUN, f.asked.size)
    }
}
