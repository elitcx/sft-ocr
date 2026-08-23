package id.dotcode.braille.ocr.mlkit

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the pure arithmetic behind the subsampled decode. The decode itself needs a
 * device; the factor that decides whether a 12MP capture allocates 48MB or 6MB does not.
 */
class ImagePreprocessorTest {

    @Test
    fun `an image already at or below the target is not subsampled`() {
        assertEquals(1, ImagePreprocessor.sampleSizeFor(1600, 1200))
        assertEquals(1, ImagePreprocessor.sampleSizeFor(800, 600))
        assertEquals(1, ImagePreprocessor.sampleSizeFor(1600, 2000))
    }

    @Test
    fun `a twelve megapixel capture is subsampled but never below the target`() {
        // 4000x3000, the usual 12MP sensor output.
        val sample = ImagePreprocessor.sampleSizeFor(4000, 3000)
        assertEquals(2, sample)
        assertTrue(4000 / sample >= ImagePreprocessor.TARGET_LONG_EDGE)
        assertTrue(4000 / (sample * 2) < ImagePreprocessor.TARGET_LONG_EDGE)
    }

    @Test
    fun `the factor grows by powers of two and always lands at or above the target`() {
        for (longEdge in listOf(1601, 3200, 3201, 6400, 12800, 12801)) {
            val sample = ImagePreprocessor.sampleSizeFor(longEdge, longEdge / 2)
            assertTrue(
                sample and (sample - 1) == 0,
                "sample size must be a power of two, got $sample for $longEdge",
            )
            assertTrue(
                longEdge / sample >= ImagePreprocessor.TARGET_LONG_EDGE,
                "subsampling $longEdge by $sample fell below the target",
            )
        }
    }

    @Test
    fun `undecodable bounds fall back to no subsampling`() {
        // BitmapFactory reports -1 x -1 when the header cannot be parsed.
        assertEquals(1, ImagePreprocessor.sampleSizeFor(-1, -1))
        assertEquals(1, ImagePreprocessor.sampleSizeFor(0, 0))
    }
}
