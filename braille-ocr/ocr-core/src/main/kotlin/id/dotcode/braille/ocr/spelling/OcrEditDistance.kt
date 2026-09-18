package id.dotcode.braille.ocr.spelling

import kotlin.math.abs

/**
 * Damerau-Levenshtein (optimal string alignment) distance, weighted for the mistakes a text
 * recognizer actually makes. Costs are in half-units so the arithmetic stays integral:
 *
 * - insert, delete, substitute, swap adjacent letters: 2
 * - substitute a look-alike pair (`i`/`l`, `e`/`c`, `o`/`c`, ...): 1
 * - read two letters as one or one as two (`rn`/`m`, `cl`/`d`, `vv`/`w`): 1
 * - drop or add one letter of a double (`mentaly`/`mentally`): 1
 *
 * So "sekolah" vs "sekclah" is half an edit while "sekolah" vs "sekelah" is a whole one: a
 * recognizer's confusion is the likelier explanation, and the ranking should say so.
 */
internal object OcrEditDistance {

    private val LOOKALIKES: Set<String> = setOf("il", "ij", "lt", "ec", "oc", "uv", "hb", "gq")
        .flatMap { listOf(it, it.reversed()) }
        .toSet()

    /** Two-letter sequences a recognizer reads as the single letter they map to, and back. */
    private val MERGES: Map<String, Char> = mapOf("rn" to 'm', "cl" to 'd', "vv" to 'w')

    /**
     * The weighted distance between [a] and [b] in half-units, or any value above [maxHalf]
     * once it is certain to exceed it (the exact overshoot is not computed).
     */
    fun halfUnits(a: String, b: String, maxHalf: Int): Int {
        val n = a.length
        val m = b.length
        if (abs(n - m) > maxHalf) return maxHalf + 1
        val d = Array(n + 1) { IntArray(m + 1) }
        for (j in 0..m) d[0][j] = 2 * j
        var previousRowMin = 0
        for (i in 1..n) {
            d[i][0] = 2 * i
            var rowMin = d[i][0]
            for (j in 1..m) {
                val ca = a[i - 1]
                val cb = b[j - 1]
                var v = d[i - 1][j - 1] + substitution(ca, cb)
                // Dropping or doubling one letter of a double ("mentaly") is a look-alike too.
                val deleteCost = if (i > 1 && a[i - 2] == ca) 1 else 2
                val insertCost = if (j > 1 && b[j - 2] == cb) 1 else 2
                v = minOf(v, d[i - 1][j] + deleteCost, d[i][j - 1] + insertCost)
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) v = minOf(v, d[i - 2][j - 2] + 2)
                if (i > 1 && MERGES["${a[i - 2]}$ca"] == cb) v = minOf(v, d[i - 2][j - 1] + 1)
                if (j > 1 && MERGES["${b[j - 2]}$cb"] == ca) v = minOf(v, d[i - 1][j - 2] + 1)
                d[i][j] = v
                if (v < rowMin) rowMin = v
            }
            // A merge reaches back two rows, so only two hopeless rows in a row prove the rest is.
            if (rowMin > maxHalf && previousRowMin > maxHalf) return maxHalf + 1
            previousRowMin = rowMin
        }
        return d[n][m]
    }

    private fun substitution(a: Char, b: Char): Int = when {
        a == b -> 0
        "$a$b" in LOOKALIKES -> 1
        else -> 2
    }
}
