package id.dotcode.braille.ocr.pipeline

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class SkewEstimatorTest {
    private val estimator = SkewEstimator(StructuringConfig())

    @Test
    fun `a straight page reports no skew`() {
        val lines = listOf(
            line("satu", 0f, 0f, 100f, 20f),
            line("dua", 0f, 30f, 100f, 20f),
        )
        assertEquals(0f, estimator.estimateDeg(lines))
    }

    @Test
    fun `median ignores a single wild outlier`() {
        val lines = listOf(
            line("satu", 0f, 0f, 100f, 20f, angleDeg = 2f),
            line("dua", 0f, 30f, 100f, 20f, angleDeg = 2f),
            line("tiga", 0f, 60f, 100f, 20f, angleDeg = 2f),
            line("empat", 0f, 90f, 100f, 20f, angleDeg = 47f),
        )
        assertTrue(abs(estimator.estimateDeg(lines) - 2f) < 0.01f)
    }

    @Test
    fun `skew below the threshold is left uncorrected`() {
        val input = page(line("satu", 100f, 100f, 200f, 20f, angleDeg = 0.2f))
        val result = estimator.deskew(input)
        assertEquals(0f, result.skewDeg)
        assertEquals(input.lines.first().box, result.result.lines.first().box)
    }

    @Test
    fun `deskew rotates boxes back to upright`() {
        // A line rotated +10 degrees about the page center should come back with a
        // near-horizontal box whose center is close to where an upright line would sit.
        val input = page(
            line("miring", 700f, 900f, 200f, 40f, angleDeg = 10f),
            line("miring dua", 700f, 960f, 200f, 40f, angleDeg = 10f),
            line("miring tiga", 700f, 1020f, 200f, 40f, angleDeg = 10f),
        )
        val result = estimator.deskew(input)
        assertTrue(abs(result.skewDeg - 10f) < 0.01f)
        // The input box is axis-aligned (not pre-tilted), so rotating its corners by 10deg
        // enlarges the enclosing hull to the analytically exact values below; this is not
        // "area-preserving" (that claim was wrong), it is fixed by the hull formula for a
        // w x h rectangle rotated by theta:
        //   height' = w*sin(theta) + h*cos(theta) = 200*0.17365 + 40*0.98481 = 74.12
        //   width'  = w*cos(theta) + h*sin(theta) = 200*0.98481 + 40*0.17365 = 203.90
        result.result.lines.forEach {
            assertTrue(abs(it.box.height - 74.12f) < 0.5f)
            assertTrue(abs(it.box.width - 203.90f) < 0.5f)
        }
    }
}
