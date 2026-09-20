package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.raw.RawLine
import id.dotcode.braille.ocr.raw.RawTextResult
import id.dotcode.braille.ocr.raw.RawWord
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [WordRowRebuilder] must never weld the two sides of a column gutter into one row.
 *
 * Nothing else proves this. The committed two-column fixtures carry no word boxes at all,
 * so they leave the rebuilder's chaining code entirely unexercised, and the only thing
 * standing between a two-column page and a scrambled one is
 * [StructuringConfig.wordRowMaxGapCharWidths].
 *
 * The page below is built so the rebuilder actually RUNS: one left-column line is
 * cross-chained (it jumps from x=40 to x=320 over two words that belong to another line at
 * the same height), which is the signature [WordRowRebuilder] gates on. Without that the
 * gate declines the page, `rebuild` returns its input untouched, and a gutter assertion
 * would pass without testing anything.
 */
class WordRowRebuilderGutterTest {

    private fun word(text: String, left: Float, top: Float) =
        RawWord(text, BoxF(left, top, left + text.length * 10f, top + 30f), confidence = 0.9f)

    private fun line(vararg w: RawWord) = RawLine(
        text = w.joinToString(" ") { it.text },
        box = BoxF(w.minOf { it.box.left }, w.minOf { it.box.top },
                   w.maxOf { it.box.right }, w.maxOf { it.box.bottom }),
        confidence = 0.9f, words = w.toList(),
    )

    /**
     * Left column occupies x=40..360, right column x=700..1020, so the gutter is 340px
     * against a 10px character width - far wider than the 6 character widths
     * [StructuringConfig.wordRowMaxGapCharWidths] allows.
     */
    private fun spread(): RawTextResult {
        val lines = mutableListOf<RawLine>()
        for (r in 0..5) {
            val y = 100f + r * 45f
            if (r == 0) {
                // The cross-chained pair that makes the gate fire, exactly as a curved page
                // produces it: this line skips over "teks" and "lagi", which sit physically
                // between its own two words on the same printed row.
                lines += line(word("kiri", 40f, y), word("pula", 320f, y))
                lines += line(word("teks", 120f, y), word("lagi", 220f, y))
            } else {
                lines += line(word("kiri", 40f, y), word("teks", 120f, y),
                              word("lagi", 220f, y), word("pula", 320f, y))
            }
            lines += line(word("kanan", 700f, y), word("lain", 800f, y),
                          word("dua", 900f, y), word("tiga", 980f, y))
        }
        return RawTextResult(1200, 400, lines)
    }

    @Test
    fun `two columns are never welded into one row`() {
        val out = WordRowRebuilder.rebuild(spread(), StructuringConfig(), rotationDegrees = 0)
        assertTrue(
            out.lines.none { it.text.contains("teks") && it.text.contains("kanan") },
            "a row spanned the gutter: ${out.lines.map { it.text }}",
        )
    }

    @Test
    fun `the rebuild actually ran on this page`() {
        // Guards the test above from going vacuously green: if the gate ever stops firing
        // here, `rebuild` returns its input and the gutter assertion proves nothing. The
        // repaired row is the evidence that chaining happened.
        val out = WordRowRebuilder.rebuild(spread(), StructuringConfig(), rotationDegrees = 0)
        assertTrue(
            out.lines.any { it.text == "kiri teks lagi pula" },
            "the cross-chained row was not rebuilt: ${out.lines.map { it.text }}",
        )
    }
}
