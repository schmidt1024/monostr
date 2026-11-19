package com.monostr.app.ui.media

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** One indicator dot: [active] is 0..1 (1 = current card), [small] marks a shrunken edge dot (more items beyond). */
data class Dot(val item: Int, val small: Boolean, val active: Float)

/**
 * Sliding window of at most [WINDOW] dots above the gallery row (backlog 93, X-like):
 * edge dots shrink when more items lie beyond them.
 */
object GalleryDots {
    const val WINDOW = 7

    /**
     * Active position (spec 2026-11-19, owner: soft transition at the row's end). [raw] = first index + fraction;
     * [remainingPx] = px still scrollable while the last card is visible (null = last card not visible),
     * [lastSizePx] = width of the last card,
     * [scrolledPx] = px scrolled from the start (null = unbounded, i.e. not within the first card). Blends raw -> count-1 as the last card slides in, so no jump.
     */
    fun position(count: Int, raw: Float, remainingPx: Float?, lastSizePx: Float, scrolledPx: Float? = null): Float {
        if (count <= 1) return 0f
        if (remainingPx == null || lastSizePx <= 0f) return raw
        // the blend window also starts at 0 at the row's start (short rows: last card visible at rest)
        val window = if (scrolledPx == null) lastSizePx else minOf(lastSizePx, remainingPx + scrolledPx)
        val t = if (window <= 0f) 1f else (1f - remainingPx / window).coerceIn(0f, 1f)
        return raw + ((count - 1) - raw) * t
    }

    /** [position]: fractional index of the current card, 0..count-1. */
    fun dots(count: Int, position: Float): List<Dot> {
        if (count <= 0) return emptyList()
        val pos = position.coerceIn(0f, (count - 1).toFloat())
        val size = minOf(count, WINDOW)
        val start = (pos.roundToInt() - WINDOW / 2).coerceIn(0, max(0, count - WINDOW))
        return (0 until size).map { slot ->
            val item = start + slot
            Dot(
                item = item,
                small = (slot == 0 && start > 0) || (slot == size - 1 && start + WINDOW < count),
                active = max(0f, 1f - abs(item - pos)),
            )
        }
    }
}
