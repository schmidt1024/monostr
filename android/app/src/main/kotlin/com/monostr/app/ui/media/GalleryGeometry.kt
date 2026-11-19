package com.monostr.app.ui.media

/** Sizes of one horizontal gallery row (backlog 93): [cardWidthsDp] per item in order, [crop] where the card is clamped. */
data class GalleryLayout(
    val rowHeightDp: Float,
    val cardWidthsDp: List<Float>,
    val crop: List<Boolean>,
    val overflows: Boolean,
)

/** Pure geometry of the gallery row (spec 2026-11-19 gallery carousel); no Compose, unit-testable. */
object GalleryGeometry {
    /** The first card takes this share of the row width; the row height follows from its ratio. */
    const val REFERENCE_WIDTH_SHARE = 0.62f
    const val MAX_CARD_WIDTH_SHARE = 0.85f
    const val MIN_CARD_ASPECT = 0.5f
    const val MIN_ROW_HEIGHT_DP = 160f
    const val GAP_DP = 4f
    const val PICTURE_FALLBACK = 4f / 5f
    const val VIDEO_FALLBACK = 16f / 9f

    private fun ratio(p: Pair<Int, Int>?): Float? =
        if (p != null && p.first > 0 && p.second > 0) p.first.toFloat() / p.second else null

    /** Width / height: the stated [dim], unless the [learned] size contradicts it (turned photo); else learned; else fallback. */
    fun aspect(dim: Pair<Int, Int>?, learned: Pair<Int, Int>?, video: Boolean): Float {
        val d = ratio(dim)
        val l = ratio(learned)
        return when {
            d != null && l != null && MediaRatios.contradicts(dim!!, learned!!) -> l
            d != null -> d
            l != null -> l
            video -> VIDEO_FALLBACK
            else -> PICTURE_FALLBACK
        }
    }

    /** Card width in dp and whether it is cropped: the natural width clamped to [MIN_CARD_ASPECT] x row height .. [MAX_CARD_WIDTH_SHARE] x row width; the upper bound always wins so the next card peeks. */
    fun cardWidth(aspect: Float, rowHeightDp: Float, rowWidthDp: Float): Pair<Float, Boolean> {
        val natural = aspect * rowHeightDp
        val min = MIN_CARD_ASPECT * rowHeightDp
        val max = MAX_CARD_WIDTH_SHARE * rowWidthDp
        val w = natural.coerceIn(minOf(min, max), max)
        return w to (w != natural)
    }

    fun layout(aspects: List<Float>, rowWidthDp: Float, maxHeightDp: Float): GalleryLayout {
        if (aspects.isEmpty()) return GalleryLayout(MIN_ROW_HEIGHT_DP, emptyList(), emptyList(), false)
        val height = (rowWidthDp * REFERENCE_WIDTH_SHARE / aspects[0])
            .coerceIn(MIN_ROW_HEIGHT_DP, maxOf(MIN_ROW_HEIGHT_DP, maxHeightDp))
        val cards = aspects.map { cardWidth(it, height, rowWidthDp) }
        val widths = cards.map { it.first }
        val total = widths.sum() + GAP_DP * (aspects.size - 1)
        return GalleryLayout(height, widths, cards.map { it.second }, total > rowWidthDp + 0.5f)
    }
}
