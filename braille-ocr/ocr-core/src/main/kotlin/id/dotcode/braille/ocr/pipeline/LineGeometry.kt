package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.geometry.PointF
import kotlin.math.atan2

/**
 * The tilt of a line's own recognizer-reported quadrilateral, read directly from its top
 * edge (`corners[0]` to `corners[1]`, matching the corner order the recognizer/[FrameRotation]
 * produce). Returns null when fewer than two corners are available (a synthetic fixture, or
 * a provider that does not report corner points) or when the two points coincide.
 *
 * Extracted as a shared, package-level utility because two call sites need a line's tilt
 * independently of [id.dotcode.braille.ocr.raw.RawLine.angleDeg]: [FrameRotation] (before
 * that field means anything, since the provider measured it in the pre-rotation frame) and
 * [ColumnSegmenter]'s facing-page check (after [SkewEstimator.deskew], which — per its KDoc —
 * resets `angleDeg` to 0 for every line once the page's single shared skew has been removed,
 * even though a line belonging to a physically different surface caught in the same frame,
 * such as a facing book page, may still carry genuine residual tilt relative to that shared
 * correction).
 */
internal fun angleFromCorners(corners: List<PointF>): Float? {
    if (corners.size < 2) return null
    val topLeft = corners[0]
    val topRight = corners[1]
    val dx = topRight.x - topLeft.x
    val dy = topRight.y - topLeft.y
    if (dx == 0f && dy == 0f) return null
    return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
}
