package id.dotcode.braille.ocr.pipeline

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class RowFragmentJoinerTest {
    private val config = StructuringConfig()
    private val joiner = RowFragmentJoiner(config)

    private fun ordered(lines: List<id.dotcode.braille.ocr.raw.RawLine>): List<OrderedLine> {
        val stats = PageStats.from(lines)
        val columns = ColumnSegmenter(config).segment(lines, stats, 1600)
        return ReadingOrderSorter(config).sort(lines, columns, stats)
    }

    @Test
    fun `two fragments split by a crease join into one line`() {
        // Real coordinates from the photographed worksheet: a physical crease split one
        // printed line into two ML Kit fragments on the same visual row.
        val fragmentA = line(
            "Concerns about financialc",
            214.07f, 302.39f, 373.75f - 214.07f, 317.30f - 302.39f,
        )
        val fragmentB = line(
            "cost, food waste, administrative complexity, and the challenge of maintaining food quality",
            370.15f, 302.46f, 914.03f - 370.15f, 323.19f - 302.46f,
        )
        val stats = PageStats.from(listOf(fragmentA, fragmentB))
        val result = joiner.join(ordered(listOf(fragmentA, fragmentB)), stats)

        assertEquals(1, result.size)
        assertEquals(
            "Concerns about financialc cost, food waste, administrative complexity, and the challenge of maintaining food quality",
            result.first().line.text,
        )
    }

    @Test
    fun `a left column line and a right column line sharing a row do not join`() {
        val lines = listOf(
            line("kiri satu", 100f, 100f, 500f, 30f),
            line("kiri dua", 100f, 300f, 500f, 30f),
            line("kanan satu", 900f, 100f, 500f, 30f),
            line("kanan dua", 900f, 300f, 500f, 30f),
        )
        val stats = PageStats.from(lines)
        val result = joiner.join(ordered(lines), stats)

        assertEquals(4, result.size)
        assertEquals(
            setOf("kiri satu", "kiri dua", "kanan satu", "kanan dua"),
            result.map { it.line.text }.toSet(),
        )
    }
}
