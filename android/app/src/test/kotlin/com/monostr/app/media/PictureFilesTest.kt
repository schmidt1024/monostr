package com.monostr.app.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/** Spec 2026-10-16 §7: monostr-<sha256(url) first 12>.<ext>, the type from the bytes, the header, the URL, else jpeg. */
class PictureFilesTest {
    private fun hex12(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)

    @Test
    fun `the name is monostr, twelve hex of the url's sha256 and the type's ending`() {
        val url = "https://media.test/abc.png"
        assertEquals("monostr-${hex12(url)}.png", PictureFiles.name(url, "image/png"))
        assertEquals("monostr-${hex12(url)}.jpg", PictureFiles.name(url, "image/jpeg"))
        assertEquals("monostr-${hex12(url)}.webp", PictureFiles.name(url, "image/webp"))
        assertEquals("monostr-${hex12(url)}.jpg", PictureFiles.name(url, "application/octet-stream"))
    }

    @Test
    fun `the type comes from the bytes, then the header, then the url, else jpeg`() {
        assertEquals("image/webp", PictureFiles.mime("image/webp", "image/jpeg", "https://x/a.png"))
        assertEquals("image/png", PictureFiles.mime(null, "image/png; charset=binary", "https://x/a.jpg"))
        assertEquals("image/gif", PictureFiles.mime(null, "application/octet-stream", "https://x/a.GIF?w=200"))
        assertEquals("image/jpeg", PictureFiles.mime(null, null, "https://x/picture"))
    }
}
