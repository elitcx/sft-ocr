package id.dotcode.braille.ocr.mlkit

import android.graphics.Bitmap
import id.dotcode.braille.ocr.model.FailureReason
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Rejects captures that cannot produce trustworthy text.
 *
 * This matters more here than in a typical OCR app. A blind student reading garbage
 * braille under their fingers has no way to notice that recognition failed, so refusing a
 * bad capture with actionable feedback is safer than emitting confident nonsense. But a
 * false rejection is harmful too: the student gets nothing at all and the app looks broken.
 * ML Kit's own [FailureReason.NoTextFound] is already a backstop for genuinely unusable
 * input, so this gate is deliberately biased toward permissiveness - it exists to catch
 * only catastrophic captures (lens cap, heavy motion blur, a dark room), not to second-guess
 * anything borderline.
 *
 * Sharpness is measured as a high percentile of the per-pixel absolute horizontal gradient,
 * not its mean. A real worksheet photo is overwhelmingly flat: white paper, desk, margins.
 * Text edges are a small fraction of all pixels, so averaging the gradient over every pixel
 * dilutes even perfectly sharp text toward zero and rejects sparse-but-sharp pages - the
 * bug this replaces. Looking at how strong the STRONGEST edges are, instead of how many
 * edges there are, measures actual sharpness: a sharp image has some pixels with large
 * gradients no matter how sparse the content is, while blur spreads every transition across
 * several pixels and caps the per-pixel magnitude everywhere, so a genuinely blurred capture
 * still fails even at a high percentile.
 *
 * Gradient magnitudes are bounded to 0..255 (an absolute difference of two lumas), so a
 * 256-bin histogram gives the exact percentile from one pass, with no sorting and no
 * allocation proportional to the frame size.
 */
class CaptureQualityGate(
    private val minMeanLuma: Int = 35,
    /**
     * The percentile of the absolute-gradient histogram used as the sharpness score.
     * 0.99 means: a capture is judged by its top 1% of pixel-to-pixel transitions. High
     * enough that JPEG noise and antialiasing on otherwise-flat regions cannot fake
     * sharpness; low enough that a text region covering just a few percent of the frame -
     * the norm for a worksheet photo with real margins - still dominates the tail of the
     * distribution and is measured.
     */
    private val sharpnessPercentile: Double = 0.99,
    /**
     * Minimum gradient magnitude (0..255) required at [sharpnessPercentile] to call a
     * capture sharp. Derived from synthetic sharp vs. blurred black-on-white text: a hard
     * glyph edge produces gradients in the 200+ range, while spreading that same edge over
     * a several-pixel ramp - what defocus or motion blur does to it - caps the per-step
     * gradient at roughly 30-50. 80 sits comfortably below the sharp case and above the
     * blurred case, with the gap biased toward letting borderline captures through rather
     * than rejecting them, per this gate's permissive design intent.
     */
    private val minSharpness: Int = 80,
) {

    /** [reason] is null when the capture passes. [detail] carries the measured values either way. */
    data class Evaluation(val reason: FailureReason?, val detail: String)

    fun evaluate(bitmap: Bitmap): Evaluation {
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
    fun evaluateLuma(luma: IntArray, width: Int, height: Int): Evaluation {
        if (luma.isEmpty()) {
            return Evaluation(FailureReason.NoTextFound, "empty frame")
        }

        val meanLuma = luma.sumOf { it.toLong() }.toDouble() / luma.size
        if (meanLuma < minMeanLuma) {
            return Evaluation(
                FailureReason.TooDark,
                "meanLuma=%.1f is below minMeanLuma=%d".format(meanLuma, minMeanLuma),
            )
        }

        // 256 bins: an absolute difference of two 0..255 lumas can never exceed 255.
        val histogram = IntArray(256)
        var samples = 0L
        for (y in 0 until height) {
            val row = y * width
            for (x in 1 until width) {
                histogram[abs(luma[row + x] - luma[row + x - 1])]++
                samples++
            }
        }
        val sharpness = percentile(histogram, samples, sharpnessPercentile)
        val detail = "meanLuma=%.1f, p%d gradient=%d (min %d)".format(
            meanLuma,
            (sharpnessPercentile * 100).toInt(),
            sharpness,
            minSharpness,
        )
        return if (sharpness < minSharpness) {
            Evaluation(FailureReason.TooBlurry, detail)
        } else {
            Evaluation(null, detail)
        }
    }

    /**
     * The smallest histogram bucket value `v` such that at least a [p] fraction of samples
     * are `<= v`. Equivalent to the [p]-th percentile of the underlying gradient values,
     * computed from the histogram's cumulative distribution in a single pass.
     */
    private fun percentile(histogram: IntArray, samples: Long, p: Double): Int {
        if (samples == 0L) return 0
        val target = ceil(samples * p).toLong().coerceAtLeast(1L)
        var cumulative = 0L
        for (value in histogram.indices) {
            cumulative += histogram[value]
            if (cumulative >= target) return value
        }
        return 255
    }
}
