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

    private fun RawLine.rotatedBy(deg: Float, pivot: PointF): RawLine =
        copy(box = box.rotatedBy(deg, pivot), angleDeg = 0f, words = words.map { it.copy(box = it.box.rotatedBy(deg, pivot)) })

    private fun BoxF.rotatedBy(deg: Float, pivot: PointF): BoxF {
        val rad = deg * Math.PI.toFloat() / 180f
        val cos = cos(rad)
        val sin = sin(rad)
        val corners = listOf(
            PointF(left, top), PointF(right, top), PointF(right, bottom), PointF(left, bottom),
        ).map { p ->
            val dx = p.x - pivot.x
            val dy = p.y - pivot.y
            PointF(pivot.x + dx * cos - dy * sin, pivot.y + dx * sin + dy * cos)
        }
        return BoxF(
            left = corners.minOf { it.x },
            top = corners.minOf { it.y },
            right = corners.maxOf { it.x },
            bottom = corners.maxOf { it.y },
        )
    }
}
