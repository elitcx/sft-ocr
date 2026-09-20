package id.dotcode.braille.ocr.app

import id.dotcode.braille.ocr.model.SpellingCorrection

/**
 * Where one correction landed inside a block's text, so the result screen can show the fix in
 * place instead of only counting it.
 *
 * [start] and [end] index into the block's own `text`, not into the marker-prefixed string the
 * screen renders; the caller shifts them by the marker's length.
 */
data class CorrectionSpan(
    val start: Int,
    val end: Int,
    val original: String,
    val corrected: String,
)

/**
 * Locates corrected words in already-corrected text.
 *
 * The correction list holds `original -> corrected` pairs in reading order but carries no
 * offsets, so the corrected word has to be found again in the text. Matching walks forward
 * only: each correction is searched from the end of the previous match, which is what keeps a
 * word corrected twice in one block mapped to the right entry rather than both highlights
 * landing on the first occurrence.
 *
 * A correction whose corrected form cannot be found at or after the cursor is skipped. That
 * happens when a later pass rewrote the word again, and highlighting the wrong word reads as a
 * bug to a judge, so the conservative choice is to show one fewer highlight than to guess.
 */
object CorrectionHighlighting {

    fun spansIn(text: String, corrections: List<SpellingCorrection>): List<CorrectionSpan> {
        if (text.isEmpty() || corrections.isEmpty()) return emptyList()
        val spans = mutableListOf<CorrectionSpan>()
        var cursor = 0
        for (correction in corrections) {
            if (correction.corrected.isEmpty()) continue
            val at = indexOfWord(text, correction.corrected, cursor)
            if (at < 0) continue
            spans += CorrectionSpan(
                start = at,
                end = at + correction.corrected.length,
                original = correction.original,
                corrected = correction.corrected,
            )
            cursor = at + correction.corrected.length
        }
        return spans
    }

    /**
     * First occurrence of [word] at or after [from] that is a whole word. Without the boundary
     * check a correction to "ada" would highlight the middle of "kepada".
     */
    private fun indexOfWord(text: String, word: String, from: Int): Int {
        var at = text.indexOf(word, from)
        while (at >= 0) {
            val startsWord = at == 0 || !isWordChar(text[at - 1])
            val after = at + word.length
            val endsWord = after >= text.length || !isWordChar(text[after])
            if (startsWord && endsWord) return at
            at = text.indexOf(word, at + 1)
        }
        return -1
    }

    /** Apostrophes and hyphens sit inside Indonesian and English words, so they do not end one. */
    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '-' || c == '’'
}
