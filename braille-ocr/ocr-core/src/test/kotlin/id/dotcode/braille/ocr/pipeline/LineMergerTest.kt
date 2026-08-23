package id.dotcode.braille.ocr.pipeline

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class LineMergerTest {
    private val config = StructuringConfig()
    private val merger = LineMerger(config)

    private fun groups(lines: List<id.dotcode.braille.ocr.raw.RawLine>): List<LineGroup> {
        val stats = PageStats.from(lines)
        val columns = ColumnSegmenter(config).segment(lines, stats)
        val ordered = ReadingOrderSorter(config).sort(lines, columns, stats)
        return merger.merge(ordered, stats)
    }

    @Test
    fun `tight lines with aligned edges form one paragraph`() {
        val lines = listOf(
            line("Sumber daya alam adalah kekayaan", 100f, 100f, 600f, 30f),
            line("yang tersedia di alam sekitar kita", 100f, 136f, 600f, 30f),
        )
        val result = groups(lines)
        assertEquals(1, result.size)
        assertEquals(2, result.first().lines.size)
    }

    @Test
    fun `a wide vertical gap starts a new block`() {
        val lines = listOf(
            line("Paragraf pertama di sini", 100f, 100f, 600f, 30f),
            line("Paragraf kedua jauh di bawah", 100f, 400f, 600f, 30f),
        )
        assertEquals(2, groups(lines).size)
    }

    @Test
    fun `terminal punctuation ends a block`() {
        val lines = listOf(
            line("Kalimat pertama selesai.", 100f, 100f, 600f, 30f),
            line("Kalimat kedua dimulai", 100f, 136f, 600f, 30f),
        )
        assertEquals(2, groups(lines).size)
    }

    @Test
    fun `a new marker always starts a block even when tightly spaced`() {
        val lines = listOf(
            line("1. Soal pertama", 100f, 100f, 600f, 30f),
            line("2. Soal kedua", 100f, 136f, 600f, 30f),
            line("3. Soal ketiga", 100f, 172f, 600f, 30f),
        )
        assertEquals(3, groups(lines).size)
    }

    @Test
    fun `a continuation line joins its marker block`() {
        val lines = listOf(
            line("1. Sebutkan tiga contoh sumber", 100f, 100f, 600f, 30f),
            line("daya alam di Indonesia", 100f, 136f, 600f, 30f),
            line("2. Jelaskan fotosintesis", 100f, 172f, 600f, 30f),
        )
        val result = groups(lines)
        assertEquals(2, result.size)
        assertEquals(2, result[0].lines.size)
        assertEquals(1, result[1].lines.size)
    }

    @Test
    fun `a column change always starts a block`() {
        val lines = listOf(
            line("kiri satu", 100f, 100f, 500f, 30f),
            line("kiri dua", 100f, 136f, 500f, 30f),
            line("kanan satu", 900f, 100f, 500f, 30f),
            line("kanan dua", 900f, 136f, 500f, 30f),
        )
        assertEquals(2, groups(lines).size)
    }

    @Test
    fun `reflow joins a hyphenated wrap without a space`() {
        val lines = listOf(
            line("pembela-", 100f, 100f, 300f, 30f),
            line("jaran", 100f, 136f, 200f, 30f),
        )
        assertEquals("pembelajaran", LineMerger.reflow(lines))
    }

    @Test
    fun `reflow joins ordinary wraps with a single space`() {
        val lines = listOf(
            line("sumber daya", 100f, 100f, 300f, 30f),
            line("alam", 100f, 136f, 200f, 30f),
        )
        assertEquals("sumber daya alam", LineMerger.reflow(lines))
    }
}
