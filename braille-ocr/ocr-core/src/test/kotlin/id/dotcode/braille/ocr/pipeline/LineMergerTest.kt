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

    private fun rightMargins(lines: List<id.dotcode.braille.ocr.raw.RawLine>): Map<Int, Float> {
        val stats = PageStats.from(lines)
        val columns = ColumnSegmenter(config).segment(lines, stats, 1600)
        val ordered = ReadingOrderSorter(config).sort(lines, columns, stats)
        return merger.columnRightMargins(ordered, stats)
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
    fun `KNOWN GAP - a final full-width line ending in a period can swallow the next paragraph`() {
        // Pins current behaviour rather than fixing it (see the coverage-hardening
        // report for the acceptability call). The line-end-tolerance fix in
        // `continues()` cannot distinguish "this line reaches the margin because it is
        // mid-paragraph" from "this line reaches the margin because it is the paragraph's
        // OWN final line and the sentence simply runs long" - both look identical: full
        // width, terminal punctuation. When the next paragraph starts immediately below
        // with no indent and a tight (sub-paragraphGapFactor) vertical gap, it merges
        // into the first paragraph's block instead of starting a new one. Reading order
        // and content are still correct (nothing is lost, nothing reordered) - only the
        // paragraph boundary itself is lost.
        val paragraphOne = listOf(
            line(
                "Pemberian program makan siang gratis untuk siswa sekolah telah menjadi topik pembahasan",
                214f, 203f, 700f, 20f,
            ),
            // This IS the paragraph's last line. It happens to reach the same right
            // margin as every other body line and ends with a period.
            line(
                "publik yang penting dan terus dibicarakan oleh banyak pihak yang peduli pendidikan.",
                214f, 224f, 700f, 20f,
            ),
        )
        val paragraphTwo = line(
            "Argumen kedua yang juga penting untuk dipertimbangkan secara mendalam adalah biaya.",
            214f, 245f, 700f, 20f, // tight gap, no indent
        )
        val result = groups(paragraphOne + listOf(paragraphTwo))

        // Pinned: they merge into ONE block, not two.
        assertEquals(1, result.size)
        assertEquals(3, result.first().lines.size)
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

    // --- columnRightMargins outlier guard. Previously untested, and previously measured
    // its "is this an outlier" distance in a column-local median character width while
    // every OTHER use of the same tolerance factor (continues(), isWrappedFullWidth(),
    // isCaption()) measured in the page-wide stats.medianCharWidth - two rulers for one
    // margin. Both the guard and its callers now use stats.medianCharWidth via the
    // dedicated marginOutlierFactor field.

    @Test
    fun `a single anomalously long line does not inflate the column's right margin`() {
        val lines = listOf(
            line("baris pendek satu di sini", 100f, 100f, 600f, 30f), // right = 700
            line("baris pendek dua di sini", 100f, 140f, 605f, 30f), // right = 705
            // A spanning line pinned into this column: far past every genuine line.
            line("baris yang sangat sangat panjang sekali melebar penuh", 100f, 180f, 1300f, 30f), // right = 1400
        )
        val margins = rightMargins(lines)
        assertEquals(705f, margins.getValue(0), 0.01f, "the 1400 outlier must not become the margin")
    }

    @Test
    fun `normal lines of similar width report the widest as the margin`() {
        val lines = listOf(
            line("baris pendek satu di sini", 100f, 100f, 600f, 30f), // right = 700
            line("baris pendek dua di sini", 100f, 140f, 605f, 30f), // right = 705
            line("baris pendek tiga di sini", 100f, 180f, 610f, 30f), // right = 710
        )
        val margins = rightMargins(lines)
        assertEquals(710f, margins.getValue(0), 0.01f)
    }

    @Test
    fun `two long lines close in width both stand - dropping a single outlier is not enough to shrink the margin`() {
        // Pins current behaviour: the guard only ever excludes the SINGLE widest line
        // by comparing it against the second-widest. When two lines are both anomalously
        // wide and close to each other, the second-widest is ALSO an outlier, so the gap
        // it is compared against (widest vs second-widest) is small and the guard does
        // not fire - the margin stays the true widest line.
        val lines = listOf(
            line("baris pendek satu di sini", 100f, 100f, 600f, 30f), // right = 700
            line("baris panjang dua yang melebar hampir penuh kolom", 100f, 140f, 1290f, 30f), // right = 1390
            line("baris panjang tiga yang melebar hampir penuh kolom", 100f, 180f, 1300f, 30f), // right = 1400
        )
        val margins = rightMargins(lines)
        assertEquals(1400f, margins.getValue(0), 0.01f, "two close wide lines both survive as legitimate")
    }

    // --- Defect B: consecutive bare table row-numbers must not merge into one block.
    // Real coordinates from real-worksheet-exercises.json: "18" and "19" are two separate
    // rows of the vocabulary table's No. column, stacked with a tight leading gap and
    // aligned left edges - the same shape as an ordinary paragraph wrap - but merging
    // them produces "18 19", a value that identifies neither row.

    @Test
    fun `two stacked bare row numbers do not merge into one block (real worksheet regression)`() {
        val lineEighteen = line("18", 226f, 920f, 240f - 226f, 933f - 920f)
        val lineNineteen = line("19", 226f, 944f, 240f - 226f, 957f - 944f)
        val result = groups(listOf(lineEighteen, lineNineteen))
        assertEquals(2, result.size, "each row number must stand as its own block")
        assertEquals("18", LineMerger.reflow(result[0].lines))
        assertEquals("19", LineMerger.reflow(result[1].lines))
    }

    @Test
    fun `a bare number followed by ordinary prose still merges normally`() {
        // Proves the bare-number guard is specific to TWO bare numbers, not to any block
        // that happens to start with digits - a numbered marker line like "1. Soal" is
        // already excluded by the marker check above it, and ordinary text following a
        // lone number (not itself bare-numeric) is unaffected.
        val lines = listOf(
            line("Sumber daya alam adalah kekayaan", 100f, 100f, 600f, 30f),
            line("yang tersedia di alam sekitar kita", 100f, 136f, 600f, 30f),
        )
        assertEquals(1, groups(lines).size)
    }
}
