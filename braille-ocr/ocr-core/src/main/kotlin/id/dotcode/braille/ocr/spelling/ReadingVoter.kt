package id.dotcode.braille.ocr.spelling

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.raw.RawTextResult
import id.dotcode.braille.ocr.raw.RawWord
import kotlin.math.max
import kotlin.math.min

/** A word from the second recognizer, with its box in some image frame. */
data class SecondWord(val text: String, val box: BoxF, val confidence: Float? = null)

/**
 * Lines two independent recognizers up word by word. Each primary word gets the second
 * recognizer's text at the same spot in [RawWord.secondReading]; [SpellCorrector] then
 * trusts words both read identically and looks harder at the rest.
 *
 * Matching is by position, not text: a second word belongs to a primary word when most of
 * it lies inside that word on the same line. Several second words inside one primary word
 * are joined with spaces, which is how "tentangkagumi" learns it may be "tentang kagumi".
 *
 * Position only means something when both recognizers report in the same frame, and for a
 * rotated capture that is not guaranteed (see [annotateBestFrame]). Pairing words across
 * frames silently attaches other lines' words, so a pairing that mostly disagrees is
 * discarded rather than trusted.
 */
object ReadingVoter {

    private const val MIN_OVERLAP = 0.5f

    /** Below this share of agreeing pairs, the frames evidently do not line up. */
    internal const val MIN_ALIGNED_AGREEMENT = 0.4

    /**
     * Tries each candidate placement of the same second-recognizer words (e.g. boxes in the
     * upright frame, and the same boxes turned back to the capture frame) and keeps the one
     * where the two recognizers agree most. Returns [primary] unannotated if none lines up.
     */
    fun annotateBestFrame(primary: RawTextResult, candidates: List<List<SecondWord>>): RawTextResult {
        val best = candidates
            .filter { it.isNotEmpty() }
            .map { annotate(primary, it) }
            .maxByOrNull { agreement(it).agreeing }
            ?: return primary
        val score = agreement(best)
        val aligned = score.paired > 0 && score.agreeing >= MIN_ALIGNED_AGREEMENT * score.paired
        return if (aligned) best else primary
    }

    fun annotate(primary: RawTextResult, second: List<SecondWord>): RawTextResult {
        if (second.isEmpty()) return primary
        val usable = second.filter { it.text.isNotBlank() }
        return primary.copy(
            lines = primary.lines.map { line ->
                line.copy(
                    words = line.words.map { word ->
                        val matches = usable.filter { belongsTo(it.box, word.box) }
                        if (matches.isEmpty()) {
                            word
                        } else {
                            word.copy(
                                secondReading = inReadingOrder(matches, word),
                                secondConfidence = matches.mapNotNull { it.confidence }.minOrNull(),
                            )
                        }
                    },
                )
            },
        )
    }

    internal data class Agreement(val paired: Int, val agreeing: Int)

    internal fun agreement(annotated: RawTextResult): Agreement {
        val paired = annotated.lines.flatMap { it.words }.filter { it.secondReading != null }
        return Agreement(paired.size, paired.count { normalize(it.text) == normalize(it.secondReading!!) })
    }

    /**
     * Joins several second words found inside one primary word. Text may run along either
     * axis, in either direction, in a capture frame; the order whose letters spell the
     * primary word wins, falling back to left-to-right / top-to-bottom.
     */
    private fun inReadingOrder(matches: List<SecondWord>, word: RawWord): String {
        if (matches.size == 1) return matches.single().text.trim()
        val alongX = word.box.width >= word.box.height
        val forward = if (alongX) matches.sortedBy { it.box.left } else matches.sortedBy { it.box.top }
        val target = normalize(word.text)
        val order = listOf(forward, forward.reversed()).firstOrNull { candidate ->
            normalize(candidate.joinToString("") { it.text }) == target
        } ?: forward
        return order.joinToString(" ") { it.text.trim() }
    }

    private fun normalize(text: String) = text.filter { it.isLetterOrDigit() }.lowercase()

    /** True when most of [inner] sits inside [outer] across the line and they share a line. */
    internal fun belongsTo(inner: BoxF, outer: BoxF): Boolean {
        val overlapX = min(inner.right, outer.right) - max(inner.left, outer.left)
        val overlapY = min(inner.bottom, outer.bottom) - max(inner.top, outer.top)
        if (overlapX <= 0f || overlapY <= 0f) return false
        // In a quarter-turned frame a line runs vertically, so "along the line" is Y.
        val alongX = outer.width >= outer.height
        val along = if (alongX) overlapX to inner.width else overlapY to inner.height
        val across = if (alongX) overlapY to min(inner.height, outer.height) else overlapX to min(inner.width, outer.width)
        return along.first >= MIN_OVERLAP * along.second && across.first >= MIN_OVERLAP * across.second
    }
}
