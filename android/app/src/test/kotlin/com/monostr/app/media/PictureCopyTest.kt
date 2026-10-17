package com.monostr.app.media

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Cleanup 0.21.1: a picture is copied in chunks and refused past the cap, never held whole in memory. */
class PictureCopyTest {
    @Test
    fun `the cap is fifty megabytes`() {
        assertEquals(50L * 1024 * 1024, MAX_PICTURE_BYTES)
    }

    @Test
    fun `a picture at or under the limit is copied exactly`() {
        val bytes = ByteArray(100) { it.toByte() }
        val out = ByteArrayOutputStream()
        assertEquals(100L, PictureCopy.capped(ByteArrayInputStream(bytes), out, limit = 100))
        assertArrayEquals(bytes, out.toByteArray())
    }

    @Test
    fun `the copy stops as soon as more than the limit was read`() {
        var served = 0L
        // endless stream: the copy must stop on its own
        val endless = object : InputStream() {
            override fun read(): Int { served++; return 7 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { b.fill(7, off, off + len); served += len; return len }
        }
        assertThrows<PictureTooLargeException> { PictureCopy.capped(endless, ByteArrayOutputStream(), limit = 1000) }
        assertTrue(served < 1000 + 64 * 1024, "read $served bytes")
    }

    @Test
    fun `a Content-Length over the limit is refused before anything is read`() {
        val untouchable = object : InputStream() {
            override fun read(): Int = error("must not be read")
            override fun read(b: ByteArray, off: Int, len: Int): Int = error("must not be read")
        }
        val out = ByteArrayOutputStream()
        assertThrows<PictureTooLargeException> { PictureCopy.capped(untouchable, out, limit = 10, declared = 11) }
        assertEquals(0, out.size())
        // a Content-Length within the limit is no reason to refuse
        assertEquals(3L, PictureCopy.capped(ByteArrayInputStream(byteArrayOf(1, 2, 3)), ByteArrayOutputStream(), limit = 10, declared = 3))
    }
}
