package com.monostr.app.media

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/** A Coil model: the poster Monostr derives from the video at [url] itself, for a video whose note names none (spec 2026-11-12). */
data class VideoFrame(val url: String)

/** Unavailable is quiet: the cell keeps its play button. */
class VideoFrameUnavailable(url: String) : Exception("no frame for $url")

/**
 * Loads a [VideoFrame] for Coil: from the frame store, or extracted once (see [VideoFrameExtractor]); Coil decodes the JPEG like any
 * picture, at the cell's size. Coil calls this off the main thread and only for a request that runs, so a cell that waits for a tap
 * or is covered asks for nothing; its cancel reaches the range reads.
 */
class VideoFrameFetcher(private val data: VideoFrame, private val extractor: VideoFrameExtractor) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val (file, fresh) = extractor.frame(data.url) ?: throw VideoFrameUnavailable(data.url)
        return SourceFetchResult(
            source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
            mimeType = "image/jpeg",
            dataSource = if (fresh) DataSource.NETWORK else DataSource.DISK,
        )
    }

    /** [extractor] is asked for with the first frame, not when the loader is built. */
    class Factory(private val extractor: () -> VideoFrameExtractor) : Fetcher.Factory<VideoFrame> {
        override fun create(data: VideoFrame, options: Options, imageLoader: ImageLoader): Fetcher = VideoFrameFetcher(data, extractor())
    }
}

/** The memory cache key of a [VideoFrame]: apart from the video's own URL, which is not a picture. */
class VideoFrameKeyer : Keyer<VideoFrame> {
    override fun key(data: VideoFrame, options: Options): String = key(data)

    companion object {
        fun key(data: VideoFrame): String = "video-frame:${data.url}"
    }
}
