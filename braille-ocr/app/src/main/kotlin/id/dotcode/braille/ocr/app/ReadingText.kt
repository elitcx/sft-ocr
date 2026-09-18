package id.dotcode.braille.ocr.app

/**
 * The document split the way Read Mode walks it: sentence by sentence, word by word. Word
 * offsets point into their sentence's text so the current word can be highlighted in place.
 */
class ReadingText private constructor(
    val sentences: List<Sentence>,
    val words: List<Word>,
) {
    data class Sentence(val text: String, val firstWord: Int, val lastWord: Int)

    data class Word(val text: String, val sentence: Int, val start: Int, val end: Int) {
        /** What a voice or a single-cell display should get: the word without edge punctuation. */
        val spoken: String get() = text.trim { !it.isLetterOrDigit() }.ifEmpty { text }
    }

    companion object {
        private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")
        private val WORD = Regex("\\S+")

        /** "1.", "a.", "12)" - list and question numbering, not the end of a sentence. */
        private val MARKER = Regex("^[(\\[]?(\\d{1,3}|[A-Za-z]|[ivxIVX]{1,4})[.)]$")
        private val ABBREVIATIONS = setOf(
            "dll", "dsb", "dst", "dkk", "yth", "no", "hlm", "bpk", "sdr", "dr", "ir", "prof", "jl",
            "tel", "mr", "mrs", "ms", "st", "vs", "etc", "e.g", "i.e", "p", "pp",
        )

        private fun endsSentence(previousToken: String): Boolean {
            if (MARKER.matches(previousToken)) return false
            if (previousToken.endsWith('.') &&
                previousToken.dropLast(1).lowercase().trimStart('(') in ABBREVIATIONS
            ) return false
            return true
        }

        private fun splitSentences(line: String): List<String> {
            val parts = mutableListOf<String>()
            var start = 0
            SENTENCE_END.findAll(line).forEach { gap ->
                val before = line.substring(start, gap.range.first)
                val previousToken = before.substringAfterLast(' ')
                if (endsSentence(previousToken)) {
                    parts += before
                    start = gap.range.last + 1
                }
            }
            parts += line.substring(start)
            return parts
        }

        fun of(plainText: String): ReadingText {
            val sentences = mutableListOf<Sentence>()
            val words = mutableListOf<Word>()
            plainText.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .flatMap { line -> splitSentences(line).asSequence() }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { sentence ->
                    val first = words.size
                    WORD.findAll(sentence).forEach { match ->
                        words += Word(match.value, sentences.size, match.range.first, match.range.last + 1)
                    }
                    sentences += Sentence(sentence, first, words.size - 1)
                }
            return ReadingText(sentences, words)
        }
    }
}

fun wordCount(text: String): Int = Regex("\\S+").findAll(text).count()
