package id.dotcode.braille.ocr.pipeline

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ColumnSegmenterTest {
    private val segmenter = ColumnSegmenter(StructuringConfig())
    private val pageWidth = 1600

    @Test
    fun `a single column page yields one column`() {
        val lines = listOf(
            line("baris satu", 100f, 100f, 800f, 30f),
            line("baris dua", 100f, 140f, 800f, 30f),
            line("baris tiga", 100f, 180f, 800f, 30f),
            line("baris empat", 100f, 220f, 800f, 30f),
        )
        val result = segmenter.segment(lines, PageStats.from(lines), pageWidth)
        assertEquals(1, result.columnCount)
        assertEquals(listOf(0, 0, 0, 0), result.columnIndex)
    }

    @Test
    fun `a wide gutter splits two columns`() {
        val lines = listOf(
            line("kiri satu", 100f, 100f, 500f, 30f),
            line("kiri dua", 100f, 140f, 500f, 30f),
            line("kanan satu", 900f, 100f, 500f, 30f),
            line("kanan dua", 900f, 140f, 500f, 30f),
        )
        val result = segmenter.segment(lines, PageStats.from(lines), pageWidth)
        assertEquals(2, result.columnCount)
        assertEquals(listOf(0, 0, 1, 1), result.columnIndex)
    }

    @Test
    fun `a full width heading above two columns no longer collapses the split`() {
        // Replaces an earlier test that pinned the opposite expectation. That test was
        // wrong: it asserted the page collapsed to ONE column, which is precisely the
        // bug — a title spanning the gutter unioned the left and right x-runs, so
        // reading order interleaved the columns. A spanning line carries no information
        // about where the gutter is, so it is now excluded from gutter detection and
        // only assigned to a column afterwards. 1400 > 0.8 * 1600, so it spans.
        val lines = listOf(
            line("JUDUL LEMBAR KERJA IPA KELAS ENAM", 60f, 40f, 1340f, 40f),
            line("kiri satu", 100f, 140f, 500f, 30f),
            line("kiri dua", 100f, 180f, 500f, 30f),
            line("kanan satu", 900f, 140f, 500f, 30f),
            line("kanan dua", 900f, 180f, 500f, 30f),
        )
        val result = segmenter.segment(lines, PageStats.from(lines), pageWidth)
        assertEquals(2, result.columnCount)
        // The heading's centre (730) sits left of the gutter midpoint (750).
        assertEquals(listOf(0, 0, 0, 1, 1), result.columnIndex)
        // Column bounds ignore the spanning line, so column 0 is 100..600 not 60..1400.
        assertEquals(100f..600f, result.bounds[0])
        assertEquals(900f..1400f, result.bounds[1])
    }

    @Test
    fun `a page of nothing but spanning lines stays single column`() {
        val lines = List(5) { i -> line("baris penuh $i", 60f, 100f + i * 40f, 1400f, 30f) }
        val result = segmenter.segment(lines, PageStats.from(lines), pageWidth)
        assertEquals(1, result.columnCount)
    }

    @Test
    fun `a lone line in a would be column collapses the split`() {
        val lines = listOf(
            line("kiri satu", 100f, 100f, 500f, 30f),
            line("kiri dua", 100f, 140f, 500f, 30f),
            line("kiri tiga", 100f, 180f, 500f, 30f),
            line("9", 1400f, 1900f, 20f, 30f),
        )
        val result = segmenter.segment(lines, PageStats.from(lines), pageWidth)
        assertEquals(1, result.columnCount)
    }

    @Test
    fun `a spanning line cannot prop up an underpopulated column`() {
        // The right "column" holds one genuine line plus a spanning title whose centre
        // happens to land right of the gutter. The populated guard counts only
        // column-bound lines, so the split still collapses.
        val lines = listOf(
            line("judul yang sangat panjang sekali", 140f, 40f, 1400f, 30f),
            line("kiri satu", 100f, 140f, 500f, 30f),
            line("kiri dua", 100f, 180f, 500f, 30f),
            line("kiri tiga", 100f, 220f, 500f, 30f),
            line("kanan satu", 900f, 140f, 500f, 30f),
        )
        val result = segmenter.segment(lines, PageStats.from(lines), pageWidth)
        assertEquals(1, result.columnCount)
    }

    @Test
    fun `too few lines never splits`() {
        val lines = listOf(
            line("kiri", 100f, 100f, 300f, 30f),
            line("kanan", 1200f, 100f, 300f, 30f),
        )
        val result = segmenter.segment(lines, PageStats.from(lines), pageWidth)
        assertEquals(1, result.columnCount)
    }
}
