package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.geometry.PointF
import id.dotcode.braille.ocr.raw.RawLine
import id.dotcode.braille.ocr.raw.RawTextResult
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class DeskewResult(val result: RawTextResult, val skewDeg: Float)

/**
 * Stage 1. Estimates page rotation and maps every coordinate into upright page space so
 * later stages may assume axis-aligned boxes.
 *
 * The estimate is a median rather than a mean: one badly recognized line at a wild angle
 * should not tilt the whole page.
 *
 * Deskew works from [RawLine.cornerPoints] — the recognizer's tilted quadrilateral — and
 * not from the axis-aligned [RawLine.box]. Rotating the four corners of an
 * already-axis-aligned box and re-enclosing them yields the AABB of a rotated AABB, whose
 * height is `w*sin(t) + h*cos(t)`: a 200x40 line at 10 degrees measures 74.1 tall instead
 * of 40. The inflation scales with line WIDTH, so long body lines inflate more than a
 * short heading, which flattens the relative-height signal RoleClassifier depends on and
 * inflates `medianLineHeight` (and with it LineMerger's paragraph-gap threshold). The
 * corner points of a genuinely tilted line rotate back to a tight upright box instead.
 */
class SkewEstimator(private val config: StructuringConfig) {

    fun estimateDeg(lines: List<RawLine>): Float {
        val angles = lines.filter { it.text.isNotBlank() }.map { it.angleDeg }
        return PageStats.median(angles) ?: 0f
    }

    fun deskew(result: RawTextResult): DeskewResult {
        val skew = estimateDeg(result.lines)
        if (abs(skew) < config.minSkewDeg) return DeskewResult(result, 0f)

        val pivot = PointF(result.imageWidth / 2f, result.imageHeight / 2f)
        val rotated = result.lines.map { it.rotatedBy(-skew, pivot) }
        return DeskewResult(result.copy(lines = rotated), skew)
    }

    private fun RawLine.rotatedBy(deg: Float, pivot: PointF): RawLine {
        val rotatedCorners = cornerPoints.map { it.rotatedAbout(deg, pivot) }
        val newBox =
            if (rotatedCorners.isNotEmpty()) {
                // Normal path: the recognizer gave us the real tilted quad, so the
                // upright box built from it is tight.
                enclosing(rotatedCorners)
            } else {
                // EXPLICIT FALLBACK. No corner points were supplied — a synthetic test
                // page, or a provider that does not report them. All we have is an
                // axis-aligned box, so the best available answer is the enclosing hull
                // of its rotated corners. This DOES inflate the box by w*sin+h*cos; it
                // is accepted only because there is no tighter answer derivable from an
                // AABB alone. Providers should populate cornerPoints.
                enclosing(box.corners().map { it.rotatedAbout(deg, pivot) })
            }
        return copy(
            box = newBox,
            cornerPoints = rotatedCorners,
            angleDeg = 0f,
            // Words carry no corner points in the contract, so they necessarily take the
            // fallback. Nothing downstream measures word geometry; they are carried
            // through only so exported coordinates stay in one consistent space.
            words = words.map { w -> w.copy(box = enclosing(w.box.corners().map { it.rotatedAbout(deg, pivot) })) },
        )
    }

    private fun BoxF.corners(): List<PointF> =
        listOf(PointF(left, top), PointF(right, top), PointF(right, bottom), PointF(left, bottom))

    private fun PointF.rotatedAbout(deg: Float, pivot: PointF): PointF {
        val rad = deg * Math.PI.toFloat() / 180f
        val cos = cos(rad)
        val sin = sin(rad)
        val dx = x - pivot.x
        val dy = y - pivot.y
        return PointF(pivot.x + dx * cos - dy * sin, pivot.y + dx * sin + dy * cos)
    }

    private fun enclosing(points: List<PointF>): BoxF = BoxF(
        left = points.minOf { it.x },
        top = points.minOf { it.y },
        right = points.maxOf { it.x },
        bottom = points.maxOf { it.y },
    )
}
