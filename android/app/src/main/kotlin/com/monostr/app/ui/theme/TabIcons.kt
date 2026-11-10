package com.monostr.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Bottom-bar icons (spec 2026-11-10, bar C): the line family of [FeatherGlyph], after Feather Icons (feathericons.com,
 * MIT), 24 dp with 1.8 dp strokes. Each tab has an outline and an active variant: filled, or for the magnifier a
 * heavier stroke, since a filled lens reads as a blot. Tinted through [androidx.compose.material3.Icon].
 */
object TabIcons {
    private const val STROKE = 1.8f
    private const val STROKE_ACTIVE = 2.6f

    // Feather's home with the door cut out as one outline, so the filled variant shows the door without a second colour
    private const val HOME = "M3 9L12 2L21 9V20A2 2 0 0 1 19 22H15V12H9V22H5A2 2 0 0 1 3 20Z"
    private const val HOME_DOOR = "M9 22V12H15V22"
    private const val SEARCH = "M19 11A8 8 0 1 1 3 11A8 8 0 1 1 19 11ZM21 21L16.65 16.65"
    private const val BOOKMARK = "M19 21L12 16L5 21V5A2 2 0 0 1 7 3H17A2 2 0 0 1 19 5Z"
    private const val BELL = "M18 8A6 6 0 0 0 6 8C6 15 3 17 3 17H21C21 17 18 15 18 8Z"
    private const val BELL_CLAPPER = "M13.73 21A2 2 0 0 1 10.27 21"
    private const val MESSAGE = "M21 11.5A8.38 8.38 0 0 1 20.1 15.3A8.5 8.5 0 0 1 12.5 20A8.38 8.38 0 0 1 8.7 19.1L3 21L4.9 15.3A8.38 8.38 0 0 1 4 11.5A8.5 8.5 0 0 1 8.7 3.9A8.38 8.38 0 0 1 12.5 3H13A8.48 8.48 0 0 1 21 11V11.5Z"

    val Home by lazy { icon("tab_home", listOf(HOME to false, HOME_DOOR to false)) }
    val HomeActive by lazy { icon("tab_home_active", listOf(HOME to true)) }
    val Search by lazy { icon("tab_search", listOf(SEARCH to false)) }
    val SearchActive by lazy { icon("tab_search_active", listOf(SEARCH to false), stroke = STROKE_ACTIVE) }
    val Bookmark by lazy { icon("tab_bookmark", listOf(BOOKMARK to false)) }
    val BookmarkActive by lazy { icon("tab_bookmark_active", listOf(BOOKMARK to true)) }
    val Bell by lazy { icon("tab_bell", listOf(BELL to false, BELL_CLAPPER to false)) }
    val BellActive by lazy { icon("tab_bell_active", listOf(BELL to true, BELL_CLAPPER to false)) }
    val Message by lazy { icon("tab_message", listOf(MESSAGE to false)) }
    val MessageActive by lazy { icon("tab_message_active", listOf(MESSAGE to true)) }

    /** [paths]: SVG path data, each with whether it is filled as well as stroked. */
    private fun icon(name: String, paths: List<Pair<String, Boolean>>, stroke: Float = STROKE): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            for ((data, filled) in paths) {
                addPath(
                    pathData = addPathNodes(data),
                    fill = if (filled) SolidColor(Color.Black) else null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = stroke,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
