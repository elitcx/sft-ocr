package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.raw.RawLine
import kotlin.math.min

/**
 * Stage 3.5. Joins ML Kit line fragments that are really one printed line split by a
 * physical defect in the photographed page - most often a fold or crease across a line of
 * text, which the recognizer reports as two adjacent fragments on the same visual row.
 *
 * Runs after [ReadingOrderSorter] (so fragments on the same row are consecutive in the
 * ordered list, sorted left to right within their column and band) and before
 * [LineMerger] (so paragraph merging never sees split fragments as separate lines).
 */
class RowFragmentJoiner(private val config: StructuringConfig) {

    fun join(ordered: List<OrderedLine>, stats: PageStats): List<OrderedLine> {
        if (ordered.size < 2) return ordered

        val result = mutableListOf<OrderedLine>()
        var current = ordered.first()

        for (i in 1 until ordered.size) {
            val next = ordered[i]
            current = if (sameRow(current, next, stats)) {
                OrderedLine(joinLines(current.line, next.line), current.columnIndex)
            } else {
                result.add(current)
                next
            }
        }
        result.add(current)
        return result
    }

    private fun sameRow(a: OrderedLine, b: OrderedLine, stats: PageStats): Boolean {
        if (a.columnIndex != b.columnIndex) return false

        val overlap = min(a.line.box.bottom, b.line.box.bottom) - maxOf(a.line.box.top, b.line.box.top)
        val smallerHeight = min(a.line.box.height, b.line.box.height)
        if (smallerHeight <= 0f || overlap <= smallerHeight * config.rowOverlapFraction) return false

        // A negative gap (slight overlap) counts as adjacent too.
        val gap = b.line.box.left - a.line.box.right
        val gapTolerance = stats.medianCharWidth * config.rowFragmentGapFactor
        return gap <= gapTolerance
    }

    private fun joinLines(a: RawLine, b: RawLine): RawLine {
        val confidence = when {
            a.confidence != null && b.confidence != null -> (a.confidence + b.confidence) / 2f
            else -> a.confidence ?: b.confidence
        }
        return a.copy(
            text = "${a.text} ${b.text}",
            box = a.box.union(b.box),
            // Dropped rather than carried through: the union box no longer corresponds
            // to either fragment's tilted quadrilateral, and there is no well-defined
            // quad for the union of two independently-recognized fragments. Producing
            // corner points that do not describe the joined box would silently corrupt
            // SkewEstimator, which trusts cornerPoints over box. This stage runs after
            // SkewEstimator's deskew pass, so dropping them here has no effect on skew
            // estimation.
            cornerPoints = emptyList(),
            confidence = confidence,
        )
    }
}
