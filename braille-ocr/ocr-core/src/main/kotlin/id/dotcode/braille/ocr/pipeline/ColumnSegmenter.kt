package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.raw.RawLine
import kotlin.math.abs

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
 * Spanning lines are excluded from gutter detection and then assigned to the leftmost
 * column they overlap.
 *
 * It also tells apart two columns of one flat sheet from two DIFFERENT physical surfaces
 * caught in the same frame - most commonly an open book's facing page, which reads as a
 * second "column" full of real, well-formed (but wrong-page) text that the line-count and
 * share checks above cannot catch. See [StructuringConfig.facingPageMaxAngleDiffDeg]'s
 * KDoc for the geometric signal and the real-corpus evidence behind it.
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

        // Column-bound lines go to the column containing their centre. Spanning lines go
        // to the LEFTMOST column they overlap - the column containing their left edge.
        //
        // Assigning a spanning line by its centre is a coin flip: on a symmetric
        // two-column page the gutter midpoint and a centred full-width title's centre
        // are the same point, so sub-pixel asymmetry decides the outcome. Landing in
        // column 1 makes ReadingOrderSorter (column-major) emit the page title AFTER
        // every left-column question, which is exactly the scrambled reading order this
        // stage exists to prevent. Leftmost-overlap is deterministic and puts a
        // full-width title ahead of all left-column content.
        //
        // WHAT THIS DOES NOT SOLVE: a full-width line in the MIDDLE of a two-column page
        // should precede the remaining content of BOTH columns. Pinning it to column 0
        // does not achieve that - it sorts among the left column's content and the right
        // column still runs past it. The real fix is a spanning-line ordering tier in
        // ReadingOrderSorter, where a spanning line splits the page into stacked column
        // groups ordered by vertical position. That is a change to the ordering model,
        // not to this assignment, and is deliberately not attempted here.
        val assignment = lines.map { l ->
            val x = if (l.box.width > spanThreshold) l.box.left else l.box.centerX
            boundaries.count { it < x }
        }
        val columnCount = boundaries.size + 1

        // Every column must be substantial, otherwise a stray page number or margin note
        // masquerades as a column. Counted over the column-bound lines only: a spanning
        // title is pinned to some column by the rule above and must not be able to prop
        // that column up on its own.
        val boundAssignment = columnBound.map { l -> boundaries.count { it < l.box.centerX } }
        val counts = (0 until columnCount).map { c -> boundAssignment.count { it == c } }
        val shareFloor = columnBound.size * config.minColumnLineShareFraction
        val populated = counts.all { it >= config.minLinesPerColumn && it >= shareFloor }
        if (!populated) {
            // A column failing the SHARE floor (not merely the absolute-count floor) is
            // clutter, not a legitimate small column - see StructuringConfig
            // .minColumnLineShareFraction. When exactly one other column clearly
            // dominates the page, drop the clutter column's lines outright: folding them
            // into the surviving column would still sort them into the reading order by
            // vertical position, scattering garbage text through the real content instead
            // of merely misreporting the column count.
            val clutterColumns = (0 until columnCount).filter { c -> counts[c] < shareFloor }
            val dominantColumns = (0 until columnCount).filterNot { it in clutterColumns }
            if (clutterColumns.isNotEmpty() && dominantColumns.size == 1) {
                val keep = dominantColumns.single()
                val keptIndices = lines.indices.filter { i -> assignment[i] == keep }
                val keptLines = keptIndices.map { lines[it] }
                if (keptLines.isNotEmpty() && keptLines.size < lines.size) {
                    val columnIndexOut = MutableList(lines.size) { -1 }
                    keptIndices.forEach { columnIndexOut[it] = 0 }
                    val bounds = listOf(keptLines.minOf { it.box.left }..keptLines.maxOf { it.box.right })
                    return ColumnAssignment(columnIndexOut, 1, bounds)
                }
            }
            return single
        }

        // Two populated, well-shared columns can still be two DIFFERENT physical surfaces
        // caught in one frame - most commonly an open book's facing page - rather than two
        // columns of the same flat sheet. Line count and share alone cannot tell these
        // apart (both surfaces can easily carry plenty of text), so this checks the one
        // thing a facing page cannot fake: its lines were photographed at a different
        // angle than the intended page. See StructuringConfig.facingPageMaxAngleDiffDeg's
        // KDoc for the real-corpus evidence behind this signal, and for why a matching
        // text-scale (line height) signal was tried and dropped rather than required
        // alongside it.
        if (columnCount == 2) {
            val angles = (0 until columnCount).map { c ->
                val columnLines = columnBound.filterIndexed { i, _ -> boundAssignment[i] == c }
                PageStats.median(columnLines.mapNotNull { angleFromCorners(it.cornerPoints) }) ?: 0f
            }
            // "Dominant" is decided by weight of text (line count), not position - the
            // facing page is not always on the same side of the frame.
            val dominant = if (counts[0] >= counts[1]) 0 else 1
            val other = 1 - dominant
            val angleDiff = abs(angles[dominant] - angles[other])
            if (angleDiff > config.facingPageMaxAngleDiffDeg) {
                val keptIndices = lines.indices.filter { i -> assignment[i] == dominant }
                val keptLines = keptIndices.map { lines[it] }
                if (keptLines.isNotEmpty() && keptLines.size < lines.size) {
                    val columnIndexOut = MutableList(lines.size) { -1 }
                    keptIndices.forEach { columnIndexOut[it] = 0 }
                    val bounds = listOf(keptLines.minOf { it.box.left }..keptLines.maxOf { it.box.right })
                    return ColumnAssignment(columnIndexOut, 1, bounds)
                }
            }
        }

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
