package id.dotcode.braille.ocr.app

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.model.BlockRole
import id.dotcode.braille.ocr.model.OcrDocument
import id.dotcode.braille.ocr.model.TextBlock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test

class GeminiProtocolTest {

    private fun document(vararg texts: String) = OcrDocument(
        pageWidth = 100, pageHeight = 100, skewDeg = 0f, columnCount = 1,
        blocks = texts.mapIndexed { i, text ->
            TextBlock(
                id = i, role = BlockRole.PARAGRAPH, columnIndex = 0, marker = if (i == 0) "1." else null,
                text = text, lines = emptyList(), box = BoxF(0f, 0f, 1f, 1f),
            )
        },
    )

    private fun response(text: String) = JSONObject()
        .put(
            "candidates",
            JSONArray().put(
                JSONObject().put(
                    "content",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text))),
                ),
            ),
        )
        .toString()

    @Test
    fun `request sends block texts as a JSON array and asks for an array back`() {
        val body = JSONObject(GeminiProtocol.requestBody(listOf("Jawablah \"soal\"", "kel0mpok")))

        val sent = body.getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0).getString("text")
        assertEquals(listOf("Jawablah \"soal\"", "kel0mpok"), JSONArray(sent).let { a -> List(a.length()) { a.getString(it) } })
        val config = body.getJSONObject("generationConfig")
        assertEquals("application/json", config.getString("responseMimeType"))
        assertEquals("ARRAY", config.getJSONObject("responseSchema").getString("type"))
        assertTrue(body.getJSONObject("systemInstruction").toString().contains("intentional"))
    }

    @Test
    fun `parses the corrected array, skipping thought parts`() {
        val raw = JSONObject().put(
            "candidates",
            JSONArray().put(
                JSONObject().put(
                    "content",
                    JSONObject().put(
                        "parts",
                        JSONArray()
                            .put(JSONObject().put("thought", true).put("text", "thinking..."))
                            .put(JSONObject().put("text", """["a","b"]""")),
                    ),
                ),
            ),
        ).toString()

        assertEquals(listOf("a", "b"), GeminiProtocol.parseCorrections(raw, expectedCount = 2))
    }

    @Test
    fun `a reply with the wrong number of blocks is rejected`() {
        assertFailsWith<GeminiException> {
            GeminiProtocol.parseCorrections(response("""["only one"]"""), expectedCount = 2)
        }
    }

    @Test
    fun `a reply that is not JSON is rejected`() {
        assertFailsWith<GeminiException> { GeminiProtocol.parseCorrections(response("Sure! Here"), 1) }
        assertFailsWith<GeminiException> { GeminiProtocol.parseCorrections("""{"candidates":[]}""", 1) }
    }

    @Test
    fun `fixes are applied per block and counted, structure is kept`() {
        val original = document("Jawablah pertanyaan berikul", "kel0mpok belajar")

        val (corrected, changed) = GeminiProtocol.apply(
            original,
            listOf("Jawablah pertanyaan berikut", "kelompok belajar"),
        )

        assertEquals(2, changed)
        assertEquals(listOf("Jawablah pertanyaan berikut", "kelompok belajar"), corrected.blocks.map { it.text })
        assertEquals(original.blocks.map { it.copy(text = "") }, corrected.blocks.map { it.copy(text = "") })
    }

    @Test
    fun `rewrites and empty replies are not accepted`() {
        val original = document("Hitung luas persegi panjang", "2 + 2 = 4")

        val (corrected, changed) = GeminiProtocol.apply(
            original,
            listOf("Tentukan berapa luas bangun datar ini", "  "),
        )

        assertEquals(0, changed)
        assertEquals(original, corrected)
    }

    @Test
    fun `a correction that changes any number is rejected`() {
        val original = document("Bab 3 Tahun Ajaran 2026", "Halaman l2 dari 2O")

        val (corrected, changed) = GeminiProtocol.apply(
            original,
            listOf("Bab 3 Tahun Ajaran 2024", "Halaman 12 dari 20"),
        )

        assertEquals(1, changed)
        assertEquals("Bab 3 Tahun Ajaran 2026", corrected.blocks[0].text)
        assertEquals("Halaman 12 dari 20", corrected.blocks[1].text)
    }

    @Test
    fun `digits inside words may still be fixed`() {
        val (corrected, changed) = GeminiProtocol.apply(
            document("kel0mpok 5 siswa"),
            listOf("kelompok 5 siswa"),
        )
        assertEquals(1, changed)
        assertEquals("kelompok 5 siswa", corrected.blocks[0].text)
    }

    /** Every change Gemini made to a real receipt scan (2026-09-16); only the year was wrong. */
    @Test
    fun `real receipt scan keeps good fixes and drops the changed year`() {
        val fixes = listOf(
            "Kagumt" to "Kagumi",
            "Out let 1" to "Outlet 1",
            "16 Sep 2026" to "16 Sep 2024",
            "Bil1 Name" to "Bill Name",
            "*1, DINE IN#" to "*1, DINE IN*",
            "Ns Gila Beneran 1k" to "Ns Gila Beneran 1x",
            "010.000" to "@10.000",
            "BCA OR" to "BCA QR",
            "Notes WFi Pass tentangkagumi" to "Notes WiFi Pass tentangkagumi",
        )

        val (corrected, changed) = GeminiProtocol.apply(
            document(*fixes.map { it.first }.toTypedArray()),
            fixes.map { it.second },
        )

        assertEquals(8, changed)
        assertEquals(
            fixes.map { (before, after) -> if (before == "16 Sep 2026") before else after },
            corrected.blocks.map { it.text },
        )
    }

    @Test
    fun `error responses become readable messages`() {
        val badKey = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key."}}"""
        assertEquals("Kunci API Gemini tidak valid.", GeminiProtocol.errorMessage(400, badKey))
        assertTrue(GeminiProtocol.errorMessage(429, "{}").contains("Kuota"))
        assertTrue(GeminiProtocol.errorMessage(503, "{}").contains("sibuk"))
        assertTrue(GeminiProtocol.errorMessage(502, "not json").contains("502"))
    }

    @Test
    fun `change ratio`() {
        assertEquals(0.0, GeminiProtocol.changeRatio("abc", "abc"))
        assertEquals(0.125, GeminiProtocol.changeRatio("kel0mpok", "kelompok"))
        assertEquals(1.0, GeminiProtocol.changeRatio("", "abc"))
    }
}
