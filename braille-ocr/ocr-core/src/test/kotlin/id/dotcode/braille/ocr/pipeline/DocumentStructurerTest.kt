package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.geometry.PointF
import id.dotcode.braille.ocr.model.Alignment
import id.dotcode.braille.ocr.model.BlockRole
import id.dotcode.braille.ocr.model.Timings
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DocumentStructurerTest {
    private val structurer = DocumentStructurer()

    @Test
    fun `an empty page yields an empty document`() {
        val doc = structurer.structure(page())
        assertEquals(0, doc.blocks.size)
        assertEquals(1, doc.columnCount)
    }

    @Test
    fun `a numbered worksheet yields addressable questions with markers stripped`() {
        val doc = structurer.structure(
            page(
                line("LEMBAR KERJA IPA", 100f, 60f, 600f, 56f),
                line("1. Sebutkan tiga contoh sumber", 100f, 300f, 700f, 30f),
                line("daya alam yang dapat diperbarui", 100f, 336f, 700f, 30f),
                line("2. Jelaskan proses fotosintesis", 100f, 420f, 700f, 30f),
                line("3. Apa fungsi akar pada tumbuhan", 100f, 500f, 700f, 30f),
            )
        )

        assertEquals(BlockRole.TITLE, doc.blocks.first().role)

        val questions = doc.blocks.filter { it.role == BlockRole.QUESTION }
        assertEquals(3, questions.size)
        assertEquals(listOf("1.", "2.", "3."), questions.map { it.marker })
        assertEquals(
            "Sebutkan tiga contoh sumber daya alam yang dapat diperbarui",
            questions.first().text,
        )
        // Original line breaks survive reflow.
        assertEquals(2, questions.first().lines.size)
    }

    @Test
    fun `block ids are sequential in reading order`() {
        val doc = structurer.structure(
            page(
                line("kanan satu", 900f, 100f, 500f, 30f),
                line("kiri satu", 100f, 100f, 500f, 30f),
                line("kanan dua", 900f, 300f, 500f, 30f),
                line("kiri dua", 100f, 300f, 500f, 30f),
            )
        )
        assertEquals(listOf(0, 1, 2, 3), doc.blocks.map { it.id })
        assertEquals("kiri satu", doc.blocks.first().text)
        assertEquals(2, doc.columnCount)
    }

    @Test
    fun `centered text is reported as centered`() {
        val doc = structurer.structure(
            page(
                line("Judul Di Tengah", 600f, 60f, 400f, 30f),
                line("baris kiri yang panjang sekali", 100f, 300f, 1400f, 30f),
                line("baris kiri lain yang panjang", 100f, 340f, 1400f, 30f),
            )
        )
        assertEquals(Alignment.CENTER, doc.blocks.first().alignment)
    }

    @Test
    fun `timings pass through untouched and mean confidence is averaged`() {
        val doc = structurer.structure(
            page(
                line("satu dua tiga", 100f, 100f, 400f, 30f, confidence = 0.8f),
                line("empat lima enam", 100f, 300f, 400f, 30f, confidence = 0.6f),
            ),
            Timings(decodeMs = 5, preprocessMs = 6, recognizeMs = 100, structureMs = 2),
        )
        assertEquals(113L, doc.timings.totalMs)
        assertTrue(doc.meanConfidence!! in 0.69f..0.71f)
    }

    @Test
    fun `two column worksheet fixture keeps questions in order`() {
        val json = checkNotNull(javaClass.getResourceAsStream("/fixtures/worksheet-two-column.json"))
            .bufferedReader().readText()
        val raw = kotlinx.serialization.json.Json.decodeFromString(
            id.dotcode.braille.ocr.raw.RawTextResult.serializer(), json,
        )
        val doc = DocumentStructurer().structure(raw)
        val markers = doc.blocks
            .filter { it.role == id.dotcode.braille.ocr.model.BlockRole.QUESTION }
            .mapNotNull { it.marker }
        assertEquals(listOf("1.", "2.", "3.", "4.", "5.", "6."), markers)
    }

    @Test
    fun `a full width title does not scramble two column reading order`() {
        // The regression behind CRITICAL 1. The title spans the gutter (1340 > 0.8 *
        // 1600), so under the old interval-merging it unioned the left and right runs,
        // the page reported ONE column, and reading order came out 1, 4, 2, 5, 3, 6.
        val doc = structurer.structure(
            page(
                line("LEMBAR KERJA IPA KELAS ENAM", 60f, 60f, 1340f, 56f),
                line("1. Sebutkan tiga sumber daya alam", 100f, 300f, 500f, 30f),
                line("2. Jelaskan proses fotosintesis", 100f, 400f, 500f, 30f),
                line("3. Apa fungsi akar pada tumbuhan", 100f, 500f, 500f, 30f),
                line("4. Sebutkan tiga hewan herbivora", 900f, 300f, 500f, 30f),
                line("5. Jelaskan siklus hidup kupu-kupu", 900f, 400f, 500f, 30f),
                line("6. Apa manfaat matahari bagi tumbuhan", 900f, 500f, 500f, 30f),
            )
        )
        assertEquals(2, doc.columnCount)
        assertEquals(
            listOf("1.", "2.", "3.", "4.", "5.", "6."),
            doc.blocks.filter { it.role == BlockRole.QUESTION }.mapNotNull { it.marker },
        )
        // Left column is read out entirely before the right one.
        assertEquals(
            listOf(0, 0, 0, 0, 1, 1, 1),
            doc.blocks.map { it.columnIndex },
        )
    }

    @Test
    fun `a centred full width title is the first block in reading order`() {
        // The coin flip, end to end. Left column 100..640, right column 860..1500, so
        // the gutter midpoint is 750; the title spans 100..1500 with its centre at 800.
        // Under centre-based assignment the title is pinned to column 1 and, because
        // ReadingOrderSorter is column-major, it comes out as block 3 — AFTER all three
        // left-column questions. That is the same scrambled reading order CRITICAL 1
        // existed to eliminate, surviving in the half that only shows up on real
        // worksheets. Leftmost-overlap assignment makes it deterministic.
        val doc = structurer.structure(
            page(
                line("LEMBAR KERJA IPA KELAS ENAM SEMESTER SATU", 100f, 60f, 1400f, 56f),
                line("1. Sebutkan sumber daya alam", 100f, 300f, 540f, 30f),
                line("2. Jelaskan fotosintesis", 100f, 400f, 540f, 30f),
                line("3. Apa fungsi akar tumbuhan", 100f, 500f, 540f, 30f),
                line("4. Sebutkan hewan herbivora", 860f, 300f, 640f, 30f),
                line("5. Jelaskan siklus kupu-kupu", 860f, 400f, 640f, 30f),
                line("6. Apa manfaat matahari", 860f, 500f, 640f, 30f),
            )
        )
        assertEquals(2, doc.columnCount)

        val first = doc.blocks.first()
        assertEquals(0, first.id)
        assertEquals(BlockRole.TITLE, first.role, "first block was '${first.text}'")
        assertTrue(
            first.text.startsWith("LEMBAR KERJA IPA"),
            "the title must be read before any question, but block 0 was '${first.text}'",
        )
        assertEquals(
            listOf("1.", "2.", "3.", "4.", "5.", "6."),
            doc.blocks.filter { it.role == BlockRole.QUESTION }.mapNotNull { it.marker },
        )
    }

    @Test
    fun `a skewed page still classifies its title as a title`() {
        // The regression behind CRITICAL 2. Before the fix, deskew rebuilt each box as
        // the AABB of the rotated AABB, inflating height by w*sin+h*cos. At 8 degrees
        // the 600x56 title measured 139.0 tall and the 700x30 body lines 127.1, so the
        // title's relative height fell to ~1.09 and it classified as PARAGRAPH. Using
        // the real corner points, the deskewed heights come back at their true 56 and
        // 30, giving a relative height of 1.87 and a TITLE.
        val pivot = PointF(800f, 1000f)
        val doc = structurer.structure(
            page(
                skewedLine("LEMBAR KERJA IPA", 100f, 60f, 600f, 56f, 8f, pivot),
                skewedLine("Bacalah teks berikut dengan saksama", 100f, 300f, 700f, 30f, 8f, pivot),
                skewedLine("lalu jawablah pertanyaan yang ada", 100f, 340f, 700f, 30f, 8f, pivot),
                skewedLine("bersama teman sebangkumu di kelas", 100f, 380f, 700f, 30f, 8f, pivot),
                skewedLine("dengan tulisan yang rapi dan jelas", 100f, 420f, 700f, 30f, 8f, pivot),
            )
        )
        assertTrue(abs(doc.skewDeg - 8f) < 0.01f, "expected 8 degrees of skew, got ${doc.skewDeg}")
        val title = doc.blocks.first()
        assertEquals(BlockRole.TITLE, title.role, "title relativeTextHeight=${title.relativeTextHeight}")
        assertTrue(
            title.relativeTextHeight > 1.6f,
            "expected the deskewed title to stay ~1.87x body height, got ${title.relativeTextHeight}",
        )
    }

    @Test
    fun `a near full width block is not reported as centered`() {
        // This line alone establishes column bounds of 100..1500 (width 1400). The
        // target line below sits at 150..1450 — its midpoint (800) lands exactly on the
        // column centre (800), so without the side-margin guard it would qualify as
        // CENTER. Its margins (50 on each side) are far below minSideMarginFraction
        // (0.15 * 1400 = 210), so the guard must force LEFT instead.
        val doc = structurer.structure(
            page(
                line("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", 100f, 60f, 1400f, 30f),
                line(
                    "target block text that spans nearly the whole column width",
                    150f,
                    300f,
                    1300f,
                    30f,
                ),
            )
        )
        val target = doc.blocks.first { it.text.startsWith("target block") }
        assertEquals(Alignment.LEFT, target.alignment)
    }

    @Test
    fun `mean confidence is null when no line reports confidence`() {
        val doc = structurer.structure(
            page(
                line("baris satu tanpa confidence", 100f, 100f, 400f, 30f, confidence = null),
                line("baris dua tanpa confidence", 100f, 300f, 400f, 30f, confidence = null),
            )
        )
        assertEquals(null, doc.meanConfidence)
    }
}
