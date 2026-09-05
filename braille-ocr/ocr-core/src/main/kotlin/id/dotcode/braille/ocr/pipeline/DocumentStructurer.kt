package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.model.Alignment
import id.dotcode.braille.ocr.model.OcrDocument
import id.dotcode.braille.ocr.model.TextBlock
import id.dotcode.braille.ocr.model.TextLine
import id.dotcode.braille.ocr.model.Timings
import id.dotcode.braille.ocr.raw.RawTextResult
import kotlin.math.abs

/**
 * Runs the six structuring stages in order and assembles the output document.
 *
 * This is the only public entry point of :ocr-core. Everything above it is an
 * implementation detail, which is what lets the ML Kit adapter be replaced by a
 * fine-tuned recognizer in a later phase without any downstream change.
 */
class DocumentStructurer(private val config: StructuringConfig = StructuringConfig()) {

    private val skewEstimator = SkewEstimator(config)
    private val columnSegmenter = ColumnSegmenter(config)
    private val readingOrderSorter = ReadingOrderSorter(config)
    private val rowFragmentJoiner = RowFragmentJoiner(config)
    private val lineMerger = LineMerger(config)
    private val roleClassifier = RoleClassifier(config)

    fun structure(raw: RawTextResult, timings: Timings = Timings()): OcrDocument {
        val usable = raw.copy(lines = raw.lines.filter { it.text.isNotBlank() })
        if (usable.lines.isEmpty()) {
            return OcrDocument(
                pageWidth = raw.imageWidth,
                pageHeight = raw.imageHeight,
                skewDeg = 0f,
                columnCount = 1,
                blocks = emptyList(),
                meanConfidence = null,
                timings = timings,
            )
        }

        val deskewed = skewEstimator.deskew(usable)
        val lines = deskewed.result.lines
        val stats = PageStats.from(lines)
        val columns = columnSegmenter.segment(lines, stats, deskewed.result.imageWidth)

        // A -1 entry marks a line ColumnSegmenter identified as clutter from outside the
        // page's real column(s) - see ColumnAssignment's KDoc. It must be excluded here,
        // not merely left out of column numbering: sorting it into the reading order by
        // vertical position would scatter garbage text through the real content.
        val keptIndices = lines.indices.filter { columns.columnIndex.getOrElse(it) { 0 } >= 0 }
        val effectiveLines = if (keptIndices.size == lines.size) lines else keptIndices.map { lines[it] }
        val effectiveColumns = if (keptIndices.size == lines.size) {
            columns
        } else {
            ColumnAssignment(keptIndices.map { columns.columnIndex[it] }, columns.columnCount, columns.bounds)
        }

        val ordered = readingOrderSorter.sort(effectiveLines, effectiveColumns, stats)
        val joined = rowFragmentJoiner.join(ordered, stats)
        val columnRightMargins = lineMerger.columnRightMargins(joined, stats)
        val groups = lineMerger.merge(joined, stats)
        val roles = roleClassifier.classify(groups, stats, deskewed.result.imageHeight, columnRightMargins, columns.bounds)
        val localBaselines = roleClassifier.localBaselines(groups, stats)

        val blocks = groups.mapIndexed { index, group ->
            val reflowed = LineMerger.reflow(group.lines)
            val marker = MarkerParser.parse(reflowed)
            val columnBounds = columns.bounds.getOrElse(group.columnIndex) { columns.bounds.first() }
            TextBlock(
                id = index,
                role = roles[index],
                columnIndex = group.columnIndex,
                marker = marker?.marker,
                indentLevel = roleClassifier.indentLevelOf(group, columnBounds.start, stats),
                alignment = alignment(group, columnBounds, stats),
                relativeTextHeight = roleClassifier.relativeHeight(group, localBaselines[index]),
                text = marker?.remainder ?: reflowed,
                lines = group.lines.map {
                    TextLine(it.text, it.box, it.confidence, it.recognizedLanguage)
                },
                box = group.box,
                confidence = averageConfidence(group.lines.mapNotNull { it.confidence }),
            )
        }

        return OcrDocument(
            pageWidth = deskewed.result.imageWidth,
            pageHeight = deskewed.result.imageHeight,
            skewDeg = deskewed.skewDeg,
            columnCount = columns.columnCount,
            blocks = blocks,
            meanConfidence = averageConfidence(effectiveLines.mapNotNull { it.confidence }),
            timings = timings,
        )
    }

    /**
     * The side-margin guard deliberately runs *before* the centre check, not after. A
     * block that fills most of its column (small margins on both sides) is visually
     * indistinguishable from full-width or justified text — there is no reliable signal
     * left to tell "centered and just happens to be wide" apart from "not centered at
     * all." Reporting such a block as CENTER would be a false positive the braille
     * renderer would act on, so any block that does not clear [StructuringConfig
     * .minSideMarginFraction] on the left is short-circuited to LEFT regardless of how
     * close its midpoint sits to the column centre. Do not reorder these checks.
     */
    private fun alignment(
        group: LineGroup,
        columnBounds: ClosedFloatingPointRange<Float>,
        stats: PageStats,
    ): Alignment {
        val columnWidth = columnBounds.endInclusive - columnBounds.start
        if (columnWidth <= 0f) return Alignment.LEFT

        val leftMargin = group.box.left - columnBounds.start
        val rightMargin = columnBounds.endInclusive - group.box.right
        val minMargin = columnWidth * config.minSideMarginFraction
        if (leftMargin < minMargin) return Alignment.LEFT

        val columnCenter = (columnBounds.start + columnBounds.endInclusive) / 2f
        val centerTolerance = columnWidth * config.centerToleranceFraction
        return when {
            abs(group.box.centerX - columnCenter) <= centerTolerance -> Alignment.CENTER
            rightMargin < minMargin -> Alignment.RIGHT
            else -> Alignment.LEFT
        }
    }

    private fun averageConfidence(values: List<Float>): Float? =
        if (values.isEmpty()) null else values.sum() / values.size
}
