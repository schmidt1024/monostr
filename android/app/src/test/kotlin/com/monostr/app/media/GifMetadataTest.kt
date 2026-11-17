package com.monostr.app.media

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class GifMetadataTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    private val header = ascii("GIF89a") + bytes(1, 0, 1, 0, 0x80, 0, 0) + bytes(0, 0, 0, 255, 255, 255) // 1x1, 2-colour table
    private val loop = bytes(0x21, 0xFF, 11) + ascii("NETSCAPE2.0") + bytes(3, 1, 0, 0, 0)
    private val control = bytes(0x21, 0xF9, 4, 0, 10, 0, 0, 0)
    private val image = bytes(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x4C, 0x01, 0)
    private val comment = bytes(0x21, 0xFE, 5) + ascii("hello") + bytes(0)
    private val xmp = bytes(0x21, 0xFF, 11) + ascii("XMP DataXMP") + bytes(4) + ascii("geo!") + bytes(0)

    private fun gif(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().apply { parts.forEach { write(it) }; write(0x3B) }.toByteArray()

    @Test
    fun `comments and foreign application blocks go, loop, timing and frames stay`() {
        val stripped = GifMetadata.strip(gif(header, loop, comment, xmp, control, image))!!
        assertArrayEquals(gif(header, loop, control, image), stripped)
    }

    @Test
    fun `a clean gif stays byte for byte`() {
        val clean = gif(header, loop, control, image)
        assertArrayEquals(clean, GifMetadata.strip(clean))
    }

    @Test
    fun `something that is no gif or breaks off is left alone`() {
        assertNull(GifMetadata.strip(ascii("PNG not a gif at all")))
        val cut = gif(header, loop, control, image).copyOf(header.size + 5)
        assertNull(GifMetadata.strip(cut))
        assertTrue(GifMetadata.strip(ByteArray(0)) == null)
    }
}
