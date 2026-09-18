package id.dotcode.braille.ocr.spelling

import id.dotcode.braille.ocr.model.OcrDocument
import id.dotcode.braille.ocr.model.SpellingCorrection
import id.dotcode.braille.ocr.model.TextWord
import kotlin.math.abs

/** Tuning for [SpellCorrector]. The defaults lean towards leaving a word alone. */
data class SpellingConfig(
    /**
     * A word the primary recognizer scored at least this is never touched. Measured on real
     * captures, misreadings still score up to ~0.85, so this has to sit above that.
     */
    val trustedConfidence: Float = 0.9f,
    /** Shorter words are only fixed when the second recognizer supplies the fix. */
    val minWordLength: Int = 4,
    /**
     * From this length a word may be one and a half edits from its correction instead of
     * one: a whole edit plus a recognizer look-alike ("reguirements"), never two whole
     * edits ("elevasinya" is not "televisinya").
     */
    val longWordLength: Int = 8,
    /** A dictionary word seen fewer times than this in its corpus is never suggested. */
    val minCandidateCount: Long = 30,
    /** Both halves of a split ("sucha" -> "such a") must be at least this common. */
    val minSplitPartCount: Long = 2_000,
    /** A suggestion more than a look-alike away must have been seen at least this often. */
    val minWholeEditCandidateCount: Long = 100,
    /** A suggested word of five letters or fewer must have been seen at least this often. */
    val minShortCandidateCount: Long = 500,
    /** When two candidates are equally close, the winner must be this many times as common. */
    val ambiguityRatio: Double = 3.0,
)

/**
 * The second layer after recognition. Each word is weighed on three pieces of evidence:
 * the primary recognizer's confidence, what a second independent recognizer read at the
 * same spot ([ReadingVoter]), and a [SpellingDictionary].
 *
 * A wrong "correction" is worse than a typo (a blind reader cannot check it against the
 * page), so a word is left alone when:
 * - both recognizers read it identically - which is also what protects deliberate typos and
 *   unusual terms that are printed clearly;
 * - the primary recognizer is very sure of it ([SpellingConfig.trustedConfidence]);
 * - it is a known word (including the long known-only tail), a number, a Roman numeral,
 *   or looks like a code (MiXeD case, or a short word with digits);
 * - it is ALL CAPS and at most five letters (an acronym);
 * - it is capitalized mid-sentence (a name) and the second recognizer offers no known word.
 *
 * Capitalized words anywhere (names, one per line in a list), words of five letters or
 * fewer, and lowercase words opening a block (both often fragments cut at the page edge)
 * are only ever changed by a recognizer look-alike ([OcrEditDistance] half-edit), never by
 * a whole-letter guess; capitalized words also never change length.
 *
 * Otherwise, in order of preference, it becomes:
 * 1. the second recognizer's reading, if that is a dictionary word close to it;
 * 2. the second recognizer's split of it into known words ("tentangkagumi");
 * 3. its look-alike-digit fix ("sek0lah" -> "sekolah");
 * 4. two common words it is a run-together of ("sucha" -> "such a");
 * 5. its single clearly-closest dictionary word ([OcrEditDistance]) - unless the second
 *    recognizer's reading points at a different word, in which case nothing changes.
 *
 * Every change is recorded in [id.dotcode.braille.ocr.model.TextBlock.corrections].
 */
class SpellCorrector(
    private val dictionary: SpellingDictionary,
    private val config: SpellingConfig = SpellingConfig(),
) {

    fun correct(document: OcrDocument): OcrDocument {
        val cache = HashMap<String, String?>()
        return document.copy(
            blocks = document.blocks.map { block ->
                val words = block.lines.flatMap { it.words }
                val (text, corrections) = correctText(block.text, words, cache)
                if (corrections.isEmpty()) block else block.copy(text = text, corrections = corrections)
            },
        )
    }

    /** [words] are the recognized words behind [text], in order; empty if unknown. */
    fun correctText(text: String, words: List<TextWord> = emptyList()): Pair<String, List<SpellingCorrection>> =
        correctText(text, words, HashMap())

    private fun correctText(
        text: String,
        words: List<TextWord>,
        cache: MutableMap<String, String?>,
    ): Pair<String, List<SpellingCorrection>> {
        val evidence = EvidenceAligner(words)
        val corrections = mutableListOf<SpellingCorrection>()
        val corrected = TOKEN.replace(text) { match ->
            val token = match.value
            val fix = suggest(token, isSentenceStart(text, match.range.first), evidence.next(token), cache)
            if (fix == null || fix == token) {
                token
            } else {
                corrections += SpellingCorrection(original = token, corrected = fix)
                fix
            }
        }
        return corrected to corrections
    }

    private fun suggest(
        token: String,
        sentenceStart: Boolean,
        evidence: Evidence?,
        cache: MutableMap<String, String?>,
    ): String? {
        val shape = CaseShape.of(token) ?: return null
        val lower = token.lowercase()
        val second = evidence?.second
        if (evidence?.agrees == true) return null
        if (evidence?.confidence != null && evidence.confidence >= config.trustedConfidence) return null
        if (token.length > MAX_WORD_LENGTH) return null

        // Class names and chapter numbers: "XII" is not a misread "XI".
        if (ROMAN_NUMERAL.matches(token)) return null

        val secondWord = second?.takeIf { ' ' !in it && it.all { c -> c in 'a'..'z' } }

        if (token.any { it.isDigit() }) {
            // "P5", "X1": short codes, not misread words.
            if (token.length < config.minWordLength) return null
            val fix = fixLookalikeDigits(lower)
                ?: secondWord?.takeIf { dictionary.isKnown(it) && isLookalikeOf(lower, it) }
            return fix?.let(shape::apply)
        }
        if (dictionary.isKnown(lower)) return null
        // An unknown short ALL-CAPS word is an acronym (CPAN, QRIS, SMK), whatever either
        // recognizer thinks it resembles.
        if (shape == CaseShape.UPPER && token.length <= MAX_ACRONYM_LENGTH) return null

        // Capitalized words are mostly names (Jehezkiel, Josefhine) - lists of them put one
        // at every line start - and short words are mostly fragments (a word cut at the page
        // edge). Neither may be rewritten beyond a recognizer look-alike.
        // A block that starts in lowercase has usually lost its first letters at the page
        // edge ("mpetition", "ingkat"): the rest of the word is not there to guess from.
        val likelyFragment = shape == CaseShape.LOWER && sentenceStart
        val lookalikeOnly = shape != CaseShape.LOWER || token.length <= SHORT_WORD_LENGTH || likelyFragment
        val secondMaxHalf = when {
            lookalikeOnly -> LOOKALIKE_HALF
            token.length >= config.longWordLength -> LONG_SECOND_READING_MAX_HALF
            else -> SECOND_READING_MAX_HALF
        }
        if (secondWord != null && token.length >= MIN_SECOND_READING_LENGTH &&
            abs(secondWord.length - token.length) <= 1 && dictionary.isKnown(secondWord) &&
            OcrEditDistance.halfUnits(lower, secondWord, secondMaxHalf) <= secondMaxHalf
        ) {
            return shape.apply(secondWord)
        }
        val secondParts = second?.split(' ')
        if (secondParts != null && secondParts.size > 1 &&
            secondParts.joinToString("") == lower && secondParts.all { dictionary.isKnown(it) }
        ) {
            return shape.apply(second)
        }

        if (shape == CaseShape.TITLE && !sentenceStart) return null
        if (token.length < config.minWordLength) return null

        val fix = cached(lower, cache) ?: return null
        val isSplit = fix.any { it == ' ' || it == '-' }
        if (lookalikeOnly && !isSplit &&
            OcrEditDistance.halfUnits(lower, fix, LOOKALIKE_HALF) > LOOKALIKE_HALF
        ) {
            return null
        }
        // Names vary in doubled letters (Callista, Alloysius): a capitalized word keeps its length.
        if (shape != CaseShape.LOWER && !isSplit && fix.length != lower.length) return null
        // "Auniversal" may be "A universal"; "Milka" is a name, not "Milk a".
        if (isSplit && shape != CaseShape.LOWER && fix.substringBefore(' ') !in SHORT_SPLIT_WORDS) return null
        // Even an unsure second reading vetoes a guess it points away from.
        val vetoWord = evidence?.anySecond?.takeIf { ' ' !in it && it.all { c -> c in 'a'..'z' } }
        if (vetoWord != null) {
            val secondFix = cached(vetoWord, cache)
            if (secondFix != null && secondFix != fix) return null
        }
        return shape.apply(fix)
    }

    private fun cached(word: String, cache: MutableMap<String, String?>): String? {
        if (word in cache) return cache[word]
        val near = nearest(word)
        val split = split(word)
        // A lost space costs less than a whole edit but more than a look-alike: "sucha" is
        // "such a", not "such"; "chitdren" is still "children".
        val fix = if (split != null && (near == null || near.distance > SPLIT_COST_HALF)) split else near?.word
        cache[word] = fix
        return fix
    }

    private fun fixLookalikeDigits(lower: String): String? {
        val digits = lower.count { it.isDigit() }
        if (lower.length < config.minWordLength || digits > MAX_DIGITS || digits * 2 >= lower.length) return null
        if (lower.any { it.isDigit() && it !in DIGIT_LOOKALIKES }) return null
        var variants = listOf("")
        for (c in lower) {
            val options = DIGIT_LOOKALIKES[c] ?: c.toString()
            variants = variants.flatMap { prefix -> options.map { prefix + it } }
        }
        return variants
            .mapNotNull { dictionary.entry(it) }
            .maxByOrNull { it.frequency }
            ?.word
    }

    private fun isLookalikeOf(withDigits: String, word: String): Boolean =
        withDigits.length == word.length && withDigits.indices.all { i ->
            val c = withDigits[i]
            c == word[i] || (DIGIT_LOOKALIKES[c]?.contains(word[i]) == true)
        }

    /**
     * "sucha" -> "such a": the word is two common words with the space lost. A repeated
     * word is Indonesian reduplication and gets its hyphen back ("jiwajiwa" -> "jiwa-jiwa",
     * "seharihari" -> "sehari-hari").
     */
    private fun split(word: String): String? {
        if (word.length < MIN_SPLIT_LENGTH) return null
        var best: Pair<String, Double>? = null
        for (i in 1 until word.length) {
            val leftText = word.substring(0, i)
            val rightText = word.substring(i)
            val left = splitPart(leftText, other = rightText) ?: continue
            val right = splitPart(rightText, other = leftText) ?: continue
            val score = minOf(left, right)
            val joined = if (leftText.endsWith(rightText)) "$leftText-$rightText" else "$leftText $rightText"
            if (best == null || score > best.second) best = joined to score
        }
        return best?.first
    }

    private fun splitPart(part: String, other: String): Double? {
        // An English article only goes with an English word: "a universal", not "a ikan".
        if (part in SHORT_SPLIT_WORDS) return if (dictionary.entry(other)?.english == true) 1.0 else null
        // Short fragments ("ty", "th", "pro") are common in the corpus and would split
        // almost any misread word.
        if (part.length < MIN_SPLIT_PART_LENGTH) return null
        val entry = dictionary.entry(part) ?: return null
        return if (entry.count >= config.minSplitPartCount) entry.frequency else null
    }

    private fun nearest(word: String): Candidate? {
        val maxHalf = if (word.length >= config.longWordLength) 3 else 2
        val mask = SpellingDictionary.letterMask(word)
        var best: Candidate? = null
        var runnerUp: Candidate? = null
        // One length step per allowed edit; two cheap merges in one word are not worth the scan.
        for (length in word.length - (maxHalf + 1) / 2..word.length + (maxHalf + 1) / 2) {
            for (entry in dictionary.byLength[length] ?: continue) {
                if (entry.count < config.minCandidateCount) continue
                // A rare short word ("ryong", "kendo") is a worse guess than a fragment.
                if (entry.word.length <= SHORT_WORD_LENGTH && entry.count < config.minShortCandidateCount) continue
                // Each whole edit flips at most two letter bits, a merge at most three.
                if (Integer.bitCount(mask xor entry.mask) > maxHalf + 1) continue
                val distance = OcrEditDistance.halfUnits(word, entry.word, maxHalf)
                if (distance > maxHalf) continue
                // Guessing a whole letter is only worth it for a word that is actually common.
                if (distance > LOOKALIKE_HALF && entry.count < config.minWholeEditCandidateCount) continue
                val candidate = Candidate(entry.word, distance, entry.frequency)
                if (best == null || candidate.beats(best)) {
                    runnerUp = best
                    best = candidate
                } else if (runnerUp == null || candidate.beats(runnerUp)) {
                    runnerUp = candidate
                }
            }
        }
        val winner = best ?: return null
        val rival = runnerUp ?: return winner
        val clear = rival.distance > winner.distance ||
            winner.frequency >= rival.frequency * config.ambiguityRatio
        return if (clear) winner else null
    }

    private class Candidate(val word: String, val distance: Int, val frequency: Double) {
        fun beats(other: Candidate): Boolean =
            distance < other.distance || (distance == other.distance && frequency > other.frequency)
    }

    /** What is known about one token of a block's text. [second] is normalized lower-case. */
    /**
     * [anySecond]: the second recognizer's reading, normalized, however unsure it was - it
     * may confirm a word or veto a guess. [second]: the same reading only when it was
     * reasonably sure - only then may it replace a word.
     */
    private class Evidence(val confidence: Float?, val second: String?, val anySecond: String?, val agrees: Boolean)

    /**
     * Walks a block's recognized words alongside the tokens of its (reflowed) text. The two
     * usually match one to one; reflow can join a hyphen-wrapped word from two, and anything
     * else unmatched just gets no evidence rather than someone else's.
     */
    private class EvidenceAligner(words: List<TextWord>) {
        private class WordToken(val text: String, val word: TextWord, val whole: Boolean)

        private val tokens: List<WordToken> = words.flatMap { word ->
            val parts = TOKEN.findAll(word.text).map { it.value }.toList()
            parts.map { WordToken(it, word, whole = parts.size == 1) }
        }
        private var position = 0

        fun next(token: String): Evidence? {
            for (j in position until minOf(position + LOOKAHEAD, tokens.size)) {
                if (tokens[j].text == token) {
                    position = j + 1
                    val t = tokens[j]
                    val reading = if (t.whole) normalizedSecond(t.word) else null
                    val trusted = reading?.takeIf { (t.word.secondConfidence ?: 1f) >= MIN_SECOND_CONFIDENCE }
                    return Evidence(t.word.confidence, trusted, reading, agrees = reading == t.text.lowercase())
                }
            }
            if (position + 1 < tokens.size && tokens[position].text + tokens[position + 1].text == token) {
                val a = tokens[position]
                val b = tokens[position + 1]
                position += 2
                return Evidence(listOfNotNull(a.word.confidence, b.word.confidence).minOrNull(), null, null, agrees = false)
            }
            return null
        }

        private fun normalizedSecond(word: TextWord): String? {
            val reading = word.secondReading ?: return null
            val parts = TOKEN.findAll(reading).map { it.value.lowercase() }.toList()
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
        }

        private companion object {
            const val LOOKAHEAD = 4
        }
    }

    /** The capitalization a word came in with, so the correction can wear the same. */
    private enum class CaseShape {
        LOWER, UPPER, TITLE;

        fun apply(word: String): String = when (this) {
            LOWER -> word
            UPPER -> word.uppercase()
            TITLE -> word.replaceFirstChar { it.uppercaseChar() }
        }

        companion object {
            /** Null for tokens with no letters or a mixed case that suggests a code. */
            fun of(token: String): CaseShape? {
                val letters = token.filter { it.isLetter() }
                return when {
                    letters.isEmpty() -> null
                    letters.all { it.isLowerCase() } -> LOWER
                    letters.length > 1 && letters.all { it.isUpperCase() } -> UPPER
                    letters.first().isUpperCase() && letters.drop(1).all { it.isLowerCase() } -> TITLE
                    else -> null
                }
            }
        }
    }

    private companion object {
        val TOKEN = Regex("[\\p{L}\\p{N}]+")
        const val MAX_WORD_LENGTH = 30
        const val MAX_DIGITS = 2
        const val MIN_SPLIT_LENGTH = 5
        const val SECOND_READING_MAX_HALF = 2
        const val LONG_SECOND_READING_MAX_HALF = 3
        const val MIN_SECOND_CONFIDENCE = 0.5f
        /** "Ajr" -> "Air" is fine; a lone symbol like "V" (V0 in physics) is not a word. */
        const val MIN_SECOND_READING_LENGTH = 3
        const val SPLIT_COST_HALF = 1
        const val MAX_ACRONYM_LENGTH = 5
        const val SHORT_WORD_LENGTH = 5
        const val LOOKALIKE_HALF = 1
        val ROMAN_NUMERAL = Regex("^(?=[MDCLXVI]+$)M{0,3}(C[MD]|D?C{0,3})(X[CL]|L?X{0,3})(I[XV]|V?I{0,3})$")
        const val MIN_SPLIT_PART_LENGTH = 4
        val SHORT_SPLIT_WORDS = setOf("a")
        val DIGIT_LOOKALIKES = mapOf('0' to "o", '1' to "il", '5' to "s", '8' to "b")
        const val SENTENCE_ENDS = ".!?:"

        fun isSentenceStart(text: String, index: Int): Boolean {
            var i = index - 1
            while (i >= 0 && text[i].isWhitespace()) i--
            return i < 0 || text[i] in SENTENCE_ENDS
        }
    }
}
