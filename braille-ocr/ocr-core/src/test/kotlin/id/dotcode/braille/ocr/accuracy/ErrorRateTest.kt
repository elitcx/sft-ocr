package id.dotcode.braille.ocr.accuracy

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ErrorRateTest {
    @Test
    fun `identical text has zero error`() {
        val report = ErrorRate.compare("sumber daya alam", "sumber daya alam")
        assertEquals(0.0, report.cer)
        assertEquals(0.0, report.wer)
    }

    @Test
    fun `one substituted character in sixteen`() {
        val report = ErrorRate.compare("sumber daya alam", "sumber daya alan")
        assertTrue(abs(report.cer - 1.0 / 16.0) < 1e-9)
    }

    @Test
    fun `one wrong word in three`() {
        val report = ErrorRate.compare("sumber daya alam", "sumber daya alan")
        assertTrue(abs(report.wer - 1.0 / 3.0) < 1e-9)
    }

    @Test
    fun `whitespace is normalized before comparison`() {
        val report = ErrorRate.compare("sumber  daya\nalam", "sumber daya alam")
        assertEquals(0.0, report.cer)
    }

    @Test
    fun `empty expected text with output is fully wrong`() {
        assertEquals(1.0, ErrorRate.compare("", "sesuatu").cer)
    }

    @Test
    fun `insertions and deletions both count`() {
        assertEquals(2, ErrorRate.levenshtein(listOf("a", "b", "c"), listOf("a", "x", "b", "c", "d")))
    }
}
