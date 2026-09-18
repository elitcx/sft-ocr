package id.dotcode.braille.ocr.app

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ReadingTextTest {

    @Test
    fun `splits paragraphs and sentences, keeping word offsets inside each sentence`() {
        val text = ReadingText.of("Ibu pergi ke pasar. Dia membeli sayur!\n\nKamar itu luas.")

        assertEquals(
            listOf("Ibu pergi ke pasar.", "Dia membeli sayur!", "Kamar itu luas."),
            text.sentences.map { it.text },
        )
        assertEquals(10, text.words.size)
        text.words.forEach { word ->
            assertEquals(word.text, text.sentences[word.sentence].text.substring(word.start, word.end))
        }
        assertEquals(4, text.sentences[1].firstWord)
        assertEquals(6, text.sentences[1].lastWord)
    }

    @Test
    fun `indented lines and extra blank lines are ignored`() {
        val text = ReadingText.of("  1. Soal pertama\n\n\n   jawab   singkat  ")

        assertEquals(listOf("1. Soal pertama", "jawab   singkat"), text.sentences.map { it.text })
        assertEquals(listOf("1.", "Soal", "pertama", "jawab", "singkat"), text.words.map { it.text })
    }

    @Test
    fun `numbering and abbreviations do not end a sentence`() {
        val text = ReadingText.of("a. Buah apel, jeruk, dll. dibeli ibu. 2) Soal kedua. (iv) Terakhir.")

        assertEquals(
            listOf("a. Buah apel, jeruk, dll. dibeli ibu.", "2) Soal kedua.", "(iv) Terakhir."),
            text.sentences.map { it.text },
        )
    }

    @Test
    fun `spoken form drops edge punctuation but never becomes empty`() {
        val words = ReadingText.of("\"Halo,\" katanya... ?").words

        assertEquals(listOf("Halo", "katanya", "?"), words.map { it.spoken })
    }

    @Test
    fun `empty text has no words`() {
        val text = ReadingText.of(" \n\n ")
        assertTrue(text.words.isEmpty())
        assertTrue(text.sentences.isEmpty())
    }

    @Test
    fun `counts words`() {
        assertEquals(0, wordCount("   "))
        assertEquals(3, wordCount(" satu dua\n\ntiga "))
    }
}
