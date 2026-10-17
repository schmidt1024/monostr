package com.monostr.app.media

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/** Spec 2026-10-16 §7: the original bytes from Coil's disk cache, else a fresh download; neither is an error. */
class PictureSourceTest {
    @Test
    fun `a cached picture is read from the cache, nothing is downloaded`() = runTest {
        var downloads = 0
        val src = PictureSource(cached = { "cached".toByteArray() }, download = { downloads++; "net".toByteArray() to "image/png" })
        val p = src.load("https://x/a.jpg")
        assertEquals("cached", String(p.bytes))
        assertNull(p.header)
        assertEquals(0, downloads)
    }

    @Test
    fun `a picture not in the cache, or a cache that fails, is downloaded`() = runTest {
        val src = PictureSource(cached = { null }, download = { "net".toByteArray() to "image/png" })
        assertEquals("image/png", src.load("u").header)
        val broken = PictureSource(cached = { throw IOException("cache gone") }, download = { "net".toByteArray() to null })
        assertEquals("net", String(broken.load("u").bytes))
    }

    @Test
    fun `no cache and no network, or an empty body, is an error`() = runTest {
        val offline = PictureSource(cached = { null }, download = { throw IOException("offline") })
        assertTrue(runCatching { offline.load("u") }.exceptionOrNull() is IOException)
        val empty = PictureSource(cached = { null }, download = { ByteArray(0) to "image/png" })
        assertTrue(runCatching { empty.load("u") }.exceptionOrNull() is IOException)
    }
}
