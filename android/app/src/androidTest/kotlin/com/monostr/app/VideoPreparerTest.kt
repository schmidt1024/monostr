package com.monostr.app

import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monostr.app.media.TransformerVideoPreparer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Spec 2026-10-27 video §4, §11: a phone video (landscape pixels, rotation flag, GPS) comes out portrait, 720p at most, H.264/AAC, without metadata. */
@RunWith(AndroidJUnit4::class)
class VideoPreparerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    private fun source(): String {
        val f = File(context.cacheDir, "source-${System.nanoTime()}.mp4")
        testContext.assets.open("rotated-gps.mp4").use { input -> f.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(f).toString()
    }

    /** Width and height as a player shows them. */
    private fun displayed(file: File): Pair<Int, Int> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            return if (rot % 180 != 0) h to w else w to h
        } finally {
            r.release()
        }
    }

    @Test
    fun a_phone_video_comes_out_portrait_small_and_without_metadata() = runBlocking {
        val prepared = TransformerVideoPreparer(context).prepare(source())
        try {
            val (w, h) = displayed(prepared.file)
            assertTrue("displayed ${w}x$h", w < h && minOf(w, h) <= 720)
            assertEquals("the note's dim is the displayed size", w to h, prepared.width to prepared.height)

            val r = MediaMetadataRetriever()
            r.setDataSource(prepared.file.path)
            // spec §4 (final review I5): the rotation is in the pixels, not a flag that some players ignore
            assertEquals("rotation flag", 0, r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0)
            assertTrue("stored pixels portrait",
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt() < r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt())
            assertNull("location", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
            // the recording's date is gone; the muxer writes the time of the encode, which the note shows anyway
            assertNull("source date", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.takeIf { it.startsWith("20260102") })
            r.release()
            assertTrue("no location atom in the bytes", "+47.3769" !in String(prepared.file.readBytes(), Charsets.ISO_8859_1))

            val mimes = MediaExtractor().run {
                setDataSource(prepared.file.path)
                val out = (0 until trackCount).map { getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                release()
                out
            }
            assertTrue("tracks $mimes", "video/avc" in mimes && "audio/mp4a-latm" in mimes)

            assertNotNull("poster", BitmapFactory.decodeFile(prepared.poster.path))
            assertEquals(prepared.size, prepared.file.length())
            assertTrue(prepared.durationMs in 1500..2500)
        } finally {
            prepared.delete()
        }
    }

    @Test
    fun a_cancel_leaves_no_file() = runBlocking {
        val dir = File(context.cacheDir, "video")
        dir.listFiles()?.forEach { it.delete() }
        val job = async(Dispatchers.Default) { TransformerVideoPreparer(context).prepare(source()) }
        delay(150)
        job.cancel()
        runCatching { job.await() }
        delay(500)
        assertEquals("left: ${dir.list()?.toList()}", 0, dir.list()?.size ?: 0)
    }
}
