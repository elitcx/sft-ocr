package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.geometry.PointF
import id.dotcode.braille.ocr.raw.RawLine
import id.dotcode.braille.ocr.raw.RawTextResult
import kotlin.math.cos
import kotlin.math.sin

/** Builds a RawLine from top-left origin plus size, which reads far better in tests than four edges. */
fun line(
    text: String,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    angleDeg: Float = 0f,
    confidence: Float? = 0.95f,
    cornerPoints: List<PointF> = emptyList(),
): RawLine = RawLine(
    text = text,
    box = BoxF(left = x, top = y, right = x + w, bottom = y + h),
    cornerPoints = cornerPoints,
    angleDeg = angleDeg,
    confidence = confidence,
)

/**
 * Builds a line as a recognizer would actually report it on a skewed page: an upright
 * `w x h` rectangle at (x, y) rotated by [angleDeg] about [pivot], with `cornerPoints`
 * holding the true tilted quadrilateral and `box` holding its axis-aligned bounding box.
 * Deskewing such a line must recover a box of approximately `w x h`.
 */
fun skewedLine(
    text: String,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    angleDeg: Float,
    pivot: PointF,
    confidence: Float? = 0.95f,
): RawLine {
    val rad = angleDeg * Math.PI.toFloat() / 180f
    val cos = cos(rad)
    val sin = sin(rad)
    val corners = listOf(
        PointF(x, y), PointF(x + w, y), PointF(x + w, y + h), PointF(x, y + h),
    ).map { p ->
        val dx = p.x - pivot.x
        val dy = p.y - pivot.y
        PointF(pivot.x + dx * cos - dy * sin, pivot.y + dx * sin + dy * cos)
    }
    return RawLine(
        text = text,
        box = BoxF(
            left = corners.minOf { it.x },
            top = corners.minOf { it.y },
            right = corners.maxOf { it.x },
            bottom = corners.maxOf { it.y },
        ),
        cornerPoints = corners,
        angleDeg = angleDeg,
        confidence = confidence,
    )
}

fun page(
    vararg lines: RawLine,
    width: Int = 1600,
    height: Int = 2000,
): RawTextResult = RawTextResult(imageWidth = width, imageHeight = height, lines = lines.toList())
