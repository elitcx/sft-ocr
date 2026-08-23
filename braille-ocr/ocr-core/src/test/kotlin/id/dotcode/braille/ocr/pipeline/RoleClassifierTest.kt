package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.model.BlockRole
import id.dotcode.braille.ocr.raw.RawLine
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class RoleClassifierTest {
    private val config = StructuringConfig()
    private val classifier = RoleClassifier(config)

    private fun classify(lines: List<RawLine>, pageHeight: Int = 2000): List<BlockRole> {
        val stats = PageStats.from(lines)
        val columns = ColumnSegmenter(config).segment(lines, stats)
        val ordered = ReadingOrderSorter(config).sort(lines, columns, stats)
        val groups = LineMerger(config).merge(ordered, stats)
        return classifier.classify(groups, stats, pageHeight)
    }

    @Test
    fun `large text at the top of the page is a title`() {
        val lines = listOf(
            line("LEMBAR KERJA SISWA", 100f, 60f, 700f, 56f),
            line("Isi paragraf biasa di sini", 100f, 300f, 700f, 30f),
            line("Paragraf lain lagi di sini", 100f, 400f, 700f, 30f),
        )
        assertEquals(BlockRole.TITLE, classify(lines).first())
    }

    @Test
    fun `moderately large text mid page is a heading`() {
        val lines = listOf(
            line("Isi paragraf biasa di sini", 100f, 300f, 700f, 30f),
            line("Bagian Kedua", 100f, 700f, 400f, 42f),
            line("Paragraf lain lagi di sini", 100f, 800f, 700f, 30f),
        )
        assertEquals(BlockRole.HEADING, classify(lines)[1])
    }

    @Test
    fun `three or more numbered blocks become questions`() {
        val lines = listOf(
            line("1. Soal pertama di sini", 100f, 300f, 700f, 30f),
            line("2. Soal kedua di sini", 100f, 400f, 700f, 30f),
            line("3. Soal ketiga di sini", 100f, 500f, 700f, 30f),
        )
        assertEquals(listOf(BlockRole.QUESTION, BlockRole.QUESTION, BlockRole.QUESTION), classify(lines))
    }

    @Test
    fun `a lone numbered block stays a list item`() {
        val lines = listOf(
            line("Paragraf pembuka yang panjang", 100f, 300f, 700f, 30f),
            line("1. Satu satunya butir", 100f, 400f, 700f, 30f),
            line("Paragraf penutup yang panjang", 100f, 500f, 700f, 30f),
        )
        assertEquals(BlockRole.LIST_ITEM, classify(lines)[1])
    }

    @Test
    fun `a bullet is always a list item`() {
        val lines = listOf(
            line("• Air bersih", 100f, 300f, 700f, 30f),
            line("Paragraf biasa di sini saja", 100f, 400f, 700f, 30f),
            line("Paragraf lain di sini saja", 100f, 500f, 700f, 30f),
        )
        assertEquals(BlockRole.LIST_ITEM, classify(lines).first())
    }

    @Test
    fun `a numeric only block at the page foot is a page number`() {
        val lines = listOf(
            line("Paragraf biasa di sini saja", 100f, 300f, 700f, 30f),
            line("Paragraf lain di sini saja", 100f, 400f, 700f, 30f),
            line("9", 800f, 1930f, 20f, 28f),
        )
        assertEquals(BlockRole.PAGE_NUMBER, classify(lines).last())
    }

    @Test
    fun `small text is a caption`() {
        val lines = listOf(
            line("Paragraf biasa di sini saja", 100f, 300f, 700f, 40f),
            line("Paragraf lain di sini saja", 100f, 400f, 700f, 40f),
            line("Gambar 1 rantai makanan", 100f, 600f, 400f, 24f),
        )
        assertEquals(BlockRole.CAPTION, classify(lines).last())
    }
}
