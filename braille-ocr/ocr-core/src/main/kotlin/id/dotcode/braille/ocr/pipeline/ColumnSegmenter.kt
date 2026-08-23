package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.raw.RawLine

/**
 * Stage 2. Finds column gutters with a vertical projection profile over line x-ranges.
 *
 * This stage exists because a two-column worksheet processed without it interleaves the
 * left and right columns line by line, producing text that is not merely misformatted
 * but semantically scrambled. It is deliberately conservative: a wrong split is far more
 * damaging than a missed one, so ambiguous pages collapse to a single column.
 */
class ColumnSegmenter(private val config: StructuringConfig) {

    fun segment(lines: List<RawLine>, stats: PageStats): ColumnAssignment {
        val single = singleColumn(lines)
        if (lines.size < config.minLinesForColumnSplit) return single

        val runs = mergeIntervals(lines.map { it.box.left to it.box.right })
        if (runs.size < 2) return single

        val threshold = stats.medianCharWidth * config.columnGutterFactor
        val boundaries = runs.zipWithNext()
            .filter { (a, b) -> b.first - a.second >= threshold }
            .map { (a, b) -> (a.second + b.first) / 2f }
        if (boundaries.isEmpty()) return single

        val assignment = lines.map { l -> boundaries.count { it < l.box.centerX } }
        val columnCount = boundaries.size + 1

        // Every column must be substantial, otherwise a stray page number or margin note
        // masquerades as a column.
        val populated = (0 until columnCount).all { c -> assignment.count { it == c } >= config.minLinesPerColumn }
        if (!populated) return single

        val bounds = (0 until columnCount).map { c ->
            val boxes = lines.filterIndexed { i, _ -> assignment[i] == c }.map { it.box }
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
