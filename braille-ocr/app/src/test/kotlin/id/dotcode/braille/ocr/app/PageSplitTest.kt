package id.dotcode.braille.ocr.app

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Telling an open book apart from one page, from the preview's line boxes alone.
 *
 * Four of the five worst-scoring pages in the corpus are open-book spreads, and a student
 * who cannot see the viewfinder has no way to notice they have caught the facing page. The
 * detector is deliberately biased toward saying nothing: a missed spread costs the student
 * nothing they did not already have, while a false "I can see two pages" on a genuine
 * two-column worksheet sends them to reframe a photo that was already fine.
 */
class PageSplitTest {

    private fun rows(left: Float, right: Float, count: Int) =
        (1..count).map { LineSpan(left, right) }

    @Test
    fun `an open book shows two blocks of lines either side of the spine`() {
        val spread = rows(0.04f, 0.45f, 8) + rows(0.56f, 0.96f, 8)
        assertTrue(PageSplit.looksLikeTwoPages(spread), "a spread was not recognised")
    }

    @Test
    fun `one page is never reported as two`() {
        assertFalse(PageSplit.looksLikeTwoPages(rows(0.05f, 0.95f, 14)))
    }

    @Test
    fun `a narrow column gutter is not a spine`() {
        // A two-column worksheet is ONE page and must be photographed as one. Its gutter is
        // a few characters wide; a book's spine plus two inner margins is far wider.
        val twoColumn = rows(0.06f, 0.47f, 10) + rows(0.52f, 0.94f, 10)
        assertFalse(
            PageSplit.looksLikeTwoPages(twoColumn),
            "a two-column page was mistaken for an open book",
        )
    }

    @Test
    fun `a margin note beside the text is not a second page`() {
        val pageWithNote = rows(0.05f, 0.70f, 12) + rows(0.88f, 0.96f, 4)
        assertFalse(PageSplit.looksLikeTwoPages(pageWithNote))
    }

    @Test
    fun `a couple of stray lines across the gap are not a page`() {
        val strays = rows(0.04f, 0.45f, 9) + rows(0.60f, 0.96f, 2)
        assertFalse(
            PageSplit.looksLikeTwoPages(strays),
            "two fragments are not enough evidence of a second page",
        )
    }

    @Test
    fun `an off-centre gap is not a spine`() {
        // A spine sits near the middle of the frame. A gap at the far edge is whitespace.
        val edgeGap = rows(0.04f, 0.12f, 6) + rows(0.25f, 0.96f, 10)
        assertFalse(PageSplit.looksLikeTwoPages(edgeGap))
    }

    @Test
    fun `too few lines to judge says nothing`() {
        assertFalse(PageSplit.looksLikeTwoPages(rows(0.04f, 0.45f, 2) + rows(0.56f, 0.96f, 2)))
    }
}
