package com.monostr.app.ui

import com.monostr.app.ui.media.Blurhash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlurhashTest {
    @Test
    fun `decodes a known hash into opaque pixels`() {
        val px = Blurhash.decode("LEHV6nWB2yk8pyo0adR*.7kCMdnj", 8, 6)!!
        assertEquals(48, px.size)
        assertTrue(px.all { (it ushr 24) == 0xFF })
        // the reference picture is a landscape with a warm ground and a cooler sky:
        // both a clearly warm and a clearly cool pixel must be present (the whole-image
        // average nets out close to neutral grey, so it is not a useful signal here).
        fun warmth(p: Int) = ((p shr 16) and 0xFF) - (p and 0xFF)
        assertTrue(px.any { warmth(it) > 30 })
        assertTrue(px.any { warmth(it) < -30 })
        // Pixels pinned against the npm `blurhash` package's own decode() of the same hash
        // at 8x6 (the canonical woltapp reference implementation), to pin the sRGB<->linear
        // conversion and the cosine basis against regressions. +/-1 per channel absorbs float
        // rounding differences between this Kotlin (Float) and the JS (Double) reference.
        assertPixel(px[0], 0xFF87A4B1.toInt()) // top-left: cool sky (r=135,g=164,b=177)
        assertPixel(px[47], 0xFF8B8F92.toInt()) // bottom-right (r=139,g=143,b=146)
    }

    private fun assertPixel(actual: Int, expected: Int) {
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val a = (actual ushr shift) and 0xFF
            val e = (expected ushr shift) and 0xFF
            assertTrue(kotlin.math.abs(a - e) <= 1) { "channel at shift $shift: expected $e +/-1, was $a" }
        }
    }

    @Test
    fun `rejects garbage`() {
        assertNull(Blurhash.decode("", 4, 4))
        assertNull(Blurhash.decode("L", 4, 4))
        assertNull(Blurhash.decode("LEHV6nWB2yk8py", 4, 4)) // too short for its component count
        assertNull(Blurhash.decode("LEHV6nWB2yk8pyo0adR*.7kCMdn!", 4, 4)) // '!' is not in the base83 alphabet
    }

    /** 32 x 24, pixel (x, y) = rgb(x*8, y*10, (x+y)*4): the picture the reference hashes below were made from. */
    private fun gradient(): IntArray = IntArray(32 * 24) { i ->
        val x = i % 32
        val y = i / 32
        (0xFF shl 24) or ((x * 8) shl 16) or ((y * 10) shl 8) or ((x + y) * 4)
    }

    @Test
    fun `encodes like the reference implementation`() {
        assertEquals("LxH27b2kwzX5mAWYjuf7gKfkfQfj", Blurhash.encode(gradient(), 32, 24))
        assertEquals("00H27b", Blurhash.encode(gradient(), 32, 24, componentsX = 1, componentsY = 1))
        val solid = IntArray(8 * 8) { (0xFF shl 24) or (200 shl 16) or (120 shl 8) or 40 }
        assertEquals("LNM}7u}qfQ}q}qs.fQs.fQfQfQfQ", Blurhash.encode(solid, 8, 8))
    }

    @Test
    fun `what it encodes, the decoder reads back`() {
        val solid = IntArray(16 * 16) { (0xFF shl 24) or (200 shl 16) or (120 shl 8) or 40 }
        // 1 x 1 components: only the average colour. With more, the AC terms of a solid picture are not zero
        // (the blurhash cosine basis has no half-sample offset), so the decoded corners would deviate.
        val px = Blurhash.decode(Blurhash.encode(solid, 16, 16, componentsX = 1, componentsY = 1)!!, 4, 4)!!
        for (p in px) assertPixel(p, 0xFFC87828.toInt())
        // transparency is ignored: only the colour channels are hashed
        val clear = IntArray(8 * 8) { (200 shl 16) or (120 shl 8) or 40 }
        assertEquals(Blurhash.encode(IntArray(8 * 8) { (0xFF shl 24) or (200 shl 16) or (120 shl 8) or 40 }, 8, 8), Blurhash.encode(clear, 8, 8))
    }

    @Test
    fun `impossible sizes give no hash`() {
        assertNull(Blurhash.encode(IntArray(0), 0, 0))
        assertNull(Blurhash.encode(IntArray(10), 4, 4)) // 10 pixels are not 4 x 4
        assertNull(Blurhash.encode(IntArray(16), 4, 4, componentsX = 0))
        assertNull(Blurhash.encode(IntArray(16), 4, 4, componentsY = 10))
    }
}
