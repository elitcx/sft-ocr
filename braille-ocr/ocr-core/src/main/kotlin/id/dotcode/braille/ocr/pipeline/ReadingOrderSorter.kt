package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.raw.RawLine
import kotlin.math.floor

/**
 * Stage 3. Column-major, then top to bottom, with lines banded into visual rows so that
 * baseline jitter does not reorder fragments that belong to the same printed row.
 *
 * The band is quantized rather than compared with a tolerance on purpose. A
 * tolerance-based comparator is not transitive, and Java's TimSort throws
 * IllegalArgumentException when it detects that.
 */
class ReadingOrderSorter(private val config: StructuringConfig) {

    fun sort(lines: List<RawLine>, columns: ColumnAssignment, stats: PageStats): List<OrderedLine> {
        if (lines.isEmpty()) return emptyList()
        val bandHeight = (stats.medianLineHeight * config.rowBandFactor).coerceAtLeast(1f)

        return lines
            .mapIndexed { index, l -> OrderedLine(l, columns.columnIndex.getOrElse(index) { 0 }) }
            .sortedWith(
                compareBy(
                    { it.columnIndex },
                    { floor(it.line.box.centerY / bandHeight).toInt() },
                    { it.line.box.left },
                )
            )
    }
}
