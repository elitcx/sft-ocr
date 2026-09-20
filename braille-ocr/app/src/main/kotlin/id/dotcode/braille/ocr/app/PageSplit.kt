package id.dotcode.braille.ocr.app

/** One recognized line's horizontal extent in the upright frame, as a fraction 0..1. */
data class LineSpan(val left: Float, val right: Float)

/**
 * Decides whether the preview is looking at an open book rather than a single page.
 *
 * Four of the five worst-scoring captures in the corpus are open-book spreads, and they are
 * the one framing mistake the pipeline cannot fully undo: the facing page's cut-off
 * fragments have to be identified and discarded downstream, and on a spread the app scores
 * 35% CER against 5% on a single page. A sighted photographer sees the second page in the
 * viewfinder. A blind student never does, which is exactly why this belongs in the guidance
 * layer rather than only in post-processing.
 *
 * The signal is the spine: a vertical band across the middle of the frame that no line of
 * text crosses, with a substantial block of lines on either side of it.
 *
 * ## Biased toward saying nothing
 *
 * Every threshold here errs toward silence. A missed spread leaves the student exactly
 * where they already were; a false alarm sends them to re-shoot a photo that was fine, and
 * they cannot look at the screen to see that the advice is wrong. So the gap must be wide,
 * near the middle, and flanked by real blocks of text on both sides.
 *
 * ## Known limitation
 *
 * A two-column layout on ONE page produces the same geometry. [MIN_GUTTER] is set wide
 * enough to clear the column gutters in this project's worksheet fixtures - a book's spine
 * plus two inner margins is several times wider than the space between two columns - but a
 * page with unusually generous column spacing would still be reported. The cost is bounded:
 * this instruction is advisory, it sits inside [GuidanceEngine]'s relaxation guard, and the
 * photo is taken regardless once the engine gives up. A sharper discriminator would be the
 * spine's own shadow, since a column gutter is as bright as the page around it.
 */
object PageSplit {

    fun looksLikeTwoPages(spans: List<LineSpan>): Boolean {
        if (spans.size < MIN_LINES_TOTAL) return false

        // Sweep a candidate spine across the middle of the frame and ask what it separates.
        var probe = SEARCH_FROM
        while (probe <= SEARCH_TO) {
            val left = spans.count { it.right <= probe }
            val right = spans.count { it.left >= probe }
            val crossing = spans.size - left - right
            if (crossing == 0 && left >= MIN_LINES_PER_PAGE && right >= MIN_LINES_PER_PAGE) {
                val gutter = (spans.filter { it.left >= probe }.minOf { it.left } -
                    spans.filter { it.right <= probe }.maxOf { it.right })
                val leftWidth = spans.filter { it.right <= probe }.maxOf { it.right } -
                    spans.filter { it.right <= probe }.minOf { it.left }
                val rightWidth = spans.filter { it.left >= probe }.maxOf { it.right } -
                    spans.filter { it.left >= probe }.minOf { it.left }
                // Both halves must be a page's worth of text, not a margin note.
                if (gutter >= MIN_GUTTER && leftWidth >= MIN_PAGE_WIDTH && rightWidth >= MIN_PAGE_WIDTH) {
                    return true
                }
            }
            probe += STEP
        }
        return false
    }

    /**
     * How wide the empty band must be, as a fraction of the frame. A book's spine plus the
     * two inner margins clears this comfortably; the gutter between two printed columns
     * does not. This single constant is what separates "reframe onto one page" from
     * nagging a student about a perfectly good two-column worksheet.
     */
    private const val MIN_GUTTER = 0.08f

    /** A spine is near the middle; an empty band at the frame's edge is just whitespace. */
    private const val SEARCH_FROM = 0.3f
    private const val SEARCH_TO = 0.7f
    private const val STEP = 0.01f

    /** Below this there is not enough text on screen to judge anything. */
    private const val MIN_LINES_TOTAL = 8

    /** A couple of stray fragments across the gap are not a page. */
    private const val MIN_LINES_PER_PAGE = 3

    /** Each side must be a page's worth of text, not a margin note or a page number. */
    private const val MIN_PAGE_WIDTH = 0.2f
}
