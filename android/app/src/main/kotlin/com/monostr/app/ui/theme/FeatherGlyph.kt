package com.monostr.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The compose button's quill (0.11.11): the "feather" icon of Feather Icons (feathericons.com, MIT),
 * a 24 dp line drawing with 2 dp strokes like the Material outlined icons. Tinted through
 * [androidx.compose.material3.Icon].
 */
val FeatherGlyph: ImageVector by lazy {
    ImageVector.Builder(name = "feather_glyph", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            // the vane: a rounded tip at the top right, the base cut square at the bottom left
            moveTo(20.24f, 12.24f)
            arcTo(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = false, 11.75f, 3.75f)
            lineTo(5f, 10.5f)
            lineTo(5f, 19f)
            lineTo(13.5f, 19f)
            close()
            // the shaft
            moveTo(16f, 8f)
            lineTo(2f, 22f)
            // the barb across the vane
            moveTo(17.5f, 15f)
            lineTo(9f, 15f)
        }
    }.build()
}
