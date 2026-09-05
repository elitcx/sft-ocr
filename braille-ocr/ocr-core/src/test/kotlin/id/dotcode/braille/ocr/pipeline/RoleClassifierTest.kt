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
        val columnRightMargins = lineMerger.columnRightMargins(ordered, stats)
        val groups = lineMerger.merge(ordered, stats)
        return classifier.classify(groups, stats, pageHeight, columnRightMargins, columns.bounds)
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

    // --- The local window is one-sided at the very top and bottom of the page (there is
    // nothing above block 0 or below the last block), and minNeighboursForLocalBaseline
    // used to compare the window's raw SIZE (which includes the block itself) rather
    // than its actual neighbour count, so the guard almost never triggered - a window
    // holding only the block plus two same-side neighbours (size 3) satisfied a
    // threshold of 3 even though "two neighbours, both on one side" is exactly the
    // under-supported, most bias-prone case the guard exists to catch.

    private val smallWindowConfig = StructuringConfig(localHeightWindowSize = 2, minNeighboursForLocalBaseline = 3)

    private fun classifySmallWindow(lines: List<RawLine>, pageHeight: Int = 3000): List<BlockRole> {
        val stats = PageStats.from(lines)
        val columns = ColumnSegmenter(smallWindowConfig).segment(lines, stats, 1600)
        val ordered = ReadingOrderSorter(smallWindowConfig).sort(lines, columns, stats)
        val lineMerger = LineMerger(smallWindowConfig)
        val columnRightMargins = lineMerger.columnRightMargins(ordered, stats)
        val groups = lineMerger.merge(ordered, stats)
        return RoleClassifier(smallWindowConfig).classify(groups, stats, pageHeight, columnRightMargins, columns.bounds)
    }

    @Test
    fun `the first block of a steep gradient falls back to the page median instead of a tiny one-sided window`() {
        // A small window (2) makes the boundary effect easy to trigger. Block 0's true
        // size (24) matches the rest of the page, but its two immediate FORWARD
        // neighbours (indices 1 and 2 - the only neighbours it has, since nothing sits
        // above the first block) dip to 10 as a local perspective/recognition wobble.
        // Block 0's raw window is [0, 1, 2] - size 3, which the old buggy check
        // (comparing window SIZE, not real neighbour count) accepted outright, so the
        // local median (10) made block 0 look 2.4x its neighbours - comfortably past
        // even titleHeightRatio. The page-wide median (dominated by the eight blocks
        // that are genuinely 24) reports block 0's true size correctly.
        val heights = List(10) { i -> if (i == 1 || i == 2) 10f else 24f }
        val lines = heights.mapIndexed { i, h ->
            line("Paragraf tubuh biasa nomor urut $i di halaman", 100f, 100f + i * 300f, 700f, h)
        }
        val roles = classifySmallWindow(lines)
        assertEquals(
            BlockRole.PARAGRAPH,
            roles.first(),
            "with too few real neighbours (both on one side, and locally anomalous) the " +
                "classifier must fall back to the page median rather than trust a tiny " +
                "biased local window: $roles",
        )
    }

    @Test
    fun `the last block of a steep gradient falls back to the page median instead of a tiny one-sided window`() {
        // Mirror case at the page foot: the LAST block's true size (24) matches the
        // rest of the page, but its two immediate BACKWARD neighbours (indices 7 and 8
        // - the only neighbours it has, since nothing sits below the last block) dip
        // to 10. The old buggy check trusted this tiny one-sided window and reported a
        // false HEADING; the page-wide median correctly reflects block 9's true size.
        val heights = List(10) { i -> if (i == 7 || i == 8) 10f else 24f }
        val lines = heights.mapIndexed { i, h ->
            line("Paragraf tubuh biasa nomor urut $i di halaman", 100f, 100f + i * 300f, 700f, h)
        }
        val roles = classifySmallWindow(lines)
        assertEquals(
            BlockRole.PARAGRAPH,
            roles.last(),
            "with too few real neighbours (both on one side, and locally anomalous) the " +
                "classifier must fall back to the page median rather than trust a tiny " +
                "biased local window: $roles",
        )
    }

    @Test
    fun `a two-block page falls back to the page median for both blocks`() {
        // With the default config (window 5, minNeighboursForLocalBaseline 3) a page of
        // only two blocks can never satisfy the neighbour-count guard for EITHER block
        // (each has exactly one real neighbour), so both must fall back to the whole
        // page's median line height rather than trust each other as a "local" baseline
        // of one. A naive height-ratio comparison between just these two blocks would
        // wrongly report whichever is taller as a HEADING relative to the other.
        val lines = listOf(
            line("Paragraf pendek pertama di halaman ini", 100f, 100f, 700f, 22f),
            line("Paragraf kedua yang sedikit lebih tinggi di halaman", 100f, 400f, 700f, 26f),
        )
        val roles = classify(lines)
        assertEquals(
            listOf(BlockRole.PARAGRAPH, BlockRole.PARAGRAPH),
            roles,
            "a two-block page has too little local context to trust either block as the " +
                "other's baseline: $roles",
        )
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

    // --- The bug behind the golden fixture's block 0: a document TITLE, set in a
    // smaller face than the body and further shrunk by perspective at the extreme top
    // of an angled photo, measured shorter than the body text below it and was
    // classified CAPTION - the single most misleading label available for a document's
    // own title, since a caption tells the reader "this is subordinate to something
    // else" when the truth is the opposite. A caption is inherently subordinate text
    // that belongs to something ABOVE it, so it is implausible at the very top of the
    // page, where nothing is above it. See docs/superpowers/plans/2026-08-30-caption-fix-report.md.

    @Test
    fun `a short block in the top band is not a caption`() {
        // Block 0's real geometry from the golden fixture (real-worksheet-reading.json):
        // box left 400.85, top 130.66, right 728.71, bottom 144.67 (height ~14.0), on a
        // 1200x1600 page, sitting above taller body lines (height 18-24). Before the
        // fix this classified CAPTION purely because it is short and stops well short
        // of the column's right margin - both true, but irrelevant at the top of a page
        // with nothing for it to caption.
        val lines = listOf(
            line("Academic Reading and Comprehension Worksheet", 400.85f, 130.66f, 327.86f, 14.01f),
            line("Should the Government Provide Free Nutritious Meals for Students", 200f, 300f, 800f, 20f),
            line("The provision of free nutritious meals for school students has become", 200f, 400f, 800f, 22f),
            line("an important topic of public discussion in many countries around", 200f, 440f, 800f, 24f),
            line("the world today for a variety of interconnected reasons", 200f, 480f, 800f, 18f),
        )
        assertEquals(BlockRole.PARAGRAPH, classify(lines, pageHeight = 1600).first())
    }

    @Test
    fun `a genuinely small block in the middle of the page is still a caption after the top-band fix`() {
        // Proves the top-band exemption did not simply delete caption detection: the
        // same short, single-line, margin-shortfall shape as above, but positioned well
        // clear of the top band, must still classify CAPTION.
        val lines = listOf(
            line("Paragraf normal pertama di sini saja", 100f, 700f, 700f, 30f),
            line("Paragraf normal kedua di sini saja juga", 100f, 850f, 700f, 30f),
            line("Gambar 1 rantai makanan", 100f, 1000f, 400f, 24f),
        )
        assertEquals(BlockRole.CAPTION, classify(lines, pageHeight = 2000).last())
    }

    // --- Defect A: a block sitting in a run of numbered questions must not be promoted
    // to TITLE/HEADING by height alone, even when a short "Reason: ______" line - itself
    // not a QUESTION - sits directly between it and the nearest recognized question. See
    // real-worksheet-exercises.json block 40: OCR dropped question 4's "4." marker
    // entirely, so it fails the marker branch and falls through to height-based
    // classification, where its large recognized height would otherwise make it a TITLE -
    // the single most misleading label available for an exam question.

    @Test
    fun `a tall block one Reason line past the last recognized question is not a title`() {
        // Mirrors the real fixture's shape: each question wraps onto a short second line
        // that ends in a period well short of the column's margin (a genuine paragraph
        // end, per LineMerger), so it never accidentally merges with the "Reason:" line
        // that follows it.
        val lines = listOf(
            // Establishes the column's left bound well to the left of the indented
            // question block, exactly as the real fixture's essay-then-exercises layout
            // does - without it, the question run and the candidate block would not
            // share a saturated (capped) indent level to compare against each other.
            line("Bacalah teks di atas dengan saksama sebelum menjawab soal berikut.", 100f, 100f, 900f, 30f),
            line("1. Pertanyaan pertama yang cukup panjang untuk diuji ini", 240f, 300f, 700f, 30f),
            line("dengan baik.", 240f, 332f, 200f, 30f),
            line("Reason:", 240f, 372f, 100f, 20f),
            line("2. Pertanyaan kedua yang cukup panjang untuk diuji ini", 240f, 412f, 700f, 30f),
            line("dengan baik.", 240f, 444f, 200f, 30f),
            line("Reason:", 240f, 484f, 100f, 20f),
            line("3. Pertanyaan ketiga yang cukup panjang untuk diuji ini", 240f, 524f, 700f, 30f),
            line("dengan baik.", 240f, 556f, 200f, 30f),
            line("Reason:", 240f, 596f, 100f, 20f),
            // No leading marker recognized (OCR data loss) - and tall enough to read as
            // a TITLE by height alone, exactly like the real fixture's question 4.
            line("Pertanyaan keempat tanpa penanda karena OCR gagal mengenalinya", 296f, 636f, 700f, 60f),
        )
        val roles = classify(lines)
        assertEquals(BlockRole.QUESTION, roles[1], "roles: $roles")
        assertEquals(BlockRole.QUESTION, roles[3], "roles: $roles")
        assertEquals(BlockRole.QUESTION, roles[5], "roles: $roles")
        assertEquals(
            BlockRole.PARAGRAPH,
            roles.last(),
            "a block adjacent to a run of questions must not be promoted to a title: $roles",
        )
    }

    @Test
    fun `an unrelated tall heading far from any question is unaffected`() {
        // Proves the question-run adjacency guard does not simply disable heading
        // detection everywhere: a genuine heading sitting far from any numbered question
        // (outside the adjacency window, and at a different indent) must still promote.
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

    // --- Defect B: a bare table row-number never becomes CAPTION, HEADING or TITLE. It
    // carries no size or position information a reader could trust as a structural label.

    @Test
    fun `a bare row number is a paragraph, not a caption`() {
        val lines = listOf(
            line("the act of supplying something needed for good health and growth", 244f, 300f, 700f, 40f),
            line("a lack of important nutrients in the body", 244f, 400f, 700f, 40f),
            line("22", 231f, 500f, 12f, 10f),
        )
        assertEquals(BlockRole.PARAGRAPH, classify(lines, pageHeight = 2000).last())
    }
}
