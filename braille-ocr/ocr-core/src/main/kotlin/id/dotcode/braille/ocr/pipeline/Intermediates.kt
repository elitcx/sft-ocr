package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.raw.RawLine

/** A line with its resolved column, produced by ColumnSegmenter and consumed by ReadingOrderSorter. */
data class OrderedLine(val line: RawLine, val columnIndex: Int)

/** Consecutive lines that LineMerger decided form one block. */
data class LineGroup(val lines: List<RawLine>, val columnIndex: Int) {
    val box: BoxF get() = BoxF.enclosing(lines.map { it.box })
}

/**
 * Column layout of a page. [columnIndex] is parallel to the input line list.
 *
 * A value of -1 marks a line as DROPPED - clutter [ColumnSegmenter] identified as
 * belonging to no real column of this page (see [StructuringConfig.minColumnLineShareFraction])
 * and that the caller must exclude from the document entirely, not merely from column
 * assignment.
 */
data class ColumnAssignment(
    val columnIndex: List<Int>,
    val columnCount: Int,
    /** Horizontal extent of each column, indexed by column. */
    val bounds: List<ClosedFloatingPointRange<Float>>,
)

/** Page-level scale references. Everything downstream measures distances in these units. */
data class PageStats(val medianLineHeight: Float, val medianCharWidth: Float) {
    companion object {
        fun from(lines: List<RawLine>): PageStats {
            val heights = lines.map { it.box.height }.filter { it > 0f }
            val charWidths = lines
                .filter { it.text.isNotBlank() && it.box.width > 0f }
                .map { it.box.width / it.text.length }
            return PageStats(
                medianLineHeight = median(heights) ?: 1f,
                medianCharWidth = median(charWidths) ?: 1f,
            )
        }

        internal fun median(values: List<Float>): Float? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
        }
    }
}
