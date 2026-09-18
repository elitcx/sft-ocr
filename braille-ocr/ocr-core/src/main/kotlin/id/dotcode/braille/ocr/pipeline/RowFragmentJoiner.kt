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

        // Two fragments of one physical row are printed at the same size. A short label
        // sitting just above a much taller line of text can satisfy the vertical-overlap
        // and horizontal-gap checks purely by layout coincidence without being the same
        // row at all - see StructuringConfig.rowFragmentMaxHeightRatioFactor.
        val largerHeight = maxOf(a.line.box.height, b.line.box.height)
        if (largerHeight > smallerHeight * config.rowFragmentMaxHeightRatioFactor) return false

        val gap = b.line.box.left - a.line.box.right
        val gapTolerance = stats.medianCharWidth * config.rowFragmentGapFactor

        // `a` is spatially to the LEFT of `b` in the ordinary case, and `gap` is then the
        // true seam width between them - a slight negative value (a couple of pixels of
        // overlap) is a crease shifting the split, and is bounded below: a gap far more
        // negative than that means one fragment's x-range is substantially CONTAINED
        // within the other's, which is two lines stacked on different physical rows, not
        // two halves of one split row (see [StructuringConfig.rowFragmentMaxOverlapFactor]).
        //
        // But the straddling-band-boundary case (see [joinLines]) can hand this function
        // the physically-RIGHT fragment as `a` - reading order sorts by band before by
        // left, so a crease-clipped fragment's shifted centerY can put it in an earlier
        // band despite sitting to the right. `gap` computed as `b.left - a.right` is then
        // NOT a seam width at all; it is roughly the negative of both fragments' combined
        // width, which the lower bound would always reject. That reversed case is
        // detected here (`a` starts to the right of `b`) and exempted from the lower
        // bound - the upper `gapTolerance` check alone is what the pre-existing
        // reversed-order test relies on, and a large negative number always clears it.
        return if (a.line.box.left <= b.line.box.left) {
            val maxOverlap = stats.medianCharWidth * config.rowFragmentMaxOverlapFactor
            gap in -maxOverlap..gapTolerance
        } else {
            gap <= gapTolerance
        }
    }

    /**
     * Joins two same-row fragments in left-to-right reading order, NOT list order.
     *
     * [join] receives fragments in [ReadingOrderSorter] order, which sorts by visual band
     * before by `left` - so two fragments of one printed row that straddle a band boundary
     * (a crease clips one fragment's vertical extent, shifting its centerY into the
     * neighbouring band) can arrive with the right fragment first. Concatenating in list
     * order would then silently reverse the text. Sorting the pair by `box.left` here makes
     * the join correct regardless of what order the caller found them in.
     */
    private fun joinLines(first: RawLine, second: RawLine): RawLine {
        val a = if (first.box.left <= second.box.left) first else second
        val b = if (a === first) second else first
        val confidence = when {
            a.confidence != null && b.confidence != null -> (a.confidence + b.confidence) / 2f
            else -> a.confidence ?: b.confidence
        }
        val union = a.box.union(b.box)
        // The union box's HEIGHT is not used as-is. A crease that clips one fragment's
        // vertical extent (see the band-boundary case above) shifts that fragment's top
        // or bottom relative to the other, so the union spans more than either fragment's
        // true line height - e.g. two 15px-tall fragments whose creased vertical offset
        // makes their union 22px tall. That inflated height feeds RoleClassifier's local
        // baseline, and because a joined line is a SINGLE-line block, the "a multi-line
        // full-width block is body text" rule can never rescue it: a crease can silently
        // manufacture a HEADING out of ordinary body text. The joined box keeps the
        // union's horizontal extent (left/right) and vertical centre - both are still
        // correct positional signals - but its height is reset to the average of the two
        // fragments' own heights, which is what a reader's eye would call this line's
        // size.
        val typicalHeight = (a.box.height + b.box.height) / 2f
        val centerY = union.centerY
        val box = union.copy(top = centerY - typicalHeight / 2f, bottom = centerY + typicalHeight / 2f)
        return a.copy(
            text = "${a.text} ${b.text}",
            words = a.words + b.words,
            box = box,
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
