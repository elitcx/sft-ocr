package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.model.Alignment
import id.dotcode.braille.ocr.model.BlockRole
import id.dotcode.braille.ocr.model.Timings
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
