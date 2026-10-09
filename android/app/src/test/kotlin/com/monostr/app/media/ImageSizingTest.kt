package com.monostr.app.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImageSizingTest {
    @Test
    fun `fit scales the long edge down and keeps the ratio`() {
        assertEquals(2048 to 1536, ImageSizing.fit(4000, 3000, 2048))
        assertEquals(1536 to 2048, ImageSizing.fit(3000, 4000, 2048))
        assertEquals(2048 to 2048, ImageSizing.fit(5000, 5000, 2048))
        assertEquals(800 to 1, ImageSizing.fit(8000, 4, 800)) // a sliver keeps at least one pixel
    }

    @Test
    fun `fit never enlarges`() {
        assertEquals(640 to 480, ImageSizing.fit(640, 480, 2048))
        assertEquals(2048 to 100, ImageSizing.fit(2048, 100, 2048))
        assertEquals(1 to 1, ImageSizing.fit(1, 1, 800))
    }

    @Test
    fun `sample size halves only while the result stays at least as large as the target`() {
        assertEquals(1, ImageSizing.sampleSize(4000, 3000, 2048)) // half would be 2000, below the target
        assertEquals(2, ImageSizing.sampleSize(4096, 3072, 2048))
        assertEquals(4, ImageSizing.sampleSize(12000, 9000, 2048))
        assertEquals(1, ImageSizing.sampleSize(100, 100, 2048))
        assertEquals(8, ImageSizing.sampleSize(8000, 8000, 800))
    }

    @Test
    fun `the scale lands every axis of the upright picture exactly on the fitted size`() {
        // 3000 x 2000 stored, a quarter turn: 2000 x 3000 upright, fitted 1365 x 2048
        val (sx, sy) = ImageSizing.scale(3000, 2000, quarterTurn = true, longEdge = 2048)
        assertEquals(1365, Math.round(2000 * sx))
        assertEquals(2048, Math.round(3000 * sy))
        val (wx, wy) = ImageSizing.scale(3000, 2000, quarterTurn = false, longEdge = 2048)
        assertEquals(2048, Math.round(3000 * wx))
        assertEquals(1365, Math.round(2000 * wy))
        // the long edge of the turned picture decides, not the stored one
        val (ax, ay) = ImageSizing.scale(4000, 1000, quarterTurn = true, longEdge = 800)
        assertEquals(200, Math.round(1000 * ax))
        assertEquals(800, Math.round(4000 * ay))
    }

    @Test
    fun `a picture that already fits is not scaled`() {
        assertEquals(1f to 1f, ImageSizing.scale(640, 480, quarterTurn = false, longEdge = 2048))
        assertEquals(1f to 1f, ImageSizing.scale(640, 480, quarterTurn = true, longEdge = 2048))
    }

    @Test
    fun `a gif is known by its first bytes`() {
        assertTrue(ImageSizing.isGif("GIF89a....".toByteArray(Charsets.ISO_8859_1)))
        assertTrue(ImageSizing.isGif("GIF87a".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(ImageSizing.isGif("GIF".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(ImageSizing.isGif(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 0, 0)))
        assertFalse(ImageSizing.isGif(ByteArray(0)))
    }
}
