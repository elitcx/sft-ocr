package id.dotcode.braille.ocr.app

import id.dotcode.braille.ocr.app.Braille.d
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class BrailleTest {

    private fun dots(word: String) = Braille.cellsFor(word).map { it.dots }

    @Test
    fun `lowercase letters use the standard grade 1 cells`() {
        assertEquals(listOf(d(2, 4), d(1, 2), d(1, 3, 6)), dots("ibu"))
        assertEquals(listOf(d(1, 2, 3, 4), d(1, 5), d(1, 2, 3, 5), d(1, 2, 4, 5), d(2, 4)), dots("pergi"))
        assertEquals(d(1, 3, 5, 6), dots("z").single())
        assertEquals(d(2, 4, 5, 6), dots("w").single())
    }

    @Test
    fun `a capital letter is preceded by the capital sign`() {
        val cells = Braille.cellsFor("Ibu")
        assertEquals(listOf(Braille.CAPITAL_SIGN, d(2, 4), d(1, 2), d(1, 3, 6)), cells.map { it.dots })
        assertEquals(listOf("", "I", "B", "U"), cells.map { it.label })
    }

    @Test
    fun `an all-caps word gets the double capital sign once`() {
        assertEquals(
            listOf(Braille.CAPITAL_SIGN, Braille.CAPITAL_SIGN, d(2, 4), d(1, 2), d(1, 3, 6)),
            dots("IBU"),
        )
    }

    @Test
    fun `a single capital letter is not treated as an all-caps word`() {
        assertEquals(listOf(Braille.CAPITAL_SIGN, d(1)), dots("A"))
    }

    @Test
    fun `digits take one number sign and the a-j cells`() {
        assertEquals(listOf(Braille.NUMBER_SIGN, d(1), d(2, 4, 5), d(2, 4)), dots("109"))
    }

    @Test
    fun `a letter a to j after a number needs the letter sign`() {
        assertEquals(listOf(Braille.NUMBER_SIGN, d(1, 2), Braille.LETTER_SIGN, d(1)), dots("2a"))
        // k-z can't be mistaken for digits, so no letter sign.
        assertEquals(listOf(Braille.NUMBER_SIGN, d(1, 2), d(1, 3)), dots("2k"))
    }

    @Test
    fun `punctuation maps to its own cells`() {
        assertEquals(listOf(d(1), d(2, 5, 6)), dots("a."))
        assertEquals(listOf(d(1), d(2)), dots("a,"))
        assertEquals(listOf(d(1), d(2, 3, 6)), dots("a?"))
        assertEquals(listOf(d(1), d(3, 6), d(1, 2)), dots("a-b"))
    }

    @Test
    fun `quotes open and close`() {
        assertEquals(listOf(d(2, 3, 6), d(1), d(3, 5, 6)), dots("\"a\""))
    }

    @Test
    fun `accented letters use their base letter`() {
        assertEquals(dots("e"), dots("é"))
    }

    @Test
    fun `an unknown symbol becomes a blank cell that keeps its label`() {
        val cell = Braille.cellsFor("@").single()
        assertEquals(0, cell.dots)
        assertEquals("@", cell.label)
    }
}
