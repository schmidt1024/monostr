package com.monostr.app.ui.media

/**
 * Spec 2026-10-16 §5/§6: width x height of pictures (and of video posters, keyed by the video's URL) that
 * came without `dim`, learned when they loaded. In memory only, least recently used out beyond [max].
 */
class MediaRatioCache(private val max: Int = MAX) {
    private val map = object : LinkedHashMap<String, Pair<Int, Int>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Int, Int>>?): Boolean = size > max
    }

    @Synchronized
    operator fun get(url: String): Pair<Int, Int>? = map[url]

    /** The loaded size's ratio differs from the stated `dim` by more than a tenth (a turned photo: width and height swapped). */
    fun contradicts(stated: Pair<Int, Int>, real: Pair<Int, Int>): Boolean {
        if (stated.first <= 0 || stated.second <= 0 || real.first <= 0 || real.second <= 0) return false
        val a = stated.first.toDouble() / stated.second
        val b = real.first.toDouble() / real.second
        return kotlin.math.abs(a - b) / a > 0.1
    }

    /** A failed or empty load (no size) is not remembered: the placeholder box stays. */
    @Synchronized
    fun put(url: String, width: Int, height: Int) {
        if (width > 0 && height > 0) map[url] = width to height
    }

    val size: Int
        @Synchronized get() = map.size

    companion object {
        const val MAX = 500
    }
}

/** The app-wide cache: a ratio learned in the feed applies at once in the thread, the profile and on scrolling back. */
val MediaRatios = MediaRatioCache()
