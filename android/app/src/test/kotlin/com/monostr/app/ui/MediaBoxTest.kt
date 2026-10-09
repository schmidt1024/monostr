package com.monostr.app.ui

import com.monostr.app.ui.media.MediaBox
import com.monostr.app.ui.media.MediaBoxes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaBoxTest {
    @Test
    fun `a landscape picture keeps its own ratio and is not cropped`() {
        val box = MediaBoxes.single(1600 to 900, widthDp = 360f)
        assertEquals(1600f / 900f, box.ratio, 0.001f)
        assertFalse(box.crop)
    }

    @Test
    fun `a panorama is a flat strip, fully visible, but never flatter than the minimum height`() {
        val wide = MediaBoxes.single(3000 to 1000, widthDp = 360f)
        assertEquals(3f, wide.ratio, 0.001f)
        assertFalse(wide.crop)
        val extreme = MediaBoxes.single(4000 to 100, widthDp = 360f)
        assertEquals(360f / MediaBoxes.MIN_HEIGHT_DP, extreme.ratio, 0.001f)
        assertFalse(extreme.crop)
    }

    @Test
    fun `a picture taller than the cap is boxed at the cap and cropped`() {
        val box = MediaBoxes.single(1000 to 2000, widthDp = 360f)
        assertEquals(360f / MediaBoxes.MAX_HEIGHT_DP, box.ratio, 0.001f)
        assertTrue(box.crop)
        val justFits = MediaBoxes.single(360 to 400, widthDp = 360f)
        assertEquals(MediaBox(0.9f, crop = false), justFits)
    }

    @Test
    fun `without dimensions the fallback box fits the picture`() {
        assertEquals(MediaBox(MediaBoxes.FALLBACK_RATIO, crop = false), MediaBoxes.single(null, widthDp = 360f))
        assertEquals(MediaBox(MediaBoxes.FALLBACK_RATIO, crop = false), MediaBoxes.single(0 to 10, widthDp = 360f))
    }
}
