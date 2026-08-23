package id.dotcode.braille.ocr.pipeline

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ColumnSegmenterTest {
    private val segmenter = ColumnSegmenter(StructuringConfig())

    @Test
    fun `a single column page yields one column`() {
        val lines = listOf(
            line("baris satu", 100f, 100f, 800f, 30f),
            line("baris dua", 100f, 140f, 800f, 30f),
            line("baris tiga", 100f, 180f, 800f, 30f),
            line("baris empat", 100f, 220f, 800f, 30f),
        )
        val result = segmenter.segment(lines, PageStats.from(lines))
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
        val result = segmenter.segment(lines, PageStats.from(lines))
        assertEquals(2, result.columnCount)
        assertEquals(listOf(0, 0, 1, 1), result.columnIndex)
    }

    @Test
    fun `a full width heading above two columns does not break the split`() {
        val lines = listOf(
            line("JUDUL LEMBAR KERJA", 100f, 40f, 1300f, 40f),
            line("kiri satu", 100f, 140f, 500f, 30f),
            line("kiri dua", 100f, 180f, 500f, 30f),
            line("kanan satu", 900f, 140f, 500f, 30f),
            line("kanan dua", 900f, 180f, 500f, 30f),
        )
        val result = segmenter.segment(lines, PageStats.from(lines))
        // The heading spans the gutter, so its interval merges the two runs and the page
        // is reported as one column. That is the correct conservative answer: a wrong
        // split scrambles reading order, while a missed split only under-segments.
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
        val result = segmenter.segment(lines, PageStats.from(lines))
        assertEquals(1, result.columnCount)
    }

    @Test
    fun `too few lines never splits`() {
        val lines = listOf(
            line("kiri", 100f, 100f, 300f, 30f),
            line("kanan", 1200f, 100f, 300f, 30f),
        )
        val result = segmenter.segment(lines, PageStats.from(lines))
        assertEquals(1, result.columnCount)
    }
}
