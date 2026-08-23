package id.dotcode.braille.ocr.pipeline

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ReadingOrderSorterTest {
    private val config = StructuringConfig()
    private val sorter = ReadingOrderSorter(config)

    private fun order(lines: List<id.dotcode.braille.ocr.raw.RawLine>): List<String> {
        val stats = PageStats.from(lines)
        val columns = ColumnSegmenter(config).segment(lines, stats)
        return sorter.sort(lines, columns, stats).map { it.line.text }
    }

    @Test
    fun `single column sorts top to bottom regardless of input order`() {
        val lines = listOf(
            line("tiga", 100f, 200f, 400f, 30f),
            line("satu", 100f, 100f, 400f, 30f),
            line("dua", 100f, 150f, 400f, 30f),
        )
        assertEquals(listOf("satu", "dua", "tiga"), order(lines))
    }

    @Test
    fun `two columns read fully down the left before the right`() {
        val lines = listOf(
            line("kanan satu", 900f, 100f, 500f, 30f),
            line("kiri satu", 100f, 100f, 500f, 30f),
            line("kanan dua", 900f, 150f, 500f, 30f),
            line("kiri dua", 100f, 150f, 500f, 30f),
        )
        assertEquals(listOf("kiri satu", "kiri dua", "kanan satu", "kanan dua"), order(lines))
    }

    @Test
    fun `lines on the same visual row sort left to right despite baseline jitter`() {
        // Both fragments belong to one printed row; the right one sits 4px lower.
        val lines = listOf(
            line("kanan", 700f, 104f, 200f, 30f),
            line("kiri", 100f, 100f, 200f, 30f),
            line("bawah", 100f, 200f, 200f, 30f),
            line("bawah dua", 100f, 250f, 200f, 30f),
        )
        assertEquals(listOf("kiri", "kanan", "bawah", "bawah dua"), order(lines))
    }

    @Test
    fun `sorting an empty page yields an empty list`() {
        assertEquals(emptyList(), order(emptyList()))
    }
}
