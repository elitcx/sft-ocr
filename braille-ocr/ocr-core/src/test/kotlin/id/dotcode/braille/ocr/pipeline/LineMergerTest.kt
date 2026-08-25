package id.dotcode.braille.ocr.pipeline

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class LineMergerTest {
    private val config = StructuringConfig()
    private val merger = LineMerger(config)

    private fun groups(lines: List<id.dotcode.braille.ocr.raw.RawLine>): List<LineGroup> {
        val stats = PageStats.from(lines)
        val columns = ColumnSegmenter(config).segment(lines, stats, 1600)
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
    fun `terminal punctuation ends a block only when the line also stops short of the margin`() {
        // Regression note: this test previously used two lines of IDENTICAL width, which
        // is exactly the ambiguous case BUG 1 exists to fix (see the real-worksheet
        // regression tests below) - a full-width line ending in a period is a sentence
        // boundary inside a wrap, not necessarily a paragraph end. Here the first line
        // stops well short of the column's right margin (established by the second,
        // wider line), which is the genuine paragraph-end signal.
        val lines = listOf(
            line("Kalimat pertama selesai.", 100f, 100f, 300f, 30f),
            line("Kalimat kedua dimulai dengan baris yang jauh lebih panjang", 100f, 136f, 600f, 30f),
        )
        assertEquals(2, groups(lines).size)
    }

    @Test
    fun `a full width line ending in a period merges with the next line (real worksheet regression)`() {
        // Real coordinates from the photographed worksheet that exposed BUG 1. Line A
        // ends with a period AND reaches the same right margin as every other body line
        // (~914) - that is a sentence boundary inside a wrap, not a paragraph end, so it
        // must merge with line B.
        val lineA = line(
            "The provision of free nutritious meals for school students has become an " +
                "important topic of public discussion.",
            238.66f, 203.65f, 913.63f - 238.66f, 224.17f - 203.65f,
        )
        val lineB = line(
            "Supporters argue that sucha program could improve chitdren's health, support " +
                "acadernic performance, and reduce",
            214.14f, 223.26f, 914.10f - 214.14f, 243.16f - 223.26f,
        )
        val result = groups(listOf(lineA, lineB))
        assertEquals(1, result.size)
        assertEquals(2, result.first().lines.size)
    }

    @Test
    fun `a short line ending in a period still splits (real worksheet regression)`() {
        // Real coordinates: line C is a genuine paragraph end - it ends with a period
        // AND stops far short of the right margin (~914) that the surrounding body
        // lines reach. It must still start a new block.
        val lineC = line(
            "perspectives.",
            214.05f, 343.29f, 290.73f - 214.05f, 357.45f - 343.29f,
        )
        val nextParagraphOpener = line(
            "Governments should weigh these perspectives before committing to any single",
            214.05f, 380f, 700f, 20.52f,
        )
        val result = groups(listOf(lineC, nextParagraphOpener))
        assertEquals(2, result.size)
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
