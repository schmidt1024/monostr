package com.monostr.app.media

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import com.monostr.nostr.PerfTrace
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Spec 2026-11-12: the poster of a video whose note gives none, taken from the video itself near its start. Only through
 * [RangeReader] on the app's gated client [http] (Tor when Tor is on), never the whole file; one extraction at a time app-wide;
 * the result goes to [store] as a JPEG and never leaves the device.
 */
class VideoFrameExtractor(private val http: () -> OkHttpClient, private val store: VideoFrameStore) {
    private val turn = Semaphore(1)

    /** Videos that cannot give a frame this way (no ranges, too far in, too slow, undecodable): not asked again this run. */
    private val failed = object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > FAILED_MAX
    }

    /** The stored poster of [url] (`fresh` when it was just extracted), or null when the video gives none. */
    suspend fun frame(url: String): Pair<File, Boolean>? = withContext(Dispatchers.IO) {
        store.get(url)?.let { return@withContext it to false }
        if (synchronized(failed) { url in failed } || store.failedRecently(url)) return@withContext null
        turn.withPermit {
            // another cell of the same video may have finished while this one waited
            store.get(url)?.let { return@withPermit it to false }
            extract(url)?.let { store.put(url, it) to true }
        }
    }

    private suspend fun extract(url: String): ByteArray? = coroutineScope {
        val reader = RangeReader(http(), url)
        val started = SystemClock.uptimeMillis()
        // the retriever blocks in native code and reads from its own threads: a stop is what ends it, for a cancel and the time cap
        val timer = launch { delay(VideoFrameRules.MAX_MS); reader.stop(RangeStop.Reason.TIMEOUT) }
        // undispatched: a cancel right after the start must still reach the finally, or the retriever reads on to the cap
        val canceler = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { reader.stop(RangeStop.Reason.CANCELED) } }
        var reason: RangeStop.Reason? = null
        var ioError = false
        val jpeg = try {
            retrieve(reader)
        } catch (e: IOException) {
            // a stop arrives as a RangeStop (an IOException) and is told by [reason]; anything else is the network
            ioError = e !is RangeStop
            null
        } catch (e: RuntimeException) {
            // setDataSource and the frame calls throw IllegalArgumentException / IllegalStateException for what they cannot read
            null
        } finally {
            // read before the canceler below ends: its stop would read as a cancel
            reason = reader.stopped
            timer.cancel()
            canceler.cancel()
        }
        if (PerfTrace.enabled) {
            val what = if (jpeg != null) "" else " failed ${reason ?: "decode"}"
            PerfTrace.mark("video frame ${SystemClock.uptimeMillis() - started} ${reader.downloaded}$what")
        }
        if (jpeg == null && rememberFailure(reason, reader.downloaded, ioError)) {
            synchronized(failed) { failed[url] = Unit }
            // no ranges, past the cap, undecodable: the same on the next start, so not read again for a day (perf review: up to
            // 4 MB per such video and cold start, over Tor); a time-out may be the circuit and stays in memory only
            if (reason != RangeStop.Reason.TIMEOUT) runCatching { store.markFailed(url) }
        }
        jpeg
    }

    private fun retrieve(reader: RangeReader): ByteArray? {
        // the first block shows whether the host serves ranges at all, before the retriever reads anything
        reader.open()
        // security review: only MP4/MOV/3GP («ftyp» box) and WebM/Matroska (EBML) reach the platform's media parser
        if (!VideoContainers.known(ByteArray(12).also { reader.readAt(0, it, 0, it.size) })) return null
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(RangeMediaSource(reader))
            val width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            // security review: the metadata may lie, so API 27+ always asks for a bounded frame; API 26 decodes only a frame
            // whose stated size is known and modest, and an out-of-memory there ends this extraction only
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1 && (width <= 0 || height <= 0 || maxOf(width, height) > VideoFrameRules.MAX_FULL_SIDE)) return null
            fun at(us: Long, option: Int): Bitmap? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    // a box that only the width limits: the height follows the ratio, also for a portrait video
                    r.getScaledFrameAtTime(us, option, VideoFrameRules.MAX_WIDTH, VideoFrameRules.MAX_WIDTH * 4)
                } else {
                    try { r.getFrameAtTime(us, option) } catch (_: OutOfMemoryError) { null }
                }
            val frame = at(VideoRules.POSTER_AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: if (reader.stopped == null) at(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) else null
            frame ?: return null
            val scaled = scaleDown(frame)
            try {
                val out = ByteArrayOutputStream()
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, VideoFrameRules.JPEG_QUALITY, out)) return null
                return out.toByteArray()
            } finally {
                if (scaled !== frame) scaled.recycle()
                frame.recycle()
            }
        } finally {
            runCatching { r.release() }
        }
    }

    /** API 26 has no scaled frame; a frame wider than [VideoFrameRules.MAX_WIDTH] is scaled here. */
    private fun scaleDown(frame: Bitmap): Bitmap {
        if (frame.width <= VideoFrameRules.MAX_WIDTH) return frame
        val height = maxOf(1, Math.round(frame.height * VideoFrameRules.MAX_WIDTH.toFloat() / frame.width))
        return Bitmap.createScaledBitmap(frame, VideoFrameRules.MAX_WIDTH, height, true)
    }

    /** The retriever's view of [reader]; an [IOException] makes it give up. */
    private class RangeMediaSource(private val reader: RangeReader) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = reader.readAt(position, buffer, offset, size)
        override fun getSize(): Long = reader.size
        override fun close() = Unit
    }

    companion object {
        private const val FAILED_MAX = 500

        /**
         * Whether a failed extraction is not tried again this run: a host without ranges, a frame past the caps, a video that did not
         * decode. Not a cancel (the cell scrolled away) and not a network error before anything was read (offline, Tor not ready).
         */
        fun rememberFailure(reason: RangeStop.Reason?, downloaded: Long, ioError: Boolean = false): Boolean = when (reason) {
            RangeStop.Reason.NO_RANGE, RangeStop.Reason.CAP, RangeStop.Reason.TIMEOUT -> true
            RangeStop.Reason.CANCELED -> false
            // read something and did not decode; a network error part way (circuit dropped) may work the next time
            null -> downloaded > 0 && !ioError
        }
    }
}

/** The containers whose first bytes [VideoFrameExtractor] accepts before the platform parser sees anything. */
internal object VideoContainers {
    /** `ftyp` at offset 4 (ISO base media: MP4, MOV, 3GP) or the EBML magic (WebM, Matroska). */
    fun known(head: ByteArray): Boolean {
        if (head.size >= 8 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() && head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()) return true
        return head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
    }
}
