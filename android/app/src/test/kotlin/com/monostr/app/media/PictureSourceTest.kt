package com.monostr.app.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/** Spec 2026-10-16 §7: the original bytes from Coil's disk cache, else a fresh download; neither is an error. Cleanup 0.21.1: through a temp file, at most the cap. */
class PictureSourceTest {
    @TempDir lateinit var dir: File

    private fun body(s: String, header: String? = null, length: Long? = null) = PictureSource.Body(ByteArrayInputStream(s.toByteArray()), length, header)
    private fun source(cached: suspend (String) -> PictureSource.Body?, download: suspend (String) -> PictureSource.Body, limit: Long = MAX_PICTURE_BYTES) =
        PictureSource(dir, cached, download, limit = limit)

    @Test
    fun `a cached picture is read from the cache, nothing is downloaded`() = runTest {
        var downloads = 0
        val src = source(cached = { body("cached") }, download = { downloads++; body("net", "image/png") })
        src.use("https://x/a.jpg") { p ->
            assertEquals("cached", p.file.readText())
            assertNull(p.header)
        }
        assertEquals(0, downloads)
    }

    @Test
    fun `a picture not in the cache, or a cache that fails, is downloaded`() = runTest {
        val src = source(cached = { null }, download = { body("net", "image/png") })
        assertEquals("image/png", src.use("u") { it.header })
        val broken = source(cached = { throw IOException("cache gone") }, download = { body("net") })
        assertEquals("net", broken.use("u") { it.file.readText() })
    }

    @Test
    fun `no cache and no network, or an empty body, is an error`() = runTest {
        val offline = source(cached = { null }, download = { throw IOException("offline") })
        assertTrue(runCatching { offline.use("u") {} }.exceptionOrNull() is IOException)
        val empty = source(cached = { null }, download = { body("", "image/png") })
        assertTrue(runCatching { empty.use("u") {} }.exceptionOrNull() is IOException)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `a picture over the limit is too large, from the cache or the network`() = runTest {
        val big = source(cached = { body("0123456789") }, download = { body("net") }, limit = 5)
        assertTrue(runCatching { big.use("u") {} }.exceptionOrNull() is PictureTooLargeException)
        val declared = source(cached = { null }, download = { body("abc", length = 6) }, limit = 5)
        assertTrue(runCatching { declared.use("u") {} }.exceptionOrNull() is PictureTooLargeException)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `the temp file is gone after the work, done or failed`() = runTest {
        val src = source(cached = { null }, download = { body("net") })
        var seen: File? = null
        src.use("u") { p -> seen = p.file; assertTrue(p.file.exists()) }
        assertTrue(seen!!.parentFile == dir && !seen!!.exists())
        assertTrue(runCatching { src.use("u") { p -> seen = p.file; throw IOException("disk full") } }.exceptionOrNull() is IOException)
        assertTrue(!seen!!.exists())
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `the body is closed after reading`() = runTest {
        var closed = 0
        val src = source(cached = { null }, download = { PictureSource.Body(ByteArrayInputStream("net".toByteArray()), null, null) { closed++ } })
        src.use("u") {}
        assertEquals(1, closed)
    }

    @Test
    fun `a cancel during the copy skips the block and deletes the temp file`() = runTest {
        var ran = false
        val src = source(cached = { null }, download = { currentCoroutineContext()[Job]!!.cancel(); body("net", "image/png") })
        assertThrows<CancellationException> { src.use("u") { ran = true } }
        assertEquals(false, ran)
        assertEquals(0, dir.listFiles()!!.size)
    }
}
