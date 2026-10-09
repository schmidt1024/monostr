package com.monostr.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Monero symbol as an outlined 24 dp icon: a ring with the "M" whose legs run to the lower
 * edge, drawn with 2 dp strokes so it sits next to the Material outlined icons. Tinted
 * through [androidx.compose.material3.Icon] like every other action icon.
 */
val MoneroGlyph: ImageVector by lazy {
    ImageVector.Builder(name = "monero_glyph", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f) {
            moveTo(22f, 12f)
            arcTo(10f, 10f, 0f, isMoreThanHalf = true, isPositiveArc = true, 2f, 12f)
            arcTo(10f, 10f, 0f, isMoreThanHalf = true, isPositiveArc = true, 22f, 12f)
        }
        // the M's outer legs end in horizontal bars that run out to the circle, as in the original Monero symbol
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(3.1f, 16.5f)
            lineTo(6.5f, 16.5f)
            lineTo(6.5f, 8.5f)
            lineTo(12f, 14f)
            lineTo(17.5f, 8.5f)
            lineTo(17.5f, 16.5f)
            lineTo(20.9f, 16.5f)
        }
    }.build()
}
