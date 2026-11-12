package com.monostr.app.media

import com.monostr.app.ui.media.MediaModels
import com.monostr.nostr.model.NoteMedia
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Spec 2026-11-12: a poster from the video itself, read by ranges, capped, stored once. */
class VideoFrameTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private val file = ByteArray(1000) { (it * 7 % 251).toByte() }
    private val ranges = CopyOnWriteArrayList<String>()
    @Volatile private var ignoreRange = false
    /** When set, every request is answered with this status and no body. */
    @Volatile private var status: Int? = null
    /** When set, a range answer carries at most this many bytes (a host that cuts ranges). */
    @Volatile private var cutTo: Int? = null

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                ranges += range ?: "none"
                status?.let { return MockResponse().setResponseCode(it) }
                if (ignoreRange || range == null) return MockResponse().setResponseCode(200).setBody(Buffer().write(file))
                val (s, e) = range.removePrefix("bytes=").split('-').map { it.toInt() }
                val end = minOf(e, file.size - 1, cutTo?.let { s + it - 1 } ?: Int.MAX_VALUE)
                return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $s-$end/${file.size}")
                    .setBody(Buffer().write(file.copyOfRange(s, end + 1)))
            }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.shutdown()

    private fun reader(block: Int = 100, max: Long = 10_000, cached: Int = 4) = RangeReader(http, server.url("/v.mp4").toString(), block, max, cached)

    @Test
    fun `reads come from aligned blocks and the size from Content-Range`() {
        val r = reader()
        assertEquals(1000L, r.open())
        val buf = ByteArray(50)
        // within one block
        assertEquals(50, r.readAt(120, buf, 0, 50))
        assertArrayEquals(file.copyOfRange(120, 170), buf)
        // across a block edge: only up to the edge, the caller asks again
        assertEquals(10, r.readAt(190, buf, 0, 50))
        assertEquals(listOf("bytes=0-99", "bytes=100-199"), ranges.toList())
        // the last block ends at the file's end; past it is the end
        val tail = ByteArray(200)
        assertEquals(50, r.readAt(950, tail, 0, 200))
        assertEquals("bytes=900-999", ranges.last())
        assertEquals(-1, r.readAt(1000, tail, 0, 10))
    }

    @Test
    fun `a cached block is not asked for again`() {
        val r = reader()
        r.open()
        val buf = ByteArray(10)
        r.readAt(5, buf, 0, 10)
        r.readAt(50, buf, 0, 10)
        assertEquals(1, r.requests)
        assertEquals(100L, r.downloaded)
    }

    @Test
    fun `the oldest block leaves the cache beyond its size`() {
        val r = reader(cached = 2)
        val buf = ByteArray(1)
        r.readAt(0, buf, 0, 1); r.readAt(100, buf, 0, 1); r.readAt(200, buf, 0, 1)
        r.readAt(0, buf, 0, 1)
        assertEquals(4, r.requests)
    }

    @Test
    fun `a host that ignores ranges is refused and read no further`() {
        ignoreRange = true
        val r = reader()
        val e = assertThrows<RangeStop> { r.open() }
        assertEquals(RangeStop.Reason.NO_RANGE, e.reason)
        assertEquals(0L, r.downloaded)
        // stopped for good: no second request
        assertThrows<RangeStop> { r.readAt(0, ByteArray(1), 0, 1) }
        assertEquals(1, ranges.size)
    }

    @Test
    fun `the byte cap ends reading before the request that would pass it`() {
        val r = reader(max = 250)
        val buf = ByteArray(1)
        r.readAt(0, buf, 0, 1); r.readAt(100, buf, 0, 1)
        val e = assertThrows<RangeStop> { r.readAt(200, buf, 0, 1) }
        assertEquals(RangeStop.Reason.CAP, e.reason)
        assertEquals(2, ranges.size)
        assertEquals(200L, r.downloaded)
        // a stopped reader reads on from nothing, not even what would fit
        assertThrows<RangeStop> { r.readAt(300, buf, 0, 1) }
        assertEquals(2, ranges.size)
    }

    @Test
    fun `a stopped reader sends nothing`() {
        val r = reader()
        r.stop(RangeStop.Reason.TIMEOUT)
        val e = assertThrows<RangeStop> { r.open() }
        assertEquals(RangeStop.Reason.TIMEOUT, e.reason)
        assertEquals(0, ranges.size)
    }

    @Test
    fun `Content-Range is parsed strictly`() {
        assertEquals(Triple(0L, 99L, 1000L), VideoFrameRules.parseContentRange("bytes 0-99/1000"))
        assertNull(VideoFrameRules.parseContentRange("bytes 0-99/*"))
        assertNull(VideoFrameRules.parseContentRange("bytes */1000"))
        assertNull(VideoFrameRules.parseContentRange("bytes 100-99/1000"))
        assertNull(VideoFrameRules.parseContentRange("bytes 0-1000/1000"))
        assertNull(VideoFrameRules.parseContentRange(null))
    }

    @Test
    fun `the store keys by the URL's sha256 and drops the least recently used`(@TempDir dir: File) {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", VideoFrameStore.key(""))
        assertEquals(File(dir, VideoFrameStore.key("a") + ".jpg"), VideoFrameStore(dir).file("a"))
        var t = 1_000_000L
        val store = VideoFrameStore(dir, maxBytes = 250) { t }
        store.put("a", ByteArray(100)); t += 1000
        store.put("b", ByteArray(100)); t += 1000
        assertTrue(store.get("a") != null); t += 1000 // a is used again, b is now the oldest
        store.put("c", ByteArray(100))
        assertTrue(store.get("a") != null)
        assertNull(store.get("b"))
        assertTrue(store.get("c") != null)
        assertTrue(dir.listFiles()!!.all { it.name.endsWith(".jpg") })
    }

    @Test
    fun `a failure is remembered unless it was a cancel or nothing was read`() {
        assertTrue(VideoFrameExtractor.rememberFailure(RangeStop.Reason.NO_RANGE, 0))
        assertTrue(VideoFrameExtractor.rememberFailure(RangeStop.Reason.CAP, 4_000_000))
        assertTrue(VideoFrameExtractor.rememberFailure(RangeStop.Reason.TIMEOUT, 10))
        assertTrue(VideoFrameExtractor.rememberFailure(null, 262_144))
        // the network failed part way (review 2026-11-12): the next try may work
        assertFalse(VideoFrameExtractor.rememberFailure(null, 262_144, ioError = true))
        assertFalse(VideoFrameExtractor.rememberFailure(RangeStop.Reason.CANCELED, 262_144))
        assertFalse(VideoFrameExtractor.rememberFailure(null, 0))
    }

    @Test
    fun `a video cell loads its poster, else the derived frame, a picture its URL`() {
        assertEquals("https://m.example/a.jpg", MediaModels.cell(NoteMedia("https://m.example/a.jpg", NoteMedia.Kind.IMAGE)))
        assertEquals("https://m.example/p.jpg", MediaModels.cell(NoteMedia("https://m.example/v.mp4", NoteMedia.Kind.VIDEO, poster = "https://m.example/p.jpg")))
        val bare = MediaModels.cell(NoteMedia("https://m.example/v.mp4", NoteMedia.Kind.VIDEO))
        assertEquals(VideoFrame("https://m.example/v.mp4"), bare)
        assertTrue(MediaModels.derived(bare))
        assertFalse(MediaModels.derived("https://m.example/p.jpg"))
        assertEquals("video-frame:https://m.example/v.mp4", VideoFrameKeyer.key(VideoFrame("https://m.example/v.mp4")))
    }

    @Test
    fun `only ISO media and EBML containers reach the parser`() {
        fun head(vararg b: Int) = ByteArray(12).also { b.forEachIndexed { i, v -> it[i] = v.toByte() } }
        assertTrue(VideoContainers.known(head(0, 0, 0, 0x20, 'f'.code, 't'.code, 'y'.code, 'p'.code)))
        assertTrue(VideoContainers.known(head(0x1A, 0x45, 0xDF, 0xA3)))
        assertFalse(VideoContainers.known(head(0x47, 0x49, 0x46, 0x38))) // GIF
        assertFalse(VideoContainers.known(head(0x89, 'P'.code, 'N'.code, 'G'.code)))
        assertFalse(VideoContainers.known(ByteArray(3)))
    }

    @Test
    fun `after a stop even a cached block is refused`() {
        val r = reader()
        r.open()
        r.stop(RangeStop.Reason.CANCELED)
        assertThrows<RangeStop> { r.readAt(0, ByteArray(4), 0, 4) }
    }

    @Test
    fun `a busy or refusing host is an error for now, a missing file is final`() {
        for (code in listOf(403, 429, 503)) {
            status = code
            val e = assertThrows<java.io.IOException> { reader().open() }
            assertFalse(e is RangeStop, "$code: $e")
        }
        for (code in listOf(404, 410)) {
            status = code
            assertEquals(RangeStop.Reason.NO_RANGE, assertThrows<RangeStop> { reader().open() }.reason, "$code")
        }
    }

    @Test
    fun `a host that cuts ranges short is refused, not read as the end`() {
        cutTo = 40
        assertEquals(RangeStop.Reason.NO_RANGE, assertThrows<RangeStop> { reader(block = 100).open() }.reason)
        // the last block may be short: it ends the file
        cutTo = null
        val r = reader(block = 300)
        val buf = ByteArray(200)
        assertEquals(100, r.readAt(900, buf, 0, 200))
    }

    @Test
    fun `a video that gave no frame is not read again for a day`(@TempDir dir: File) {
        var t = 1_000_000L
        val store = VideoFrameStore(dir) { t }
        assertFalse(store.failedRecently("v"))
        store.markFailed("v")
        assertTrue(store.failedRecently("v"))
        assertFalse(store.failedRecently("w"))
        t += VideoFrameRules.FAILED_TTL_MS
        assertFalse(store.failedRecently("v"))
        assertFalse(File(dir, VideoFrameStore.key("v") + ".fail").exists())
    }
}
