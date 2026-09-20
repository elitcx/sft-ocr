package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.raw.RawLine
import id.dotcode.braille.ocr.raw.RawWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameEdgeWordFilterTest {

    private fun word(text: String, left: Float, right: Float) =
        RawWord(text = text, box = BoxF(left, 100f, right, 130f), confidence = 0.9f)

    private fun line(vararg words: RawWord): RawLine {
        val box = BoxF(words.minOf { it.box.left }, 100f, words.maxOf { it.box.right }, 130f)
        return RawLine(text = words.joinToString(" ") { it.text }, box = box,
            confidence = 0.9f, words = words.toList())
    }

    /** Frame is 1000 wide; margin = 15px, a fragment must be under 350px to qualify. */
    private val config = StructuringConfig()

    @Test
    fun `a stack of cut-off fragments at the right edge is rejected`() {
        val body = (1..4).map { line(word("kalimat", 100f, 700f)) }
        val fragments = (1..3).map { line(word("menyet", 960f, 998f)) }
        val edge = FrameEdgeFragmentFilter.edgeWords(body + fragments, 1000, config)
        assertEquals(3, edge.size, "the three cut-off fragments must be rejected")
        assertTrue(edge.all { it.text == "menyet" }, "no body word may be rejected")
    }

    @Test
    fun `a full-width line touching the edge is kept`() {
        val lines = (1..4).map { line(word("judul-yang-panjang-sekali", 10f, 990f)) }
        assertTrue(
            FrameEdgeFragmentFilter.edgeWords(lines, 1000, config).isEmpty(),
            "a tightly cropped page has full-width lines at the edge; they are real text",
        )
    }

    @Test
    fun `a lone confident short line at the edge is kept`() {
        val lines = listOf(
            line(word("kalimat", 100f, 700f)),
            line(word("penuh", 100f, 700f)),
            line(RawWord("akhir", BoxF(960f, 100f, 998f, 130f), confidence = 0.95f)),
        )
        assertTrue(
            FrameEdgeFragmentFilter.edgeWords(lines, 1000, config).isEmpty(),
            "one confident short line can legitimately end at the margin",
        )
    }
}
