package id.dotcode.braille.ocr.mlkit

import id.dotcode.braille.ocr.model.FailureReason
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CaptureQualityGateTest {
    private val gate = CaptureQualityGate()

    /** A synthetic grayscale plane: sharp alternating stripes have high gradient energy. */
    private fun stripes(width: Int, height: Int, period: Int, low: Int, high: Int): IntArray =
        IntArray(width * height) { i -> if ((i % width) / period % 2 == 0) low else high }

    private fun flat(width: Int, height: Int, value: Int): IntArray = IntArray(width * height) { value }

    /**
     * A large mostly-flat frame ([background] fill) carrying one small rectangular block of
     * sharp alternating stripes, simulating the text region of a real worksheet photo against
     * its paper/desk/margin surroundings. [blockWidth] x [blockHeight] is placed at the
     * top-left corner of the frame.
     */
    private fun sparseTextOnFlat(
        width: Int,
        height: Int,
        background: Int,
        blockWidth: Int,
        blockHeight: Int,
        period: Int,
        low: Int,
        high: Int,
    ): IntArray {
        val luma = IntArray(width * height) { background }
        for (y in 0 until blockHeight) {
            for (x in 0 until blockWidth) {
                val stripe = if ((x / period) % 2 == 0) low else high
                luma[y * width + x] = stripe
            }
        }
        return luma
    }

    /**
     * Replaces this sparse-text frame's hard stripe edges with gradual ramps spread over
     * [rampWidth] pixels, simulating what defocus/motion blur does to a real edge: it does
     * not erase the transition, it spreads it out and caps its per-pixel magnitude.
     */
    private fun blurHorizontally(luma: IntArray, width: Int, height: Int, rampWidth: Int): IntArray {
        val blurred = luma.copyOf()
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sum = 0
                var count = 0
                for (k in -rampWidth..rampWidth) {
                    val xi = x + k
                    if (xi in 0 until width) {
                        sum += luma[row + xi]
                        count++
                    }
                }
                blurred[row + x] = sum / count
            }
        }
        return blurred
    }

    /** The OLD, buggy metric this change replaces: mean absolute gradient over ALL pixels. */
    private fun oldMeanSharpness(luma: IntArray, width: Int, height: Int): Double {
        var gradientSum = 0L
        var samples = 0L
        for (y in 0 until height) {
            val row = y * width
            for (x in 1 until width) {
                gradientSum += abs(luma[row + x] - luma[row + x - 1]).toLong()
                samples++
            }
        }
        return if (samples == 0L) 0.0 else gradientSum.toDouble() / samples
    }

    @Test
    fun `sharp well lit content passes`() {
        assertNull(gate.evaluateLuma(stripes(64, 64, 4, 20, 235), 64, 64).reason)
    }

    @Test
    fun `a flat mid gray frame is no text found, not too blurry`() {
        // A flat frame has ZERO gradient anywhere: there is no text to be blurry, so
        // "hold the phone steadier" would be misleading advice. See the reasoning in
        // `a fully flat frame with no edges anywhere is too blurry` below (renamed
        // expectation - both flat-frame cases are NoTextFound under the new edge-only
        // metric, which can tell "no edges" apart from "edges too weak").
        assertEquals(FailureReason.NoTextFound, gate.evaluateLuma(flat(64, 64, 128), 64, 64).reason)
    }

    @Test
    fun `a near black frame is too dark`() {
        assertEquals(FailureReason.TooDark, gate.evaluateLuma(stripes(64, 64, 4, 2, 10), 64, 64).reason)
    }

    @Test
    fun `darkness is reported before blurriness`() {
        assertEquals(FailureReason.TooDark, gate.evaluateLuma(flat(64, 64, 3), 64, 64).reason)
    }

    @Test
    fun `sparse sharp text on a mostly flat worksheet frame passes`() {
        val width = 1600
        val height = 1200
        val luma = sparseTextOnFlat(
            width = width,
            height = height,
            background = 250,
            blockWidth = 192,
            blockHeight = 600,
            period = 4,
            low = 20,
            high = 235,
        )

        // This is the regression: the OLD mean-based metric drowns the sharp text edges in
        // the surrounding flat frame and rejects the capture, even though it is perfectly
        // sharp. Confirm the old metric really does fail here before asserting the new gate
        // does not.
        val oldMetricRejects = oldMeanSharpness(luma, width, height) < 4.0
        assertTrue(oldMetricRejects, "expected the old mean-gradient metric to fail on sparse text")

        val result = gate.evaluateLuma(luma, width, height)
        assertNull(result.reason, "sparse but sharp text should pass: ${result.detail}")
    }

    @Test
    fun `genuinely blurred sparse text is still rejected`() {
        val width = 1600
        val height = 1200
        val sharp = sparseTextOnFlat(
            width = width,
            height = height,
            background = 250,
            blockWidth = 192,
            blockHeight = 600,
            period = 4,
            low = 20,
            high = 235,
        )
        val blurred = blurHorizontally(sharp, width, height, rampWidth = 6)

        val result = gate.evaluateLuma(blurred, width, height)
        assertEquals(FailureReason.TooBlurry, result.reason, result.detail)
    }

    @Test
    fun `a fully flat frame with no edges anywhere is no text found`() {
        // Renamed expectation: under the pixel-density-independent metric, "no edges at
        // all" (a blank sheet, a lens cap) is distinguished from "edges exist but are
        // weak" (genuine blur). The former is NoTextFound, not TooBlurry - see class KDoc.
        val result = gate.evaluateLuma(flat(1600, 1200, 200), 1600, 1200)
        assertEquals(FailureReason.NoTextFound, result.reason)
    }

    @Test
    fun `sparse sharp text on a dim but legible background passes`() {
        val width = 1600
        val height = 1200
        val luma = sparseTextOnFlat(
            width = width,
            height = height,
            background = 40,
            blockWidth = 192,
            blockHeight = 600,
            period = 4,
            low = 10,
            high = 130,
        )

        val result = gate.evaluateLuma(luma, width, height)
        assertNull(result.reason, "dim but sharp text should not be double-rejected: ${result.detail}")
    }

    /**
     * Pins the exact sharpness boundary documented on [CaptureQualityGate.minSharpness]
     * (80): a future tweak to the metric or the threshold should not be able to move this
     * line silently. [stripes] with a fixed step size G produces edge pixels whose gradient
     * magnitude is EXACTLY G at every transition and 0 everywhere else, so the p50-over-edge-
     * pixels sharpness score this gate computes is exactly G - no approximation needed to
     * land a synthetic frame precisely on either side of the threshold.
     */
    @Test
    fun `sharpness decision flips exactly at the documented threshold`() {
        // G = 80 (the threshold itself): sharpness < minSharpness is false, so it passes.
        val atThreshold = stripes(64, 64, 4, 0, 80)
        val atResult = gate.evaluateLuma(atThreshold, 64, 64)
        assertNull(atResult.reason, "a frame scoring exactly at minSharpness must pass: ${atResult.detail}")

        // G = 79: one gradient level below the threshold must flip the decision to TooBlurry.
        val justBelow = stripes(64, 64, 4, 0, 79)
        val belowResult = gate.evaluateLuma(justBelow, 64, 64)
        assertEquals(
            FailureReason.TooBlurry,
            belowResult.reason,
            "a frame scoring one gradient level below minSharpness must fail: ${belowResult.detail}",
        )
    }

    /**
     * Coverage sweep: the p99-over-all-pixels metric this gate replaces still depended on
     * how much of the frame was text (it cleared ~1.5% coverage but fell below ~1%). The
     * edge-only metric must be density-independent: sharp text passes and blurred text is
     * rejected at every coverage level, from a title-and-a-few-short-questions page (0.2%)
     * up to a fairly dense block (5%).
     */
    @Test
    fun `sharp text passes and blurred text is rejected across a coverage sweep`() {
        val width = 1600
        val height = 1200
        val blockWidth = 160

        for (coveragePercent in listOf(0.2, 0.5, 1.0, 5.0)) {
            val blockHeight = ((coveragePercent / 100.0) * width * height / blockWidth).toInt()
            val sharp = sparseTextOnFlat(
                width = width,
                height = height,
                background = 250,
                blockWidth = blockWidth,
                blockHeight = blockHeight,
                period = 4,
                low = 20,
                high = 235,
            )
            val sharpResult = gate.evaluateLuma(sharp, width, height)
            assertNull(
                sharpResult.reason,
                "sharp text at ~$coveragePercent% coverage should pass: ${sharpResult.detail}",
            )

            val blurred = blurHorizontally(sharp, width, height, rampWidth = 6)
            val blurredResult = gate.evaluateLuma(blurred, width, height)
            assertEquals(
                FailureReason.TooBlurry,
                blurredResult.reason,
                "blurred text at ~$coveragePercent% coverage should be rejected: ${blurredResult.detail}",
            )
        }
    }
}
