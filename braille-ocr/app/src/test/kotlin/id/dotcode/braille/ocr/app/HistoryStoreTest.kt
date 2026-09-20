package id.dotcode.braille.ocr.app

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.model.BlockRole
import id.dotcode.braille.ocr.model.OcrDocument
import id.dotcode.braille.ocr.model.TextBlock
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HistoryStoreTest {

    private val box = BoxF(0f, 0f, 1f, 1f)

    private fun block(id: Int, text: String, role: BlockRole = BlockRole.PARAGRAPH) =
        TextBlock(id = id, role = role, columnIndex = 0, text = text, lines = emptyList(), box = box)

    private fun document(vararg blocks: TextBlock) =
        OcrDocument(pageWidth = 100, pageHeight = 100, skewDeg = 0f, columnCount = 1, blocks = blocks.toList())

    private fun entry(id: String, createdAt: Long) = HistoryEntry(
        id = id, createdAt = createdAt, title = "Judul $id", preview = "isi", sourceKind = SourceKind.CAMERA,
        wordCount = 3, corrected = false, sentToDevice = false,
    )

    @Test
    fun `saves, lists newest first and loads both texts back`(@TempDir dir: File) {
        val store = HistoryStore(dir)
        val corrected = document(block(1, "Ibu pergi ke pasar"))
        val raw = document(block(1, "lbu pergi ke pasar"))

        store.save(entry("old", 1_000), corrected, uncorrected = null)
        store.save(entry("new", 2_000).copy(corrected = true), corrected, uncorrected = raw)

        assertEquals(listOf("new", "old"), store.list().map { it.id })
        val loaded = assertNotNull(store.load("new"))
        assertEquals(corrected, loaded.document)
        assertEquals(raw, loaded.uncorrected)
        assertNull(assertNotNull(store.load("old")).uncorrected)
    }

    @Test
    fun `metadata round-trips`(@TempDir dir: File) {
        val original = entry("x", 42).copy(sourceKind = SourceKind.DOCUMENT, sentToDevice = true, corrected = true)

        assertEquals(original, HistoryStore.parseMeta(HistoryStore.metaJson(original)))
    }

    @Test
    fun `updating metadata keeps the document`(@TempDir dir: File) {
        val store = HistoryStore(dir)
        store.save(entry("a", 1), document(block(1, "teks")), null)

        store.updateMeta(entry("a", 1).copy(sentToDevice = true))

        assertTrue(store.list().single().sentToDevice)
        assertNotNull(store.load("a"))
    }

    @Test
    fun `keeps only the newest entries`(@TempDir dir: File) {
        val store = HistoryStore(dir, maxEntries = 2)
        (1..4).forEach { store.save(entry("e$it", it.toLong()), document(block(1, "teks")), null) }

        assertEquals(listOf("e4", "e3"), store.list().map { it.id })
        assertNull(store.load("e1"))
    }

    @Test
    fun `delete and deleteAll remove files`(@TempDir dir: File) {
        val store = HistoryStore(dir)
        store.save(entry("a", 1), document(block(1, "teks")), document(block(1, "tcks")))
        store.save(entry("b", 2), document(block(1, "teks")), null)

        store.delete("a")
        assertEquals(listOf("b"), store.list().map { it.id })
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("a.") })

        store.deleteAll()
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `an unreadable row is skipped rather than breaking the list`(@TempDir dir: File) {
        val store = HistoryStore(dir)
        store.save(entry("a", 1), document(block(1, "teks")), null)
        File(dir, "broken.meta.json").writeText("{not json")

        assertEquals(listOf("a"), store.list().map { it.id })
    }

    @Test
    fun `title prefers a file name, then a heading, then the opening words`() {
        val doc = document(
            block(1, "Pada suatu hari yang cerah sekali, ibu pergi ke pasar untuk membeli sayur"),
            block(2, "Bab 3: Ekosistem", BlockRole.HEADING),
        )

        assertEquals("Tugas Biologi", HistoryStore.titleFor(doc, "Tugas Biologi.pdf"))
        assertEquals("Bab 3: Ekosistem", HistoryStore.titleFor(doc, null))
        val opening = HistoryStore.titleFor(document(doc.blocks.first()), null)
        assertTrue(opening.endsWith("…") && opening.length <= 49, opening)
        assertTrue(opening.startsWith("Pada suatu hari"), opening)
    }

    @Test
    fun `preview skips the title text`() {
        val doc = document(block(1, "Bab 3", BlockRole.HEADING), block(2, "Makhluk hidup saling bergantung."))

        assertEquals("Makhluk hidup saling bergantung.", HistoryStore.previewFor(doc, "Bab 3"))
    }

    @Test
    fun `shorten cuts at a word boundary`() {
        assertEquals("satu dua…", HistoryStore.shorten("satu dua tiga empat", 12))
        assertEquals("pendek", HistoryStore.shorten("  pendek ", 12))
    }

    @Test
    fun `a reading position survives a round trip`() {
        val saved = entry("a", 1_000).copy(lastWord = 120)
        assertEquals(120, HistoryStore.parseMeta(HistoryStore.metaJson(saved)).lastWord)
    }

    @Test
    fun `history written before reading positions existed opens at the beginning`() {
        // Meta files on devices that already have this app have no lastWord key. Parsing
        // must treat that as "start at the beginning" rather than throwing, or the update
        // would make every past scan unopenable.
        val old = """{"id":"a","createdAt":1000,"title":"Judul","preview":"isi",
            "sourceKind":"CAMERA","wordCount":3,"corrected":false,"sentToDevice":false}"""
        val parsed = HistoryStore.parseMeta(old)
        assertEquals(0, parsed.lastWord)
        assertEquals("a", parsed.id)
    }

    @Test
    fun `a half-read page resumes where it stopped`() {
        assertEquals(120, resumeIndex(lastWord = 120, wordCount = 250))
    }

    @Test
    fun `a page that was read to the end starts over`() {
        // Dropping someone on the last word of a page they already finished gives them
        // nowhere to go. Starting over is the only useful thing to do.
        assertEquals(0, resumeIndex(lastWord = 249, wordCount = 250))
    }

    @Test
    fun `a position beyond the text is clamped`() {
        // The document can be re-corrected between readings, so a stored position may no
        // longer exist. It must never land outside the word list.
        assertEquals(0, resumeIndex(lastWord = 900, wordCount = 250))
        assertEquals(0, resumeIndex(lastWord = 5, wordCount = 0))
        assertEquals(0, resumeIndex(lastWord = -3, wordCount = 250))
    }

}
