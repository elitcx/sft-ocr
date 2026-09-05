package id.dotcode.braille.ocr.mlkit

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.dotcode.braille.ocr.model.FailureReason
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * This is the test that would have caught all three [CaptureQualityGate] regressions before
 * they shipped: it runs the gate against real worksheet photos captured on a real phone,
 * rather than only the synthetic fixtures in [CaptureQualityGateTest]. Every prior version
 * of this metric (whole-frame percentile, edge-only median) passed its synthetic fixtures
 * and still rejected every one of these as "too blurry" - see the class KDoc on
 * [CaptureQualityGate] for the measured root cause.
 *
 * Assets under `assets/quality/` are nine genuinely sharp, entirely legible A4 worksheet
 * photos supplied by the project owner (crisp enough to read by eye), decoded and downscaled
 * exactly the way the production pipeline does in [ImagePreprocessor] before this gate ever
 * sees them.
 */
@RunWith(AndroidJUnit4::class)
class CaptureQualityGateRealPhotosTest {

    private val realPhotoAssets = listOf(
        "IMG-20260819-WA0002.jpg",
        "IMG-20260819-WA0006.jpg",
        "IMG-20260820-WA0000.jpg",
        "IMG-20260820-WA0001.jpg",
        "IMG-20260820-WA0002.jpg",
        "IMG-20260821-WA0033.jpg",
        "IMG-20260821-WA0034.jpg",
        "IMG-20260821-WA0035.jpg",
        "IMG-20260821-WA0036.jpg",
    )

    private val gate = CaptureQualityGate()

    private fun loadPreprocessed(assetName: String): Bitmap {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = context.assets.open("quality/$assetName").use { it.readBytes() }
        val decoded = ImagePreprocessor.decodeSampled(bytes)
            ?: fail("failed to decode asset $assetName")
        return ImagePreprocessor.downscale(decoded)
    }

    /**
     * The regression test: every one of these nine real, sharp worksheet photos must pass
     * the gate. Each of this metric's three attempts (whole-frame percentile, edge-only
     * median, and now edge-only p95) claimed to fix the same underlying problem; only running
     * against real captures - not synthetic fixtures - actually proves it, because the
     * synthetic fixtures' clean binary edges do not reproduce the JPEG/texture noise that
     * broke the previous two attempts.
     */
    @Test
    fun allRealSharpWorksheetPhotosPass() {
        val failures = mutableListOf<String>()
        for (assetName in realPhotoAssets) {
            val bitmap = loadPreprocessed(assetName)
            val result = gate.evaluate(bitmap)
            if (result.reason != null) {
                failures += "$assetName: reason=${result.reason} detail=${result.detail}"
            }
        }
        if (failures.isNotEmpty()) {
            fail("expected all real sharp worksheet photos to pass; failures:\n" + failures.joinToString("\n"))
        }
    }

    /**
     * A heavily blurred real photo must be reported as [FailureReason.TooBlurry], not
     * [FailureReason.NoTextFound]. This pins the ordering/threshold fix on
     * [CaptureQualityGate.contentGradientFloor]: heavy blur collapses the *sharpness* edge
     * population (floor 10) to near zero, but real printed content - even mashed by blur -
     * still has far more pixel-to-pixel variation than a genuinely blank frame, so a lower
     * existence floor must still see it. Telling a student "no text found" when the real
     * problem is camera shake sends them to the wrong remedy.
     */
    @Test
    fun heavilyBlurredRealPhotoIsReportedAsTooBlurryNotNoTextFound() {
        val bitmap = loadPreprocessed(realPhotoAssets.first())
        val blurred = boxBlur(bitmap, radius = 10)
        val result = gate.evaluate(blurred)
        assertEquals(
            FailureReason.TooBlurry,
            result.reason,
            "a heavily blurred real photo must be TooBlurry, not misreported: ${result.detail}",
        )
    }

    /**
     * A simple separable box blur, applied horizontally then vertically, implemented directly
     * against [Bitmap] pixels so this test needs no image-processing library beyond what the
     * production code already depends on. [radius] pixels on each side average to 2*radius+1
     * total - large enough (radius 10) to simulate genuine heavy motion blur/defocus, which is
     * exactly the case the ordering fix targets.
     */
    private fun boxBlur(bitmap: Bitmap, radius: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        fun luma(p: Int): Int = (p shr 16 and 0xFF) * 299 / 1000 + (p shr 8 and 0xFF) * 587 / 1000 + (p and 0xFF) * 114 / 1000

        val gray = IntArray(pixels.size) { luma(pixels[it]) }
        val horizontallyBlurred = IntArray(pixels.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sum = 0
                var count = 0
                for (k in -radius..radius) {
                    val xi = x + k
                    if (xi in 0 until width) {
                        sum += gray[row + xi]
                        count++
                    }
                }
                horizontallyBlurred[row + x] = sum / count
            }
        }
        val fullyBlurred = IntArray(pixels.size)
        for (x in 0 until width) {
            for (y in 0 until height) {
                var sum = 0
                var count = 0
                for (k in -radius..radius) {
                    val yi = y + k
                    if (yi in 0 until height) {
                        sum += horizontallyBlurred[yi * width + x]
                        count++
                    }
                }
                fullyBlurred[y * width + x] = sum / count
            }
        }

        val outPixels = IntArray(pixels.size) { i ->
            val g = min(255, fullyBlurred[i])
            (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        return Bitmap.createBitmap(outPixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
