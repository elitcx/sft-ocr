package id.dotcode.braille.ocr.spelling

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.pipeline.DocumentStructurer
import id.dotcode.braille.ocr.raw.RawLine
import id.dotcode.braille.ocr.raw.RawTextResult
import id.dotcode.braille.ocr.raw.RawWord
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ReadingVoterTest {

    private fun word(text: String, left: Float, right: Float, top: Float = 100f) =
        RawWord(text, BoxF(left, top, right, top + 30f), confidence = 0.7f)

    private val page = RawTextResult(
        imageWidth = 1000,
        imageHeight = 1000,
        lines = listOf(
            RawLine(
                text = "Tuliskan jawabanrnu tentangkagumi",
                box = BoxF(100f, 100f, 700f, 130f),
                words = listOf(
                    word("Tuliskan", 100f, 220f),
                    word("jawabanrnu", 230f, 400f),
                    word("tentangkagumi", 410f, 700f),
                ),
            ),
        ),
    )

    @Test
    fun `second words are attached to the primary word they sit in`() {
        val annotated = ReadingVoter.annotate(
            page,
            listOf(
                SecondWord("Tuliskan", BoxF(102f, 101f, 218f, 129f), 91f),
                SecondWord("jawabanmu", BoxF(232f, 100f, 398f, 131f), 88f),
                SecondWord("kagumi", BoxF(580f, 100f, 700f, 130f), 80f),
                SecondWord("tentang", BoxF(410f, 100f, 570f, 130f), 85f),
                // A different line: must not attach.
                SecondWord("lain", BoxF(230f, 300f, 400f, 330f), 90f),
            ),
        )

        val words = annotated.lines.single().words
        assertEquals(listOf("Tuliskan", "jawabanmu", "tentang kagumi"), words.map { it.secondReading })
        assertEquals(80f, words[2].secondConfidence)
    }

    @Test
    fun `words with nothing at their position keep no second reading`() {
        val annotated = ReadingVoter.annotate(page, listOf(SecondWord("x", BoxF(0f, 0f, 10f, 10f))))
        assertTrue(annotated.lines.single().words.all { it.secondReading == null })
    }

    @Test
    fun `a sliver of overlap is not a match`() {
        assertFalse(ReadingVoter.belongsTo(BoxF(390f, 100f, 500f, 130f), BoxF(230f, 100f, 400f, 130f)))
        assertTrue(ReadingVoter.belongsTo(BoxF(240f, 105f, 390f, 125f), BoxF(230f, 100f, 400f, 130f)))
    }

    @Test
    fun `the placement where the recognizers agree is chosen`() {
        val aligned = listOf(
            SecondWord("Tuliskan", BoxF(102f, 101f, 218f, 129f)),
            SecondWord("jawabanmu", BoxF(232f, 100f, 398f, 131f)),
        )
        // The same words in the wrong frame: they land on the wrong primary words.
        val shifted = aligned.map { it.copy(box = it.box.copy(left = it.box.left + 180f, right = it.box.right + 180f)) }

        val words = ReadingVoter.annotateBestFrame(page, listOf(shifted, aligned)).lines.single().words

        assertEquals(listOf("Tuliskan", "jawabanmu", null), words.map { it.secondReading })
    }

    @Test
    fun `second readings that mostly disagree are discarded`() {
        val shifted = listOf(
            SecondWord("Tuliskan", BoxF(282f, 101f, 398f, 129f)),
            SecondWord("jawabanmu", BoxF(412f, 100f, 578f, 131f)),
        )

        val annotated = ReadingVoter.annotateBestFrame(page, listOf(shifted))

        assertEquals(page, annotated)
    }

    @Test
    fun `words in a quarter-turned frame are matched along the vertical line`() {
        // "tentangkagumi" written top-to-bottom, split in two by the second recognizer.
        val turned = RawTextResult(
            imageWidth = 1000,
            imageHeight = 1000,
            lines = listOf(
                RawLine(
                    text = "tentangkagumi",
                    box = BoxF(100f, 100f, 130f, 400f),
                    words = listOf(RawWord("tentangkagumi", BoxF(100f, 100f, 130f, 400f), 0.7f)),
                ),
            ),
        )
        val second = listOf(
            SecondWord("kagumi", BoxF(100f, 270f, 130f, 400f)),
            SecondWord("tentang", BoxF(101f, 100f, 129f, 260f)),
        )

        val word = ReadingVoter.annotate(turned, second).lines.single().words.single()

        assertEquals("tentang kagumi", word.secondReading)
    }

    @Test
    fun `second readings survive structuring into the document`() {
        val annotated = ReadingVoter.annotate(
            page,
            listOf(SecondWord("jawabanmu", BoxF(232f, 100f, 398f, 131f), 88f)),
        )

        val words = DocumentStructurer().structure(annotated).blocks.single().lines.single().words

        assertEquals(listOf("Tuliskan", "jawabanrnu", "tentangkagumi"), words.map { it.text })
        assertEquals("jawabanmu", words[1].secondReading)
        assertNull(words[0].secondReading)
    }
}
