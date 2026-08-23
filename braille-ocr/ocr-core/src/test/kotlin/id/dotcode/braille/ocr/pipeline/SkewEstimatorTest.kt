package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.geometry.PointF
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
    fun `deskew recovers the true line height from corner points`() {
        // AMENDED. The previous version of this test pinned the WRONG behaviour: it
        // asserted that a deskewed 200x40 line comes back 74.12 tall and 203.90 wide.
        // The arithmetic was right (that is the hull of a rotated AABB) but the
        // behaviour was a bug — deskew must recover the line's true upright size, not
        // the hull of a shape it was never actually in. Inflation scales with line
        // width, so long body lines inflated more than short headings and destroyed the
        // relative-height signal RoleClassifier uses to find titles.
        val pivot = PointF(800f, 1000f)
        val input = page(
            skewedLine("miring", 700f, 900f, 200f, 40f, 10f, pivot),
            skewedLine("miring dua", 700f, 960f, 200f, 40f, 10f, pivot),
            skewedLine("miring tiga", 700f, 1020f, 200f, 40f, 10f, pivot),
        )
        // As reported by the recognizer, the axis-aligned box IS the inflated hull.
        input.lines.forEach {
            assertTrue(abs(it.box.height - 74.12f) < 0.5f, "input hull height ${it.box.height}")
        }

        val result = estimator.deskew(input)
        assertTrue(abs(result.skewDeg - 10f) < 0.01f)
        result.result.lines.forEach {
            assertTrue(abs(it.box.height - 40f) < 0.1f, "deskewed height ${it.box.height}")
            assertTrue(abs(it.box.width - 200f) < 0.1f, "deskewed width ${it.box.width}")
        }
    }

    @Test
    fun `without corner points deskew falls back to the rotated hull`() {
        // Documents the explicit fallback: given only an axis-aligned box there is no
        // tighter answer available, so the hull formula applies —
        //   height' = w*sin(t) + h*cos(t) = 200*0.17365 + 40*0.98481 = 74.12
        //   width'  = w*cos(t) + h*sin(t) = 200*0.98481 + 40*0.17365 = 203.90
        val input = page(
            line("miring", 700f, 900f, 200f, 40f, angleDeg = 10f),
            line("miring dua", 700f, 960f, 200f, 40f, angleDeg = 10f),
            line("miring tiga", 700f, 1020f, 200f, 40f, angleDeg = 10f),
        )
        val result = estimator.deskew(input)
        result.result.lines.forEach {
            assertTrue(abs(it.box.height - 74.12f) < 0.5f)
            assertTrue(abs(it.box.width - 203.90f) < 0.5f)
        }
    }
}
