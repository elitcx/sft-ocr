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
        val columns = ColumnSegmenter(config).segment(lines, stats, 1600)
        val ordered = ReadingOrderSorter(config).sort(lines, columns, stats)
        val lineMerger = LineMerger(config)
        val columnRightMargins = lineMerger.columnRightMargins(ordered)
        val groups = lineMerger.merge(ordered, stats)
        return classifier.classify(groups, stats, pageHeight, columnRightMargins)
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

    // --- Regressions found against a real photographed worksheet. Perspective made a
    // block's line height climb steadily toward the bottom of the page (0.78x-1.26x the
    // page-wide median) purely from camera distance, with no change in printed font
    // size. See docs/superpowers/plans/2026-08-25-role-classification-fix-report.md.

    @Test
    fun `a wrapped full-width paragraph at the page foot is not a heading`() {
        // Mirrors blocks 19-21 from the real worksheet: a four-line, full-width body
        // paragraph sitting near the bottom of a photographed page, where perspective
        // makes its lines measure ~25% taller than lines higher up the page. Under the
        // old page-wide-median comparison this classified HEADING (ratio ~1.25). A block
        // that wraps across multiple lines reaching the column's right margin can never
        // be a heading or caption, regardless of any height estimate.
        val lines = listOf(
            line("Paragraf normal pertama di sini", 100f, 100f, 700f, 30f),
            line("Paragraf normal kedua di sini juga", 100f, 250f, 700f, 30f),
            line("Paragraf yang panjang sekali dan berlanjut", 100f, 1500f, 700f, 37.5f),
            line("hingga baris berikutnya karena isinya banyak", 100f, 1547.5f, 700f, 37.5f),
            line("dan terus berlanjut lagi ke baris ketiga ini", 100f, 1595f, 700f, 37.5f),
            line("sampai akhirnya selesai di sini.", 100f, 1642.5f, 500f, 37.5f),
        )
        val roles = classify(lines)
        assertEquals(3, roles.size, "expected the four wrapped lines to merge into one block")
        assertEquals(BlockRole.PARAGRAPH, roles.last())
    }

    @Test
    fun `a genuinely larger heading among normal text is still a heading`() {
        // Proves the wrap/local-baseline fixes did not simply disable heading detection:
        // a real 1.5x-height single-line heading sitting among six ordinary body blocks
        // (all comfortably below the top band) must still classify HEADING.
        val lines = listOf(
            line("Kalimat paragraf satu di sini", 100f, 100f, 700f, 30f),
            line("Kalimat paragraf dua di sini", 100f, 250f, 700f, 30f),
            line("Kalimat paragraf tiga di sini", 100f, 400f, 700f, 30f),
            line("Bagian Kedua", 100f, 550f, 400f, 45f),
            line("Kalimat paragraf lima di sini", 100f, 700f, 700f, 30f),
            line("Kalimat paragraf enam di sini", 100f, 850f, 700f, 30f),
            line("Kalimat paragraf tujuh di sini", 100f, 1000f, 700f, 30f),
        )
        assertEquals(BlockRole.HEADING, classify(lines)[3])
    }

    @Test
    fun `a smooth top-to-bottom perspective gradient yields no headings or captions`() {
        // The core regression: simulates a photographed page where every block is
        // ordinary body text but line height grows steadily from ~14px at the top to
        // ~24px at the bottom purely from camera perspective. Comparing each block only
        // to the page-wide median would falsely promote the tallest blocks to HEADING
        // and the shortest to CAPTION. Comparing locally must cancel the gradient.
        val blockCount = 15
        val startHeight = 14f
        val endHeight = 24f
        val lines = (0 until blockCount).map { i ->
            val height = startHeight + i * (endHeight - startHeight) / (blockCount - 1)
            line("Kalimat paragraf biasa nomor urut $i", 100f, 100f + i * 80f, 700f, height)
        }
        val roles = classify(lines, pageHeight = 100 + blockCount * 80 + 200)
        assertEquals(blockCount, roles.size)
        assertEquals(
            emptyList<BlockRole>(),
            roles.filter { it == BlockRole.HEADING || it == BlockRole.CAPTION || it == BlockRole.TITLE },
            "perspective gradient alone must never produce a HEADING, CAPTION or TITLE: $roles",
        )
    }

    @Test
    fun `a short block that still reaches the margin is a paragraph, not a caption`() {
        // Slightly below the local baseline (0.83x) but its line still reaches the
        // column's right margin - that is a short paragraph, not a caption. Reporting
        // CAPTION here would be a false structural claim about a block that is really
        // just brief body text.
        val lines = listOf(
            line("Paragraf normal pertama di sini saja", 100f, 100f, 700f, 30f),
            line("Paragraf normal kedua di sini saja juga", 100f, 250f, 700f, 30f),
            line("Paragraf pendek yang tetap penuh lebar", 100f, 400f, 700f, 25f),
            line("Paragraf normal ketiga di sini saja", 100f, 550f, 700f, 30f),
        )
        assertEquals(BlockRole.PARAGRAPH, classify(lines)[2])
    }

    @Test
    fun `a genuinely small block that stops well short of the margin is a caption`() {
        val lines = listOf(
            line("Paragraf normal pertama di sini saja", 100f, 100f, 700f, 30f),
            line("Paragraf normal kedua di sini saja juga", 100f, 250f, 700f, 30f),
            line("Gambar 1 rantai makanan", 100f, 400f, 400f, 24f),
        )
        assertEquals(BlockRole.CAPTION, classify(lines).last())
    }
}
