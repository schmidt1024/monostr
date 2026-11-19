package com.monostr.app.ui.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GalleryDotsTest {
    @Test fun `few items - one dot each, none small`() {
        val d = GalleryDots.dots(3, 0f)
        assertEquals(listOf(0, 1, 2), d.map { it.item }); assertTrue(d.none { it.small })
        assertEquals(listOf(1f, 0f, 0f), d.map { it.active })
    }

    @Test fun `start of a long row - window at 0, last edge small`() {
        val d = GalleryDots.dots(19, 0f)
        assertEquals((0..6).toList(), d.map { it.item })
        assertFalse(d.first().small); assertTrue(d.last().small); assertEquals(1f, d.first().active)
    }

    @Test fun `middle - both edges small, active centred`() {
        val d = GalleryDots.dots(19, 9f)
        assertEquals((6..12).toList(), d.map { it.item })
        assertTrue(d.first().small); assertTrue(d.last().small); assertEquals(1f, d[3].active)
    }

    @Test fun `end - first edge small, last active`() {
        val d = GalleryDots.dots(19, 18f)
        assertEquals((12..18).toList(), d.map { it.item })
        assertTrue(d.first().small); assertFalse(d.last().small); assertEquals(1f, d.last().active)
    }

    @Test fun `between two cards both are half active`() {
        val d = GalleryDots.dots(5, 2.5f)
        assertEquals(0.5f, d[2].active, 0.001f); assertEquals(0.5f, d[3].active, 0.001f)
    }

    @Test fun `exactly seven - no small dots anywhere`() {
        assertTrue(GalleryDots.dots(7, 3f).none { it.small })
    }

    @Test fun `empty count gives no dots`() {
        assertTrue(GalleryDots.dots(0, 0f).isEmpty())
    }

    @Test fun `position - last card not visible keeps raw`() = assertEquals(4.2f, GalleryDots.position(7, 4.2f, null, 100f), 1e-4f)
    @Test fun `position - last card just entering keeps raw`() = assertEquals(4.2f, GalleryDots.position(7, 4.2f, 100f, 100f), 1e-4f)
    @Test fun `position - at the end is the last dot`() = assertEquals(6f, GalleryDots.position(7, 4.2f, 0f, 100f), 1e-4f)
    @Test fun `position - halfway blends`() = assertEquals(5.1f, GalleryDots.position(7, 4.2f, 50f, 100f), 1e-4f)
    @Test fun `position - bad last size keeps raw`() = assertEquals(4.2f, GalleryDots.position(7, 4.2f, 0f, 0f), 1e-4f)
    @Test fun `position - single item is 0`() = assertEquals(0f, GalleryDots.position(1, 0.5f, 0f, 100f), 1e-4f)
    @Test fun `position - short row at rest on the start is the first dot`() = assertEquals(0f, GalleryDots.position(2, 0f, 90f, 223f, 0f), 1e-4f)
    @Test fun `position - short row scrolled 45 blends within the shortened window`() =
        assertEquals(0.2f + 0.8f * (1f - 90f / 135f), GalleryDots.position(2, 0.2f, 90f, 223f, 45f), 1e-4f)
}
