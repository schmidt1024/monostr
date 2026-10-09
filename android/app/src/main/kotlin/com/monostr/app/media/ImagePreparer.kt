package com.monostr.app.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.monostr.app.ui.media.Blurhash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.roundToInt

/** What a picture is for decides how large it may be (spec 5.1). */
enum class ImageTarget(val longEdge: Int) { NOTE(2048), AVATAR(800), BANNER(1500) }

/** A picture ready for upload: the bytes as they will be stored, and what the note says about them. */
class PreparedImage(val bytes: ByteArray, val type: String, val width: Int, val height: Int, val sha256: String, val blurhash: String?)

/** Turns a picked picture into upload bytes. [source] is a `content://` or `file://` URI. Throws [UploadException.Unreadable] or [UploadException.TooLarge]. */
fun interface PicturePreparer {
    suspend fun prepare(source: String, target: ImageTarget): PreparedImage
}

/** Size arithmetic without Android types, so the JVM tests reach it. */
object ImageSizing {
    /** What the app uploads at most; the project's server refuses more (spec 1). */
    const val MAX_UPLOAD_BYTES = 10 * 1024 * 1024

    /** The largest size with the ratio of [w] x [h] whose long edge is at most [longEdge]; never larger than the original. */
    fun fit(w: Int, h: Int, longEdge: Int): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= longEdge) return w to h
        val k = longEdge.toDouble() / long
        return maxOf(1, (w * k).roundToInt()) to maxOf(1, (h * k).roundToInt())
    }

    /**
     * The scale factors for the upright picture (x, y) that land it exactly on [fit]'s size; [w] x [h]
     * is the decoded picture, [quarterTurn] says that turning it upright swaps width and height.
     */
    fun scale(w: Int, h: Int, quarterTurn: Boolean, longEdge: Int): Pair<Float, Float> {
        val (uw, uh) = if (quarterTurn) h to w else w to h
        val (tw, th) = fit(uw, uh, longEdge)
        return tw.toFloat() / uw to th.toFloat() / uh
    }

    /** Power-of-two subsampling for the decoder that keeps the decoded long edge at or above [longEdge]. */
    fun sampleSize(w: Int, h: Int, longEdge: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= longEdge) s *= 2
        return s
    }

    fun isGif(bytes: ByteArray): Boolean =
        bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte() && (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) && bytes[5] == 'a'.code.toByte()
}

/**
 * Spec 5.1: reads the picked picture, turns it upright, fits it into the target's long edge and
 * encodes it anew - JPEG, or PNG when it has an alpha channel. Encoding anew drops every piece of
 * metadata (position, device, time): that is the privacy point of this class. A GIF goes through
 * unchanged, because re-encoding would lose its animation.
 *
 * One picture at a time: a large photo needs its source bytes plus a decoded and a transformed
 * bitmap, and four of those at once do not fit into the app's memory. The uploads that follow
 * stay parallel.
 */
class ImagePreparer(private val resolver: ContentResolver) : PicturePreparer {
    private val oneAtATime = Mutex()

    override suspend fun prepare(source: String, target: ImageTarget): PreparedImage = oneAtATime.withLock {
        try {
            val raw = withContext(Dispatchers.IO) { read(Uri.parse(source)) }
            withContext(Dispatchers.Default) { if (ImageSizing.isGif(raw)) gif(raw) else still(raw, target) }
        } catch (e: OutOfMemoryError) {
            throw UploadException.TooLarge()
        }
    }

    private fun read(uri: Uri): ByteArray = try {
        resolver.openInputStream(uri)?.use { capped(it) } ?: throw UploadException.Unreadable()
    } catch (e: IOException) {
        throw UploadException.Unreadable(e)
    } catch (e: SecurityException) {
        throw UploadException.Unreadable(e)
    }

    /** Reads at most [MAX_SOURCE_BYTES]; a larger source is refused instead of filling the memory. */
    private fun capped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_SOURCE_BYTES) throw UploadException.TooLarge()
        }
        return out.toByteArray()
    }

    private fun bounds(raw: ByteArray): BitmapFactory.Options {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) throw UploadException.Unreadable()
        return opts
    }

    private fun gif(raw: ByteArray): PreparedImage {
        if (raw.size > ImageSizing.MAX_UPLOAD_BYTES) throw UploadException.TooLarge()
        val size = bounds(raw)
        val sample = BitmapFactory.Options().apply { inSampleSize = ImageSizing.sampleSize(size.outWidth, size.outHeight, BLURHASH_EDGE) }
        val first = BitmapFactory.decodeByteArray(raw, 0, raw.size, sample)
        val hash = first?.let { blurhash(it) }
        first?.recycle()
        return PreparedImage(raw, "image/gif", size.outWidth, size.outHeight, sha256(raw), hash)
    }

    private fun still(raw: ByteArray, target: ImageTarget): PreparedImage {
        val size = bounds(raw)
        val opts = BitmapFactory.Options().apply { inSampleSize = ImageSizing.sampleSize(size.outWidth, size.outHeight, target.longEdge) }
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: throw UploadException.Unreadable()
        val png = decoded.hasAlpha()
        val bitmap = upright(decoded, orientation(raw), target.longEdge)
        if (bitmap !== decoded) decoded.recycle()
        try {
            val out = ByteArrayOutputStream()
            val encoded = if (png) bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) else bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (!encoded) throw UploadException.Unreadable()
            val bytes = out.toByteArray()
            if (bytes.size > ImageSizing.MAX_UPLOAD_BYTES) throw UploadException.TooLarge()
            return PreparedImage(bytes, if (png) "image/png" else "image/jpeg", bitmap.width, bitmap.height, sha256(bytes), blurhash(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    /** The EXIF orientation of the source; a source without EXIF (PNG, WebP without it) or with broken EXIF is upright. */
    private fun orientation(raw: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(raw)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        // android.media.ExifInterface on API 26/27 throws runtime exceptions on malformed EXIF
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Turns [bitmap] upright and fits it into [longEdge] in one step: one matrix, one new bitmap (or none when nothing changes). */
    private fun upright(bitmap: Bitmap, orientation: Int, longEdge: Int): Bitmap {
        val m = Matrix()
        val quarterTurn = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> { m.postRotate(90f); true }
            ExifInterface.ORIENTATION_ROTATE_180 -> { m.postRotate(180f); false }
            ExifInterface.ORIENTATION_ROTATE_270 -> { m.postRotate(270f); true }
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> { m.postScale(-1f, 1f); false }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { m.postScale(1f, -1f); false }
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f); true }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f); true }
            else -> false
        }
        val (sx, sy) = ImageSizing.scale(bitmap.width, bitmap.height, quarterTurn, longEdge)
        m.postScale(sx, sy)
        if (m.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private fun blurhash(bitmap: Bitmap): String? {
        val (w, h) = ImageSizing.fit(bitmap.width, bitmap.height, BLURHASH_EDGE)
        val small = if (w == bitmap.width && h == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        return Blurhash.encode(pixels, w, h)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val JPEG_QUALITY = 85
        const val BLURHASH_EDGE = 32
        /** Larger sources are refused unread: decoding them would not fit into the app's memory anyway. */
        const val MAX_SOURCE_BYTES = 40 * 1024 * 1024
    }
}
