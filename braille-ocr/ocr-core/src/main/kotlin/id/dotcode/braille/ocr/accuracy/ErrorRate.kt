package id.dotcode.braille.ocr.accuracy

/**
 * Character and word error rates against a reference transcription.
 *
 * This turns the concept paper's ">90% accuracy across 50 SLB-A worksheets" from an
 * aspiration into a number that can be produced on demand once the samples exist.
 */
data class AccuracyReport(
    val cer: Double,
    val wer: Double,
    val expectedChars: Int,
    val expectedWords: Int,
) {
    val characterAccuracy: Double get() = (1.0 - cer).coerceIn(0.0, 1.0)
    val wordAccuracy: Double get() = (1.0 - wer).coerceIn(0.0, 1.0)
}

object ErrorRate {

    fun compare(expected: String, actual: String): AccuracyReport {
        val expectedNorm = normalize(expected)
        val actualNorm = normalize(actual)
        val expectedChars = expectedNorm.map { it.toString() }
        val actualChars = actualNorm.map { it.toString() }
        val expectedWords = expectedNorm.split(' ').filter { it.isNotEmpty() }
        val actualWords = actualNorm.split(' ').filter { it.isNotEmpty() }

        return AccuracyReport(
            cer = rate(levenshtein(expectedChars, actualChars), expectedChars.size, actualChars.size),
            wer = rate(levenshtein(expectedWords, actualWords), expectedWords.size, actualWords.size),
            expectedChars = expectedChars.size,
            expectedWords = expectedWords.size,
        )
    }

    fun characterErrorRate(expected: String, actual: String): Double = compare(expected, actual).cer

    fun wordErrorRate(expected: String, actual: String): Double = compare(expected, actual).wer

    fun levenshtein(a: List<String>, b: List<String>): Int {
        if (a.isEmpty()) return b.size
        if (b.isEmpty()) return a.size

        var previous = IntArray(b.size + 1) { it }
        var current = IntArray(b.size + 1)
        for (i in 1..a.size) {
            current[0] = i
            for (j in 1..b.size) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.size]
    }

    private fun rate(distance: Int, expectedSize: Int, actualSize: Int): Double = when {
        expectedSize > 0 -> distance.toDouble() / expectedSize
        actualSize > 0 -> 1.0
        else -> 0.0
    }

    private fun normalize(text: String): String = text.trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")
}
