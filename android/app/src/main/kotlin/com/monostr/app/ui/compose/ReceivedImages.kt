package com.monostr.app.ui.compose

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.monostr.app.media.ImagePreparer
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Spec 2026-10-16 §3 (rules of the former clipboard button): a pasted or keyboard-inserted picture comes
 * from another app, so only another app's `content://` pictures are taken: a `file://` URI or one of
 * monostr's own authorities could point at monostr's private files, which would then be uploaded for
 * everyone to see. [type] must come from the resolver, not from the clip.
 *
 * The read grant for such a URI is temporary (a keyboard's grant lives only as long as its
 * `InputContentInfo`, a clipboard grant only as long as the clip), while the upload, a retry and the
 * tile read the picture later: so it is copied at once into [dir], and the copy is the attachment.
 */
internal object ReceivedImages {
    fun accept(scheme: String?, authority: String?, type: String?, ownPackage: String): Boolean =
        scheme == ContentResolver.SCHEME_CONTENT &&
            authority != null && !authority.startsWith(ownPackage) &&
            type?.startsWith("image/") == true

    /**
     * Whether one received item is used up here. A picture is, when it was attached; one without room
     * stays, so the keyboard learns it was not taken (a paste then inserts the item's text, if any).
     * Anything else is dropped unless it carries text, which the field inserts: the field inserts only
     * an item's text, never its URI.
     */
    fun taken(image: Boolean, attached: Boolean, hasText: Boolean): Boolean = if (image) attached else !hasText

    /** Where the received pictures are copied to. */
    fun dir(context: Context): File = File(context.cacheDir, "received")

    /**
     * The attachment source for a received picture: a copy in [dir], or [uri] itself when it cannot
     * be copied (unreadable or too large), so that the upload reports why.
     */
    fun keep(resolver: ContentResolver, uri: Uri, type: String, dir: File): String {
        val copy = try {
            resolver.openInputStream(uri)?.use { copy(it, type, dir) }
        } catch (e: Exception) {
            null // a misbehaving provider must not crash the receive listener (main thread, not suspending)
        }
        return copy?.let(::source) ?: uri.toString()
    }

    /** Copies [input] into a new file in [dir]; null, and nothing left behind, past [maxBytes] or on a read error. */
    fun copy(input: InputStream, type: String, dir: File, maxBytes: Int = ImagePreparer.MAX_SOURCE_BYTES): File? {
        dir.mkdirs()
        val ext = type.substringAfter('/').substringBefore(';').filter { it.isLetterOrDigit() }.take(8).ifEmpty { "img" }
        val file = File(dir, "${UUID.randomUUID()}.$ext")
        try {
            file.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("too large")
                    out.write(buffer, 0, n)
                }
            }
            return file
        } catch (e: IOException) {
            file.delete()
            return null
        }
    }

    fun source(file: File): String = "file://${file.absolutePath}"

    /** Deletes [source] when it is one of the copies in [dir]; any other source is left alone. */
    fun release(source: String, dir: File) {
        if (!source.startsWith("file://")) return
        val file = File(source.removePrefix("file://"))
        if (file.canonicalFile.parentFile == dir.canonicalFile) file.delete()
    }

    /** Copies left behind by a process that died with the composer open. */
    fun sweep(dir: File, now: Long, maxAgeMs: Long = 24 * 60 * 60 * 1000L) {
        dir.listFiles()?.filter { it.lastModified() < now - maxAgeMs }?.forEach { it.delete() }
    }
}
