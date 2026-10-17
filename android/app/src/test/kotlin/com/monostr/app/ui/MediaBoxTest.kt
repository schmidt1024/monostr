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

    @Test
    fun `a video without dimensions is a 16 to 9 box, cropped, never 4 to 3`() {
        assertEquals(MediaBox(MediaBoxes.VIDEO_FALLBACK_RATIO, crop = true), MediaBoxes.single(null, widthDp = 360f, video = true))
    }

    @Test
    fun `a video with dimensions follows the picture rules but its poster always fills the box`() {
        assertEquals(MediaBox(1600f / 900f, crop = true), MediaBoxes.single(1600 to 900, widthDp = 360f, video = true))
        assertEquals(MediaBox(360f / MediaBoxes.MAX_HEIGHT_DP, crop = true), MediaBoxes.single(1080 to 1920, widthDp = 360f, video = true))
    }

    @Test
    fun `a remembered size is used exactly like a dim`() {
        val cache = com.monostr.app.ui.media.MediaRatioCache()
        cache.put("https://x/a.jpg", 1000, 500)
        assertEquals(MediaBoxes.single(1000 to 500, widthDp = 360f), MediaBoxes.single(cache["https://x/a.jpg"], widthDp = 360f))
    }
}
