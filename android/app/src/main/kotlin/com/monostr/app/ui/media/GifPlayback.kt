package com.monostr.app.ui.media

import com.monostr.app.data.GifMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/** Frames and length of one loop of a GIF, read from its blocks without decoding a picture. */
object GifTiming {
    data class Info(val frames: Int, val loopMillis: Long)

    private const val CENTISECOND_MS = 10L
    /** Browsers play a delay of 0 or 1 centiseconds at 100 ms; so does the decoder on older Android. */
    private const val UNSET_DELAY_MS = 100L

    /** Null when [input] is no GIF or holds no complete frame header; a cut file counts the frames it has. */
    fun read(input: InputStream): Info? {
        val d = DataInputStream(input.buffered())
        var frames = 0
        var total = 0L
        try {
            val head = ByteArray(6)
            d.readFully(head)
            val signature = String(head, Charsets.US_ASCII)
            if (signature != "GIF87a" && signature != "GIF89a") return null
            val screen = ByteArray(7)
            d.readFully(screen)
            skipTable(d, screen[4].toInt())
            var delay = 0L
            while (true) {
                when (d.readUnsignedByte()) {
                    0x21 -> when (d.readUnsignedByte()) {
                        0xF9 -> {
                            val block = ByteArray(d.readUnsignedByte())
                            d.readFully(block)
                            if (block.size >= 3) {
                                val cs = (block[1].toInt() and 0xff) or ((block[2].toInt() and 0xff) shl 8)
                                delay = if (cs <= 1) UNSET_DELAY_MS else cs * CENTISECOND_MS
                            }
                            skipSubBlocks(d)
                        }
                        else -> skipSubBlocks(d)
                    }
                    0x2C -> {
                        val image = ByteArray(9)
                        d.readFully(image)
                        frames++
                        total += delay
                        delay = 0
                        skipTable(d, image[8].toInt())
                        d.readUnsignedByte() // minimum code size
                        skipSubBlocks(d)
                    }
                    else -> break // the trailer, or something that is no block
                }
            }
        } catch (_: EOFException) {
            // a cut file: what was read counts
        }
        return if (frames > 0) Info(frames, total) else null
    }

    private fun skipTable(d: DataInputStream, flags: Int) {
        if (flags and 0x80 != 0) skip(d, 3L shl ((flags and 0x07) + 1))
    }

    private fun skipSubBlocks(d: DataInputStream) {
        while (true) {
            val n = d.readUnsignedByte()
            if (n == 0) return
            skip(d, n.toLong())
        }
    }

    private fun skip(d: DataInputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = d.skip(left)
            if (n > 0) left -= n else { d.readUnsignedByte(); left-- }
        }
    }
}

/**
 * Backlog 66: how long a GIF plays. WCAG 2.2.2: moving content that starts by itself stops within five seconds or
 * can be paused, so not a fixed number of loops (3 x 0.8 s, but 3 x 61.5 s): short loops repeat until the budget
 * is used up and finish the running loop, a longer loop plays once and its badge pauses it.
 */
object GifPlayback {
    const val BUDGET_MS = 5_000L
    /** Larger files only play on a tap: their frames cost memory and decoding while scrolling. */
    const val MAX_AUTOPLAY_BYTES = 8L * 1024 * 1024

    fun playMillis(loopMillis: Long?): Long {
        if (loopMillis == null || loopMillis <= 0) return BUDGET_MS
        if (loopMillis >= BUDGET_MS) return loopMillis
        val loops = (BUDGET_MS + loopMillis - 1) / loopMillis
        return loops * loopMillis
    }

    /** [animationsOff]: the system's animations are switched off (Accessibility, developer options). */
    fun autoplays(mode: GifMode, animationsOff: Boolean, bytes: Long?): Boolean =
        mode == GifMode.AUTO && !animationsOff && (bytes == null || bytes <= MAX_AUTOPLAY_BYTES)

    fun isGif(url: String, mime: String?): Boolean =
        mime.equals("image/gif", ignoreCase = true) || url.substringBefore('?').substringBefore('#').endsWith(".gif", ignoreCase = true)
}

/** Which GIF may play by itself: the topmost one that is at least half in view, one at a time. Main thread only. */
class GifStage {
    private class Slot(val top: Float, val share: Float)

    private val slots = HashMap<String, Slot>()
    private var paused = false
    private val _active = MutableStateFlow<String?>(null)
    val active: StateFlow<String?> = _active.asStateFlow()

    fun report(key: String, top: Float, visibleShare: Float) {
        slots[key] = Slot(top, visibleShare)
        update()
    }

    fun remove(key: String) {
        slots.remove(key)
        update()
    }

    /** The app left the screen: nothing plays. */
    fun setPaused(paused: Boolean) {
        this.paused = paused
        update()
    }

    private fun update() {
        _active.value = if (paused) null else slots.entries.filter { it.value.share >= MIN_VISIBLE_SHARE }.minByOrNull { it.value.top }?.key
    }

    private companion object {
        const val MIN_VISIBLE_SHARE = 0.5f
    }
}

/** The app's one stage (only one screen shows at a time), and the GIFs that have had their turn this session: scrolling back does not start them again. */
object Gifs {
    val stage = GifStage()
    private val played = java.util.Collections.newSetFromMap(object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 500
    })

    fun hasPlayed(key: String): Boolean = key in played

    fun markPlayed(key: String) {
        played += key
    }
}
