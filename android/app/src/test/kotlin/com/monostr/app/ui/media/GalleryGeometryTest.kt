package com.monostr.app.ui.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GalleryGeometryTest {
    private val tol = 0.01f

    private fun assertList(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertEquals(expected[it], actual[it], tol) }
    }

    @Test fun `aspect prefers dim, then a contradicting learned size, then the fallback`() {
        assertEquals(0.75f, GalleryGeometry.aspect(300 to 400, null, video = false), tol)
        assertEquals(0.75f, GalleryGeometry.aspect(300 to 400, 303 to 400, video = false), tol)
        assertEquals(4f / 3f, GalleryGeometry.aspect(300 to 400, 400 to 300, video = false), tol)
        assertEquals(2f, GalleryGeometry.aspect(null, 200 to 100, video = false), tol)
        assertEquals(0.8f, GalleryGeometry.aspect(null, null, video = false), tol)
        assertEquals(16f / 9f, GalleryGeometry.aspect(null, null, video = true), tol)
        assertEquals(0.8f, GalleryGeometry.aspect(0 to 400, null, video = false), tol)
    }

    @Test fun `three portraits overflow, next card peeks`() {
        val l = GalleryGeometry.layout(listOf(0.8f, 0.8f, 0.8f), rowWidthDp = 360f, maxHeightDp = 600f)
        assertEquals(279f, l.rowHeightDp, tol)
        assertList(listOf(223.2f, 223.2f, 223.2f), l.cardWidthsDp)
        assertEquals(listOf(false, false, false), l.crop)
        assertTrue(l.overflows)
    }

    @Test fun `two that fit do not overflow`() {
        val l = GalleryGeometry.layout(listOf(1f, 0.5f), 360f, 600f)
        assertEquals(223.2f, l.rowHeightDp, tol)
        assertList(listOf(223.2f, 111.6f), l.cardWidthsDp)
        assertFalse(l.overflows)
    }

    @Test fun `row height is clamped to min and cap`() {
        assertEquals(160f, GalleryGeometry.layout(listOf(1.5f, 1.5f), 360f, 600f).rowHeightDp, tol)
        assertEquals(600f, GalleryGeometry.layout(listOf(0.2f, 1f), 360f, 600f).rowHeightDp, tol)
    }

    @Test fun `extreme cards are clamped and cropped`() {
        val tall = GalleryGeometry.layout(listOf(0.2f, 1f), 360f, 600f)
        assertEquals(300f, tall.cardWidthsDp[0], tol); assertTrue(tall.crop[0])
        val pano = GalleryGeometry.layout(listOf(1f, 4f), 360f, 600f)
        assertEquals(306f, pano.cardWidthsDp[1], tol); assertTrue(pano.crop[1])
    }

    @Test fun `layout keeps one width per item, in order`() {
        val aspects = List(20) { i -> if (i % 2 == 0) 0.8f else 1.5f }
        val l = GalleryGeometry.layout(aspects, 360f, 600f)
        assertEquals(20, l.cardWidthsDp.size); assertTrue(l.cardWidthsDp.all { it > 0f })
    }

    @Test fun `cardWidth clamps against a frozen row height`() {
        val a = GalleryGeometry.cardWidth(0.8f, 279f, 360f)
        assertEquals(223.2f, a.first, tol); assertFalse(a.second)
        val b = GalleryGeometry.cardWidth(4f, 160f, 360f)
        assertEquals(306f, b.first, tol); assertTrue(b.second)
        val c = GalleryGeometry.cardWidth(0.2f, 600f, 360f)
        assertEquals(300f, c.first, tol); assertTrue(c.second)
        val narrow = GalleryGeometry.cardWidth(0.2f, 640f, 296f)
        assertEquals(251.6f, narrow.first, tol); assertTrue(narrow.second)
    }
}
