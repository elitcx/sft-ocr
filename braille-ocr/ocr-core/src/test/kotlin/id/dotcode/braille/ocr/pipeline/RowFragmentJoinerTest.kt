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
    fun `fragments straddling a band boundary join in left-to-right reading order`() {
        // A crease clips the RIGHT fragment's vertical extent, shifting its centerY (and
        // therefore its ReadingOrderSorter band) upward relative to the LEFT fragment, even
        // though the two fragments still overlap enough vertically to be the same printed
        // row. ReadingOrderSorter sorts by band before by left, so the right fragment (band
        // 19) is ordered BEFORE the left fragment (band 20) despite sitting physically to
        // the right. A naive list-order join would then emit "<right> <left>": reversed text.
        val left = line("Concerns about financialc", 100f, 302f, 150f, 28f) // right=250, bottom=330
        val right = line("cost and food waste", 400f, 295f, 200f, 17f) // right=600, bottom=312

        val stats = PageStats.from(listOf(left, right))
        val orderedLines = ordered(listOf(left, right))

        // Confirm the premise: the sorter really does put the right fragment first because
        // of the band split, which is exactly the situation this fix must handle correctly.
        assertEquals(
            listOf("cost and food waste", "Concerns about financialc"),
            orderedLines.map { it.line.text },
            "test premise broken: expected ReadingOrderSorter to place the right fragment first",
        )

        val result = joiner.join(orderedLines, stats)

        assertEquals(1, result.size)
        assertEquals(
            "Concerns about financialc cost and food waste",
            result.first().line.text,
        )
        assertEquals(100f, result.first().line.box.left)
        assertEquals(600f, result.first().line.box.right)
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
