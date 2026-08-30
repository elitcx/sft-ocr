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
 * Sharpness is measured over EDGE pixels only, not all pixels - and not by a percentile
 * over the whole frame. The previous fix (a high percentile of the gradient histogram over
 * every pixel) still depended on how much of the frame was text: it only reflected the text
 * once high-gradient pixels passed roughly 1% of the frame, so a genuinely sharp photo of a
 * sparse worksheet - a title and a couple of short questions with wide margins, a completely
 * normal page - fell below that cliff and was rejected as blurry again. That is the bug this
 * replaces.
 *
 * The fix separates two questions the old metric conflated: "how much of the frame is text"
 * (irrelevant to sharpness) and "how strong are the edges that exist" (the actual signal).
 * A pixel counts as an EDGE when its gradient exceeds [edgeGradientFloor]; the sharpness
 * score is then the [sharpnessPercentile] of gradient magnitude computed ONLY over those
 * edge pixels. A sharp glyph edge produces a large gradient in one step regardless of how
 * few such edges the page has; blur spreads every transition across several pixels and caps
 * the per-step magnitude everywhere, including at the edges that do exist - so the edge-only
 * score still separates sharp from blurred at any text coverage. Separately, [minEdgeFraction]
 * asks "is there any text-like content at all" - a blank sheet or a lens-cap shot has (near)
 * zero edge pixels, which is a different failure ([FailureReason.NoTextFound]) from a page
 * that has real content but shot out of focus ([FailureReason.TooBlurry]): telling a student
 * to hold the phone steadier is misleading advice when there is nothing there to focus on.
 *
 * Gradient magnitudes are bounded to 0..255 (an absolute difference of two lumas), so a
 * 256-bin histogram gives exact percentiles and fractions from one pass, with no sorting and
 * no allocation proportional to the frame size.
 */
class CaptureQualityGate(
    private val minMeanLuma: Int = 35,
    /**
     * Minimum absolute gradient (0..255) for a pixel to count as an EDGE pixel at all,
     * rather than JPEG noise or antialiasing on an otherwise-flat region. Synthetic flat
     * regions in this gate's own test fixtures produce zero gradient; real camera sensor
     * noise on paper/desk background is typically a few luma levels. 10 sits comfortably
     * above that noise floor while still well below the ~18 a genuinely blurred glyph edge
     * produces (see [minSharpness]), so it does not itself risk misclassifying blur as
     * "no edges".
     */
    private val edgeGradientFloor: Int = 10,
    /**
     * Minimum fraction of all pixel-to-pixel gradient samples that must be EDGE pixels
     * (see [edgeGradientFloor]) before this gate will even attempt a sharpness verdict.
     * Below this, the page is treated as having no text at all ([FailureReason.NoTextFound])
     * rather than being blurry. A synthetic sharp worksheet fixture at 0.2% text coverage -
     * near the sparse end of a real page (a title and a couple of short questions) -
     * measures roughly 0.05% edge-pixel fraction; 0.0001 (0.01%) sits five times below that,
     * so genuine sparse content is never mistaken for a blank page, while a fully flat frame
     * (zero edge pixels) is always caught.
     */
    private val minEdgeFraction: Double = 0.0001,
    /**
     * The percentile of gradient magnitude, computed over EDGE pixels only, used as the
     * sharpness score. The median (0.5) is deliberately not a high percentile like the old
     * whole-frame metric: restricting to edge pixels already isolates the text-edge
     * population, so the score should describe that population's typical strength rather
     * than chase its extreme tail, which would make the score sensitive to a handful of
     * outlier reflections or JPEG artifacts.
     */
    private val sharpnessPercentile: Double = 0.5,
    /**
     * Minimum sharpness score required to call a capture sharp. Derived from synthetic
     * sharp vs. blurred black-on-white text at text coverage from 0.2% to 5%: hard glyph
     * edges score ~215 regardless of coverage, while the same edges blurred by a 13-pixel
     * ramp score ~17, also regardless of coverage - restricting the metric to edge pixels
     * makes both numbers coverage-independent. 80 sits in the wide gap between them, biased
     * toward letting borderline captures through rather than rejecting them, per this
     * gate's permissive design intent.
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

        val edgeSamples = histogram.drop(edgeGradientFloor + 1).sumOf { it.toLong() }
        val edgeFraction = if (samples == 0L) 0.0 else edgeSamples.toDouble() / samples
        if (edgeFraction < minEdgeFraction) {
            return Evaluation(
                FailureReason.NoTextFound,
                "edgeFraction=%.6f is below minEdgeFraction=%.6f (edgeSamples=%d)"
                    .format(edgeFraction, minEdgeFraction, edgeSamples),
            )
        }

        val sharpness = percentile(histogram, edgeSamples, sharpnessPercentile, startValue = edgeGradientFloor + 1)
        val detail = "meanLuma=%.1f, edgeFraction=%.6f, p%d edge-gradient=%d (min %d)".format(
            meanLuma,
            edgeFraction,
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
     * The smallest histogram bucket value `v` in `[startValue, 255]` such that at least a
     * [p] fraction of [samples] (the total count across that same range) are `<= v`.
     * Equivalent to the [p]-th percentile of the underlying gradient values restricted to
     * `>= startValue`, computed from the histogram's cumulative distribution in one pass.
     */
    private fun percentile(histogram: IntArray, samples: Long, p: Double, startValue: Int = 0): Int {
        if (samples == 0L) return 0
        val target = ceil(samples * p).toLong().coerceAtLeast(1L)
        var cumulative = 0L
        for (value in startValue..255) {
            cumulative += histogram[value]
            if (cumulative >= target) return value
        }
        return 255
    }
}
