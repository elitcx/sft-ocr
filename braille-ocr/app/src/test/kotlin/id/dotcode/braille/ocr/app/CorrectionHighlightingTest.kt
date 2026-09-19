package id.dotcode.braille.ocr.app

import id.dotcode.braille.ocr.model.SpellingCorrection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CorrectionHighlightingTest {

    private fun spans(text: String, vararg pairs: Pair<String, String>) =
        CorrectionHighlighting.spansIn(text, pairs.map { SpellingCorrection(it.first, it.second) })

    @Test
    fun `no corrections costs nothing`() {
        assertTrue(CorrectionHighlighting.spansIn("Rumah itu besar.", emptyList()).isEmpty())
    }

    @Test
    fun `empty text yields no spans`() {
        assertTrue(spans("", "rumh" to "rumah").isEmpty())
    }

    @Test
    fun `single correction is located`() {
        val found = spans("Rumah itu besar.", "rumh" to "Rumah")
        assertEquals(1, found.size)
        assertEquals(0, found[0].start)
        assertEquals(5, found[0].end)
        assertEquals("rumh", found[0].original)
    }

    @Test
    fun `match is anchored to word boundaries`() {
        // "ada" occurs inside "kepada" first; the standalone word is the real match.
        val found = spans("Dia kepada kami ada di sana.", "adaa" to "ada")
        assertEquals(1, found.size)
        assertEquals("ada", "Dia kepada kami ada di sana.".substring(found[0].start, found[0].end))
        assertEquals(16, found[0].start)
    }

    @Test
    fun `a word corrected twice maps to successive occurrences`() {
        val text = "buku ini dan buku itu"
        val found = spans(text, "bku" to "buku", "buko" to "buku")
        assertEquals(2, found.size)
        assertEquals(0, found[0].start)
        assertEquals(13, found[1].start)
        assertEquals("bku", found[0].original)
        assertEquals("buko", found[1].original)
    }

    @Test
    fun `unfindable correction is skipped rather than guessed`() {
        val found = spans("Rumah itu besar.", "xyz" to "tidakada")
        assertTrue(found.isEmpty())
    }

    @Test
    fun `a later unfindable correction does not drop an earlier one`() {
        val found = spans("Rumah itu besar.", "rumh" to "Rumah", "xyz" to "tidakada")
        assertEquals(1, found.size)
        assertEquals("rumh", found[0].original)
    }

    @Test
    fun `hyphenated words are treated as one word`() {
        // "anak" must not match inside "anak-anak".
        val found = spans("Para anak-anak bermain dan anak itu tertawa.", "ank" to "anak")
        assertEquals(1, found.size)
        assertEquals(27, found[0].start)
    }

    @Test
    fun `spans never overlap and stay in order`() {
        val text = "satu dua tiga empat"
        val found = spans(text, "stu" to "satu", "dva" to "dua", "tga" to "tiga")
        assertEquals(3, found.size)
        found.zipWithNext { a, b -> assertTrue(a.end <= b.start, "spans overlap: $a then $b") }
    }

    @Test
    fun `spans index into block text so substring round-trips`() {
        val text = "Ibu pergi ke pasar"
        val found = spans(text, "psar" to "pasar")
        assertEquals("pasar", text.substring(found[0].start, found[0].end))
    }
}
