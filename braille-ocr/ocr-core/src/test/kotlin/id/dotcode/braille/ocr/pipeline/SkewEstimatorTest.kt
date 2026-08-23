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
        // enlarges the enclosing hull: for w=200,h=40 the true post-rotation height is
        // w*sin(10deg) + h*cos(10deg) ~= 74.1, not the ~30-60 the brief's comment assumed.
        // The brief's "rotation is area-preserving for the enclosing hull" claim does not
        // hold for an axis-aligned box rotated in place; widened the bound to match reality
        // while still asserting the height stays sane (not blown up to page scale).
        result.result.lines.forEach { assertTrue(it.box.height in 30f..80f) }
    }
}
