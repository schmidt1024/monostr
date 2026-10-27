package com.monostr.app.media

import com.monostr.app.data.UiSettingsStore
import com.monostr.nostr.model.NoteAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * A picture or a video that sits on a media server, ready to be named in a note or a profile. A video carries its
 * poster picture (uploaded beside it, [posterUrl]) and its length (spec 2026-10-27 video §5, §8); [blurhash] is then
 * the poster's.
 */
@Serializable
data class UploadedMedia(
    /** The server it was uploaded to; a later delete goes there, whatever the setting says by then. */
    val server: String,
    val url: String,
    val type: String,
    val sha256: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val blurhash: String?,
    /** The server did not have this blob before the upload: only then is it ours to delete again. */
    val fresh: Boolean,
    val posterUrl: String? = null,
    val posterSha256: String? = null,
    /** As [fresh], for the poster. */
    val posterFresh: Boolean = false,
    val durationMs: Long? = null,
) {
    val isVideo: Boolean get() = posterUrl != null

    fun toAttachment(): NoteAttachment =
        NoteAttachment(url, type, sha256, size, width, height, blurhash, image = posterUrl, durationSeconds = durationMs?.let { it / 1000.0 })
}

/** The seam between the screens and the upload machinery (spec 5.1); controllers and their tests know only this. */
interface MediaUploader {
    /**
     * Prepares the picture behind [source] (a `content://` or `file://` URI) for [target] and
     * uploads it to the configured server. Throws [UploadException], or the signer's exception
     * when the user refuses the signature.
     */
    suspend fun upload(source: String, target: ImageTarget, onProgress: (Float) -> Unit = {}): UploadedMedia

    /**
     * Encodes the video behind [source] on the device ([onPreparing] 0..1), uploads its poster and then the video to
     * the configured server ([onProgress] 0..1). Throws [UploadException], or the signer's exception (spec 2026-10-27 video §8).
     */
    suspend fun uploadVideo(source: String, onPreparing: (Float) -> Unit = {}, onProgress: (Float) -> Unit = {}): UploadedMedia =
        throw UploadException.Unreadable()

    /** The length of the video behind [source] in ms, read before any work (spec 2026-10-27 video §3). Throws [UploadException.Unreadable]. */
    suspend fun videoDurationMs(source: String): Long = throw UploadException.Unreadable()

    /** Asks the server to delete an upload that will not be used after all: in the background, best effort, never throws (spec 5.2). */
    fun discard(media: UploadedMedia)

    /** Like [discard], but returns once the delete is done or failed (logout: before the signer goes). Never throws. */
    suspend fun discardNow(media: UploadedMedia) = discard(media)
}

class BlossomMediaUploader(
    private val preparer: PicturePreparer,
    private val client: BlossomClient,
    private val settings: UiSettingsStore,
    /** Outlives the screens: a delete started while leaving the composer must still go out. */
    private val background: CoroutineScope,
    /**
     * Spec 2026-10-16 §8: true while a published own note or the profile shows the blob ([OwnPictures.published]).
     * A draft's fresh upload may be named there too, uploaded again (not fresh) after the draft was saved.
     */
    private val inUse: suspend (sha256: String) -> Boolean = { false },
    /** Null: this uploader takes pictures only. */
    private val videos: VideoPreparer? = null,
) : MediaUploader {
    /** One lock per sha256, kept for the life of the session (a handful of pictures). */
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(sha256: String): Mutex = locks.computeIfAbsent(sha256) { Mutex() }

    override suspend fun upload(source: String, target: ImageTarget, onProgress: (Float) -> Unit): UploadedMedia =
        uploadPicture(settings.mediaServer.first(), source, target, onProgress)

    private suspend fun uploadPicture(server: String, source: String, target: ImageTarget, onProgress: (Float) -> Unit): UploadedMedia {
        val image = preparer.prepare(source, target)
        // one blob at a time: a second upload of the same bytes sees the first one's result (HEAD 200, not ours),
        // and a pending delete of them goes out before the next look-up
        return lockFor(image.sha256).withLock {
            val fresh = client.absent(server, image.sha256)
            val descriptor = try {
                client.upload(server, image.bytes, image.type, image.sha256, onProgress)
            } catch (e: CancellationException) {
                // the body may have arrived before the cancel: delete it, unless the server had the blob already.
                // The delete waits for this lock in the background; it is not awaited here, so nothing deadlocks.
                discard(UploadedMedia(server, "", image.type, image.sha256, image.bytes.size.toLong(), image.width, image.height, null, fresh))
                throw e
            }
            UploadedMedia(server, descriptor.url, image.type, image.sha256, image.bytes.size.toLong(), image.width, image.height, image.blurhash, fresh)
        }
    }

    override suspend fun videoDurationMs(source: String): Long = (videos ?: throw UploadException.Unreadable()).durationMs(source)

    override suspend fun uploadVideo(source: String, onPreparing: (Float) -> Unit, onProgress: (Float) -> Unit): UploadedMedia {
        val prep = videos ?: throw UploadException.Unreadable()
        val server = settings.mediaServer.first()
        val video = prep.prepare(source, onPreparing)
        try {
            // the poster first, to the same server; it is a tenth of the bytes at most
            val poster = uploadPicture(server, video.poster.toURI().toString(), ImageTarget.NOTE) { onProgress(it * POSTER_SHARE) }
            try {
                return lockFor(video.sha256).withLock {
                    val fresh = client.absent(server, video.sha256)
                    val descriptor = try {
                        client.uploadFile(server, video.file, VIDEO_TYPE, video.sha256) { onProgress(POSTER_SHARE + it * (1 - POSTER_SHARE)) }
                    } catch (e: CancellationException) {
                        discard(UploadedMedia(server, "", VIDEO_TYPE, video.sha256, video.size, video.width, video.height, null, fresh))
                        throw e
                    }
                    UploadedMedia(
                        server, descriptor.url, VIDEO_TYPE, video.sha256, video.size, video.width, video.height, poster.blurhash, fresh,
                        posterUrl = poster.url, posterSha256 = poster.sha256, posterFresh = poster.fresh, durationMs = video.durationMs,
                    )
                }
            } catch (e: Throwable) {
                // the video did not make it: its poster has no note to show it in
                discard(poster)
                throw e
            }
        } finally {
            video.delete()
        }
    }

    override fun discard(media: UploadedMedia) {
        if (!media.fresh && !media.posterFresh) return // another note may use this blob
        // undispatched: the delete queues for the blob's lock before discard returns, ahead of any later upload of it
        background.launch(start = CoroutineStart.UNDISPATCHED) { discardNow(media) }
    }

    override suspend fun discardNow(media: UploadedMedia) {
        if (media.fresh) deleteBlob(media.server, media.sha256)
        // a video's poster goes with it (spec 2026-10-27 video §3)
        if (media.posterFresh) media.posterSha256?.let { deleteBlob(media.server, it) }
    }

    private suspend fun deleteBlob(server: String, sha256: String) {
        try {
            lockFor(sha256).withLock { if (!inUse(sha256)) client.delete(server, sha256) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // spec 5.2: an effort, not a guarantee; the picture then stays on the server
        }
    }

    private companion object {
        const val VIDEO_TYPE = "video/mp4"
        /** The poster's part of a video's upload progress. */
        const val POSTER_SHARE = 0.1f
    }
}
