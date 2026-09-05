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

        val columnRightMargins = columnRightMargins(ordered, stats)

        val groups = mutableListOf<LineGroup>()
        var current = mutableListOf(ordered.first().line)
        var currentColumn = ordered.first().columnIndex

        for (i in 1 until ordered.size) {
            val prev = ordered[i - 1]
            val next = ordered[i]
            if (continues(prev, next, stats, columnRightMargins)) {
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

    /**
     * The right margin each column's lines are measured against, used to tell a
     * mid-paragraph sentence break (line reaches the margin) from a genuine paragraph
     * end (line stops short of it). Ordinarily this is the widest right edge seen in the
     * column, but a single anomalously long line - for example a spanning line pinned to
     * this column by [ColumnSegmenter] - would otherwise inflate the margin and make
     * every genuinely short line in the column look like it stops short by comparison.
     * When the widest line is far ahead of the next-widest one, the next-widest is
     * trusted instead.
     *
     * Exposed internally so [RoleClassifier] can reuse this exact margin — rather than
     * inventing a second way to compute it — to tell a wrapped, full-width body
     * paragraph from a short heading or caption.
     *
     * The outlier distance is measured in the PAGE-WIDE [PageStats.medianCharWidth],
     * the same unit [continues] uses for its own margin-shortfall tolerance. A
     * column-local character width would give a column with only a handful of lines
     * (or an unusually large/small font in one column) a different notion of "how far
     * is an outlier" than the rest of the page uses for "did this line reach the
     * margin" - two comparisons against the same margin value should use the same
     * ruler.
     */
    internal fun columnRightMargins(ordered: List<OrderedLine>, stats: PageStats): Map<Int, Float> =
        ordered.groupBy { it.columnIndex }.mapValues { (_, group) ->
            val rights = group.map { it.line.box.right }.sorted()
            val max = rights.last()
            if (rights.size >= 3) {
                val secondWidest = rights[rights.size - 2]
                if (max - secondWidest > stats.medianCharWidth * config.marginOutlierFactor) secondWidest else max
            } else {
                max
            }
        }

    private fun continues(
        prev: OrderedLine,
        next: OrderedLine,
        stats: PageStats,
        columnRightMargins: Map<Int, Float>,
    ): Boolean {
        if (prev.columnIndex != next.columnIndex) return false
        if (MarkerParser.parse(next.line.text) != null) return false
        if (TERMINAL_PUNCTUATION_REGEX.containsMatchIn(prev.line.text.trimEnd())) {
            val margin = columnRightMargins[prev.columnIndex] ?: prev.line.box.right
            val shortfall = margin - prev.line.box.right
            val tolerance = stats.medianCharWidth * config.lineEndToleranceFactor
            // Terminal punctuation only ends a block when the line ALSO stops short of
            // its column's right margin. A full-width line ending in a period is a
            // sentence boundary inside a wrap, not a paragraph end.
            if (shortfall > tolerance) return false
        }

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
