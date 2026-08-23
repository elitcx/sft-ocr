package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.raw.RawLine
import kotlin.math.abs

/**
 * Stage 4. Groups consecutive lines into blocks.
 *
 * Two lines merge only when they share a column, sit within a paragraph-sized vertical
 * gap, have aligned left edges (or a consistent first-line indent), the earlier line does
 * not end in terminal punctuation, and the later line does not open a new marker.
 */
class LineMerger(private val config: StructuringConfig) {

    fun merge(ordered: List<OrderedLine>, stats: PageStats): List<LineGroup> {
        if (ordered.isEmpty()) return emptyList()

        val groups = mutableListOf<LineGroup>()
        var current = mutableListOf(ordered.first().line)
        var currentColumn = ordered.first().columnIndex

        for (i in 1 until ordered.size) {
            val prev = ordered[i - 1]
            val next = ordered[i]
            if (continues(prev, next, stats)) {
                current.add(next.line)
            } else {
                groups.add(LineGroup(current.toList(), currentColumn))
                current = mutableListOf(next.line)
                currentColumn = next.columnIndex
            }
        }
        groups.add(LineGroup(current.toList(), currentColumn))
        return groups
    }

    private fun continues(prev: OrderedLine, next: OrderedLine, stats: PageStats): Boolean {
        if (prev.columnIndex != next.columnIndex) return false
        if (MarkerParser.parse(next.line.text) != null) return false
        if (TERMINAL_PUNCTUATION_REGEX.containsMatchIn(prev.line.text.trimEnd())) return false

        val gap = next.line.box.top - prev.line.box.bottom
        if (gap > stats.medianLineHeight * config.paragraphGapFactor) return false
        if (gap < -stats.medianLineHeight) return false // overlapping rows are not a wrap

        val indentTolerance = stats.medianCharWidth * config.leftAlignToleranceFactor
        val leftDelta = abs(next.line.box.left - prev.line.box.left)
        // A first-line indent means the CONTINUATION sits further left than the opener.
        val isIndentedOpener =
            prev.line.box.left - next.line.box.left in 0f..(indentTolerance * config.firstLineIndentFactor)
        return leftDelta <= indentTolerance || isIndentedOpener
    }

    companion object {
        private val TERMINAL_PUNCTUATION_REGEX = Regex("[.!?:;]$")
        private val HYPHEN_WRAP = Regex("[-‐‑]$")

        /** Joins block lines into one string, collapsing hyphenated wraps. */
        fun reflow(lines: List<RawLine>): String {
            if (lines.isEmpty()) return ""
            val builder = StringBuilder(lines.first().text.trim())
            for (i in 1 until lines.size) {
                val text = lines[i].text.trim()
                if (HYPHEN_WRAP.containsMatchIn(builder)) {
                    builder.deleteCharAt(builder.lastIndex)
                    builder.append(text)
                } else {
                    builder.append(' ').append(text)
                }
            }
            return builder.toString()
        }
    }
}
