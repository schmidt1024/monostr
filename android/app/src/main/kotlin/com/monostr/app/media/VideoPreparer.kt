package com.monostr.app.media

import androidx.annotation.OptIn
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Spec 2026-10-27 video §3, §4: what the device takes and the size it encodes to; no Android types, so the JVM tests reach it. */
object VideoRules {
    /** 2:00 plus 1 s for the rounding of containers. */
    const val MAX_SOURCE_MS = 121_000L
    /** What the project's server takes for a video (50 MiB). */
    const val MAX_BYTES = 52_428_800L
    const val SHORT_EDGE = 720
    const val MAX_FPS = 30f
    const val VIDEO_BITRATE = 2_000_000
    /** Once more at this rate when the first result is above [MAX_BYTES]. */
    const val RETRY_BITRATE = 1_200_000
    const val AUDIO_BITRATE = 128_000
    /** The poster is the frame at 1 s, or the first one of a shorter video. */
    const val POSTER_AT_US = 1_000_000L

    fun tooLong(durationMs: Long): Boolean = durationMs > MAX_SOURCE_MS

    /**
     * The encoded size for a source of [width] × [height] stored with a [rotation] flag: the flag is
     * applied, the shorter side is at most [SHORT_EDGE] (never scaled up), both sides even.
     */
    fun target(width: Int, height: Int, rotation: Int): Pair<Int, Int> {
        val (w, h) = if (rotation % 180 != 0) height to width else width to height
        val short = minOf(w, h)
        if (short <= SHORT_EDGE) return even(w) to even(h)
        val scale = SHORT_EDGE.toDouble() / short
        return even(Math.round(w * scale).toInt()) to even(Math.round(h * scale).toInt())
    }

    private fun even(n: Int) = n - n % 2
}

/** A video ready for upload: the encoded [file], what the note says about it, and its [poster] picture (both in the app's cache). */
class PreparedVideo(val file: File, val width: Int, val height: Int, val durationMs: Long, val sha256: String, val size: Long, val poster: File) {
    /** Removes both files; after an upload, a cancel or a failure. */
    fun delete() {
        file.delete()
        poster.delete()
    }
}

/** Turns a picked video into upload files (spec 2026-10-27 video §4). [source] is a `content://` or `file://` URI. */
interface VideoPreparer {
    /** The source's length in ms. Throws [UploadException.Unreadable]. */
    suspend fun durationMs(source: String): Long

    /**
     * Encodes [source] to H.264/AAC MP4 at most 720p, without metadata, plus a poster picture; [onProgress]
     * gets 0..1. Throws [UploadException.VideoTooLong], [UploadException.TooLarge] or [UploadException.Unreadable];
     * a cancel or a failure leaves no file behind.
     */
    suspend fun prepare(source: String, onProgress: (Float) -> Unit = {}): PreparedVideo
}

// Media3's Transformer and its HDR modes are marked unstable/experimental; the app relies on them on purpose (lint)
@OptIn(UnstableApi::class, ExperimentalApi::class)
class TransformerVideoPreparer(private val context: Context) : VideoPreparer {
    /** One encode at a time: the hardware encoder is shared, and a second one would only slow both. */
    private val oneAtATime = Mutex()
    private val dir: File get() = File(context.cacheDir, "video").apply { mkdirs() }

    override suspend fun durationMs(source: String): Long = withContext(Dispatchers.IO) {
        retrieve(Uri.parse(source)) { it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() }
            ?: throw UploadException.Unreadable()
    }

    override suspend fun prepare(source: String, onProgress: (Float) -> Unit): PreparedVideo = oneAtATime.withLock {
        val uri = Uri.parse(source)
        val (width, height, rotation) = withContext(Dispatchers.IO) {
            retrieve(uri) { r ->
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                if (w == null || h == null || d == null || w < 1 || h < 1) null
                else {
                    if (VideoRules.tooLong(d)) throw UploadException.VideoTooLong()
                    Triple(w, h, r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0)
                }
            } ?: throw UploadException.Unreadable()
        }
        val (tw, th) = VideoRules.target(width, height, rotation)
        val out = File(dir, "v-${System.nanoTime()}.mp4")
        val poster = File(dir, "p-${System.nanoTime()}.jpg")
        try {
            var result = encode(uri, out, tw, th, VideoRules.VIDEO_BITRATE) { onProgress(it * 0.9f) }
            if (out.length() > VideoRules.MAX_BYTES) {
                out.delete()
                result = encode(uri, out, tw, th, VideoRules.RETRY_BITRATE) { onProgress(0.9f + it * 0.1f) }
                if (out.length() > VideoRules.MAX_BYTES) throw UploadException.TooLarge()
            }
            withContext(Dispatchers.IO) { writePoster(out, poster) }
            val sha = withContext(Dispatchers.IO) { sha256(out) }
            // the export result names the stored pixels; a note's `dim` is the size as shown (orientation applied)
            val (dw, dh) = withContext(Dispatchers.IO) { displayed(out) } ?: (result.width to result.height)
            onProgress(1f)
            PreparedVideo(out, dw, dh, result.durationMs, sha, out.length(), poster)
        } catch (e: Throwable) {
            out.delete(); poster.delete()
            throw e
        }
    }

    /** What the export reports; width and height are the stored pixels, before any orientation. */
    private class Encoded(val width: Int, val height: Int, val durationMs: Long)

    /**
     * One Transformer run on the main looper. HDR is tone-mapped to SDR where the device can; where it cannot,
     * the run is repeated reading HDR as SDR (colours may look washed out), never refused (spec §4).
     */
    private suspend fun encode(uri: Uri, out: File, width: Int, height: Int, bitrate: Int, onProgress: (Float) -> Unit): Encoded =
        try {
            encodeOnce(uri, out, width, height, bitrate, Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL, onProgress)
        } catch (e: ExportException) {
            out.delete()
            try {
                encodeOnce(uri, out, width, height, bitrate, Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR, onProgress)
            } catch (again: ExportException) {
                throw UploadException.Unreadable(again)
            }
        }

    private suspend fun encodeOnce(uri: Uri, out: File, width: Int, height: Int, bitrate: Int, hdrMode: Int, onProgress: (Float) -> Unit): Encoded =
        withContext(Dispatchers.Main) {
            coroutineScope {
                lateinit var transformer: Transformer
                val ticker = launch {
                    val holder = ProgressHolder()
                    while (true) {
                        delay(PROGRESS_EVERY_MS)
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                    }
                }
                try {
                    suspendCancellableCoroutine { cont ->
                        transformer = Transformer.Builder(context)
                            .setVideoMimeType(MimeTypes.VIDEO_H264)
                            .setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .setEncoderFactory(
                                DefaultEncoderFactory.Builder(context)
                                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                                    .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(VideoRules.AUDIO_BITRATE).build())
                                    .setEnableFallback(true)
                                    .build(),
                            )
                            // the rotation goes into the pixels, not a flag some players ignore (spec §4, final review I5)
                            .setPortraitEncodingEnabled(true)
                            // no location, dates, device or orientation flag in the file (spec §4); index up front for playback
                            .setMuxerFactory(InAppMp4Muxer.Factory(::dropAllMetadata).setAttemptStreamableOutputEnabled(true))
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    if (cont.isActive) cont.resume(Encoded(exportResult.width, exportResult.height, exportResult.durationMs))
                                }

                                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                    if (cont.isActive) cont.resumeWithException(exportException)
                                }
                            })
                            .build()
                        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                            .setEffects(
                                Effects(
                                    emptyList(),
                                    listOf(
                                        Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT),
                                        FrameDropEffect.createDefaultFrameDropEffect(VideoRules.MAX_FPS),
                                    ),
                                ),
                            )
                            .build()
                        val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build()).setHdrMode(hdrMode).build()
                        transformer.start(composition, out.absolutePath)
                    }
                } catch (e: CancellationException) {
                    // on the main looper, where the transformer runs: it stops before the caller deletes its file
                    transformer.cancel()
                    throw e
                } finally {
                    ticker.cancel()
                }
            }
        }

    private fun displayed(video: File): Pair<Int, Int>? = retrieve(Uri.fromFile(video)) { r ->
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (w == null || h == null) null else if (rotation % 180 != 0) h to w else w to h
    }

    private fun writePoster(video: File, poster: File) {
        val frame = retrieve(Uri.fromFile(video)) { r ->
            r.getFrameAtTime(VideoRules.POSTER_AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.getFrameAtTime(0)
        } ?: throw UploadException.Unreadable()
        try {
            poster.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, POSTER_QUALITY, it) }
        } finally {
            frame.recycle()
        }
    }

    private fun <T> retrieve(uri: Uri, read: (MediaMetadataRetriever) -> T?): T? {
        val r = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file") r.setDataSource(uri.path) else r.setDataSource(context, uri)
            read(r)
        } catch (e: UploadException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw UploadException.Unreadable(e)
        } finally {
            r.release()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PROGRESS_EVERY_MS = 250L
        const val POSTER_QUALITY = 85

        fun dropAllMetadata(entries: MutableSet<Metadata.Entry>) {
            entries.clear()
        }
    }
}
