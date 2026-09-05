package id.dotcode.braille.ocr.pipeline

enum class MarkerKind { NUMERIC, ALPHA, ROMAN, BULLET }

data class ParsedMarker(val marker: String, val kind: MarkerKind, val remainder: String)

/**
 * Stage 5. Splits a leading list or question marker away from the text body.
 *
 * This is what makes per-question answer tracking possible downstream: question 7 becomes
 * an addressable object rather than a substring the answer recorder has to hunt for.
 *
 * Order matters — roman numerals are tested before single letters so that "i." and "v."
 * are not misread as alphabetic markers.
 */
object MarkerParser {

    private val ROMAN = Regex("^([ivxlIVXL]{1,6})[.)]\\s+(\\S.*)$")

    // The space after the marker punctuation is normally required, but ML Kit routinely
    // drops it on real photographed worksheets ("1.Providing free nutritious meals..."),
    // so the space is made optional when the character immediately following the
    // punctuation is a LETTER. It stays mandatory when that character is anything else,
    // which is what keeps "3.14 adalah nilai pi" from being misread as marker "3." - '1'
    // is not a letter, so the no-space branch never fires and the mandatory-space branch
    // fails on the missing space, exactly as before.
    private val NUMERIC = Regex("^(\\d{1,3})[.)](?:\\s+|(?=[A-Za-z]))(\\S.*)$")
    private val ALPHA = Regex("^([a-zA-Z])[.)]\\s+(\\S.*)$")
    private val BULLET = Regex("^([•·●*\\-–])\\s+(\\S.*)$")

    fun parse(text: String): ParsedMarker? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        ROMAN.matchEntire(trimmed)?.let { m ->
            val token = m.groupValues[1]
            // "i" and "x" are also valid single letters; treat length-1 as roman only for i, v, x.
            if (token.length > 1 || token.lowercase() in setOf("i", "v", "x")) {
                return ParsedMarker(token + trimmed[token.length], MarkerKind.ROMAN, m.groupValues[2])
            }
        }
        NUMERIC.matchEntire(trimmed)?.let { m ->
            val token = m.groupValues[1]
            return ParsedMarker(token + trimmed[token.length], MarkerKind.NUMERIC, m.groupValues[2])
        }
        ALPHA.matchEntire(trimmed)?.let { m ->
            val token = m.groupValues[1]
            return ParsedMarker(token + trimmed[token.length], MarkerKind.ALPHA, m.groupValues[2])
        }
        BULLET.matchEntire(trimmed)?.let { m ->
            return ParsedMarker(m.groupValues[1], MarkerKind.BULLET, m.groupValues[2])
        }
        return null
    }
}
