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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
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

/** The original bytes of a picture: from Coil's disk cache when the viewer already loaded it, else downloaded. */
class PictureSource(
    private val cached: suspend (String) -> ByteArray?,
    private val download: suspend (String) -> Pair<ByteArray, String?>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** [header] is the server's Content-Type; null when the bytes came from the cache. */
    class Picture(val bytes: ByteArray, val header: String?)

    suspend fun load(url: String): Picture = withContext(io) {
        val hit = try {
            cached(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // a broken cache entry: download instead
        }
        val picture = if (hit != null && hit.isNotEmpty()) Picture(hit, null) else download(url).let { (bytes, type) -> Picture(bytes, type) }
        if (picture.bytes.isEmpty()) throw IOException("empty picture")
        picture
    }

    companion object {
        /** Coil's default disk cache keys network images by their URL. */
        fun create(context: Context, http: OkHttpClient) = PictureSource(
            cached = { url ->
                SingletonImageLoader.get(context).diskCache?.let { cache ->
                    cache.openSnapshot(url)?.use { s -> cache.fileSystem.read(s.data) { readByteArray() } }
                }
            },
            download = { url ->
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("no body")
                    body.bytes() to r.header("Content-Type")
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

    /** Blocking; call on an IO thread. A failed write leaves no row or file behind. */
    fun save(context: Context, bytes: ByteArray, name: String, mime: String): Uri {
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
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("no output stream")
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
            file.writeBytes(bytes)
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

    /** Blocking; writes [bytes] to cacheDir/shared (what an earlier share left there goes) and returns the provider URI. */
    fun prepare(context: Context, bytes: ByteArray, name: String): Uri {
        val dir = File(context.cacheDir, DIR)
        dir.listFiles()?.forEach { it.delete() }
        dir.mkdirs()
        val file = File(dir, name).apply { writeBytes(bytes) }
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
