package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.raw.RawLine
import id.dotcode.braille.ocr.raw.RawTextResult
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class FrameEdgeFragmentFilterTest {

    private val config = StructuringConfig()

    private fun line(text: String, left: Float, right: Float, y: Float, confidence: Float = 0.85f) =
        RawLine(text, BoxF(left, y, right, y + 30f), confidence = confidence)

    private val body = List(6) { i -> line("real body line $i", 300f, 900f, 100f + i * 40f) }

    @Test
    fun `a stack of cut-off lines on one side is dropped, however clearly it was read`() {
        val facingPage = listOf("Sejarah Seko", "Riwayat Sant", "Visi & Misi").mapIndexed { i, t ->
            line(t, 1080f, 1199f, 100f + i * 40f, confidence = 0.84f)
        }
        val kept = FrameEdgeFragmentFilter.filter(body + facingPage, 1200, config)
        assertEquals(body, kept)
    }

    @Test
    fun `a lone short line at the edge is kept when it was read confidently`() {
        val lone = line("dan nilai-nilai", 1000f, 1195f, 400f, confidence = 0.77f)
        assertEquals(body + lone, FrameEdgeFragmentFilter.filter(body + lone, 1200, config))
    }

    @Test
    fun `a lone short line at the edge is dropped when the recognizer was unsure`() {
        val lone = line("oman", 2f, 70f, 400f, confidence = 0.46f)
        assertEquals(body, FrameEdgeFragmentFilter.filter(body + lone, 1200, config))
    }

    @Test
    fun `full-width lines of a tightly cropped page stay`() {
        val tight = List(4) { i -> line("tightly cropped worksheet line $i", 0f, 1200f, 100f + i * 40f) }
        assertEquals(tight, FrameEdgeFragmentFilter.filter(tight, 1200, config))
    }

    @Test
    fun `a page of nothing but fragments is left as it is`() {
        val fragments = List(4) { i -> line("frag$i", 0f, 50f, 100f + i * 40f, confidence = 0.3f) }
        assertEquals(fragments, FrameEdgeFragmentFilter.filter(fragments, 1200, config))
    }

    @Test
    fun `the real facing-page photo loses its cut-off left margin`() {
        val json = checkNotNull(javaClass.getResourceAsStream("/fixtures/real-facing-page-231108-raw.json"))
            .bufferedReader().readText()
        val text = DocumentStructurer().structure(RawTextResult.fromJson(json))
            .blocks.joinToString(" ") { it.text }
        for (fragment in listOf("A00a", "sar ni", "gnitif dan", "usi Uni Roma", "ebahagiaan jm")) {
            assertTrue(fragment !in text, "facing-page fragment '$fragment' is still in the page")
        }
    }
}
