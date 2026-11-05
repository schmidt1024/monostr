package com.monostr.app.ui.media

import com.monostr.app.data.GifMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class GifPlaybackTest {
    /** A GIF with one 1x1 frame per delay (centiseconds). */
    private fun gif(vararg delays: Int, globalTable: Boolean = true): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("GIF89a".toByteArray())
        o.write(byteArrayOf(1, 0, 1, 0, if (globalTable) 0x80.toByte() else 0, 0, 0))
        if (globalTable) o.write(byteArrayOf(0, 0, 0, -1, -1, -1))
        // a comment and the loop block are skipped
        o.write(byteArrayOf(0x21, 0xFE.toByte(), 2, 65, 66, 0))
        o.write(byteArrayOf(0x21, 0xFF.toByte(), 11) + "NETSCAPE2.0".toByteArray() + byteArrayOf(3, 1, 0, 0, 0))
        for (d in delays) {
            o.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, (d and 0xff).toByte(), (d shr 8).toByte(), 0, 0))
            // image with its own 2-colour table
            o.write(byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0x80.toByte(), 0, 0, 0, -1, -1, -1))
            o.write(byteArrayOf(2, 2, 0x44, 1, 0))
        }
        o.write(0x3B)
        return o.toByteArray()
    }

    @Test
    fun `the timing sums the frames, an unset delay counts like a browser`() {
        assertEquals(GifTiming.Info(2, 300), GifTiming.read(gif(10, 20).inputStream()))
        assertEquals(GifTiming.Info(2, 200), GifTiming.read(gif(0, 1).inputStream()))
        assertEquals(GifTiming.Info(1, 500), GifTiming.read(gif(50, globalTable = false).inputStream()))
        assertEquals(GifTiming.Info(3, 60_000), GifTiming.read(gif(2000, 2000, 2000).inputStream()))
    }

    @Test
    fun `anything else is no gif, a cut file still counts what is there`() {
        assertNull(GifTiming.read("not a gif at all".toByteArray().inputStream()))
        assertNull(GifTiming.read(ByteArray(0).inputStream()))
        val whole = gif(10, 20)
        assertEquals(1, GifTiming.read(whole.copyOf(whole.size - 20).inputStream())?.frames)
    }

    @Test
    fun `short loops repeat until about five seconds, a long one plays once`() {
        assertEquals(5_600L, GifPlayback.playMillis(800L))
        assertEquals(5_000L, GifPlayback.playMillis(5_000L))
        assertEquals(61_500L, GifPlayback.playMillis(61_500L))
        assertEquals(GifPlayback.BUDGET_MS, GifPlayback.playMillis(null))
    }

    @Test
    fun `autoplay needs the setting, animations on, a playable size`() {
        assertTrue(GifPlayback.autoplays(GifMode.AUTO, animationsOff = false, bytes = 1_000))
        assertFalse(GifPlayback.autoplays(GifMode.AUTO, animationsOff = true, bytes = 1_000))
        assertFalse(GifPlayback.autoplays(GifMode.ON_TAP, animationsOff = false, bytes = 1_000))
        assertFalse(GifPlayback.autoplays(GifMode.OFF, animationsOff = false, bytes = 1_000))
        assertFalse(GifPlayback.autoplays(GifMode.AUTO, animationsOff = false, bytes = GifPlayback.MAX_AUTOPLAY_BYTES + 1))
    }

    @Test
    fun `what looks like a gif`() {
        assertTrue(GifPlayback.isGif("https://a.example/x.gif", null))
        assertTrue(GifPlayback.isGif("https://a.example/x.GIF?w=1", null))
        assertTrue(GifPlayback.isGif("https://a.example/x", "image/gif"))
        assertFalse(GifPlayback.isGif("https://a.example/x.jpg", "image/jpeg"))
        assertFalse(GifPlayback.isGif("https://a.example/gif/x.png", null))
    }

    @Test
    fun `only the topmost mostly visible gif plays, one at a time`() {
        val a = GifStage()
        a.report("low", top = 900f, visibleShare = 1f)
        assertEquals("low", a.active.value)
        a.report("high", top = 100f, visibleShare = 0.8f)
        assertEquals("high", a.active.value)
        a.report("high", top = -400f, visibleShare = 0.2f)
        assertEquals("low", a.active.value)
        a.remove("low")
        assertNull(a.active.value)
        a.report("high", top = 0f, visibleShare = 1f)
        a.setPaused(true)
        assertNull(a.active.value)
        a.setPaused(false)
        assertEquals("high", a.active.value)
    }
}
