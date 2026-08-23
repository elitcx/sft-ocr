package id.dotcode.braille.ocr.mlkit

import android.graphics.Bitmap
import id.dotcode.braille.ocr.model.FailureReason
import kotlin.math.abs

/**
 * Rejects captures that cannot produce trustworthy text.
 *
 * This matters more here than in a typical OCR app. A blind student reading garbage
 * braille under their fingers has no way to notice that recognition failed, so refusing a
 * bad capture with actionable feedback is safer than emitting confident nonsense.
 *
 * Sharpness uses mean absolute horizontal gradient rather than a true Laplacian variance:
 * it is a single pass, allocation-free, and discriminates blur just as well at this
 * threshold.
 */
class CaptureQualityGate(
    private val minMeanLuma: Int = 35,
    private val minSharpness: Double = 4.0,
) {

    fun evaluate(bitmap: Bitmap): FailureReason? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val luma = IntArray(pixels.size) { i ->
            val p = pixels[i]
            ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        return evaluateLuma(luma, width, height)
    }

    /** Separated from [evaluate] so the thresholds are testable without an Android runtime. */
    fun evaluateLuma(luma: IntArray, width: Int, height: Int): FailureReason? {
        if (luma.isEmpty()) return FailureReason.NoTextFound

        val meanLuma = luma.sumOf { it.toLong() }.toDouble() / luma.size
        if (meanLuma < minMeanLuma) return FailureReason.TooDark

        var gradientSum = 0L
        var samples = 0L
        for (y in 0 until height) {
            val row = y * width
            for (x in 1 until width) {
                gradientSum += abs(luma[row + x] - luma[row + x - 1]).toLong()
                samples++
            }
        }
        val sharpness = if (samples == 0L) 0.0 else gradientSum.toDouble() / samples
        return if (sharpness < minSharpness) FailureReason.TooBlurry else null
    }
}
