package com.monostr.app.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Spec 2026-11-10 (squircle, user choice C): a corner whose curvature ramps up instead of jumping onto a circle, as
 * iOS draws it. [smoothing] 0 is a plain rounded corner; 0.6 is the iOS look. Corner sizes work like
 * [androidx.compose.foundation.shape.RoundedCornerShape], so top-only sheets and segmented-button ends keep working.
 *
 * Below Android 13 a path outline is clipped without anti-aliasing (jagged picture corners), so there the shape falls
 * back to a plain rounded rectangle with the same radii; the difference is a few pixels.
 */
class SquircleShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
    val smoothing: Float = DEFAULT_SMOOTHING,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {

    constructor(radius: Dp, smoothing: Float = DEFAULT_SMOOTHING) : this(CornerSize(radius), CornerSize(radius), CornerSize(radius), CornerSize(radius), smoothing)

    override fun copy(topStart: CornerSize, topEnd: CornerSize, bottomEnd: CornerSize, bottomStart: CornerSize) =
        SquircleShape(topStart, topEnd, bottomEnd, bottomStart, smoothing)

    override fun createOutline(size: Size, topStart: Float, topEnd: Float, bottomEnd: Float, bottomStart: Float, layoutDirection: LayoutDirection): Outline {
        if (topStart + topEnd + bottomEnd + bottomStart == 0f) return Outline.Rectangle(size.toRect())
        val ltr = layoutDirection == LayoutDirection.Ltr
        val tl = if (ltr) topStart else topEnd
        val tr = if (ltr) topEnd else topStart
        val br = if (ltr) bottomEnd else bottomStart
        val bl = if (ltr) bottomStart else bottomEnd
        if (smoothing <= 0f || Build.VERSION.SDK_INT < 33) {
            return Outline.Rounded(RoundRect(size.toRect(), CornerRadius(tl), CornerRadius(tr), CornerRadius(br), CornerRadius(bl)))
        }
        return Outline.Generic(squirclePath(size.width, size.height, tl, tr, br, bl, smoothing))
    }

    override fun equals(other: Any?): Boolean = other is SquircleShape && topStart == other.topStart && topEnd == other.topEnd &&
        bottomEnd == other.bottomEnd && bottomStart == other.bottomStart && smoothing == other.smoothing

    override fun hashCode(): Int = ((((topStart.hashCode() * 31 + topEnd.hashCode()) * 31 + bottomEnd.hashCode()) * 31 + bottomStart.hashCode()) * 31) + smoothing.hashCode()

    override fun toString() = "SquircleShape(topStart = $topStart, topEnd = $topEnd, bottomEnd = $bottomEnd, bottomStart = $bottomStart, smoothing = $smoothing)"

    companion object {
        /** The iOS look; the mockup the user chose (2026-11-10) used it. */
        const val DEFAULT_SMOOTHING = 0.6f
    }
}

/**
 * One smoothed corner, after figma-squircle (MIT): a cubic from the edge into a circular arc of [radius] and a mirrored
 * cubic out of it. [p] is how far from the sharp corner the curve leaves each edge; [arc] the arc's extent along each axis.
 * [budget] (half the shorter side) caps both, and smoothing gives way first, so a pill stays a pill.
 */
internal class SquircleCorner(val radius: Float, val smoothing: Float, val p: Float, val a: Float, val b: Float, val c: Float, val d: Float, val arc: Float) {
    /** The arc's sweep in degrees: 90 for a plain corner, less the more the cubics take over. */
    val sweep: Float get() = 90f * (1f - smoothing)

    companion object {
        fun of(radius: Float, smoothing: Float, budget: Float): SquircleCorner {
            val r = min(radius, budget)
            if (r <= 0f) return SquircleCorner(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            val s = min(smoothing, max(0f, budget / r - 1f))
            val p = min((1f + smoothing) * r, budget)
            val arcMeasure = 90f * (1f - s)
            val arc = sin(rad(arcMeasure / 2f)) * r * sqrt(2f)
            val alpha = (90f - arcMeasure) / 2f
            val p3ToP4 = r * tan(rad(alpha / 2f))
            val beta = 45f * s
            val c = p3ToP4 * cos(rad(beta))
            val d = c * tan(rad(beta))
            val b = (p - arc - c - d) / 3f
            return SquircleCorner(r, s, p, 2f * b, b, c, d, arc)
        }

        private fun rad(deg: Float) = (deg * Math.PI / 180.0).toFloat()
    }
}

/**
 * The corner's points in corner-local terms: [along] the incoming edge before the sharp corner [corner] runs in
 * direction [inDir]; the outgoing edge runs in [outDir], which is also "inwards" for the incoming edge.
 */
internal class CornerFrame(val corner: Offset, val inDir: Offset, val outDir: Offset) {
    /** A point [t] before the corner on the incoming side, [k] inwards. */
    fun before(t: Float, k: Float) = corner - inDir * t + outDir * k
    /** A point [t] after the corner on the outgoing side, [k] inwards. */
    fun after(t: Float, k: Float) = corner + outDir * t - inDir * k
    /** The arc's circle centre for radius [r]. */
    fun centre(r: Float) = corner - inDir * r + outDir * r
}

internal fun squirclePath(w: Float, h: Float, tl: Float, tr: Float, br: Float, bl: Float, smoothing: Float): Path {
    val budget = min(w, h) / 2f
    // clockwise from the top-left: each corner is entered along its incoming edge and left along the next one
    val frames = listOf(
        CornerFrame(Offset(w, 0f), Offset(1f, 0f), Offset(0f, 1f)) to tr,
        CornerFrame(Offset(w, h), Offset(0f, 1f), Offset(-1f, 0f)) to br,
        CornerFrame(Offset(0f, h), Offset(-1f, 0f), Offset(0f, -1f)) to bl,
        CornerFrame(Offset(0f, 0f), Offset(0f, -1f), Offset(1f, 0f)) to tl,
    )
    val path = Path()
    val (tlFrame, tlRadius) = frames.last()
    val start = tlFrame.after(SquircleCorner.of(tlRadius, smoothing, budget).p, 0f)
    path.moveTo(start.x, start.y)
    for ((frame, radius) in frames) {
        val k = SquircleCorner.of(radius, smoothing, budget)
        if (k.radius <= 0f) { path.lineTo(frame.corner.x, frame.corner.y); continue }
        val s0 = frame.before(k.p, 0f)
        path.lineTo(s0.x, s0.y)
        val c1 = frame.before(k.p - k.a, 0f)
        val c2 = frame.before(k.p - k.a - k.b, 0f)
        val arcStart = frame.before(k.p - k.a - k.b - k.c, k.d)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, arcStart.x, arcStart.y)
        val centre = frame.centre(k.radius)
        val startAngle = Math.toDegrees(atan2((arcStart.y - centre.y).toDouble(), (arcStart.x - centre.x).toDouble())).toFloat()
        path.arcTo(Rect(centre, k.radius), startAngle, k.sweep, forceMoveTo = false)
        val c3 = frame.after(k.p - k.a - k.b, 0f)
        val c4 = frame.after(k.p - k.a, 0f)
        val end = frame.after(k.p, 0f)
        path.cubicTo(c3.x, c3.y, c4.x, c4.y, end.x, end.y)
    }
    path.close()
    return path
}
