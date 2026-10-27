package com.monostr.app.media

import com.monostr.app.ui.FakeUiSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Spec 2026-10-27 video §8: a video goes up with its poster, streamed, to the configured server; nothing stays behind. */
@OptIn(ExperimentalCoroutinesApi::class)
class BlossomVideoUploaderTest {
    @TempDir lateinit var dir: File
    private val server = MockWebServer()
    private val settings = FakeUiSettings()
    private val posterHash = "a".repeat(64)
    private val videoHash = "b".repeat(64)
    private lateinit var base: String
    private val pictures = PicturePreparer { _, _ -> PreparedImage(byteArrayOf(1, 2, 3), "image/jpeg", 720, 1280, posterHash, "LNM}7u}qfQ}q") }
    private lateinit var videoFile: File
    private lateinit var posterFile: File

    private val videos = object : VideoPreparer {
        override suspend fun durationMs(source: String) = 61_500L
        override suspend fun prepare(source: String, onProgress: (Float) -> Unit): PreparedVideo {
            onProgress(1f)
            return PreparedVideo(videoFile, 720, 1280, 61_500L, videoHash, videoFile.length(), posterFile)
        }
    }

    /** Remembers every request; PUT answers with a descriptor of the hash it names; [holdVideoPut] holds the video's PUT. */
    private inner class Server(private val holdVideoPut: CountDownLatch? = null) : Dispatcher() {
        val log: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

        override fun dispatch(request: RecordedRequest): MockResponse {
            val type = request.getHeader("Content-Type") ?: ""
            log += "${request.method} ${request.path} $type".trim()
            return when (request.method) {
                "HEAD" -> MockResponse().setResponseCode(404)
                "PUT" -> {
                    val sha = request.getHeader("X-SHA-256")!!
                    if (sha == videoHash) holdVideoPut?.await(10, TimeUnit.SECONDS)
                    val ext = if (sha == videoHash) "mp4" else "jpg"
                    MockResponse().setResponseCode(201).setBody("""{"url":"$base/$sha.$ext","sha256":"$sha","size":${request.bodySize},"type":"${type.substringBefore(';')}"}""")
                }
                else -> MockResponse().setResponseCode(204)
            }
        }
    }

    @BeforeEach
    fun start() {
        server.start()
        base = server.url("/").toString().trimEnd('/')
        settings.mediaServerState.value = base
        videoFile = File(dir, "v.mp4").apply { writeBytes(ByteArray(300_000) { 7 }) }
        posterFile = File(dir, "p.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    }

    @AfterEach
    fun stop() = runCatching { server.shutdown() }.let { }

    private fun uploader(scope: CoroutineScope) =
        BlossomMediaUploader(pictures, BlossomClient(OkHttpClient(), FakeSigner()) { 1000L }, settings, scope, videos = videos)

    @Test
    fun `uploadVideo uploads poster then video to the configured server`() = runTest {
        val s = Server().also { server.dispatcher = it }
        val media = uploader(this).uploadVideo("content://videos/1")

        val puts = s.log.filter { it.startsWith("PUT") }
        assertEquals(listOf("PUT /upload image/jpeg", "PUT /upload video/mp4"), puts.map { it.substringBefore(";") })
        assertEquals(base, media.server)
        assertEquals("$base/$videoHash.mp4", media.url)
        assertEquals("video/mp4", media.type)
        assertEquals(720 to 1280, media.width to media.height)
        assertEquals("$base/$posterHash.jpg", media.posterUrl)
        assertEquals(posterHash, media.posterSha256)
        assertEquals("LNM}7u}qfQ}q", media.blurhash) // the poster's
        assertEquals(61_500L, media.durationMs)
        assertTrue(media.fresh && media.posterFresh)
        val a = media.toAttachment()
        assertEquals(media.posterUrl, a.image)
        assertEquals(61.5, a.durationSeconds)
        assertFalse(videoFile.exists() || posterFile.exists(), "the temp files are gone")
    }

    @Test
    fun `cancel during the video upload discards poster and video and deletes the temp files`() = runTest {
        val hold = CountDownLatch(1)
        val s = Server(hold).also { server.dispatcher = it }
        try {
            val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
            val upload = async(Dispatchers.IO) { uploader(scope).uploadVideo("content://videos/1") }
            val deadline = System.currentTimeMillis() + 5000
            while (s.log.none { it.contains("video/mp4") } && System.currentTimeMillis() < deadline) Thread.sleep(10)
            upload.cancel()
            assertInstanceOf(CancellationException::class.java, runCatching { upload.await() }.exceptionOrNull())
        } finally {
            hold.countDown()
        }
        val deadline = System.currentTimeMillis() + 5000
        while (s.log.count { it.startsWith("DELETE") } < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(setOf("DELETE /$posterHash", "DELETE /$videoHash"), s.log.filter { it.startsWith("DELETE") }.toSet())
        assertFalse(videoFile.exists() || posterFile.exists(), "the temp files are gone")
    }

    @Test
    fun `discard of a video deletes its poster too`() = runTest {
        val s = Server().also { server.dispatcher = it }
        val up = uploader(this)
        val media = up.uploadVideo("content://videos/1")
        up.discardNow(media)
        assertEquals(setOf("DELETE /$posterHash", "DELETE /$videoHash"), s.log.filter { it.startsWith("DELETE") }.toSet())
    }

    @Test
    fun `an old draft without the video fields still loads`() {
        val old = """{"server":"https://m.example","url":"https://m.example/x.jpg","type":"image/jpeg","sha256":"${"c".repeat(64)}","size":3,"width":4,"height":5,"blurhash":null,"fresh":true}"""
        val media = Json.decodeFromString(UploadedMedia.serializer(), old)
        assertNull(media.posterUrl)
        assertNull(media.durationMs)
        assertNull(media.toAttachment().image)
    }
}
