package com.monostr.app.media

import java.io.ByteArrayOutputStream

/**
 * A GIF is uploaded as it is (re-encoding would lose the animation), so its own metadata goes with it: comment
 * extensions and application extensions (XMP from an editor, ICC profiles, camera or tool data). [strip] keeps the
 * picture, its frames and timing, and of the application extensions only the loop count (NETSCAPE2.0/ANIMEXTS1.0).
 */
object GifMetadata {
    private const val EXTENSION = 0x21
    private const val IMAGE = 0x2C
    private const val TRAILER = 0x3B
    private const val COMMENT = 0xFE
    private const val APPLICATION = 0xFF
    private val LOOP_IDS = setOf("NETSCAPE2.0", "ANIMEXTS1.0")

    /** The GIF without comment and foreign application blocks; null when [gif] is not a GIF this reader can follow. */
    fun strip(gif: ByteArray): ByteArray? = runCatching { rewrite(gif) }.getOrNull()

    private fun rewrite(b: ByteArray): ByteArray? {
        if (b.size < 13) return null
        val sig = String(b, 0, 6, Charsets.US_ASCII)
        if (sig != "GIF87a" && sig != "GIF89a") return null
        val out = ByteArrayOutputStream(b.size)
        var p = 13
        val screenFlags = b[10].toInt() and 0xFF
        if (screenFlags and 0x80 != 0) p += 3 * (1 shl ((screenFlags and 7) + 1))
        if (p > b.size) return null
        out.write(b, 0, p)
        while (p < b.size) {
            when (b[p].toInt() and 0xFF) {
                TRAILER -> {
                    out.write(TRAILER)
                    return out.toByteArray()
                }
                IMAGE -> {
                    if (p + 10 > b.size) return null
                    val flags = b[p + 9].toInt() and 0xFF
                    var end = p + 10
                    if (flags and 0x80 != 0) end += 3 * (1 shl ((flags and 7) + 1))
                    end += 1 // LZW minimum code size
                    end = afterSubBlocks(b, end) ?: return null
                    out.write(b, p, end - p)
                    p = end
                }
                EXTENSION -> {
                    if (p + 2 > b.size) return null
                    val label = b[p + 1].toInt() and 0xFF
                    val end = afterSubBlocks(b, p + 2) ?: return null
                    val keep = when (label) {
                        COMMENT -> false
                        APPLICATION -> {
                            val len = b[p + 2].toInt() and 0xFF
                            len == 11 && p + 3 + 11 <= b.size && String(b, p + 3, 11, Charsets.US_ASCII) in LOOP_IDS
                        }
                        else -> true // graphic control (frame timing, transparency), plain text
                    }
                    if (keep) out.write(b, p, end - p)
                    p = end
                }
                else -> return null
            }
        }
        // no trailer: close the file properly
        out.write(TRAILER)
        return out.toByteArray()
    }

    /** The index after a chain of data sub-blocks starting at [start] (size byte, data, …, 0); null when it runs past the end. */
    private fun afterSubBlocks(b: ByteArray, start: Int): Int? {
        var p = start
        while (true) {
            if (p >= b.size) return null
            val n = b[p].toInt() and 0xFF
            p += 1
            if (n == 0) return p
            p += n
        }
    }
}
