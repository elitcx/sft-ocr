package id.dotcode.braille.ocr.spelling

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.model.BlockRole
import id.dotcode.braille.ocr.model.OcrDocument
import id.dotcode.braille.ocr.model.SpellingCorrection
import id.dotcode.braille.ocr.model.TextBlock
import id.dotcode.braille.ocr.model.TextLine
import id.dotcode.braille.ocr.model.TextWord
import id.dotcode.braille.ocr.pipeline.DocumentStructurer
import id.dotcode.braille.ocr.raw.RawTextResult
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SpellCorrectorTest {

    private val small = SpellCorrector(
        SpellingDictionary.fromWordLists(
            mapOf(
                "id" to sequenceOf(
                    "sekolah 5000", "siswa 3000", "membaca 4000", "jawablah 500", "ikan 4000",
                    "kata 8000", "kita 9000", "rumah 7000", "langka 10", "air 6000",
                    "tentang 5000", "kagumi 2500", "jiwa 3000", "harapan 3000",
                ),
                "en" to sequenceOf("such 9000", "universal 3000", "only 9000", "modern 900", "family 5000", "program 4000"),
            ),
        ),
    )

    private fun fix(text: String) = small.correctText(text).first

    /** Words as the recognizers saw them: text, primary confidence, second reading. */
    private fun fix(text: String, vararg words: Triple<String, Float, String?>) =
        small.correctText(text, words.map { TextWord(it.first, it.second, it.third) }).first

    @Nested
    inner class LeftAlone {
        @Test
        fun `known words`() {
            assertEquals("siswa membaca", fix("siswa membaca"))
            assertEquals(emptyList(), small.correctText("siswa membaca").second)
        }

        @Test
        fun `a word both recognizers read identically, even if it is not a word`() {
            // A deliberate typo in an exercise, printed clearly.
            assertEquals("membca", fix("membca", Triple("membca", 0.6f, "membca")))
        }

        @Test
        fun `a word the primary recognizer is very sure of`() {
            assertEquals("Fotosintesa", fix("Fotosintesa", Triple("Fotosintesa", 0.95f, null)))
        }

        @Test
        fun `numbers and codes`() {
            assertEquals("EXP0628 72 km/jam 0,8", fix("EXP0628 72 km/jam 0,8"))
            assertEquals("BEXDORDn", fix("BEXDORDn"))
            assertEquals("2026", fix("2026", Triple("2026", 0.5f, "2024")))
        }

        @Test
        fun `capitalized words mid-sentence are treated as names`() {
            assertEquals("ke rumah Siswo", fix("ke rumah Siswo"))
        }

        @Test
        fun `a symbol the second recognizer read as a word`() {
            // Physics "V0" printed as V with a subscript; Tesseract read "Vo".
            assertEquals("kecepatan V awal", fix("kecepatan V awal", Triple("kecepatan", 0.9f, "kecepatan"), Triple("V", 0.6f, "Vo"), Triple("awal", 0.9f, "awal")))
        }

        @Test
        fun `short words without a second reading`() {
            assertEquals("kat", fix("kat"))
        }

        @Test
        fun `rare dictionary words are never suggested`() {
            assertEquals("langkx", fix("langkx"))
        }

        @Test
        fun `an ambiguous word`() {
            // "kxta" is one edit from both "kata" (8000) and "kita" (9000): no clear winner.
            assertEquals("kxta", fix("kxta"))
        }

        @Test
        fun `a word whose second reading points at a different word`() {
            // Dictionary alone says "membaca"; the second recognizer read something closer to
            // another word, so the evidence conflicts.
            assertEquals("membada", fix("membada", Triple("membada", 0.7f, "sekolaz")))
        }
    }

    @Nested
    inner class Fixed {
        @Test
        fun `a single wrong letter, recorded`() {
            val (text, corrections) = small.correctText("siswa membada")
            assertEquals("siswa membaca", text)
            assertEquals(listOf(SpellingCorrection("membada", "membaca")), corrections)
        }

        @Test
        fun `recognizer look-alikes and merged letters`() {
            assertEquals("sekolah", fix("sekclah"))
            assertEquals("modern", fix("modem"))
        }

        @Test
        fun `look-alike digits inside a word`() {
            assertEquals("sekolah", fix("sek0lah"))
            assertEquals("siswa", fix("si5wa"))
        }

        @Test
        fun `capitalization is preserved`() {
            assertEquals("SEKOLAH", fix("SEKCLAH"))
            assertEquals("Sekolah buku.", fix("Sekclah buku."))
        }

        @Test
        fun `a short word the second recognizer read as a known word`() {
            assertEquals("Air Mineral", fix("Ajr Mineral", Triple("Ajr", 0.7f, "Air")))
        }

        @Test
        fun `a name-like word the second recognizer read as a known word`() {
            assertEquals("ke Sekolah", fix("ke Sekclah", Triple("ke", 0.9f, "ke"), Triple("Sekclah", 0.6f, "Sekolah")))
        }

        @Test
        fun `words run together, split by the second recognizer`() {
            assertEquals(
                "tentang kagumi",
                fix("tentangkagumi", Triple("tentangkagumi", 0.73f, "tentang kagumi")),
            )
        }

        @Test
        fun `words run together, split by the dictionary`() {
            assertEquals("such a program", fix("sucha program"))
            assertEquals("only a family", fix("onlya family"))
        }

        @Test
        fun `a second reading that agrees with the dictionary`() {
            assertEquals(
                "siswa membaca",
                fix("siswa membada", Triple("siswa", 0.9f, "siswa"), Triple("membada", 0.7f, "membacq")),
            )
        }
    }

    @Test
    fun `document blocks carry their corrections, using each line's words`() {
        val block = TextBlock(
            id = 0, role = BlockRole.PARAGRAPH, columnIndex = 0,
            text = "siswa membca",
            lines = listOf(
                TextLine(
                    "siswa membca", BoxF(0f, 0f, 1f, 1f),
                    words = listOf(TextWord("siswa", 0.95f, "siswa"), TextWord("membca", 0.7f, "membaca")),
                ),
            ),
            box = BoxF(0f, 0f, 1f, 1f),
        )
        val document = OcrDocument(100, 100, 0f, 1, listOf(block, block.copy(id = 1, text = "siswa", lines = emptyList())))

        val corrected = small.correct(document)

        assertEquals("siswa membaca", corrected.blocks[0].text)
        assertEquals(listOf(SpellingCorrection("membca", "membaca")), corrected.blocks[0].corrections)
        assertEquals(document.blocks[1], corrected.blocks[1])
    }

    @Test
    fun `teacher words are never corrected`() {
        val base = SpellingDictionary.fromWordLists(mapOf("id" to sequenceOf("fotosintesa 40", "fotosintesis 20")))
        val withTerms = SpellCorrector(base.withExtraWords(listOf("Fotosintesiz")))
        assertEquals("fotosintesiz", withTerms.correctText("fotosintesiz").first)
    }

    /** The dictionaries the app actually ships, against real captures. */
    @Nested
    inner class BundledDictionary {
        private val assets = File("../ocr-mlkit/src/main/assets/spelling")
        private val corrector = SpellCorrector(
            SpellingDictionary.fromBundled { File(assets, it).readLines().asSequence() },
        )

        @Test
        fun `typical recognition mistakes are fixed`() {
            val cases = mapOf(
                "Jawablah pertanyaan berikut dengan benar." to "Jawablah pertanyaan berikut dengan benar.",
                "Jawablah pertanyaan berikul dengan benar." to "Jawablah pertanyaan berikut dengan benar.",
                "perhatlkan gambar di bawah ini" to "perhatikan gambar di bawah ini",
                "Tuliskan jawabanrnu" to "Tuliskan jawabanmu",
                "kel0mpok belajar" to "kelompok belajar",
                "KELOMPCK" to "KELOMPOK",
                // "sucha" is itself in the known-only list, so it stays.
                "Supporters argue that sucha program could improve chitdren's health" to
                    "Supporters argue that sucha program could improve children's health",
                "support acadernic performance" to "support academic performance",
            )
            for ((input, expected) in cases) {
                assertEquals(expected, corrector.correctText(input).first, "input: $input")
            }
        }

        /** Wrong guesses found on 139 real handbook photos (2026-09-16), now left alone. */
        @Test
        fun `names, codes, school terms and cut-off words from real photos are left alone`() {
            val lines = listOf(
                "Josefhine", "Jehezkiel", "Nathanael", "Callista", "Alloysius",
                "XII G", "Kelas X & X1", "Tim Projek IL dan P5", "oleh SMK Santo",
                "Totalitas memiliki pengertian", "nilai gotong royong", "Asesmen formatif dilakukan",
                "murid yang literat digital", "bebas kekerasan dan perundungan", "Libur Hari Raya Nyepi",
                "mpetition 2025", "ingkat SMA/SMK", "jateng", "gaimana", "Surat irin keluar",
            )
            for (line in lines) {
                assertEquals(emptyList(), corrector.correctText(line).second, line)
            }
        }

        /** A real receipt scan (2026-09-16): names, codes and acronyms in capitals. */
        @Test
        fun `capitalized names, codes and acronyms are left alone`() {
            val receipt = "0822******11 KENNETH JEHEZKIEL MARVEL WIJAY Issuer: BCA " +
                "CPAN#9360001410269271465 PAYMENT OR DATE/TINE 16 SEP, 26 RRN QRIS 495385469 TOTAL"
            assertEquals(emptyList(), corrector.correctText(receipt).second)
        }

        @Test
        fun `ground-truth pages pass through unchanged`() {
            val groundTruth = File("../ocr-mlkit/src/androidTest/assets/ground-truth")
            for (file in groundTruth.listFiles()!!.filter { it.extension == "txt" }) {
                for (line in file.readLines()) {
                    assertEquals(emptyList(), corrector.correctText(line).second, "${file.name}: $line")
                }
            }
        }

        /**
         * Every change on real recognizer output, each reviewed by hand against the page
         * (2026-09-16). Two good fixes were given up for safety: "sucha" (now a known-only word)
         * and "agdis" (a short word, so look-alikes only). These captures carry no second reading, so this is the dictionary
         * layer on its own. Any drift here needs the same review before updating the list.
         */
        @Test
        fun `real captures get exactly the reviewed corrections`() {
            val structurer = DocumentStructurer()
            val expected = mapOf(
                "real-worksheet-reading.json" to listOf(
                    "chitdren" to "children", "acadernic" to "academic",
                    "onlya" to "only a", "progran" to "program", "financialc" to "financial",
                    "mentaly" to "mentally", "activety" to "actively", "regularby" to "regularly",
                    "importantty" to "importantly", "inplementation" to "implementation",
                    "heatth" to "health", "reguirements" to "requirements",
                    "fo0ds" to "foods",
                ),
                "real-worksheet-exercises.json" to listOf(
                    "particularty" to "particularly", "difficuties" to "difficulties",
                    "foodsafety" to "food safety", "onty" to "only", "quickty" to "quickly",
                    "extremety" to "extremely", "systerm" to "system", "Auniversal" to "A universal",
                ),
                // "agdis" -> "gadis" is right, but a short word is only fixed for a look-alike.
                "real-rotated-230941-raw.json" to emptyList(),
                "real-rotated-231023-raw.json" to listOf("dengarn" to "dengan"),
                "real-facing-page-231108-raw.json" to listOf("dikembangkaň" to "dikembangkan"),
            )
            for ((name, fixes) in expected) {
                val json = checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")).bufferedReader().readText()
                val document = corrector.correct(structurer.structure(RawTextResult.fromJson(json)))
                assertEquals(
                    fixes.map { (from, to) -> SpellingCorrection(from, to) },
                    document.blocks.flatMap { it.corrections },
                    name,
                )
            }
        }

        @Test
        fun `a page of unknown words is corrected quickly`() {
            val page = "Soal " + List(60) { "pertanyaen perhatlkan kelornpok menentukam" }.joinToString(" ")
            corrector.correctText("warm up")
            val started = System.nanoTime()
            val (_, corrections) = corrector.correctText(page)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(240, corrections.size)
            assertTrue(elapsedMs < 1000, "took $elapsedMs ms")
        }
    }
}
