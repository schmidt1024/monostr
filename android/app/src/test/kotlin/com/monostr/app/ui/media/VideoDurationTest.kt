package com.monostr.app.ui.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VideoDurationTest {
    @Test
    fun `a video's length reads like a player's clock`() {
        assertEquals("0:05", VideoDuration.label(5))
        assertEquals("1:00", VideoDuration.label(60))
        assertEquals("8:12", VideoDuration.label(492))
        assertEquals("59:59", VideoDuration.label(3599))
        assertEquals("1:00:00", VideoDuration.label(3600))
        assertEquals("1:02:03", VideoDuration.label(3723))
    }
}
