package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.raw.RawLine
import id.dotcode.braille.ocr.raw.RawTextResult

/** Builds a RawLine from top-left origin plus size, which reads far better in tests than four edges. */
fun line(
    text: String,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    angleDeg: Float = 0f,
    confidence: Float? = 0.95f,
): RawLine = RawLine(
    text = text,
    box = BoxF(left = x, top = y, right = x + w, bottom = y + h),
    angleDeg = angleDeg,
    confidence = confidence,
)

fun page(
    vararg lines: RawLine,
    width: Int = 1600,
    height: Int = 2000,
): RawTextResult = RawTextResult(imageWidth = width, imageHeight = height, lines = lines.toList())
