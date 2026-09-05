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
    fun `a justified paragraph with sentence-ending wraps stays one block, followed by a second paragraph`() {
        // End-to-end regression for BUG 1: several full-width lines whose sentences
        // happen to end at the line break, followed by a genuinely short final line,
        // then a second paragraph with the same shape. Must come back as exactly two
        // blocks, not one split per sentence.
        val doc = structurer.structure(
            page(
                line(
                    "Pemberian program makan siang gratis untuk siswa sekolah telah menjadi topik pembahasan publik yang penting.",
                    214f, 203f, 700f, 20f,
                ),
                line(
                    "Para pendukung berpendapat bahwa program semacam ini dapat meningkatkan kesehatan anak dan performa akademik",
                    214f, 224f, 700f, 20f,
                ),
                line(
                    "serta membantu keluarga kurang mampu memenuhi kebutuhan gizi harian anak-anak mereka setiap hari.",
                    214f, 245f, 700f, 20f,
                ),
                line("Itulah gambaran singkatnya.", 214f, 266f, 200f, 20f),
                line(
                    "Namun demikian terdapat pula tantangan besar terkait pembiayaan dan distribusi program tersebut.",
                    214f, 300f, 700f, 20f,
                ),
                line("Itulah tantangannya.", 214f, 321f, 200f, 20f),
            )
        )

        assertEquals(2, doc.blocks.size)
        assertEquals(4, doc.blocks[0].lines.size)
        assertEquals(2, doc.blocks[1].lines.size)
        assertTrue(doc.blocks[0].text.startsWith("Pemberian program makan siang gratis"))
        assertTrue(doc.blocks[0].text.endsWith("Itulah gambaran singkatnya."))
        assertTrue(doc.blocks[1].text.startsWith("Namun demikian terdapat pula tantangan"))
        assertTrue(doc.blocks[1].text.endsWith("Itulah tantangannya."))
    }

    @Test
    fun `golden end-to-end fixture - a real photographed worksheet reads in true order with no false headings`() {
        // 61 REAL RawLine records captured from an actual photographed page: an English
        // essay titled "Should the Government Provide Free Nutritious Meals for
        // Students?", preceded by two small worksheet header lines. This is the only
        // test in the suite that measures the thing that actually matters: would a
        // blind student reading this block-by-block get the same content, in the same
        // order, that a sighted reader gets from the photo? The OCR itself made several
        // errors ("sucha", "chitdren's", "acadernic", "heatth", "fo0ds") - those are ML
        // Kit's and are pinned here VERBATIM. Correcting them would be a different bug:
        // silently rewriting what the page actually says.
        val json = checkNotNull(javaClass.getResourceAsStream("/fixtures/real-worksheet-reading.json"))
            .bufferedReader().readText()
        val raw = kotlinx.serialization.json.Json.decodeFromString(
            id.dotcode.braille.ocr.raw.RawTextResult.serializer(), json,
        )
        val doc = DocumentStructurer().structure(raw)

        assertEquals(1, doc.columnCount, "this page is a single column of prose")

        val expectedTexts = listOf(
            "Academic Reading and Comprehension Worksheet",
            "Advanced English-TKA Practice, 9th Period - Second Meeting",
            "Should the Government Provide Free Nutritious Meals for Students?",
            "The provision of free nutritious meals for school students has become an " +
                "important topic of public discussion. Supporters argue that sucha program " +
                "could improve chitdren's health, support acadernic performance, and reduce " +
                "inequality among students from different socioeconomic backgrounds. They " +
                "contend that access to nutritious food is not onlya family responsibility " +
                "but also an important component of educational development. Opponents, " +
                "however, question whether governments can implement such a large-scale " +
                "progran effectively and sustainably. They also raise Concerns about " +
                "financialc cost, food waste, administrative complexity, and the challenge " +
                "of maintaining food quality and safety. For these reasons, the provision " +
                "of free nutritious meals deserves careful consideration from multiple " +
                "perspectives.",
            "One of the strongest arguments in favor of free nutritious meals is their " +
                "potential to support students' overall health. Some children may arrive at " +
                "school without an adequate breakfast or may consume meals that do not " +
                "provide essential nutrients. A balanced meal containing protein, " +
                "carbohydrates, healthy fats, vitamins, and minerals can contribute to " +
                "physical growth and development. Adequate nutrition can also support " +
                "immune function and help reduce the risk of nutritional deficiencies.",
            "Furthermore, establishing healthy eating habits at an early age may influence " +
                "students' lifestyle choices in the future. If schools consistently provide " +
                "nutritious food, students may become more familiar with balanced diets and " +
                "develop a greater appreciation of healthy eating. In the long term, such a " +
                "program could contribute to a healthier population and may help reduce the " +
                "prevalence of diet-related health problems.",
            "Another important argument is that adequate nutrition can support students' " +
                "cognitive functioning. Students who experience hunger during school hours " +
                "may find it difficult to concentrate, may become fatigued, or may " +
                "participate less actively in classroom activities. By contrast, students " +
                "who receive sufficient nutrients may be more likely to have the energy and " +
                "mental alertness required for learning.",
            "Free nutritious meals could therefore contribute indirectly to improved " +
                "educational outcomes. When students are physically comfortable and " +
                "mentaly prepared, they may be more attentive during lessons, participate " +
                "more activety in discussions, and complete academic tasks more " +
                "effectively. Nutrition alone cannot guarantee academic success, but it can " +
                "provide an important foundation for effective learning",
            "A third argument in favor of the program is that it could help reduce " +
                "socioeconomic disparities among students. Families have different " +
                "financial circumstances, and some parents may find it difficult to " +
                "provide nutritious meals regularby. As a result, students from " +
                "low-income households mnay face nutritional disadvantages compared with " +
                "their more affluent classmates.",
            "A universal free-meal program could provide every student with access to at " +
                "least one nutritious meal, regardless of family income. This approach " +
                "could also reduce the stigma that might arise if assistance were provided " +
                "only to students from disadvantaged households. More importantty, it " +
                "could help ensure that poverty does not become a major barrier to " +
                "students' health and educational opportunities.",
            "Despite these potential benefits, a free nutritious meal program could impose " +
                "a substantial financial burden on the government. Providing meals to " +
                "large numbers of students would require continuing funding for food " +
                "ingredients, kitchen facilities, transportation, storage, cooking staff, " +
                "packaging, and waste management. These expenses would not be limited to " +
                "the initiatl inplementation of the program but would continue from year " +
                "to year.",
            "Critics argue that public budgets are limited and must be allocated across " +
                "sectors such as education, heatth care. infrastructure. and social " +
                "welfare. If an excessive share of funding were directed toward free " +
                "meals, other important educational programs might receive insufficient " +
                "support. Policymakers would therefore need to conduct a thorough " +
                "cost-benefit analysis before implementing sucha program on a national " +
                "scale.",
            "Another potential problem is food waste. Students have different " +
                "preferences, dietary habits, and nutritional reguirements, Some may " +
                "refuse certain fo0ds because of taste, while others may have allergies or " +
                "other dietary restrictions. If meals are prepared in large quantities " +
                "without considering these differences, significant amounts of food could " +
                "remain uneaten.",
            "In addition, distributing meals to schools in remote or geographically " +
                "challenging areas could be difficult. Poor transportation " +
                "infrastructure, inadequate storage facilities, or delivery delays could " +
                "cause food to deteriorate before it reaches students. Without effective " +
                "monitoring and distribution systems, the program could become " +
                "inefficient and waste valuable resources.",
            "Maintaining food safety and nutritional quality on a large scale could " +
                "present another major challenge. Meals must be prepared under hygienic " +
                "conditions, stored at appropriate temperatures, transported safely, and " +
                "distributed within a suitable period. Failures in these procedures could " +
                "lead to contamination and potentially cause illness among students.",
            "Moreover, the program would require strong oversight and transparency to " +
                "reduce the risk of mismanagement, Corruption, or misuse of public funds. " +
                "Schools and government agencies would need qualified personnel to " +
                "monitor suppliers, evaluate nutritional standards, and ensure that the " +
                "allocated budget was used appropriately. Without effective supervision, " +
                "the program might fail to achieve its intended objectives.",
        )
        assertEquals(
            expectedTexts,
            doc.blocks.map { it.text },
            "reading order and/or content diverged from what a sighted reader sees on the page",
        )

        // Paragraph structure: 13 body paragraphs (block 3 is the essay's intro
        // paragraph, blocks 4-15 are the following 12), each preceded by an indented
        // opening line, plus the two-line worksheet header and the essay's own title
        // question ahead of them. Lines 9 and 10 of the fixture ("Concerns about
        // financialc" / "cost, food waste, ...") are one printed line a paper crease
        // split into two ML Kit fragments on the same visual row: RowFragmentJoiner
        // must fuse them back (9 raw lines -> 8 lines in block 3), left-to-right, with
        // no duplication and no reordering - already confirmed above by the exact text
        // match ("... They also raise Concerns about financialc cost, food waste, ...").
        assertEquals(
            listOf(1, 1, 1, 8, 5, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4),
            doc.blocks.map { it.lines.size },
            "unexpected paragraph/line grouping",
        )

        // The regression that matters most: a top-to-bottom perspective gradient on a
        // handheld photo used to manufacture false HEADINGs out of ordinary body text.
        // Every one of the 13 body paragraphs (blocks 3-15) is ordinary prose and must
        // never be promoted to HEADING, TITLE, CAPTION or any other non-paragraph role.
        val bodyRoles = doc.blocks.drop(3).map { it.role }
        assertTrue(
            bodyRoles.all { it == BlockRole.PARAGRAPH },
            "a body paragraph was misclassified: $bodyRoles",
        )

        // KNOWN DISCREPANCY (see docs/superpowers/plans/2026-08-30-test-hardening-report.md):
        // block 0, "Academic Reading and Comprehension Worksheet", is the worksheet's
        // own header line - a sighted reader would call it a title/header, never a
        // caption. It is genuinely SHORTER (height ~14) than the surrounding essay body
        // text (height ~20) on this fixture, an unusual but real layout, and
        // RoleClassifier's CAPTION rule (short single line, stops well short of the
        // column's right margin) has no exemption for a block sitting in the page's own
        // top band - only the SYMMETRIC case (relative height ABOVE headingHeightRatio
        // AND inTopBand) is special-cased to TITLE. This is pinned as a known bug
        // rather than silently asserted away or silently "fixed" outside the five
        // findings this task scoped; see the report for the recommended follow-up.
        assertEquals(
            BlockRole.CAPTION,
            doc.blocks[0].role,
            "if this now fails, RoleClassifier's top-of-page handling changed - update " +
                "the report rather than this assertion",
        )
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
