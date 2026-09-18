package id.dotcode.braille.ocr.app

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.model.BlockRole
import id.dotcode.braille.ocr.model.OcrDocument
import id.dotcode.braille.ocr.model.TextBlock
import java.io.File
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Calls the real Gemini API, so it only runs when a key is available: the GEMINI_API_KEY
 * environment variable, or a `.gemini-key` file in the project root (git-ignored).
 * Skipped otherwise. Model override: GEMINI_MODEL.
 */
class GeminiLiveTest {

    private val apiKey: String? = System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
        ?: File("../.gemini-key").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    private val model = System.getenv("GEMINI_MODEL") ?: GeminiProtocol.DEFAULT_MODEL

    private fun block(id: Int, text: String) = TextBlock(
        id = id, role = BlockRole.PARAGRAPH, columnIndex = 0,
        text = text, lines = emptyList(), box = BoxF(0f, 0f, 1f, 1f),
    )

    @Test
    fun `fixes recognition errors and keeps everything else`() {
        assumeTrue(apiKey != null, "no Gemini API key configured")
        val document = OcrDocument(
            pageWidth = 100, pageHeight = 100, skewDeg = 0f, columnCount = 1,
            blocks = listOf(
                block(0, "Jawablah pertanyaan berikul dengan benar!"),
                block(1, "Mobil bergerak dengan kecepatan tetap 72 km/jam selama 20 s."),
                block(2, "Tuliskan jawabanrnu pada lembar kerja kel0mpok."),
                block(3, "Fotosintesis terjadi di klorofil daun."),
                block(4, "Temukan kata yang salah ejaan: Ibu pergi ke pasar membli sayur."),
            ),
        )

        val outcome = runBlocking { GeminiCorrector().correct(document, apiKey!!, model) }
        val texts = outcome.document.blocks.map { it.text }
        println("model=$model elapsed=${outcome.elapsedMs}ms changed=${outcome.changedBlocks}")
        texts.forEachIndexed { i, t -> println("  [$i] ${document.blocks[i].text}\n   -> $t") }

        assertEquals("Jawablah pertanyaan berikut dengan benar!", texts[0])
        assertEquals(document.blocks[1].text, texts[1])
        assertEquals("Tuliskan jawabanmu pada lembar kerja kelompok.", texts[2])
        assertEquals(document.blocks[3].text, texts[3])
        assertEquals(document.blocks[4].text, texts[4], "the exercise's deliberate typo must stay")
    }
}
