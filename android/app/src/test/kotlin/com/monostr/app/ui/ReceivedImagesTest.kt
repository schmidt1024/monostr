package com.monostr.app.ui

import com.monostr.app.ui.compose.ReceivedImages
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Spec 2026-10-16 §3: a pasted or inserted picture is taken only as another app's content:// image. */
class ReceivedImagesTest {
    private val own = "com.monostr.app.debug"

    @Test
    fun `another app's content picture is taken`() {
        assertTrue(ReceivedImages.accept("content", "com.android.providers.media.documents", "image/jpeg", own))
        assertTrue(ReceivedImages.accept("content", "com.google.android.inputmethod.latin.fileprovider", "image/gif", own))
    }

    @Test
    fun `files, monostr's own providers, other and unknown types are not`() {
        assertFalse(ReceivedImages.accept("file", null, "image/jpeg", own))
        assertFalse(ReceivedImages.accept("https", "example.com", "image/png", own))
        assertFalse(ReceivedImages.accept("content", "$own.files", "image/png", own))
        assertFalse(ReceivedImages.accept("content", "media", "video/mp4", own))
        assertFalse(ReceivedImages.accept("content", "media", null, own))
        assertFalse(ReceivedImages.accept("content", null, "image/png", own))
    }

    @Test
    fun `a picture is used up only when attached, other URIs only when they carry no text`() {
        assertTrue(ReceivedImages.taken(image = true, attached = true, hasText = false))
        assertFalse(ReceivedImages.taken(image = true, attached = false, hasText = false)) // no room: the keyboard is told
        assertTrue(ReceivedImages.taken(image = false, attached = false, hasText = false))
        assertFalse(ReceivedImages.taken(image = false, attached = false, hasText = true)) // its text, never its URI, goes in
    }

    @Test
    fun `a received picture is copied into the folder`(@TempDir tmp: File) {
        val dir = File(tmp, "received")
        val bytes = ByteArray(200_000) { it.toByte() }
        val copy = ReceivedImages.copy(ByteArrayInputStream(bytes), "image/gif", dir)
        assertNotNull(copy)
        assertEquals(dir, copy!!.parentFile)
        assertTrue(copy.name.endsWith(".gif"))
        assertArrayEquals(bytes, copy.readBytes())
        assertEquals("file://${copy.absolutePath}", ReceivedImages.source(copy))
    }

    @Test
    fun `a picture too large or unreadable leaves nothing behind`(@TempDir tmp: File) {
        val dir = File(tmp, "received")
        assertNull(ReceivedImages.copy(ByteArrayInputStream(ByteArray(101)), "image/png", dir, maxBytes = 100))
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("gone")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("gone")
        }
        assertNull(ReceivedImages.copy(broken, "image/png", dir))
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `only copies in the folder are released`(@TempDir tmp: File) {
        val dir = File(tmp, "received")
        val copy = ReceivedImages.copy(ByteArrayInputStream(byteArrayOf(1)), "image/jpeg", dir)!!
        val outside = File(tmp, "keep.jpg").apply { writeBytes(byteArrayOf(1)) }
        ReceivedImages.release("file://${outside.absolutePath}", dir)
        ReceivedImages.release("file://${dir.absolutePath}/../keep.jpg", dir)
        ReceivedImages.release("content://media/1", dir)
        assertTrue(outside.exists())
        ReceivedImages.release(ReceivedImages.source(copy), dir)
        assertFalse(copy.exists())
    }

    @Test
    fun `old copies left by a killed process are swept`(@TempDir tmp: File) {
        val dir = File(tmp, "received")
        val old = ReceivedImages.copy(ByteArrayInputStream(byteArrayOf(1)), "image/jpeg", dir)!!
        val fresh = ReceivedImages.copy(ByteArrayInputStream(byteArrayOf(2)), "image/jpeg", dir)!!
        val now = System.currentTimeMillis()
        old.setLastModified(now - 25 * 60 * 60 * 1000L)
        ReceivedImages.sweep(dir, now)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        ReceivedImages.sweep(File(tmp, "missing"), now) // no folder yet: nothing to do
    }
}
