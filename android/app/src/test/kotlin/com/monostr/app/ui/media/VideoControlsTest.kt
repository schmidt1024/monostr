package com.monostr.app.ui.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VideoControlsTest {
    @Test
    fun `short videos loop, long ones and unknown lengths do not`() {
        assertTrue(VideoControls.loops(15_000))
        assertTrue(VideoControls.loops(60_000))
        assertFalse(VideoControls.loops(60_001))
        assertFalse(VideoControls.loops(0))
        assertFalse(VideoControls.loops(-9_223_372_036_854_775_807L)) // C.TIME_UNSET
    }

    @Test
    fun `a double tap jumps ten seconds and stays inside the video`() {
        assertEquals(15_000, VideoControls.seekTarget(25_000, 100_000, forward = false))
        assertEquals(35_000, VideoControls.seekTarget(25_000, 100_000, forward = true))
        assertEquals(0, VideoControls.seekTarget(4_000, 100_000, forward = false))
        assertEquals(100_000, VideoControls.seekTarget(95_000, 100_000, forward = true))
        assertEquals(35_000, VideoControls.seekTarget(25_000, -9_223_372_036_854_775_807L, forward = true)) // length unknown
    }
}
