package id.dotcode.braille.ocr.spelling

/**
 * The known-word list the spelling pass checks words against, merged from one frequency
 * list per language (the app bundles Indonesian and English), a longer tail of words that
 * are only *recognized* as real, and any extra words a teacher adds.
 *
 * Counts from different corpora are not comparable, so each list is normalized to relative
 * frequency (count / list total) before merging; a word in both keeps its higher one. The
 * raw count is kept too: a word seen only a handful of times in a subtitle corpus is as
 * likely to be a typo as a word, and must not be offered as a correction.
 */
class SpellingDictionary private constructor(
    private val words: Map<String, Entry>,
    private val knownOnly: Set<String>,
) {

    /** [english] marks a word that is at least as common in English as in Indonesian. */
    internal class Entry(val word: String, val frequency: Double, val count: Long, val english: Boolean) {
        val mask: Int = letterMask(word)
    }

    /** Entries grouped by word length: the corrector only compares near-equal lengths. */
    internal val byLength: Map<Int, List<Entry>> = words.values.groupBy { it.word.length }

    val size: Int get() = words.size + knownOnly.size

    /** [word] must already be lower-case. True for suggestible and known-only words. */
    operator fun contains(word: String): Boolean = word in words || word in knownOnly

    internal fun entry(word: String): Entry? = words[word]

    /**
     * True if [word] (lower-case) is listed, or is a prefixed/suffixed form of a listed root
     * that is common enough not to be noise - see [Morphology].
     */
    fun isKnown(word: String): Boolean =
        contains(word) || Morphology.roots(word).any { root -> (words[root]?.count ?: 0) >= MIN_ROOT_COUNT }

    /**
     * A copy that also knows [extra] (e.g. a teacher's subject terms). They are treated as
     * common words, so they are never corrected and can be suggested.
     */
    fun withExtraWords(extra: Collection<String>): SpellingDictionary {
        val added = extra
            .map { it.trim().lowercase() }
            .filter { it.length >= 2 && it.all { c -> c in 'a'..'z' } && it !in words }
        if (added.isEmpty()) return this
        return SpellingDictionary(
            words + added.associateWith { Entry(it, EXTRA_WORD_FREQUENCY, EXTRA_WORD_COUNT, english = false) },
            knownOnly,
        )
    }

    companion object {
        /** Suggestible word lists shipped in the app's `spelling/` assets, by language. */
        val BUNDLED_LISTS: Map<String, String> = mapOf("id" to "id.txt", "en" to "en.txt")

        /** Recognize-only word lists shipped alongside [BUNDLED_LISTS]. */
        val BUNDLED_KNOWN_ONLY: List<String> = listOf("id-known.txt", "en-known.txt", "id-school.txt")

        /** Builds the dictionary the app ships, reading each bundled file through [open]. */
        fun fromBundled(open: (fileName: String) -> Sequence<String>): SpellingDictionary = fromWordLists(
            lists = BUNDLED_LISTS.mapValues { (_, file) -> open(file) },
            knownOnly = BUNDLED_KNOWN_ONLY.map(open),
        )

        private const val MIN_ROOT_COUNT = 100L
        private const val EXTRA_WORD_FREQUENCY = 1e-4
        private const val EXTRA_WORD_COUNT = 100_000L

        /**
         * Builds a dictionary from word lists whose lines read `word count` (the format of
         * `tools/build_spelling_dictionary.py`), keyed by language code ("id", "en"), plus
         * [knownOnly] lists of one word per line. Malformed lines are skipped.
         */
        fun fromWordLists(
            lists: Map<String, Sequence<String>>,
            knownOnly: List<Sequence<String>> = emptyList(),
        ): SpellingDictionary {
            val merged = HashMap<String, Entry>()
            val english = HashMap<String, Double>()
            val other = HashMap<String, Double>()
            for ((language, list) in lists) {
                val counts = LinkedHashMap<String, Long>()
                for (line in list) {
                    val space = line.indexOf(' ')
                    if (space <= 0) continue
                    val word = line.substring(0, space).lowercase()
                    val count = line.substring(space + 1).trim().toLongOrNull() ?: continue
                    if (word.all { it in 'a'..'z' }) counts[word] = count
                }
                val total = counts.values.sum().toDouble().takeIf { it > 0 } ?: continue
                val byLanguage = if (language == "en") english else other
                for ((word, count) in counts) {
                    val frequency = count / total
                    byLanguage[word] = frequency
                    val existing = merged[word]
                    if (existing == null || existing.frequency < frequency) {
                        merged[word] = Entry(word, frequency, count, english = false)
                    }
                }
            }
            val words = merged.mapValues { (word, entry) ->
                val isEnglish = (english[word] ?: 0.0) >= (other[word] ?: 0.0)
                Entry(word, entry.frequency, entry.count, isEnglish)
            }
            val known = HashSet<String>()
            for (list in knownOnly) {
                for (line in list) {
                    val word = line.trim().lowercase()
                    if (word.length >= 2 && word.all { it in 'a'..'z' } && word !in words) known += word
                }
            }
            return SpellingDictionary(words, known)
        }

        /** One bit per letter a-z present in [word]: a cheap pre-filter before edit distance. */
        internal fun letterMask(word: String): Int {
            var mask = 0
            for (c in word) if (c in 'a'..'z') mask = mask or (1 shl (c - 'a'))
            return mask
        }
    }
}
