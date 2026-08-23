package id.dotcode.braille.ocr.pipeline

/**
 * Every tunable threshold in the structuring pipeline. Defaults were chosen for
 * A4 worksheets photographed at a ~1600px long edge.
 */
data class StructuringConfig(
    /** Below this many degrees, skew correction is skipped as noise. */
    val minSkewDeg: Float = 0.5f,
    /** A gutter wider than this many median character widths splits a column. */
    val columnGutterFactor: Float = 3.0f,
    /**
     * A line wider than this fraction of the page is treated as a spanning element — a
     * title, header or instruction sentence that crosses the gutter — and is excluded
     * from gutter detection. It is still assigned to a column afterwards by its centre.
     */
    val spanningLineWidthFraction: Float = 0.8f,
    /** A column must hold at least this many lines to be considered real. */
    val minLinesPerColumn: Int = 2,
    /** Below this many lines, never attempt column splitting. */
    val minLinesForColumnSplit: Int = 4,
    /** Row banding granularity for reading order, in median line heights. */
    val rowBandFactor: Float = 0.7f,
    /** Lines merge into one block when their gap is at most this many median line heights. */
    val paragraphGapFactor: Float = 1.6f,
    /** Left edges count as aligned within this many median character widths. */
    val leftAlignToleranceFactor: Float = 1.2f,
    /** A first-line indent may exceed the left-alignment tolerance by up to this factor. */
    val firstLineIndentFactor: Float = 4.0f,
    /** Relative text height above which a block is a HEADING. */
    val headingHeightRatio: Float = 1.25f,
    /** Relative text height above which a block is a TITLE. */
    val titleHeightRatio: Float = 1.6f,
    /** Relative text height below which a block is a CAPTION. */
    val captionHeightRatio: Float = 0.85f,
    /** Fraction of page height counted as the top band for TITLE promotion. */
    val topBandFraction: Float = 0.15f,
    /** Fraction of page height counted as the bottom band for PAGE_NUMBER. */
    val bottomBandFraction: Float = 0.92f,
    /** Numeric-marker blocks become QUESTION only once the page holds this many. */
    val minNumberedBlocksForQuestions: Int = 3,
    /** Indent quantum, in median character widths. */
    val indentQuantumFactor: Float = 2.0f,
    /** Max indent level reported. */
    val maxIndentLevel: Int = 4,
    /** Centering tolerance as a fraction of column width. */
    val centerToleranceFraction: Float = 0.05f,
    /** A block must leave this fraction of the column free on the left to count as centered or right-aligned. */
    val minSideMarginFraction: Float = 0.15f,
)
