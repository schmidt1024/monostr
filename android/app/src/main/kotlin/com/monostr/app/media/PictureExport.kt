package com.monostr.app.media

import android.Manifest
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil3.SingletonImageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Spec 2026-10-16 §7: names and types of a picture saved or shared from the viewer. Pure. */
object PictureFiles {
    /** `monostr-<first 12 hex of sha256(url)>.<ext>`: the same picture gets the same name. */
    fun name(url: String, mime: String): String {
        val hex = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return "monostr-${hex.take(12)}.${extension(mime)}"
    }

    fun extension(mime: String): String = when (mime.substringBefore(';').trim().lowercase()) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/avif" -> "avif"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        else -> "jpg"
    }

    /** The bytes' own type (from the decoder) first, then the server's header, then the URL's ending; image/jpeg when nothing says otherwise. */
    fun mime(sniffed: String?, header: String?, url: String): String {
        sniffed?.lowercase()?.takeIf { it.startsWith("image/") }?.let { return it }
        header?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.startsWith("image/") }?.let { return it }
        return when (url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "heic" -> "image/heic"
            else -> "image/jpeg"
        }
    }
}

/** Cleanup 0.21.1: the largest picture monostr saves or shares. */
const val MAX_PICTURE_BYTES = 50L * 1024 * 1024

/** The picture is over [MAX_PICTURE_BYTES]: its own message in the viewer. */
class PictureTooLargeException : IOException("picture over the size limit")

/** The capped copy behind save and share. Pure. */
object PictureCopy {
    /**
     * Copies [input] to [output] in chunks and returns the bytes copied; throws [PictureTooLargeException]
     * as soon as more than [limit] were read, or before reading when [declared] (Content-Length) is over it.
     */
    fun capped(input: InputStream, output: OutputStream, limit: Long = MAX_PICTURE_BYTES, declared: Long? = null): Long {
        if (declared != null && declared > limit) throw PictureTooLargeException()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return total
            total += n
            if (total > limit) throw PictureTooLargeException()
            output.write(buffer, 0, n)
        }
    }
}

/** The original bytes of a picture, in a temp file: from Coil's disk cache when the viewer already loaded it, else downloaded. */
class PictureSource(
    private val dir: File,
    private val cached: suspend (String) -> Body?,
    private val download: suspend (String) -> Body,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val limit: Long = MAX_PICTURE_BYTES,
) {
    /** An opened picture: [length] is the Content-Length (null when unknown), [header] the server's Content-Type; closing it closes what it came from. */
    class Body(val stream: InputStream, val length: Long?, val header: String?, private val onClose: () -> Unit = {}) : Closeable {
        override fun close() {
            try { stream.close() } finally { onClose() }
        }
    }

    /** [header] is the server's Content-Type; null when the bytes came from the cache. */
    class Picture(val file: File, val header: String?)

    /** Loads [url] into a temp file under [dir], runs the blocking [block] with it on the IO dispatcher, and deletes the file afterwards, done or failed. */
    suspend fun <T> use(url: String, block: (Picture) -> T): T = withContext(io) {
        dir.mkdirs()
        val file = File.createTempFile("picture", ".tmp", dir)
        try {
            val header = fill(url, file)
            currentCoroutineContext().ensureActive() // left the viewer during the copy: write nothing
            if (file.length() == 0L) throw IOException("empty picture")
            block(Picture(file, header))
        } finally {
            file.delete()
        }
    }

    /** Copies the cached picture, else the download, into [file]; returns the Content-Type of a download. */
    private suspend fun fill(url: String, file: File): String? {
        val fromCache = try {
            cached(url)?.use { body -> copy(body, file) > 0 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PictureTooLargeException) {
            throw e
        } catch (e: Exception) {
            null // a broken cache entry: download instead
        }
        if (fromCache == true) return null
        return download(url).use { body -> copy(body, file); body.header }
    }

    private fun copy(body: Body, file: File): Long = FileOutputStream(file).use { out -> PictureCopy.capped(body.stream, out, limit, body.length) }

    companion object {
        /** Coil's default disk cache keys network images by their URL; temp files go to cacheDir/export. */
        fun create(context: Context, http: OkHttpClient) = PictureSource(
            dir = File(context.cacheDir, "export"),
            cached = { url ->
                SingletonImageLoader.get(context).diskCache?.let { cache ->
                    cache.openSnapshot(url)?.let { s ->
                        try {
                            val size = cache.fileSystem.metadataOrNull(s.data)?.size
                            Body(cache.fileSystem.source(s.data).buffer().inputStream(), size, null) { s.close() }
                        } catch (e: Exception) {
                            s.close()
                            throw e
                        }
                    }
                }
            },
            download = { url ->
                val r = http.newCall(Request.Builder().url(url).build()).execute()
                try {
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("no body")
                    Body(body.byteStream(), body.contentLength().takeIf { it >= 0 }, r.header("Content-Type")) { r.close() }
                } catch (e: Exception) {
                    r.close()
                    throw e
                }
            },
        )
    }
}

/** Spec 2026-10-16 §7: into the gallery's Pictures/monostr. */
object PictureSaver {
    /** API 26–28 write the public directory themselves and need the storage permission first. */
    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    /** Blocking; call on an IO thread. Copies [source] by stream; a failed write leaves no row or file behind. */
    fun save(context: Context, source: File, name: String, mime: String): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/monostr")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("no MediaStore row")
            try {
                resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } } ?: throw IOException("no output stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "monostr").apply { mkdirs() }
        val file = File(dir, name)
        try {
            source.copyTo(file, overwrite = true)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
        return Uri.fromFile(file)
    }
}

/** Spec 2026-10-16 §7: the picture itself, through monostr's FileProvider. */
object PictureSharer {
    const val DIR = "shared"

    /** Blocking; copies [source] to cacheDir/shared (what an earlier share left there goes) and returns the provider URI. */
    fun prepare(context: Context, source: File, name: String): Uri {
        val dir = File(context.cacheDir, DIR)
        dir.listFiles()?.forEach { it.delete() }
        dir.mkdirs()
        val file = File(dir, name)
        source.copyTo(file, overwrite = true)
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    fun intent(uri: Uri, mime: String, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri) // carries the read grant to the chosen app
        return Intent.createChooser(send, title)
    }
}
