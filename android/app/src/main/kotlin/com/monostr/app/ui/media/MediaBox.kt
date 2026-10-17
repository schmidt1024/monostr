package com.monostr.app.ui.media

/**
 * How a single picture is laid out in a note: the box's width/height ratio and whether the
 * picture is cropped to it.
 *
 * The picture always spans the full width; its height follows the real ratio (no clamping, so a
 * panorama is a flat, fully visible strip) up to [MediaBoxes.MAX_HEIGHT_DP]. Only a picture taller
 * than that cap is cropped (centre). Without known dimensions a 4:3 box shows the picture fitted,
 * never cut. A video's poster always fills its box (crop); without dimensions a video is 16:9.
 */
data class MediaBox(val ratio: Float, val crop: Boolean)

object MediaBoxes {
    const val MAX_HEIGHT_DP = 400f
    /** A box flatter than this would be unusable to tap; the picture is fitted into it instead. */
    const val MIN_HEIGHT_DP = 80f
    const val FALLBACK_RATIO = 4f / 3f
    /** Spec 2026-10-16 §6: a video without `dim` and without a known poster size. */
    const val VIDEO_FALLBACK_RATIO = 16f / 9f

    fun single(dim: Pair<Int, Int>?, widthDp: Float, maxHeightDp: Float = MAX_HEIGHT_DP, minHeightDp: Float = MIN_HEIGHT_DP, video: Boolean = false): MediaBox {
        if (dim == null || dim.first <= 0 || dim.second <= 0 || widthDp <= 0f) {
            return if (video) MediaBox(VIDEO_FALLBACK_RATIO, crop = true) else MediaBox(FALLBACK_RATIO, crop = false)
        }
        val natural = dim.first.toFloat() / dim.second
        val height = widthDp / natural
        val box = when {
            height > maxHeightDp -> MediaBox(widthDp / maxHeightDp, crop = true)
            height < minHeightDp -> MediaBox(widthDp / minHeightDp, crop = false)
            else -> MediaBox(natural, crop = false)
        }
        // spec 2026-10-16 §6: the poster fills the box, no bars; the player shows the whole video
        return if (video) box.copy(crop = true) else box
    }
}
