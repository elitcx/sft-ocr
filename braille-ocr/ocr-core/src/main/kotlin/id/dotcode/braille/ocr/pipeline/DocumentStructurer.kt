package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.model.Alignment
import id.dotcode.braille.ocr.model.OcrDocument
import id.dotcode.braille.ocr.model.TextBlock
import id.dotcode.braille.ocr.model.TextLine
import id.dotcode.braille.ocr.model.Timings
import id.dotcode.braille.ocr.raw.RawTextResult
import kotlin.math.abs
import kotlin.math.roundToInt

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
        val ordered = readingOrderSorter.sort(lines, columns, stats)
        val joined = rowFragmentJoiner.join(ordered, stats)
        val groups = lineMerger.merge(joined, stats)
        val roles = roleClassifier.classify(groups, stats, deskewed.result.imageHeight)

        val blocks = groups.mapIndexed { index, group ->
            val reflowed = LineMerger.reflow(group.lines)
            val marker = MarkerParser.parse(reflowed)
            val columnBounds = columns.bounds.getOrElse(group.columnIndex) { columns.bounds.first() }
            TextBlock(
                id = index,
                role = roles[index],
                columnIndex = group.columnIndex,
                marker = marker?.marker,
                indentLevel = indentLevel(group, columnBounds.start, stats),
                alignment = alignment(group, columnBounds, stats),
                relativeTextHeight = roleClassifier.relativeHeight(group, stats),
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
            meanConfidence = averageConfidence(lines.mapNotNull { it.confidence }),
            timings = timings,
        )
    }

    private fun indentLevel(group: LineGroup, columnLeft: Float, stats: PageStats): Int {
        val quantum = (stats.medianCharWidth * config.indentQuantumFactor).coerceAtLeast(1f)
        val level = ((group.box.left - columnLeft) / quantum).roundToInt()
        return level.coerceIn(0, config.maxIndentLevel)
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
