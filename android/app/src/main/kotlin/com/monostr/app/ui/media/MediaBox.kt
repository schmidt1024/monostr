package com.monostr.app.ui.media

/**
 * How a single picture is laid out in a note: the box's width/height ratio and whether the
 * picture is cropped to it.
 *
 * The picture always spans the full width; its height follows the real ratio (no clamping, so a
 * panorama is a flat, fully visible strip) up to [MediaBoxes.MAX_HEIGHT_DP]. Only a picture taller
 * than that cap is cropped (centre). Without known dimensions a 4:3 box shows the picture fitted,
 * never cut.
 */
data class MediaBox(val ratio: Float, val crop: Boolean)

object MediaBoxes {
    const val MAX_HEIGHT_DP = 400f
    /** A box flatter than this would be unusable to tap; the picture is fitted into it instead. */
    const val MIN_HEIGHT_DP = 80f
    const val FALLBACK_RATIO = 4f / 3f

    fun single(dim: Pair<Int, Int>?, widthDp: Float, maxHeightDp: Float = MAX_HEIGHT_DP, minHeightDp: Float = MIN_HEIGHT_DP): MediaBox {
        if (dim == null || dim.first <= 0 || dim.second <= 0 || widthDp <= 0f) return MediaBox(FALLBACK_RATIO, crop = false)
        val natural = dim.first.toFloat() / dim.second
        val height = widthDp / natural
        return when {
            height > maxHeightDp -> MediaBox(widthDp / maxHeightDp, crop = true)
            height < minHeightDp -> MediaBox(widthDp / minHeightDp, crop = false)
            else -> MediaBox(natural, crop = false)
        }
    }
}
