package id.dotcode.braille.ocr.model

import id.dotcode.braille.ocr.geometry.BoxF
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class OcrDocumentTest {
    private fun sample() = OcrDocument(
        pageWidth = 1600,
        pageHeight = 2000,
        skewDeg = -1.5f,
        columnCount = 2,
        blocks = listOf(
            TextBlock(
                id = 0,
                role = BlockRole.QUESTION,
                columnIndex = 0,
                marker = "1.",
                indentLevel = 0,
                alignment = Alignment.LEFT,
                relativeTextHeight = 1.0f,
                text = "Sebutkan tiga contoh sumber daya alam.",
                lines = listOf(
                    TextLine("1. Sebutkan tiga contoh", BoxF(0f, 0f, 400f, 30f), null, null),
                    TextLine("sumber daya alam.", BoxF(0f, 34f, 300f, 64f), null, null),
                ),
                box = BoxF(0f, 0f, 400f, 64f),
                confidence = 0.94f,
            ),
        ),
        meanConfidence = 0.94f,
        timings = Timings(decodeMs = 12, preprocessMs = 8, recognizeMs = 210, structureMs = 3),
    )

    @Test
    fun `timings total sums every stage`() {
        assertEquals(233L, sample().timings.totalMs)
    }

    @Test
    fun `document survives a json round trip`() {
        val original = sample()
        assertEquals(original, OcrDocument.fromJson(original.toJson()))
    }

    @Test
    fun `json is human readable with named roles`() {
        val json = sample().toJson()
        assertEquals(true, json.contains("\"role\": \"QUESTION\""))
    }
}
