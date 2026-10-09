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
import java.util.concurrent.ConcurrentHashMap

/** A picture that sits on a media server, ready to be named in a note or a profile. */
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
) {
    fun toAttachment(): NoteAttachment = NoteAttachment(url, type, sha256, size, width, height, blurhash)
}

/** The seam between the screens and the upload machinery (spec 5.1); controllers and their tests know only this. */
interface MediaUploader {
    /**
     * Prepares the picture behind [source] (a `content://` or `file://` URI) for [target] and
     * uploads it to the configured server. Throws [UploadException], or the signer's exception
     * when the user refuses the signature.
     */
    suspend fun upload(source: String, target: ImageTarget, onProgress: (Float) -> Unit = {}): UploadedMedia

    /** Asks the server to delete an upload that will not be used after all: in the background, best effort, never throws (spec 5.2). */
    fun discard(media: UploadedMedia)
}

class BlossomMediaUploader(
    private val preparer: PicturePreparer,
    private val client: BlossomClient,
    private val settings: UiSettingsStore,
    /** Outlives the screens: a delete started while leaving the composer must still go out. */
    private val background: CoroutineScope,
) : MediaUploader {
    /** One lock per sha256, kept for the life of the session (a handful of pictures). */
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(sha256: String): Mutex = locks.computeIfAbsent(sha256) { Mutex() }

    override suspend fun upload(source: String, target: ImageTarget, onProgress: (Float) -> Unit): UploadedMedia {
        val server = settings.mediaServer.first()
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

    override fun discard(media: UploadedMedia) {
        if (!media.fresh) return // another note may use this blob
        // undispatched: the delete queues for the blob's lock before discard returns, ahead of any later upload of it
        background.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                lockFor(media.sha256).withLock { client.delete(media.server, media.sha256) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // spec 5.2: an effort, not a guarantee; the picture then stays on the server
            }
        }
    }
}
