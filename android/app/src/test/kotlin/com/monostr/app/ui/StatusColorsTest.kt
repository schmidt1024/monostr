package com.monostr.app.ui

import com.monostr.app.ui.theme.Contrast
import com.monostr.app.ui.theme.MonoColors
import com.monostr.app.ui.theme.StatusColors
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StatusColorsTest {
    @Test
    fun `status colours read on their surfaces`() {
        listOf(StatusColors.LightOk, StatusColors.LightBad).forEach { c ->
            assertTrue(Contrast.ratio(c, MonoColors.LightSurface) >= 4.5, "light ${c.toString(16)}")
        }
        listOf(StatusColors.DarkOk, StatusColors.DarkBad).forEach { c ->
            assertTrue(Contrast.ratio(c, MonoColors.DarkSurface) >= 4.5, "dark ${c.toString(16)}")
        }
    }
}
