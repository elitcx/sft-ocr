package id.dotcode.braille.ocr.pipeline

import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
        // The heading spans, so it takes the leftmost column it overlaps (its left edge
        // 60 sits left of the gutter midpoint 750).
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
        // The right "column" holds one genuine line plus a spanning title. The populated
        // guard counts only column-bound lines, so the split still collapses no matter
        // which column the spanning title is pinned to.
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
    fun `a spanning line takes the leftmost column it overlaps not the one holding its centre`() {
        // The coin flip. Left column 100..640, right column 860..1500, so the gutter
        // midpoint is 750. A title spanning 100..1500 has its centre at 800 — right of
        // the boundary — so centre-based assignment pins the page title to column 1 and
        // ReadingOrderSorter emits it after every left-column question. Its LEFT edge
        // (100) is unambiguously in column 0, which is the whole point: a line that
        // overlaps both columns belongs to the first one a reader reaches.
        val lines = listOf(
            line("LEMBAR KERJA IPA KELAS ENAM SEMESTER SATU", 100f, 60f, 1400f, 56f),
            line("1. Sebutkan sumber daya alam", 100f, 300f, 540f, 30f),
            line("2. Jelaskan fotosintesis", 100f, 400f, 540f, 30f),
            line("3. Apa fungsi akar tumbuhan", 100f, 500f, 540f, 30f),
            line("4. Sebutkan hewan herbivora", 860f, 300f, 640f, 30f),
            line("5. Jelaskan siklus kupu-kupu", 860f, 400f, 640f, 30f),
            line("6. Apa manfaat matahari", 860f, 500f, 640f, 30f),
        )
        val result = segmenter.segment(lines, PageStats.from(lines), pageWidth)
        assertEquals(2, result.columnCount)
        assertEquals(0, result.columnIndex.first(), "the spanning title must land in column 0")
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1), result.columnIndex)
    }

    @Test
    fun `background clutter at the frame edge is dropped, not treated as a second column`() {
        // Representative of a real defect: a second sheet lying underneath the
        // photographed worksheet, upside down, partially visible at the left frame
        // edge. Its five small fragments (x roughly 56..110) satisfy the OLD
        // minLinesPerColumn floor (5 >= 2) despite being a vanishingly small share of
        // the page's real content (72 lines), which used to make the page report a
        // phantom 2-column layout with the clutter read FIRST.
        val clutter = listOf(
            line("noN", 56f, 0f, 50f, 15f),
            line("\"ue", 61f, 40f, 30f, 20f),
            line("Buou", 72f, 80f, 32f, 20f),
            line("1snu", 80f, 125f, 27f, 17f),
            line("pur", 88f, 172f, 21f, 14f),
        )
        // 40 body lines keeps clutter's share at 5 / 45 = 11%, below the 15% clutter
        // floor - proportional to the real fixture, where 5 clutter lines out of 77
        // total (6.5%) is what must be dropped.
        val body = (0 until 40).map { i -> line("baris tubuh nomor $i pada halaman ini", 191f, 50f + i * 20f, 760f, 17f) }
        val lines = clutter + body
        val result = segmenter.segment(lines, PageStats.from(lines), pageWidth)

        assertEquals(1, result.columnCount, "clutter must not be reported as a real column")
        assertEquals(
            List(clutter.size) { -1 },
            result.columnIndex.take(clutter.size),
            "clutter lines must be marked dropped (-1), not folded into the surviving column",
        )
        assertTrue(
            result.columnIndex.drop(clutter.size).all { it == 0 },
            "every real body line must survive in the single surviving column",
        )
    }

    @Test
    fun `an under-populated column with a fair share is collapsed but not dropped`() {
        // Distinguishes "clutter" (dropped) from "a genuinely small second column"
        // (kept, just not treated as its own reading column). This column fails only
        // the ABSOLUTE line-count floor, not the share floor (1 of 4 lines is 25%,
        // comfortably above the 15% clutter threshold) - it must survive.
        val lines = listOf(
            line("kiri satu", 100f, 100f, 500f, 30f),
            line("kiri dua", 100f, 140f, 500f, 30f),
            line("kiri tiga", 100f, 180f, 500f, 30f),
            line("kanan satu", 900f, 100f, 500f, 30f),
        )
        val result = segmenter.segment(lines, PageStats.from(lines), pageWidth)

        assertEquals(1, result.columnCount)
        assertTrue(result.columnIndex.all { it == 0 }, "a fair-share column must not be dropped, only collapsed")
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
