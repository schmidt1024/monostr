package com.monostr.app.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec 2026-10-27 video §3, §4: what the device accepts and the size it encodes to. */
class VideoRulesTest {
    @Test
    fun `up to 121 s is taken, longer is not`() {
        assertFalse(VideoRules.tooLong(120_999))
        assertFalse(VideoRules.tooLong(121_000))
        assertTrue(VideoRules.tooLong(121_001))
    }

    @Test
    fun `the shorter side becomes 720 at most`() {
        assertEquals(1280 to 720, VideoRules.target(1920, 1080, 0))
        assertEquals(720 to 1280, VideoRules.target(1080, 1920, 0))
    }

    @Test
    fun `a rotation flag is applied`() {
        assertEquals(720 to 1280, VideoRules.target(1920, 1080, 90))
        assertEquals(720 to 1280, VideoRules.target(1920, 1080, 270))
        assertEquals(1280 to 720, VideoRules.target(1920, 1080, 180))
    }

    @Test
    fun `small videos are not scaled up`() {
        assertEquals(640 to 360, VideoRules.target(640, 360, 0))
    }

    @Test
    fun `odd sizes become even`() {
        assertEquals(720 to 1282, VideoRules.target(1081, 1925, 0)) // 1925 * 720 / 1081 = 1282.2
        assertEquals(638 to 358, VideoRules.target(639, 359, 0))
    }
}
