package com.monostr.app.media

import java.io.File
import java.security.MessageDigest

/**
 * The derived video posters on disk (spec 2026-11-12): one JPEG per video URL, named by the URL's sha256, so a video is read from
 * its host once. Bounded by [maxBytes]; the least recently used go first (a hit renews the file's time).
 */
class VideoFrameStore(private val dir: File, private val maxBytes: Long = VideoFrameRules.STORE_BYTES, private val now: () -> Long = System::currentTimeMillis) {
    fun file(url: String): File = File(dir, key(url) + ".jpg")

    /** The stored poster of [url], or null. */
    fun get(url: String): File? = file(url).takeIf { it.isFile && it.length() > 0 }?.also { it.setLastModified(now()) }

    /** Stores [jpeg] for [url] (whole or not at all) and trims the store. */
    @Synchronized
    fun put(url: String, jpeg: ByteArray): File {
        dir.mkdirs()
        val target = file(url)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(jpeg)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("could not store the frame")
        }
        target.setLastModified(now())
        trim(keep = target)
        return target
    }

    /** Whether [url] gave no frame within the last [VideoFrameRules.FAILED_TTL_MS] ([markFailed]); an older mark goes. */
    fun failedRecently(url: String): Boolean {
        val mark = File(dir, key(url) + FAILED)
        if (!mark.isFile) return false
        if (now() - mark.lastModified() < VideoFrameRules.FAILED_TTL_MS) return true
        mark.delete()
        return false
    }

    /** Notes that [url] gave no frame: an empty file, at most [VideoFrameRules.FAILED_MAX] of them (the oldest go). */
    @Synchronized
    fun markFailed(url: String) {
        dir.mkdirs()
        val mark = File(dir, key(url) + FAILED)
        if (!mark.exists()) mark.createNewFile()
        mark.setLastModified(now())
        val marks = dir.listFiles { f -> f.isFile && f.name.endsWith(FAILED) }?.toList() ?: return
        if (marks.size > VideoFrameRules.FAILED_MAX) marks.sortedBy { it.lastModified() }.take(marks.size - VideoFrameRules.FAILED_MAX).forEach { it.delete() }
    }

    private fun trim(keep: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.toList() ?: return
        var total = files.sumOf { it.length() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            if (f == keep) continue
            val length = f.length()
            if (f.delete()) total -= length
        }
    }

    companion object {
        private const val FAILED = ".fail"

        /** The file name of [url]'s poster: no URL in a file name. */
        fun key(url: String): String =
            MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
