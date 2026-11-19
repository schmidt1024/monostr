package com.monostr.app.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VideoLimitsTest {
    private fun json(seconds: String = "900", bytes: String = "314572800") =
        """{"max_video_seconds":$seconds,"max_video_bytes":$bytes,"videos_per_day":20,"quota_bytes":21474836480,"used_bytes":5,"future":true}"""

    @Test
    fun `the default is two minutes with the margin off`() {
        assertEquals(121_000L, VideoLimits.DEFAULT.maxSourceMs)
        assertEquals(2, VideoLimits.DEFAULT.minutes)
    }

    @Test
    fun `a trusted account's numbers derive`() {
        assertEquals(896_000L, VideoLimits(900, 314_572_800).maxSourceMs)
        assertEquals(15, VideoLimits(900, 314_572_800).minutes)
        assertEquals(1, VideoLimits(60, 52_428_800).minutes)
    }

    @Test
    fun `the server's answer parses, unknown keys ignored`() {
        assertEquals(VideoLimits(900, 314_572_800), VideoLimits.parse(json()))
    }

    @Test
    fun `anything missing, malformed or out of range is null`() {
        assertNull(VideoLimits.parse(json(seconds = "999999")))
        assertNull(VideoLimits.parse(json(seconds = "-1")))
        assertNull(VideoLimits.parse(json(seconds = "9")))
        assertNull(VideoLimits.parse(json(seconds = "\"900\"")))
        assertNull(VideoLimits.parse(json(bytes = "0")))
        assertNull(VideoLimits.parse(json(bytes = "419430400")))
        assertNull(VideoLimits.parse("""{"max_video_seconds":900}"""))
        assertNull(VideoLimits.parse("<html>"))
    }
}
