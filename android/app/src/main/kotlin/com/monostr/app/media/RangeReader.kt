package com.monostr.app.media

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Spec 2026-11-12 video frame: the limits of deriving a poster from a video that has none. No Android types, so the JVM tests
 * reach them.
 */
object VideoFrameRules {
    /** What one range request asks for; reads are served from whole aligned blocks. */
    const val BLOCK_SIZE = 256 * 1024
    /** How long a video that gave no frame is not read again ([VideoFrameStore.markFailed]), and how many such marks stay. */
    const val FAILED_TTL_MS = 24L * 60 * 60 * 1000
    const val FAILED_MAX = 2_000
    /** Blocks kept in memory per extraction (2 MiB); the retriever jumps between the header, the index and the first frame. */
    const val CACHED_BLOCKS = 8
    /** Bytes read from the host per extraction at most; a video whose first frame lies further in keeps its play button only. */
    const val MAX_BYTES = 4L * 1024 * 1024
    /** Time per extraction at most, Tor included. */
    const val MAX_MS = 20_000L
    /** The derived poster's width at most. */
    const val MAX_WIDTH = 720
    /** API 26 only (no scaled frame): the longest stated side a full frame is decoded at; larger videos get no poster there. */
    const val MAX_FULL_SIDE = 1920
    const val JPEG_QUALITY = 80
    /** The disk cache of derived posters (about 300 at 50 KiB). */
    const val STORE_BYTES = 16L * 1024 * 1024

    /** `Content-Range: bytes <start>-<end>/<total>`; null for anything else, a total of `*` included. */
    fun parseContentRange(value: String?): Triple<Long, Long, Long>? {
        val m = CONTENT_RANGE.matchEntire(value?.trim() ?: return null) ?: return null
        val (start, end, total) = m.destructured
        val s = start.toLongOrNull() ?: return null
        val e = end.toLongOrNull() ?: return null
        val t = total.toLongOrNull() ?: return null
        return if (s <= e && e < t) Triple(s, e, t) else null
    }

    private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE) // regex
}

/** Why a [RangeReader] stopped reading; the extraction gives up quietly then. */
class RangeStop(val reason: Reason) : IOException("range read stopped: $reason") {
    enum class Reason {
        /** The host answered without a byte range (a 200 with the whole body, or a range that does not fit): nothing more is read. */
        NO_RANGE,
        /** [VideoFrameRules.MAX_BYTES] would be passed. */
        CAP,
        TIMEOUT,
        CANCELED,
    }
}

/**
 * Random reads of the file at [url] through HTTP range requests on the app's gated client [http] (spec 2026-11-12): whole
 * [blockSize] blocks aligned to their size, the last [cachedBlocks] of them kept, at most [maxBytes] read from the host in total.
 * The first request learns the size from `Content-Range`. Thread-safe; [stop] aborts a request in flight from any thread.
 */
class RangeReader(
    private val http: OkHttpClient,
    private val url: String,
    private val blockSize: Int = VideoFrameRules.BLOCK_SIZE,
    private val maxBytes: Long = VideoFrameRules.MAX_BYTES,
    private val cachedBlocks: Int = VideoFrameRules.CACHED_BLOCKS,
) {
    /** Bytes read from the host so far. */
    @Volatile var downloaded: Long = 0L
        private set

    /** The file's size, known after the first block; -1 before. */
    @Volatile var size: Long = -1L
        private set

    /** Set once reading has stopped for good. */
    @Volatile var stopped: RangeStop.Reason? = null
        private set

    /** Requests sent, for the tests. */
    @Volatile var requests: Int = 0
        private set

    @Volatile private var call: Call? = null

    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > cachedBlocks
    }

    /** Reads the first block, which tells the size; throws [RangeStop] for a host without ranges. */
    fun open(): Long = synchronized(this) {
        block(0)
        size
    }

    /** As `MediaDataSource.readAt`: up to [length] bytes at [position] into [buffer], -1 at the end. */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = synchronized(this) {
        if (length <= 0) return 0
        if (size < 0) block(0)
        if (position >= size) return -1
        val index = position / blockSize
        val data = block(index)
        val inBlock = (position - index * blockSize).toInt()
        if (inBlock >= data.size) return -1
        val n = minOf(length, data.size - inBlock)
        System.arraycopy(data, inBlock, buffer, offset, n)
        n
    }

    /** Ends all reading with [reason]; a request in flight is canceled. The first reason stays. */
    fun stop(reason: RangeStop.Reason) {
        if (stopped == null) stopped = reason
        call?.cancel()
    }

    private fun block(index: Long): ByteArray {
        // first: after a stop every read fails, also one the block cache could answer (security review), so the retriever ends
        stopped?.let { throw RangeStop(it) }
        blocks[index]?.let { return it }
        val start = index * blockSize
        val endExclusive = if (size >= 0) minOf(start + blockSize, size) else start + blockSize
        val want = endExclusive - start
        if (want <= 0) return ByteArray(0)
        if (downloaded + want > maxBytes) {
            stop(RangeStop.Reason.CAP)
            throw RangeStop(RangeStop.Reason.CAP)
        }
        val request = Request.Builder().url(url).header("Range", "bytes=$start-${endExclusive - 1}").build()
        val c = http.newCall(request)
        call = c
        // a stop between the check above and here must not let the request out
        stopped?.let { throw RangeStop(it) }
        requests++
        val data = try {
            c.execute().use { response ->
                // anything but a range of what was asked: closed unread, so a host that sends the whole video sends no more of it
                // a whole file (200) or none (404/410) is the host's answer for good; a refusal or a busy host (403, 429, 5xx,
                // often only this Tor exit) may answer the next time: an error, not remembered (review 2026-11-12)
                if (response.code == 200 || response.code == 404 || response.code == 410) throw RangeStop(RangeStop.Reason.NO_RANGE)
                if (response.code != 206) throw IOException("HTTP ${response.code}")
                val (s, e, total) = VideoFrameRules.parseContentRange(response.header("Content-Range")) ?: throw RangeStop(RangeStop.Reason.NO_RANGE)
                if (s != start || (size >= 0 && total != size)) throw RangeStop(RangeStop.Reason.NO_RANGE)
                if (size < 0) size = total
                val length = minOf(e - s + 1, want, total - s).toInt()
                // a host that cuts ranges shorter than asked: the short block would read as the end of the video
                if (length < want && s + length < total) throw RangeStop(RangeStop.Reason.NO_RANGE)
                if (downloaded + length > maxBytes) throw RangeStop(RangeStop.Reason.CAP)
                val source = response.body?.source() ?: throw IOException("no body")
                source.require(length.toLong())
                source.readByteArray(length.toLong())
            }
        } catch (e: RangeStop) {
            stop(e.reason)
            throw e
        } catch (e: IOException) {
            // canceled by stop(): say why
            stopped?.let { throw RangeStop(it) }
            throw e
        } finally {
            call = null
        }
        downloaded += data.size
        blocks[index] = data
        return data
    }
}
