package com.monostr.app.media

import com.monostr.app.ui.FakeUiSettings
import com.monostr.nostr.SilentSignUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class BlossomMediaUploaderTest {
    private val server = MockWebServer()
    private val signer = FakeSigner()
    private val hash = "a".repeat(64)
    private val settings = FakeUiSettings()
    private val prepared = ArrayList<String>()
    private val preparer = PicturePreparer { source, target ->
        prepared += "$target:$source"
        PreparedImage(byteArrayOf(1, 2, 3), "image/jpeg", 400, 300, hash, "LNM}7u}qfQ}q")
    }
    private lateinit var base: String

    @BeforeEach
    fun start() {
        server.start()
        base = server.url("/").toString().trimEnd('/')
        settings.mediaServerState.value = base
    }

    @AfterEach
    fun stop() = runCatching { server.shutdown() }.let { }

    private fun uploader(scope: CoroutineScope) = BlossomMediaUploader(preparer, BlossomClient(OkHttpClient(), signer) { 1000L }, settings, scope)

    /** Answers like a Blossom server: HEAD says [has] (200) or not (404), PUT stores, DELETE succeeds; [putGate] holds the PUT back. */
    private fun serve(has: Boolean, putGate: CountDownLatch? = null) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                "HEAD" -> MockResponse().setResponseCode(if (has) 200 else 404)
                "PUT" -> {
                    putGate?.await(10, TimeUnit.SECONDS)
                    MockResponse().setResponseCode(201).setBody("""{"url":"$base/$hash.jpg","sha256":"$hash","size":3,"type":"image/jpeg","uploaded":1}""")
                }
                else -> MockResponse().setResponseCode(204)
            }
        }
    }

    /**
     * A Blossom server with memory: PUT stores the hash (once the body has arrived), DELETE removes it, HEAD
     * answers from what is stored. [holdFirstPut] is awaited by the first PUT only, after storing.
     * Every request is logged at arrival as "METHOD", in arrival order.
     */
    private inner class StoringServer(private val holdFirstPut: () -> Unit = {}) : Dispatcher() {
        val stored: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
        val log: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        private val puts = java.util.concurrent.atomic.AtomicInteger()

        override fun dispatch(request: RecordedRequest): MockResponse {
            log += request.method!!
            return when (request.method) {
                "HEAD" -> MockResponse().setResponseCode(if (request.path!!.removePrefix("/") in stored) 200 else 404)
                "PUT" -> {
                    stored += request.getHeader("X-SHA-256")!!
                    if (puts.incrementAndGet() == 1) holdFirstPut()
                    MockResponse().setResponseCode(201).setBody("""{"url":"$base/$hash.jpg","sha256":"$hash","size":3,"type":"image/jpeg","uploaded":1}""")
                }
                "DELETE" -> { stored -= request.path!!.removePrefix("/"); MockResponse().setResponseCode(204) }
                else -> MockResponse().setResponseCode(405)
            }
        }

        fun count(method: String) = synchronized(log) { log.count { it == method } }
    }

    /** Holds every dispatched task until [open]; then runs them, and all later ones, on [Dispatchers.IO]. */
    private class Gate : kotlinx.coroutines.CoroutineDispatcher() {
        private val held = ArrayList<Runnable>()
        private var opened = false

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            synchronized(this) { if (!opened) { held += block; return } }
            Dispatchers.IO.dispatch(context, block)
        }

        fun open() {
            val waiting = synchronized(this) { opened = true; ArrayList(held).also { held.clear() } }
            waiting.forEach { Dispatchers.IO.dispatch(kotlin.coroutines.EmptyCoroutineContext, it) }
        }
    }

    private fun awaitTrue(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (condition()) return true; Thread.sleep(10) }
        return condition()
    }

    @Test
    fun `two uploads of the same bytes at once give one fresh result, and the other one deletes nothing`() = runTest {
        val secondHead = CountDownLatch(1)
        // the first PUT waits for a second HEAD: without one upload after the other, both HEADs see 404
        val storing = StoringServer(holdFirstPut = { secondHead.await(1, TimeUnit.SECONDS) })
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                storing.dispatch(request).also { if (request.method == "HEAD" && storing.count("HEAD") == 2) secondHead.countDown() }
        }
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val uploader = uploader(scope)
        val a = async(Dispatchers.IO) { uploader.upload("content://pictures/1", ImageTarget.NOTE) }
        val b = async(Dispatchers.IO) { uploader.upload("content://pictures/2", ImageTarget.NOTE) }
        val results = listOf(a.await(), b.await())
        assertEquals(1, results.count { it.fresh }, "fresh: ${results.map { it.fresh }}")
        uploader.discard(results.single { !it.fresh })
        assertEquals(false, awaitTrue(300) { storing.count("DELETE") > 0 })
        assertEquals(true, hash in storing.stored)
    }

    @Test
    fun `the delete of a cancelled upload reaches the server before the same bytes are looked up again`() = runTest {
        val release = CountDownLatch(1)
        val storing = StoringServer(holdFirstPut = { release.await(10, TimeUnit.SECONDS) })
        server.dispatcher = storing
        val gate = Gate()
        try {
            val uploader = uploader(CoroutineScope(SupervisorJob() + gate))
            val first = async(Dispatchers.IO) { uploader.upload("content://pictures/7", ImageTarget.NOTE) }
            assertEquals(true, awaitTrue(5_000) { storing.count("PUT") == 1 })
            first.cancel()
            assertInstanceOf(CancellationException::class.java, runCatching { first.await() }.exceptionOrNull())
            // the delete is held back in the background; the same picture is picked again right away
            val second = async(Dispatchers.IO) { uploader.upload("content://pictures/7", ImageTarget.NOTE) }
            // one upload after the other: the second does not look the blob up before the delete went out
            awaitTrue(1_000) { storing.count("HEAD") == 2 }
            gate.open()
            val media = second.await()
            assertEquals(listOf("HEAD", "PUT", "DELETE", "HEAD", "PUT"), storing.log.toList())
            assertEquals(true, media.fresh)
            assertEquals(true, hash in storing.stored)
        } finally {
            gate.open()
            release.countDown()
        }
    }

    private fun methods(): List<String> = generateSequence { server.takeRequest(300, TimeUnit.MILLISECONDS) }.map { it.method!! }.toList()

    @Test
    fun `upload prepares for the target, sends to the configured server and describes the result`() = runTest {
        serve(has = false)
        val media = uploader(this).upload("content://pictures/7", ImageTarget.AVATAR)
        assertEquals(listOf("AVATAR:content://pictures/7"), prepared)
        assertEquals(UploadedMedia(base, "$base/$hash.jpg", "image/jpeg", hash, 3, 400, 300, "LNM}7u}qfQ}q", fresh = true), media)
        assertEquals(listOf("HEAD", "PUT"), methods())
        val a = media.toAttachment()
        assertEquals(listOf(media.url, "image/jpeg", hash, 3L, 400, 300, "LNM}7u}qfQ}q"), listOf(a.url, a.mime, a.sha256, a.size, a.width, a.height, a.blurhash))
    }

    @Test
    fun `a refusal by the server reaches the caller`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "HEAD") MockResponse().setResponseCode(404) else MockResponse().setResponseCode(403).setHeader("X-Monostr-Reason", "nsfw")
        }
        val e = runCatching { uploader(this).upload("content://pictures/7", ImageTarget.NOTE) }.exceptionOrNull()
        assertInstanceOf(UploadException.Nsfw::class.java, e)
    }

    @Test
    fun `discard deletes at the server the picture was uploaded to, in the background`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val media = UploadedMedia(base, "$base/$hash.jpg", "image/jpeg", hash, 3, 400, 300, null, fresh = true)
        // the setting has changed since the upload: the delete still goes to the old server
        settings.mediaServerState.value = "https://elsewhere.example"
        uploader(scope).discard(media)
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("DELETE", request!!.method)
        assertEquals("/$hash", request.path)
    }

    @Test
    fun `a failed discard is swallowed`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        server.shutdown()
        uploader(scope).discard(UploadedMedia(base, "$base/$hash.jpg", "image/jpeg", hash, 3, 400, 300, null, fresh = true))
        // nothing to assert but that no exception escaped the background scope
    }

    @Test
    fun `a discard that would need the user sends nothing and asks nobody`() = runTest {
        val escaped = ArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> escaped += e })
        val needsUser = FakeSigner(onSign = { fail("a discard must not open a signer prompt") }, onSilent = { throw SilentSignUnavailable() })
        val uploader = BlossomMediaUploader(preparer, BlossomClient(OkHttpClient(), needsUser) { 1000L }, settings, scope)
        uploader.discard(UploadedMedia(base, "$base/$hash.jpg", "image/jpeg", hash, 3, 400, 300, null, fresh = true))
        assertEquals(emptyList<String>(), methods())
        assertEquals(emptyList<Throwable>(), escaped)
    }

    @Test
    fun `a blob the server already had is not fresh and is never deleted`() = runTest {
        serve(has = true)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val media = uploader(scope).upload("content://pictures/7", ImageTarget.NOTE)
        assertEquals(false, media.fresh)
        uploader(scope).discard(media)
        assertEquals(listOf("HEAD", "PUT"), methods()) // no DELETE
    }

    @Test
    fun `an upload that is cancelled while the body is on its way is deleted at the server`() = runTest {
        val release = CountDownLatch(1)
        serve(has = false, putGate = release)
        try {
            val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
            val upload = async(Dispatchers.IO) { uploader(scope).upload("content://pictures/7", ImageTarget.NOTE) }
            assertEquals("HEAD", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
            assertEquals("PUT", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
            upload.cancel()
            val outcome = runCatching { upload.await() }.exceptionOrNull()
            assertInstanceOf(CancellationException::class.java, outcome)
            val delete = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(delete)
            assertEquals("DELETE", delete!!.method)
            assertEquals("/$hash", delete.path)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `cancelling the upload of a blob the server already had deletes nothing`() = runTest {
        val release = CountDownLatch(1)
        serve(has = true, putGate = release)
        try {
            val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
            val upload = async(Dispatchers.IO) { uploader(scope).upload("content://pictures/7", ImageTarget.NOTE) }
            assertEquals("HEAD", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
            assertEquals("PUT", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
            upload.cancel()
            assertInstanceOf(CancellationException::class.java, runCatching { upload.await() }.exceptionOrNull())
            assertEquals(emptyList<String>(), methods())
        } finally {
            release.countDown()
        }
    }
}
