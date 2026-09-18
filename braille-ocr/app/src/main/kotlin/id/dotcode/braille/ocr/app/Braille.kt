package id.dotcode.braille.ocr.app

import java.text.Normalizer

/**
 * Grade 1 (uncontracted) braille for on-screen display, following the literary code that
 * Indonesian braille shares with English: letters a–z, the number sign (dots 3-4-5-6) before
 * digits, the letter sign (dots 5-6) when a–j follows a number, the capital sign (dot 6,
 * doubled for an all-caps word) and common punctuation.
 *
 * Display only: the BraillePad receives plain text and does its own conversion.
 */
object Braille {

    /** [dots] has dot n at bit n-1; [label] is what the prototype prints under the cell. */
    data class Cell(val dots: Int, val label: String)

    const val NUMBER_SIGN = 0b111100 // 3-4-5-6
    const val LETTER_SIGN = 0b110000 // 5-6
    const val CAPITAL_SIGN = 0b100000 // 6

    private val LETTERS: Map<Char, Int> = mapOf(
        'a' to d(1), 'b' to d(1, 2), 'c' to d(1, 4), 'd' to d(1, 4, 5), 'e' to d(1, 5),
        'f' to d(1, 2, 4), 'g' to d(1, 2, 4, 5), 'h' to d(1, 2, 5), 'i' to d(2, 4), 'j' to d(2, 4, 5),
        'k' to d(1, 3), 'l' to d(1, 2, 3), 'm' to d(1, 3, 4), 'n' to d(1, 3, 4, 5), 'o' to d(1, 3, 5),
        'p' to d(1, 2, 3, 4), 'q' to d(1, 2, 3, 4, 5), 'r' to d(1, 2, 3, 5), 's' to d(2, 3, 4),
        't' to d(2, 3, 4, 5), 'u' to d(1, 3, 6), 'v' to d(1, 2, 3, 6), 'w' to d(2, 4, 5, 6),
        'x' to d(1, 3, 4, 6), 'y' to d(1, 3, 4, 5, 6), 'z' to d(1, 3, 5, 6),
    )

    private val PUNCTUATION: Map<Char, Int> = mapOf(
        ',' to d(2), ';' to d(2, 3), ':' to d(2, 5), '.' to d(2, 5, 6), '!' to d(2, 3, 5),
        '?' to d(2, 3, 6), '-' to d(3, 6), '\'' to d(3), '(' to d(2, 3, 5, 6), ')' to d(2, 3, 5, 6),
        '/' to d(3, 4),
    )

    private const val OPEN_QUOTE = 0b100110 // 2-3-6
    private const val CLOSE_QUOTE = 0b110100 // 3-5-6

    fun d(vararg dots: Int): Int = dots.fold(0) { mask, dot -> mask or (1 shl (dot - 1)) }

    /** Cells for one whitespace-free token, signs included. */
    fun cellsFor(word: String): List<Cell> {
        val text = stripAccents(word)
        val letters = text.filter { it.isLetter() }
        val allCaps = letters.length > 1 && letters.all { it.isUpperCase() }
        val cells = mutableListOf<Cell>()
        var inNumber = false
        var capsWordSignWritten = false
        text.forEachIndexed { index, char ->
            val lower = char.lowercaseChar()
            when {
                char.isDigit() -> {
                    if (!inNumber) cells += Cell(NUMBER_SIGN, "#")
                    inNumber = true
                    val digit = char.digitToInt()
                    cells += Cell(LETTERS.getValue(if (digit == 0) 'j' else 'a' + (digit - 1)), char.toString())
                }
                lower in LETTERS -> {
                    if (inNumber && lower in 'a'..'j') cells += Cell(LETTER_SIGN, "")
                    inNumber = false
                    if (char.isUpperCase()) {
                        if (allCaps) {
                            if (!capsWordSignWritten) {
                                cells += Cell(CAPITAL_SIGN, "")
                                cells += Cell(CAPITAL_SIGN, "")
                                capsWordSignWritten = true
                            }
                        } else {
                            cells += Cell(CAPITAL_SIGN, "")
                        }
                    }
                    cells += Cell(LETTERS.getValue(lower), char.uppercase())
                }
                char == '"' || char == '“' || char == '”' -> {
                    inNumber = false
                    val opening = char == '“' || (char == '"' && index == 0)
                    cells += Cell(if (opening) OPEN_QUOTE else CLOSE_QUOTE, "\"")
                }
                char in PUNCTUATION -> {
                    inNumber = false
                    cells += Cell(PUNCTUATION.getValue(char), char.toString())
                }
                char == '’' || char == '‘' -> {
                    inNumber = false
                    cells += Cell(PUNCTUATION.getValue('\''), "'")
                }
                else -> {
                    inNumber = false
                    cells += Cell(0, char.toString())
                }
            }
        }
        return cells
    }

    private fun stripAccents(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
}
