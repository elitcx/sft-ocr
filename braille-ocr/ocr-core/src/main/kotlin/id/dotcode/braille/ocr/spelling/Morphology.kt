package id.dotcode.braille.ocr.spelling

/**
 * Candidate root words of an inflected or derived word, so the dictionary can recognize
 * "terkasihnya" (ter- + kasih + -nya) or "sustainably" (sustainable) without listing every
 * form. A word whose root is known is treated as known and left alone.
 *
 * This deliberately over-generates: a wrong root only makes the corrector leave a word
 * alone, which is the safe failure.
 */
internal object Morphology {

    private const val MIN_ROOT_LENGTH = 3

    private val ID_PREFIXES = listOf("memper", "diper", "ber", "ter", "per", "di", "ke", "se", "me", "pe", "")
    private val ID_SUFFIXES = listOf("kannya", "annya", "nya", "kan", "lah", "kah", "pun", "an", "i", "")

    /** meN-/peN- drop the root's first consonant: memukul from pukul, menulis from tulis. */
    private val ID_NASAL_PREFIXES = mapOf(
        "meng" to listOf("k", ""), "meny" to listOf("s"), "mem" to listOf("p", ""), "men" to listOf("t", ""),
        "peng" to listOf("k", ""), "peny" to listOf("s"), "pem" to listOf("p", ""), "pen" to listOf("t", ""),
    )

    private val EN_PREFIXES = listOf("under", "over", "non", "pre", "dis", "mis", "un", "re", "")

    /** Suffix to the endings its root may have had. */
    private val EN_SUFFIXES = mapOf(
        "ably" to listOf("able"), "ibly" to listOf("ible"), "ily" to listOf("y"),
        "ence" to listOf("ent"), "ance" to listOf("ant"), "ies" to listOf("y"),
        "ness" to listOf(""), "ment" to listOf(""), "ly" to listOf(""),
        "ing" to listOf("", "e"), "ed" to listOf("", "e"), "er" to listOf("", "e"),
        "est" to listOf("", "e"), "es" to listOf(""), "s" to listOf(""), "" to listOf(""),
    )

    fun roots(word: String): Sequence<String> = sequence {
        for (suffix in ID_SUFFIXES) {
            if (!word.endsWith(suffix)) continue
            val withoutSuffix = word.dropLast(suffix.length)
            for (prefix in ID_PREFIXES) {
                if (prefix.isEmpty() && suffix.isEmpty()) continue
                if (withoutSuffix.startsWith(prefix)) yieldRoot(withoutSuffix.drop(prefix.length))
            }
            for ((prefix, restored) in ID_NASAL_PREFIXES) {
                if (!withoutSuffix.startsWith(prefix)) continue
                val rest = withoutSuffix.drop(prefix.length)
                for (head in restored) yieldRoot(head + rest)
            }
        }
        for ((suffix, endings) in EN_SUFFIXES) {
            if (!word.endsWith(suffix)) continue
            val withoutSuffix = word.dropLast(suffix.length)
            for (prefix in EN_PREFIXES) {
                if (prefix.isEmpty() && suffix.isEmpty()) continue
                if (!withoutSuffix.startsWith(prefix)) continue
                val stem = withoutSuffix.drop(prefix.length)
                for (ending in endings) yieldRoot(stem + ending)
            }
        }
    }

    private suspend fun SequenceScope<String>.yieldRoot(root: String) {
        if (root.length >= MIN_ROOT_LENGTH) yield(root)
    }
}
