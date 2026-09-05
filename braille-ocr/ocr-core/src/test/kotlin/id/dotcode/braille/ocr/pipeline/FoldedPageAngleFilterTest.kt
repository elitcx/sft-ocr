package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.geometry.PointF
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class FoldedPageAngleFilterTest {
    private val config = StructuringConfig()
    private val pivot = PointF(800f, 1000f)

    @Test
    fun `lines with no corner points are never dropped`() {
        val lines = listOf(
            line("baris satu", 100f, 100f, 500f, 30f),
            line("baris dua", 100f, 140f, 500f, 30f),
            line("baris tiga", 100f, 180f, 500f, 30f),
        )
        val result = FoldedPageAngleFilter.filter(lines, config)
        assertEquals(lines, result)
    }

    @Test
    fun `a flat page where every line agrees keeps everything`() {
        val lines = (0..5).map { i ->
            skewedLine("baris $i", 100f, 100f + i * 40f, 500f, 30f, 1.5f, pivot)
        }
        val result = FoldedPageAngleFilter.filter(lines, config)
        assertEquals(6, result.size)
    }

    @Test
    fun `scattered lines far outside the dominant cluster are dropped even without forming a column`() {
        // The defect this filter exists for: a folded/curved facing page whose lines
        // scatter across many DIFFERENT angles rather than lining up into a second
        // column ColumnSegmenter could otherwise catch. Every line here shares
        // (approximately) the same x-range - deliberately never forming a second
        // column - so only a per-line angle signal can tell the scattered intruders
        // from the dominant, coherent page.
        val dominant = (0..5).map { i ->
            skewedLine("dominant $i", 100f, 100f + i * 40f, 500f, 30f, 0f, pivot)
        }
        val scattered = listOf(
            skewedLine("scattered a", 120f, 400f, 500f, 30f, 35f, pivot),
            skewedLine("scattered b", 120f, 440f, 500f, 30f, -40f, pivot),
            skewedLine("scattered c", 120f, 480f, 500f, 30f, 55f, pivot),
        )
        val result = FoldedPageAngleFilter.filter(dominant + scattered, config)

        assertEquals(dominant.map { it.text }.toSet(), result.map { it.text }.toSet())
        for (s in scattered) {
            assertTrue(s.text !in result.map { it.text }, "${s.text} should have been dropped as an off-angle outlier")
        }
    }

    @Test
    fun `a deviation within the measured single-page tolerance is kept`() {
        // real-worksheet-exercises.json's widest genuine single-page deviation measured
        // ~13.5 degrees - comfortably inside foldedPageAngleToleranceDeg (20). A line at
        // that same order of magnitude must survive.
        val dominant = (0..5).map { i ->
            skewedLine("dominant $i", 100f, 100f + i * 40f, 500f, 30f, 0f, pivot)
        }
        val slightlyTilted = skewedLine("slightly tilted", 100f, 500f, 500f, 30f, 13f, pivot)
        val result = FoldedPageAngleFilter.filter(dominant + listOf(slightlyTilted), config)
        assertTrue("slightly tilted" in result.map { it.text })
    }

    @Test
    fun `never discards a majority of the page when no single cluster dominates`() {
        // Three groups (-80, 0, +80 degrees), sized 5/5/3, none of them a majority on its
        // own. The page median lands inside the middle group (0), so a naive filter would
        // "keep" only that group - 5 of 13 lines, 38%, well under half the page. That is
        // clustering failure, not genuine evidence of an intruding facing page (a real
        // facing page is the MINORITY of the page's text, not comparable thirds), so the
        // safer outcome, per this project's governing priority, is to keep every line.
        val groupA = (0..4).map { i -> skewedLine("groupA $i", 100f, 100f + i * 40f, 500f, 30f, -80f, pivot) }
        val groupB = (0..4).map { i -> skewedLine("groupB $i", 100f, 400f + i * 40f, 500f, 30f, 0f, pivot) }
        val groupC = (0..2).map { i -> skewedLine("groupC $i", 100f, 700f + i * 40f, 500f, 30f, 80f, pivot) }
        val result = FoldedPageAngleFilter.filter(groupA + groupB + groupC, config)
        assertEquals(13, result.size, "clustering found no true majority - must keep everything")
    }

    @Test
    fun `fewer than two lines with a usable angle is left untouched`() {
        val lines = listOf(
            skewedLine("only one angled line", 100f, 100f, 500f, 30f, 40f, pivot),
            line("no corner points", 100f, 300f, 500f, 30f),
        )
        val result = FoldedPageAngleFilter.filter(lines, config)
        assertEquals(lines, result)
    }
}
