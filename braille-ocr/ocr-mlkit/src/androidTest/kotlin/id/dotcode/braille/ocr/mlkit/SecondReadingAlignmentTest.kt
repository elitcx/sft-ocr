package id.dotcode.braille.ocr.mlkit

import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.dotcode.braille.ocr.model.OcrResult
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tesseract's words must land on the ML Kit words at the same spot, whatever quarter-turn
 * the camera stored the photo in. Each seed page is fed sideways/upside down together with
 * the rotation that makes it upright, and the share of paired words both recognizers read
 * identically is measured: a frame mix-up pairs words from other lines and collapses it.
 */
@RunWith(AndroidJUnit4::class)
class SecondReadingAlignmentTest {

    private val tag = "SecondReadingAlignment"

    @Test
    fun secondReadingsLandOnTheSameWordsInEveryOrientation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val images = context.assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".jpg") }.sorted()
        assertTrue(images.isNotEmpty(), "no seed images")

        val engine = OcrEngine(targetContext)
        engine.correctionSettings = OcrEngine.CorrectionSettings(enabled = true, secondReadGraceMs = 300_000L)
        val failures = mutableListOf<String>()
        try {
            for (name in images) {
                val upright = context.assets.open("$ASSET_DIR/$name").use { BitmapFactory.decodeStream(it) }
                for (rotation in listOf(0, 90, 180, 270)) {
                    // Store the page turned back by `rotation`, as a camera would, so that
                    // turning it by `rotation` makes it upright again.
                    val stored = turn(upright, -rotation)
                    val result = engine.recognize(stored, rotation)
                    val words = (result as? OcrResult.Success)?.document?.blocks
                        ?.flatMap { b -> b.lines.flatMap { it.words } }
                        .orEmpty()
                    val paired = words.filter { it.secondReading != null }
                    val agreeing = paired.count { normalize(it.text) == normalize(it.secondReading!!) }
                    val share = if (paired.isEmpty()) 0.0 else agreeing.toDouble() / paired.size
                    Log.i(tag, "$name rot=$rotation words=${words.size} paired=${paired.size} agree=$agreeing (${"%.2f".format(share)})")
                    paired.filter { normalize(it.text) != normalize(it.secondReading!!) }.take(6).forEach {
                        Log.i(tag, "   '${it.text}' vs '${it.secondReading}'")
                    }
                    if (paired.size < words.size / 3 || share < MIN_AGREEMENT) {
                        failures += "$name rot=$rotation paired=${paired.size}/${words.size} agree=${"%.2f".format(share)}"
                    }
                }
            }
        } finally {
            engine.close()
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun turn(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun normalize(text: String) = text.filter { it.isLetterOrDigit() }.lowercase()

    private companion object {
        const val ASSET_DIR = "ground-truth"
        const val MIN_AGREEMENT = 0.6
    }
}
