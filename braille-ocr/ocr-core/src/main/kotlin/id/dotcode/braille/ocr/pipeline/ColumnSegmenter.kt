package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.raw.RawLine

/**
 * Stage 2. Finds column gutters with a vertical projection profile over line x-ranges.
 *
 * This stage exists because a two-column worksheet processed without it interleaves the
 * left and right columns line by line, producing text that is not merely misformatted
 * but semantically scrambled. It is deliberately conservative: a wrong split is far more
 * damaging than a missed one, so ambiguous pages collapse to a single column.
 *
 * Only *column-bound* lines inform the profile. A line wider than
 * [StructuringConfig.spanningLineWidthFraction] of the page — a title, a running header,
 * an instruction sentence — crosses the gutter by construction and therefore carries no
 * information about where the gutter is. Feeding it into the interval merge unions the
 * left and right runs into one and collapses the page to a single column, which is what
 * used to happen on essentially every real worksheet (they all have a full-width title).
 * Spanning lines are excluded from gutter detection and then assigned to a column by
 * their centre like everything else.
 */
class ColumnSegmenter(private val config: StructuringConfig) {

    /**
     * @param pageWidth width of the page in the same coordinate space as the line boxes.
     * Used only to decide which lines are full-width spanning elements. A non-positive
     * value falls back to the observed extent of the lines themselves.
     */
    fun segment(lines: List<RawLine>, stats: PageStats, pageWidth: Int): ColumnAssignment {
        val single = singleColumn(lines)
        if (lines.size < config.minLinesForColumnSplit) return single

        val effectiveWidth =
            if (pageWidth > 0) pageWidth.toFloat()
            else (lines.maxOf { it.box.right } - lines.minOf { it.box.left })
        val spanThreshold = effectiveWidth * config.spanningLineWidthFraction

        // Lines that stay inside one column are the only evidence of column structure.
        val columnBound = lines.filter { it.box.width <= spanThreshold }
        if (columnBound.size < config.minLinesForColumnSplit) return single

        val runs = mergeIntervals(columnBound.map { it.box.left to it.box.right })
        if (runs.size < 2) return single

        val threshold = stats.medianCharWidth * config.columnGutterFactor
        val boundaries = runs.zipWithNext()
            .filter { (a, b) -> b.first - a.second >= threshold }
            .map { (a, b) -> (a.second + b.first) / 2f }
        if (boundaries.isEmpty()) return single

        val assignment = lines.map { l -> boundaries.count { it < l.box.centerX } }
        val columnCount = boundaries.size + 1

        // Every column must be substantial, otherwise a stray page number or margin note
        // masquerades as a column. Counted over the column-bound lines only: a spanning
        // title lands in one column by centre and must not be able to prop it up.
        val boundAssignment = columnBound.map { l -> boundaries.count { it < l.box.centerX } }
        val populated = (0 until columnCount).all { c ->
            boundAssignment.count { it == c } >= config.minLinesPerColumn
        }
        if (!populated) return single

        // Column extents come from the column-bound lines too, so a spanning title does
        // not stretch a column's reported bounds across the whole page and skew the
        // downstream indent and alignment measurements.
        val bounds = (0 until columnCount).map { c ->
            val boxes = columnBound.filterIndexed { i, _ -> boundAssignment[i] == c }.map { it.box }
            boxes.minOf { it.left }..boxes.maxOf { it.right }
        }
        return ColumnAssignment(assignment, columnCount, bounds)
    }

    private fun singleColumn(lines: List<RawLine>): ColumnAssignment {
        val bounds = if (lines.isEmpty()) listOf(0f..0f)
        else listOf(lines.minOf { it.box.left }..lines.maxOf { it.box.right })
        return ColumnAssignment(List(lines.size) { 0 }, 1, bounds)
    }

    private fun mergeIntervals(intervals: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val sorted = intervals.sortedBy { it.first }
        val merged = mutableListOf<Pair<Float, Float>>()
        for ((left, right) in sorted) {
            val last = merged.lastOrNull()
            if (last != null && left <= last.second) {
                merged[merged.lastIndex] = last.first to maxOf(last.second, right)
            } else {
                merged.add(left to right)
            }
        }
        return merged
    }
}
