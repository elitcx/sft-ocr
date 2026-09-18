package id.dotcode.braille.ocr.pipeline

/**
 * Every tunable threshold in the structuring pipeline. Defaults were chosen for
 * A4 worksheets photographed at a ~1600px long edge.
 */
data class StructuringConfig(
    /** Below this many degrees, skew correction is skipped as noise. */
    val minSkewDeg: Float = 0.5f,
    /**
     * A page's median line angle beyond this many degrees, measured right after the
     * EXIF-declared rotation is applied, is not genuine camera tilt — no handheld
     * worksheet or book-page photo tilts anywhere near this far — and instead means the
     * EXIF orientation tag itself was wrong by a quarter turn. See
     * [RotationPlausibilityGuard]. Evidence: 5 real corpus photos tagged EXIF
     * orientation 3 (180 degrees) measured ~83-93 degrees of residual angle after that
     * declared rotation was applied; their true physical rotation, confirmed by
     * inspecting the raw pixels, was 90 degrees. 45 sits well below that evidenced
     * failure range and well above [minSkewDeg]'s few-degrees noise floor and any
     * plausible genuine camera tilt.
     */
    val maxPlausibleSkewDeg: Float = 45f,
    /**
     * A line narrower than this many median character widths is a candidate isolated
     * margin fragment for [MarginFragmentFilter] — never a real multi-word sentence.
     * Evidence: the 7 dropped margin-noise fragments on a real curved book page
     * (fixture `real-rotated-230941-raw.json`) measured under 5 median character
     * widths each. 8 stays comfortably above that while still excluding every real
     * short line on the same page this project must not drop — "belajar sendiri."
     * (13.9), "Sisi Tuhan." (9.2), "Angela bahwa" (13.9), "tekun berdoa" (11.4) all
     * measure wider than 8; only the genuine page-number block ("[6]", 2.8) is
     * narrower, and that block is correctly kept anyway because it fails the position
     * check below (it sits well inside the body's margin, not past it).
     */
    val marginFragmentMaxWidthFactor: Float = 8.0f,
    /** See [FrameEdgeFragmentFilter]: how close to the frame's side a line must reach. */
    val frameEdgeMarginFraction: Float = 0.015f,
    /** See [FrameEdgeFragmentFilter]: edge-touching lines at least this wide are kept. */
    val frameEdgeMaxWidthFraction: Float = 0.35f,
    /** See [FrameEdgeFragmentFilter]: this many cut-off lines on one side mark a facing page. */
    val frameEdgeMinStack: Int = 3,
    /** See [FrameEdgeFragmentFilter]: a lone cut-off line is dropped only below this confidence. */
    val frameEdgeLoneMaxConfidence: Float = 0.7f,
    /**
     * A candidate margin fragment must sit at least this many median character widths
     * to the right of the rightmost edge any non-narrow ("body") line reaches anywhere
     * on the page to count as isolated, for [MarginFragmentFilter]. Evidence: on the
     * same real fixture, 7 of the 8 observed fragments cleared this gap by 14-64
     * pixels (1.1-4.8 median character widths); the 8th ("fogpro") cleared it by only
     * 0.6 pixels and is deliberately left alone — a gap that thin is genuine ambiguity,
     * not isolation, and guessing wrong here corrupts real text, the worst outcome
     * under this project's governing priority.
     */
    val marginFragmentGapFactor: Float = 1.0f,
    /**
     * A candidate margin fragment must also measure recognizer confidence below this
     * value for [MarginFragmentFilter] to drop it. Evidence: all 7 dropped fragments on
     * the real fixture measured 0.28-0.69; every real body line on the same page
     * measured 0.76 or above. Confidence alone is not a safe signal on its own — the
     * page's own genuine page-number block measured a LOWER confidence (0.49) than
     * several of the dropped fragments — so this is required together with the width
     * and position checks above, never in place of them.
     */
    val marginFragmentMaxConfidence: Float = 0.7f,
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
    /**
     * A block may only be promoted to TITLE or HEADING by height when it has at most
     * this many words. Above this, it is prose, no matter how tall perspective made it
     * measure.
     *
     * Chosen from real-fixture evidence (real-worksheet-exercises.json): a vocabulary
     * table's Definition cells were promoted to TITLE/HEADING purely by height - e.g.
     * "extremety careful, thorough, and exacting people responsible for developing or
     * deciding public policies" (13 words) and "the process of putting a plan, policy,
     * or systerm into action in a way that can continue over the long term without
     * exhausting resources" (24 words). Both are ordinary sentence-length definitions,
     * not headings. 9 is the word count of the real-worksheet-reading.json fixture's
     * own genuine heading, "Should the Government Provide Free Nutritious Meals for
     * Students?" - the highest word count a real heading in either fixture is known to
     * need, so it is the highest ceiling that still fixes both observed regressions
     * without excluding a real heading this codebase has actually seen.
     *
     * KNOWN RESIDUAL GAP: several shorter Definition cells in the same table (8-9
     * words each, e.g. "the process of distributing money, resources, or
     * responsibilities") sit at or below this same ceiling and are NOT caught by it -
     * word count alone cannot separate them from a genuine short heading of the same
     * length. See the report for the full list. Under the governing priority (a wrong
     * label is worse than none), lowering the ceiling further to close that gap would
     * also exclude the real 9-word heading above, which is the greater harm.
     */
    val headingMaxWordCount: Int = 9,
    /**
     * A block may only be promoted to TITLE or HEADING by height when it spans at most
     * this many lines. A real heading is a short label, not a multi-line passage; a
     * block this long that still measures tall is far more likely to be an
     * unfortunately-tall paragraph that [RoleClassifier]'s wrapped-full-width check
     * (which only fires when a line reaches the column's right margin) did not catch,
     * for example a short-lined table cell that never reaches the margin at all.
     */
    val headingMaxLineCount: Int = 2,
    /**
     * The most two columns' median line angle may differ, in degrees, and still be treated
     * by [ColumnSegmenter] as two columns of ONE photographed sheet rather than two
     * different physical surfaces (an open book's facing page caught in the same frame).
     * Measured from each line's own recognizer-reported quadrilateral (see
     * [angleFromCorners]) AFTER [SkewEstimator.deskew] — which corrects the whole page by
     * ONE shared angle, so a genuine second column of the same flat sheet returns to
     * (near) zero along with the rest of the page, while a facing page that was tilted
     * differently before capture keeps whatever residual angle that one shared correction
     * did not remove.
     *
     * Evidence, real corpus photos run through the FULL pipeline (FrameRotation,
     * SkewEstimator and MarginFragmentFilter all applied first, exactly as production
     * does), dominant column vs the other: the ONE real photo whose two surfaces both
     * survive to this check, `20260731_231108` (an open handbook; the facing page is
     * curved and clearly wrong), measured 5.01 degrees apart. The synthetic two-column
     * worksheet fixture (one flat sheet) measures 0. 4.0 sits with margin between those
     * two points and, per this project's governing priority, leans toward the higher
     * (more conservative) side: a borderline page keeps both columns rather than wrongly
     * discarding real content.
     *
     * This evidence base is thin (one real positive case) because most other real
     * facing-page photos in the corpus never reach this check at all: MarginFragmentFilter
     * and [ColumnSegmenter]'s own line-count/share floor already reduce them to a single
     * column beforehand (`20260731_230929`, `20260731_231146`), or produce three columns
     * this check does not examine (`20260731_232336`, a same-page multi-column table).
     * This check exists for the case those cannot catch: a facing page substantial enough
     * to look like a legitimate second column.
     *
     * A matching text-SCALE signal (median line height ratio between the two columns) was
     * tried and dropped: measured after the full pipeline, `20260731_231108`'s real
     * facing-page case showed a height ratio of only 1.02 — indistinguishable from the
     * synthetic same-sheet control's 1.00. [SkewEstimator.deskew] re-encloses each line's
     * ROTATED corners (see its KDoc on why: an already-tight box, not the inflated AABB of
     * a rotated AABB), and that re-enclosure evidently absorbs most of the scale
     * difference a facing page's greater distance/angle would otherwise leave in the
     * axis-aligned box height, unlike the angle signal, which survives the shared
     * rotation intact. Angle alone is used.
     */
    val facingPageMaxAngleDiffDeg: Float = 4.0f,
    /**
     * How far a single line's own post-deskew corner angle (see [angleFromCorners]) may
     * disagree with the page's dominant (median) line angle, in degrees, before
     * [FoldedPageAngleFilter] drops it as text from a different physical surface - a
     * folded or curved open-book facing page whose lines scatter across many angles
     * rather than forming one coherent second column. See [FoldedPageAngleFilter]'s KDoc
     * for why this is a distinct, earlier mechanism from [facingPageMaxAngleDiffDeg]
     * above, and for the full reconciliation between the two.
     *
     * Evidence, both measured post-deskew (the same signal [ColumnSegmenter]'s own
     * facing-page check uses):
     * - `real-worksheet-exercises.json`, a real single page with no facing page at all,
     *   measures a maximum genuine per-line deviation from its own median angle of ~13.5
     *   degrees - the widest legitimate single-page spread found in the corpus so far, and
     *   the floor this threshold must clear without cutting into it.
     * - `real-facing-page-231108-raw.json`'s scattered facing-page outliers reach 19.95,
     *   20.45, 22.85, 23.76 and 27.27 degrees away from the dominant page's median.
     *
     * 20 sits just above the worksheet-exercises ceiling - the higher, more conservative
     * side, per this project's governing priority - while still catching the widest
     * folded-page outliers. It is deliberately not tuned to catch every scattered
     * fragment in that one fixture; the residual is left to [facingPageMaxAngleDiffDeg]'s
     * complementary column-based check.
     */
    val foldedPageAngleToleranceDeg: Float = 20.0f,
    /**
     * [FoldedPageAngleFilter] must never discard more than this fraction of a page's
     * lines. If the "dominant" angle cluster it finds retains fewer lines than this, that
     * is a sign the clustering itself failed - for example a genuinely, uniformly
     * skewed real photo unlike anything in the corpus this threshold was tuned against -
     * rather than genuine evidence of an intruding facing page. The safer failure, per
     * this project's governing priority, is to keep every line rather than risk gutting
     * the document. 0.5 (never discard a majority of the page) is a wide, deliberately
     * conservative floor: on the one real fixture with a genuine scattered intruder
     * (`real-facing-page-231108-raw.json`), the filter retains ~94% of lines - far above
     * this floor - so 0.5 only ever engages as a backstop against a future, more
     * aggressive tolerance or an unanticipated real photo, never in the corpus evidence
     * gathered so far.
     */
    val minFoldedPageRetainedLineShareFraction: Float = 0.5f,
)
