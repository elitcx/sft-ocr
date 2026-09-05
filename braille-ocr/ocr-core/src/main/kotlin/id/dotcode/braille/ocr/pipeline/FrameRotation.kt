package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.geometry.PointF
import id.dotcode.braille.ocr.raw.RawLine
import id.dotcode.braille.ocr.raw.RawTextResult

/**
 * Corrects a recognizer's EXIF-driven capture rotation before anything else in the
 * pipeline runs.
 *
 * This is a *discrete* frame transform — an exact multiple of 90 degrees, known in
 * advance from EXIF orientation — and is deliberately kept separate from
 * [SkewEstimator]'s *continuous* skew correction (a few degrees of camera tilt, unknown
 * until measured from the text itself). Conflating the two was a real, shipped bug: a
 * caller-side fix once handed [DocumentStructurer] the EXIF-rotated page dimensions
 * (e.g. a 90-degree capture's page reported as tall-and-narrow) while leaving the
 * recognizer's own line coordinates in the ORIGINAL, un-rotated, wide-and-short frame —
 * ML Kit's `Text.Line` coordinates are relative to the bitmap actually decoded, not to
 * the upright orientation implied by the `rotationDegrees` hint passed alongside it.
 * [SkewEstimator] then saw every line's reported angle sitting near +/-90 degrees (a
 * real page tilted 90 degrees relative to its own declared page space) and "corrected"
 * it by rotating about a pivot built from the WRONG, swapped page dimensions. The
 * result: a page it reported as skewed 85-91 degrees for perfectly upright real
 * photographs, and line boxes that spilled outside the declared page bounds because
 * they were rotated about the wrong center. Reading order still came out right for
 * business-as-usual photos only because a near-90-degree rotation about almost any
 * nearby pivot still roughly preserves top-to-bottom, left-to-right ordering — the bug
 * was silent corruption of `skewDeg` and box bounds, not (consistently) of reading
 * order, which is exactly the kind of error that is easy to miss without measuring
 * real captures.
 *
 * [apply] must run BEFORE [SkewEstimator]: once the frame is upright, only genuine
 * residual camera tilt remains for [SkewEstimator] to measure, typically a couple of
 * degrees.
 */
object FrameRotation {

    /**
     * @param raw recognizer output whose [RawTextResult.imageWidth]/[RawTextResult
     * .imageHeight] and every line/word coordinate are all in the SAME frame — the
     * frame the recognizer actually decoded, before any EXIF correction. Passing
     * already-corrected dimensions here (while coordinates remain uncorrected, or vice
     * versa) reproduces the exact bug this object exists to prevent.
     * @param rotationDegrees the EXIF-driven clockwise rotation, in degrees, that must
     * be applied to [raw] to make it upright. Must be a multiple of 90; any other value
     * is a caller error, not a recoverable geometry problem, since EXIF orientation
     * only ever specifies quarter turns.
     */
    fun apply(raw: RawTextResult, rotationDegrees: Int): RawTextResult {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (normalized == 0) return raw
        require(normalized % 90 == 0) {
            "rotationDegrees must be a multiple of 90, was $rotationDegrees"
        }

        val quarterTurn = normalized % 180 != 0
        val newWidth = if (quarterTurn) raw.imageHeight else raw.imageWidth
        val newHeight = if (quarterTurn) raw.imageWidth else raw.imageHeight

        fun turn(p: PointF): PointF = when (normalized) {
            90 -> PointF(raw.imageHeight - p.y, p.x)
            180 -> PointF(raw.imageWidth - p.x, raw.imageHeight - p.y)
            else -> PointF(p.y, raw.imageWidth - p.x) // 270
        }

        fun turnBox(box: BoxF): BoxF = enclosing(box.corners().map(::turn))

        val rotatedLines = raw.lines.map { line ->
            val rotatedCorners = line.cornerPoints.map(::turn)
            val newBox = if (rotatedCorners.isNotEmpty()) enclosing(rotatedCorners) else turnBox(line.box)
            line.copy(
                box = newBox,
                cornerPoints = rotatedCorners,
                // The provider's angle was measured in the pre-rotation frame and no
                // longer means anything on its own; re-derive it from the rotated
                // geometry itself so it reflects only genuine residual skew regardless
                // of sign conventions the provider used. Falls back to 0 (assumed
                // upright) when no corner points were supplied.
                angleDeg = angleFromCorners(rotatedCorners) ?: 0f,
                words = line.words.map { it.copy(box = turnBox(it.box)) },
            )
        }
        return RawTextResult(imageWidth = newWidth, imageHeight = newHeight, lines = rotatedLines)
    }

    private fun BoxF.corners(): List<PointF> =
        listOf(PointF(left, top), PointF(right, top), PointF(right, bottom), PointF(left, bottom))

    private fun enclosing(points: List<PointF>): BoxF = BoxF(
        left = points.minOf { it.x },
        top = points.minOf { it.y },
        right = points.maxOf { it.x },
        bottom = points.maxOf { it.y },
    )
}
