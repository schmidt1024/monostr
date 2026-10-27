package com.monostr.app.ui.media

/**
 * Backlog 39: how a note's pictures are arranged, like X: one full width, two side by side, three as one big on the
 * left and two stacked on the right (all three square, [bigLeftSides]), four as 2 × 2; from five on 2 × 2 with «+N»
 * ([more]) on the fourth.
 */
data class MediaGridLayout(val kind: Kind, val shown: Int, val more: Int) {
    enum class Kind { SINGLE, PAIR, BIG_LEFT, GRID }

    companion object {
        /**
         * Three squares in a row of [width] with [gap] between the cells: the big one on the left is as tall as the two
         * small ones stacked with the gap between them. Returns (big side, small side); the big side is the row's height.
         */
        fun bigLeftSides(width: Float, gap: Float): Pair<Float, Float> {
            val small = (width - 2 * gap) / 3
            return (2 * small + gap) to small
        }

        /** The block of four pictures has this width : height. */
        const val BLOCK_RATIO = 16f / 9f

        fun of(count: Int): MediaGridLayout = when {
            count <= 1 -> MediaGridLayout(Kind.SINGLE, count.coerceAtLeast(0), 0)
            count == 2 -> MediaGridLayout(Kind.PAIR, 2, 0)
            count == 3 -> MediaGridLayout(Kind.BIG_LEFT, 3, 0)
            else -> MediaGridLayout(Kind.GRID, 4, count - 4)
        }
    }
}
