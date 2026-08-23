package id.dotcode.braille.ocr.mlkit

import id.dotcode.braille.ocr.model.FailureReason
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class CaptureQualityGateTest {
    private val gate = CaptureQualityGate()

    /** A synthetic grayscale plane: sharp alternating stripes have high gradient energy. */
    private fun stripes(width: Int, height: Int, period: Int, low: Int, high: Int): IntArray =
        IntArray(width * height) { i -> if ((i % width) / period % 2 == 0) low else high }

    private fun flat(width: Int, height: Int, value: Int): IntArray = IntArray(width * height) { value }

    @Test
    fun `sharp well lit content passes`() {
        assertNull(gate.evaluateLuma(stripes(64, 64, 4, 20, 235), 64, 64))
    }

    @Test
    fun `a flat mid gray frame is too blurry`() {
        assertEquals(FailureReason.TooBlurry, gate.evaluateLuma(flat(64, 64, 128), 64, 64))
    }

    @Test
    fun `a near black frame is too dark`() {
        assertEquals(FailureReason.TooDark, gate.evaluateLuma(stripes(64, 64, 4, 2, 10), 64, 64))
    }

    @Test
    fun `darkness is reported before blurriness`() {
        assertEquals(FailureReason.TooDark, gate.evaluateLuma(flat(64, 64, 3), 64, 64))
    }
}
