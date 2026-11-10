package com.monostr.app.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.atan2

class SquircleShapeTest {
    private val eps = 1e-3f

    @Test
    fun `no smoothing is a plain quarter circle`() {
        val k = SquircleCorner.of(radius = 12f, smoothing = 0f, budget = 50f)
        assertEquals(12f, k.p, eps)
        assertEquals(12f, k.arc, eps)
        assertEquals(0f, k.a + k.b + k.c + k.d, eps)
        assertEquals(90f, k.sweep, eps)
    }

    @Test
    fun `smoothing starts the curve earlier and shortens the arc`() {
        val k = SquircleCorner.of(radius = 12f, smoothing = 0.6f, budget = 50f)
        assertEquals(19.2f, k.p, eps) // (1 + 0.6) * 12
        assertEquals(36f, k.sweep, eps)
        assertTrue(k.a > 0f && k.b > 0f && k.c > 0f && k.d > 0f)
        // the pieces add up to the length the curve takes from each edge
        assertEquals(k.p, k.a + k.b + k.c + k.d + k.arc, eps)
    }

    @Test
    fun `both ends of the arc sit on its circle, the sweep apart`() {
        for (s in listOf(0f, 0.3f, 0.6f, 1f)) {
            val k = SquircleCorner.of(radius = 20f, smoothing = s, budget = 100f)
            val f = CornerFrame(Offset(200f, 0f), Offset(1f, 0f), Offset(0f, 1f)) // top-right corner
            val centre = f.centre(k.radius)
            val start = f.before(k.p - k.a - k.b - k.c, k.d)
            val end = f.after(k.p - k.a - k.b - k.c, k.d)
            assertEquals(k.radius, (start - centre).getDistance(), 1e-2f, "start, smoothing $s")
            assertEquals(k.radius, (end - centre).getDistance(), 1e-2f, "end, smoothing $s")
            val turned = Math.toDegrees((angle(end - centre) - angle(start - centre)).toDouble()).toFloat()
            assertEquals(k.sweep, turned, 1e-2f, "sweep, smoothing $s")
        }
    }

    @Test
    fun `a pill keeps its round ends - smoothing gives way to the budget`() {
        // a 40 high button with a 20 radius has no room for the cubics: it stays a half circle
        val k = SquircleCorner.of(radius = 20f, smoothing = 0.6f, budget = 20f)
        assertEquals(20f, k.p, eps)
        assertEquals(0f, k.smoothing, eps)
        assertEquals(90f, k.sweep, eps)
        // a radius past the budget is cut to it
        assertEquals(20f, SquircleCorner.of(radius = 80f, smoothing = 0.6f, budget = 20f).radius, eps)
    }

    @Test
    fun `zero radius is a sharp corner`() {
        val k = SquircleCorner.of(radius = 0f, smoothing = 0.6f, budget = 20f)
        assertEquals(0f, k.radius)
        assertEquals(0f, k.p)
    }

    @Test
    fun `equality covers corners and smoothing, copy keeps the smoothing`() {
        assertEquals(SquircleShape(12.dp), SquircleShape(12.dp))
        assertEquals(SquircleShape(12.dp).hashCode(), SquircleShape(12.dp).hashCode())
        assertNotEquals(SquircleShape(12.dp), SquircleShape(16.dp))
        assertNotEquals(SquircleShape(12.dp), SquircleShape(12.dp, smoothing = 0f))
        val top = SquircleShape(24.dp, smoothing = 0.5f).copy(bottomEnd = CornerSize(0.dp), bottomStart = CornerSize(0.dp))
        assertTrue(top is SquircleShape)
        assertEquals(0.5f, (top as SquircleShape).smoothing)
    }

    private fun angle(o: Offset) = atan2(o.y, o.x).let { if (abs(it) < 1e-6f) 0f else it }
}
