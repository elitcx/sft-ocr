package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.model.BlockRole
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

    // --- BUG: a crease join inflated line height, which could manufacture a false
    // HEADING. See RoleClassifier: a joined line is a single-line block, so the
    // "multi-line full-width block is body text" safety net can never rescue it.

    @Test
    fun `a joined line's height reflects the fragments' typical height, not the union's vertical span`() {
        // The two fragments individually measure 25px tall, but the crease shifts the
        // right fragment's vertical extent upward by 9px, so their UNION spans 34px -
        // 36 percent taller than either fragment actually is.
        val fragmentA = line("Concerns about financialc", 100f, 500f, 200f, 25f) // top 500..525
        val fragmentB = line("cost and food waste", 305f, 491f, 200f, 25f) // top 491..516

        val stats = PageStats.from(listOf(fragmentA, fragmentB))
        val result = joiner.join(ordered(listOf(fragmentA, fragmentB)), stats)

        assertEquals(1, result.size)
        val box = result.first().line.box
        // Typical height: average of the two fragments' own heights (25 and 25), not
        // the union's 34px vertical span.
        assertEquals(25f, box.height, 0.01f)
        // Position (horizontal extent, and the vertical centre) still comes from the
        // union, so the line's location on the page is unaffected.
        assertEquals(100f, box.left)
        assertEquals(505f, box.right)
        assertEquals((491f + 525f) / 2f, box.centerY, 0.01f)
    }

    @Test
    fun `a crease join in a page of uniform body text never produces a heading`() {
        // End to end: seven ordinary single-line paragraphs (height 25, well separated
        // vertically) plus one row split by a crease into two fragments whose UNION
        // would measure 34px tall - 36 percent taller than the local baseline, comfortably
        // past headingHeightRatio (1.25). Before the fix this fed RoleClassifier a false
        // HEADING; a joined line is single-line, so the wrap-rule safety net cannot help.
        val config = StructuringConfig()
        val plainLines = (0 until 7).map { i ->
            line("Paragraf tubuh biasa nomor $i di halaman ini", 100f, 100f + i * 150f, 700f, 25f)
        }
        val creaseRow = 100f + 4.5f * 150f // sits between two plain paragraphs
        val fragmentA = line("Concerns about financialc", 100f, creaseRow, 200f, 25f)
        val fragmentB = line("cost and food waste", 305f, creaseRow - 9f, 200f, 25f)

        val allLines = plainLines + listOf(fragmentA, fragmentB)
        val doc = DocumentStructurer(config).structure(page(*allLines.toTypedArray(), width = 1600, height = 1400))

        assertTrue(
            doc.blocks.none { it.role == BlockRole.HEADING || it.role == BlockRole.TITLE },
            "a crease join must never manufacture a heading out of uniform body text: ${doc.blocks.map { it.role }}",
        )
    }

    // --- BUG: joins can chain along one row (a table row, or a "label: ____" pair in a
    // single column), and the horizontal-gap guard that should prevent that had no
    // dedicated single-column test - the only existing negative test above proves only
    // the COLUMN guard, not the gap guard.

    @Test
    fun `two widely separated items on one row in a single column do not join`() {
        val label = line("Nama:", 100f, 500f, 100f, 30f) // right = 200
        val answer = line("Budi Santoso", 700f, 500f, 200f, 30f) // right = 900, gap = 500
        val stats = PageStats.from(listOf(label, answer))
        val result = joiner.join(ordered(listOf(label, answer)), stats)

        assertEquals(2, result.size)
        assertEquals(listOf("Nama:", "Budi Santoso"), result.map { it.line.text })
    }

    @Test
    fun `a chain of three widely separated items on one row does not collapse`() {
        // A table-like row: three separate cells on the same visual row, each pair
        // separated by a wide gap. If the gap guard only prevented the FIRST join, a
        // chain could still collapse cell 2 into cell 3. It must not collapse at all.
        val cellA = line("Kolom Satu", 100f, 500f, 150f, 30f) // right = 250
        val cellB = line("Kolom Dua", 600f, 500f, 150f, 30f) // right = 750, gap from A = 350
        val cellC = line("Kolom Tiga", 1100f, 500f, 150f, 30f) // right = 1250, gap from B = 350
        val stats = PageStats.from(listOf(cellA, cellB, cellC))
        val result = joiner.join(ordered(listOf(cellA, cellB, cellC)), stats)

        assertEquals(3, result.size)
        assertEquals(listOf("Kolom Satu", "Kolom Dua", "Kolom Tiga"), result.map { it.line.text })
    }
}
