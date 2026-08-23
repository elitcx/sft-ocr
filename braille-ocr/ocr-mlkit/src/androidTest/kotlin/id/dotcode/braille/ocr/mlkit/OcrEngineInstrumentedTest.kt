package id.dotcode.braille.ocr.mlkit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.dotcode.braille.ocr.model.OcrResult
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end proof that a real [OcrEngine], running ML Kit on-device, turns a worksheet
 * image into a structured [id.dotcode.braille.ocr.model.OcrDocument] — not just that it
 * rejects a bad capture. No sample worksheet photo exists yet (see Task 14's brief), so
 * this synthesizes one in-process: a title line plus three numbered Indonesian questions,
 * rendered as black text on a white canvas, laid out with generous spacing so ML Kit has
 * an unambiguous image to read.
 */
@RunWith(AndroidJUnit4::class)
class OcrEngineInstrumentedTest {

    private val worksheetLines = listOf(
        "LEMBAR KERJA IPA",
        "1. Sebutkan tiga contoh sumber daya alam",
        "2. Jelaskan proses fotosintesis",
        "3. Apa fungsi akar pada tumbuhan",
    )

    private fun buildWorksheetBitmap(): Bitmap {
        // Sized to closely wrap the text rather than leave large blank margins: the
        // capture-quality gate's sharpness check averages the gradient over the WHOLE
        // frame, so a mostly-empty canvas dilutes a handful of crisp text lines below
        // its threshold even though the text itself is perfectly readable (verified by
        // reproducing CaptureQualityGate's exact gradient formula against candidate
        // layouts offline before picking this one — width is neutral to the average,
        // only how tightly the vertical margins wrap the text matters). This layout
        // scores well clear of the gate's minSharpness=4.0 threshold.
        val width = 1000
        val height = 310
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 44f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            isSubpixelText = true
        }

        val leftMargin = 30f
        val lineSpacing = 64f
        var y = 60f
        for (line in worksheetLines) {
            canvas.drawText(line, leftMargin, y, paint)
            y += lineSpacing
        }
        return bitmap
    }

    @Test
    fun recognizesSyntheticWorksheetAndProducesStructuredDocument() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = buildWorksheetBitmap()

        val engine = OcrEngine(context)
        try {
            val result = engine.recognize(bitmap)

            val document = when (result) {
                is OcrResult.Success -> result.document
                is OcrResult.Failure -> fail(
                    "expected OcrResult.Success but recognition failed: " +
                        "reason=${result.reason} detail=${result.detail}",
                )
            }

            Log.i(
                "OcrEngineInstrumentedTest",
                "recognized ${document.blocks.size} blocks, timings=${document.timings}: " +
                    document.blocks.joinToString(" | ") { "[id=${it.id} role=${it.role} marker=${it.marker} text=\"${it.text}\"]" },
            )

            // Real structure, not merely non-empty output.
            assertTrue(
                document.blocks.size >= 3,
                "expected at least 3 blocks, got ${document.blocks.size}: " +
                    document.blocks.joinToString { "[marker=${it.marker} text=${it.text}]" },
            )

            val numberedBlocks = document.blocks.filter { it.marker != null && it.marker!!.first().isDigit() }
            assertTrue(
                numberedBlocks.size >= 3,
                "expected at least 3 blocks carrying a numeric marker, got " +
                    "${numberedBlocks.size}: " +
                    document.blocks.joinToString { "[marker=${it.marker} text=${it.text}]" },
            )

            val markers = numberedBlocks.take(3).map { it.marker }
            assertEquals(
                listOf("1.", "2.", "3."),
                markers,
                "expected markers in ascending order 1., 2., 3. but got $markers — full blocks: " +
                    document.blocks.joinToString { "[marker=${it.marker} text=${it.text}]" },
            )

            // The marker must actually be stripped from the text, not merely detected.
            for (block in numberedBlocks.take(3)) {
                val marker = block.marker!!
                assertTrue(
                    !block.text.trimStart().startsWith(marker),
                    "block text still starts with its own marker '$marker': '${block.text}'",
                )
            }

            assertTrue(
                document.timings.recognizeMs > 0,
                "expected timings.recognizeMs > 0, got ${document.timings.recognizeMs}",
            )
            assertTrue(
                document.timings.totalMs > 0,
                "expected timings.totalMs > 0, got ${document.timings.totalMs}",
            )
        } finally {
            engine.close()
        }
    }
}
