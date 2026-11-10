package com.monostr.app.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpacingTest {
    @Test
    fun `scale is the 4 dp grid plus a hairline`() {
        val scale = with(Spacing) { listOf(xxs, xs, s, m, l, xl, xxl, xxxl) }
        assertEquals(listOf(2, 4, 8, 12, 16, 24, 32, 48).map { it.dp }, scale)
    }
}
