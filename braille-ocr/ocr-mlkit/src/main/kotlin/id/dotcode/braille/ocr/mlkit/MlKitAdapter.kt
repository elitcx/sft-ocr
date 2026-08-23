package id.dotcode.braille.ocr.mlkit

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.geometry.PointF
import id.dotcode.braille.ocr.raw.RawLine
import id.dotcode.braille.ocr.raw.RawTextResult
import id.dotcode.braille.ocr.raw.RawWord

/**
 * Translates ML Kit output into the provider-agnostic contract.
 *
 * ML Kit's own block grouping is discarded here on purpose. Its blocks are geometric
 * clusters rather than semantic units, and LineMerger re-derives paragraph boundaries
 * under rules this project controls.
 */
object MlKitAdapter {

    fun toRawTextResult(text: Text, imageWidth: Int, imageHeight: Int): RawTextResult {
        val lines = text.textBlocks
            .flatMap { it.lines }
            .map { line ->
                RawLine(
                    text = line.text,
                    box = line.boundingBox.toBoxF(),
                    cornerPoints = line.cornerPoints.orEmpty().map { PointF(it.x.toFloat(), it.y.toFloat()) },
                    angleDeg = line.angle,
                    recognizedLanguage = line.recognizedLanguage,
                    confidence = line.confidence,
                    words = line.elements.map { element ->
                        RawWord(
                            text = element.text,
                            box = element.boundingBox.toBoxF(),
                            confidence = element.confidence,
                        )
                    },
                )
            }
        return RawTextResult(imageWidth = imageWidth, imageHeight = imageHeight, lines = lines)
    }

    private fun Rect?.toBoxF(): BoxF =
        if (this == null) BoxF(0f, 0f, 0f, 0f)
        else BoxF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}
