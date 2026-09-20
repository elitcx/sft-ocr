package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.accuracy.DocumentFlattener
import id.dotcode.braille.ocr.accuracy.ErrorRate
import id.dotcode.braille.ocr.raw.RawTextResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reading order on a curved page, from a real capture.
 *
 * Fixture `real-curved-230858-raw.json` is the recognizer's own output for
 * `20260731_230858.jpg`, a handbook page photographed with the paper curving away from
 * the spine. Measured end to end through the app, that page scores 23% CER while only
 * 3.6% of the corpus error is substitutions — the words are read correctly and then
 * emitted in the wrong order.
 *
 * The cause is upstream of every stage in [DocumentStructurer]. On a curved page the
 * recognizer groups words into a "line" by baseline proximity, and words at different
 * x-positions sit at different y, so it chains across the curve: line 3 of this fixture
 * reads "puluh panjang yang" from word boxes at x=462, x=964 and x=1058, skipping
 * "lima tahun adalah sebuah perjalanan" (x=533..949) which is physically between them on
 * the same printed row. Every pipeline stage operates on whole lines, so none of them can
 * repair a line whose own text is already interleaved.
 *
 * These tests pin the printed reading order of the first two rows. They are written
 * against the page as a person reads it, not against any particular repair strategy.
 */
class CurvedPageWordOrderTest {

    private fun structure(fixture: String): String {
        val json = checkNotNull(javaClass.getResourceAsStream("/fixtures/$fixture"))
            .bufferedReader().use { it.readText() }
        // rotation 90: this capture's EXIF orientation is 6, and the raw-dump harness
        // recorded `rotation=90` for it. Feeding 0 makes RotationPlausibilityGuard turn an
        // already-upright page on its side, which is a fault of the test, not the pipeline.
        val config = StructuringConfig(rebuildRowsFromWords = true)
        val document = DocumentStructurer(config).structure(RawTextResult.fromJson(json), rotationDegrees = 90)
        return DocumentFlattener.flatten(document).replace(Regex("\\s+"), " ")
    }

    private fun structure(fixture: String, rebuild: Boolean): String {
        val config = StructuringConfig(rebuildRowsFromWords = rebuild)
        val document = DocumentStructurer(config).structure(rawOf(fixture), rotationDegrees = 90)
        return DocumentFlattener.flatten(document)
    }

    private fun rawOf(fixture: String): RawTextResult {
        val json = checkNotNull(javaClass.getResourceAsStream("/fixtures/$fixture"))
            .bufferedReader().use { it.readText() }
        return RawTextResult.fromJson(json)
    }

    private fun groundTruth(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name"))
            .bufferedReader().use { it.readText() }

    private fun cer(ref: String, hyp: String): Double = ErrorRate.compare(ref, hyp).cer

    @Test
    fun `reads the opening sentence of a curved page in printed order`() {
        val text = structure("real-curved-230858-raw.json")
        assertTrue(
            text.contains("Tujuh puluh lima tahun adalah sebuah perjalanan panjang yang"),
            "opening sentence is out of printed order.\nGot: ${text.take(220)}",
        )
    }

    @Test
    fun `keeps a date in printed order on a curved page`() {
        val text = structure("real-curved-230858-raw.json")
        assertTrue(
            text.contains("17 Juli 1951"),
            "date emitted out of order (expected '17 Juli 1951').\nGot: ${text.take(260)}",
        )
    }

    @Test
    fun `an intruding facing page is not absorbed into a rebuilt row`() {
        // `real-spread-223954-raw.json` is `20260919_223954.jpg`, an open-book spread.
        // FrameEdgeFragmentFilter.filter rejects the facing page as SHORT LINES AT THE
        // FRAME EDGE - a signal that exists only while the recognizer's own line grouping
        // does. Rebuilding rows destroys it: the fragments get absorbed into long rows,
        // stop looking short, and survive. Measured on this fixture, that is 525
        // characters of facing-page text and CER 38.76% -> 67.58%.
        val text = structure("real-spread-223954-raw.json")
        for (fragment in FACING_PAGE_FRAGMENTS) {
            assertFalse(
                text.contains(fragment),
                "facing-page fragment '$fragment' leaked into the rebuilt rows. Got: ${text.take(400)}",
            )
        }
    }

    @Test
    fun `rebuilding rows never makes an open-book spread worse`() {
        // The acceptance criterion the previous attempt failed. Rebuilding is only worth
        // enabling if it cannot damage a page it was not meant to help: on this spread the
        // gate DOES fire (108 lines -> 75 rows), so the rebuild happens and must still not
        // cost accuracy.
        // NOTE: real-spread-223954.txt is the AI_TRANSCRIBED transcript, not human-verified
        // ground truth. That is fine here: the assertion is RELATIVE (on vs off against the
        // same reference), so an imperfect reference cancels out. Do not quote this CER as
        // an accuracy figure.
        val ref = groundTruth("real-spread-223954.txt")
        val off = cer(ref, structure("real-spread-223954-raw.json", rebuild = false))
        val on = cer(ref, structure("real-spread-223954-raw.json", rebuild = true))
        assertTrue(
            on <= off + 0.005,
            "rebuilding made this spread worse: CER ${pct(off)} -> ${pct(on)}",
        )
    }

    @Test
    fun `the real page survives the rebuild on an open-book spread`() {
        // The counterweight: keeping the facing page out must not be achieved by
        // discarding the page the camera was actually pointed at.
        val text = structure("real-spread-223954-raw.json")
        assertTrue(
            text.contains("Zaman mendengarkan saksama setiap kata"),
            "the real page's content was lost by the rebuild. Got: ${text.take(400)}",
        )
    }

    @Test
    fun `a spread the gate declines is left exactly as it was`() {
        // `real-facing-page-231108-raw.json` is another open-book spread, but its lines are
        // NOT cross-chained, so the gate declines it and the recognizer's grouping stands.
        // Pins that "rebuilding is off by default for this page" stays true: the existing
        // line-level facing-page rejection in DocumentStructurerTest still guards its output.
        val rebuilt = WordRowRebuilder.rebuild(rawOf("real-facing-page-231108-raw.json"), REBUILDING, rotationDegrees = 90)
        assertEquals(
            rawOf("real-facing-page-231108-raw.json").lines.size,
            rebuilt.lines.size,
            "the cross-chain gate fired on a page it should have declined",
        )
    }

    private companion object {
        val REBUILDING = StructuringConfig(rebuildRowsFromWords = true)

        /** Fragments of the FACING page of `20260919_223954.jpg`, absent from its transcript. */
        val FACING_PAGE_FRAGMENTS = listOf(
            "menyet", "mengusap", "trili", "surat-m", "ratusar", "jompo",
        )

        fun pct(v: Double) = String.format("%.2f%%", v * 100)
    }
}
