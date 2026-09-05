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
    /**
     * A column must also hold at least this fraction of the page's column-bound lines to
     * be considered real, in addition to [minLinesPerColumn]. A handful of fragments from
     * background clutter in the photographed frame - most commonly a second sheet lying
     * underneath the worksheet, partially visible at a frame edge - can satisfy the
     * absolute line-count floor while being a vanishingly small share of the page's real
     * content, which [minLinesPerColumn] alone cannot catch: five clutter fragments comfortably
     * clear a floor of two. A share floor does, without needing an absolute count so high it
     * misfires on a genuinely short second column on a real two-column worksheet.
     *
     * A column failing ONLY this share floor (not [minLinesPerColumn]) is treated as
     * clutter: when exactly one other column clearly dominates the page, that column's
     * lines are dropped from the document entirely rather than folded into the survivor,
     * where sorting them by vertical position would scatter garbage text throughout the
     * real content instead of merely misreporting the column count. A column failing only
     * the absolute-count floor (but still holding a fair share) is NOT clutter by this
     * signal - it collapses the page to a single column as before, but nothing is
     * discarded, since a small legitimate column is a very different situation from a
     * different sheet of paper in the frame.
     */
    val minColumnLineShareFraction: Float = 0.15f,
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
    /** Relative text height below which a block is a CAPTION candidate (also see [captionMinShortfallFactor]). */
    val captionHeightRatio: Float = 0.85f,
    /**
     * Number of neighbouring blocks, on each side in reading order, averaged into a
     * block's local height baseline. A handheld photo has a smooth perspective gradient
     * down the page, so comparing a block's height to nearby blocks (rather than the
     * whole page's median) cancels that gradient while still catching a genuinely larger
     * heading sitting among normal text.
     */
    val localHeightWindowSize: Int = 5,
    /**
     * A local baseline is only trusted when the block has at least this many actual
     * NEIGHBOURS in its window (the block itself does not count). Below this, there is
     * not enough local context to cancel perspective distortion, so the classifier falls
     * back to the whole page's median line height instead. This matters most at the top
     * and bottom of the page, where the window can only extend to one side.
     */
    val minNeighboursForLocalBaseline: Int = 3,
    /**
     * A block only qualifies as a CAPTION when it also stops at least this many median
     * character widths short of its column's right margin. A short block that still
     * reaches the margin is body text that merely happens to be brief, not a caption.
     */
    val captionMinShortfallFactor: Float = 4.0f,
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
    /** A line ending this many median character widths short of its column's right margin is a paragraph end, not a wrap. */
    val lineEndToleranceFactor: Float = 3.0f,
    /**
     * How far ahead of the column's second-widest line (in page-wide median character
     * widths - [PageStats.medianCharWidth], the same unit [lineEndToleranceFactor] uses)
     * the single widest line must sit before [LineMerger.columnRightMargins] treats it as
     * an outlier and reports the second-widest line as the column's right margin instead.
     *
     * This is a semantically distinct quantity from [lineEndToleranceFactor] - one
     * measures a distance FROM a known margin to decide "did this line reach it", the
     * other measures a gap BETWEEN two candidate margins to decide "which one is real" -
     * even though both used to reuse the same field and the same (inconsistent) units
     * before this was split out. A spanning line pinned into a column by [ColumnSegmenter]
     * is the usual cause of such an outlier: it stretches far past every genuine line in
     * the column and must not be allowed to inflate the margin every other line is judged
     * against.
     */
    val marginOutlierFactor: Float = 3.0f,
    /** Two fragments on the same visual row join when their horizontal gap is below this many median character widths. */
    val rowFragmentGapFactor: Float = 2.5f,
    /**
     * The most a second same-row fragment's left edge may sit BEHIND the first fragment's
     * right edge (a negative horizontal gap) and still be treated as a crease-shifted
     * split of one printed row, in median character widths. A genuine crease split only
     * ever overlaps by a couple of pixels at the seam.
     *
     * Without this bound, a large negative gap - one fragment's x-range almost entirely
     * contained within the other's - passed the old "negative gap counts as adjacent"
     * rule unconditionally. That is not a split row at all: it is two lines stacked on
     * different physical rows whose bounding boxes merely happen to overlap vertically,
     * for example a short "Reason: ____" answer line printed with tight leading right
     * above the next numbered question. Joining them concatenates the label onto the
     * following question's text, burying that question's marker mid-string where
     * [MarkerParser] can never find it.
     */
    val rowFragmentMaxOverlapFactor: Float = 1.5f,
    /**
     * The most the taller of two candidate same-row fragments' heights may exceed the
     * shorter one's, as a ratio, and still be treated as one crease-split printed row.
     * Two fragments of the SAME physical line are printed at the same font size and
     * therefore measure similar heights - a crease shifting one fragment's vertical
     * extent by a few pixels does not change that. A short label like "Reason:" sitting
     * just above a much taller multi-word question line can satisfy both the vertical-overlap
     * and horizontal-gap checks purely by coincidence of layout, without being anywhere
     * near the same printed row; its height being a fraction of the other fragment's is
     * the tell. Genuine crease splits observed in real captures stay well under 1.7x; this
     * leaves headroom above that while still catching a same-row false positive whose
     * height ratio runs past 2x.
     */
    val rowFragmentMaxHeightRatioFactor: Float = 2.0f,
    /** Two lines are on the same visual row when their vertical extents overlap by more than this fraction of the smaller height. */
    val rowOverlapFraction: Float = 0.5f,
    /**
     * How many blocks back, in reading order, [RoleClassifier] looks for a preceding
     * QUESTION at the same indent level before letting a height-driven TITLE/HEADING
     * promotion stand. A block sitting in an already-established run of numbered
     * questions is itself body text (an exam question, or one OCR failed to recognize a
     * marker for), never a document title or section heading, no matter how tall
     * perspective or a recognizer quirk makes it measure.
     *
     * The window must be wider than 1: a "Reason: ______" answer line commonly sits
     * between one question and the next, so the nearest QUESTION predecessor is often two
     * blocks back, not one. A window of 2 reaches past exactly one such intervening block
     * without reaching so far that it starts pulling in unrelated headings from a
     * different section of the page. It is deliberately backward-only - see
     * [RoleClassifier]'s use of it - so a worksheet's own title, sitting immediately
     * above its first question, is never demoted merely because a question run starts
     * right after it.
     */
    val questionRunAdjacencyWindow: Int = 2,
)
