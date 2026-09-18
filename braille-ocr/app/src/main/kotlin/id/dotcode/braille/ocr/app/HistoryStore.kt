package id.dotcode.braille.ocr.app

import id.dotcode.braille.ocr.model.BlockRole
import id.dotcode.braille.ocr.model.OcrDocument
import org.json.JSONObject
import java.io.File

/** What the History list shows; the documents themselves load only when opened. */
data class HistoryEntry(
    val id: String,
    val createdAt: Long,
    val title: String,
    val preview: String,
    val sourceKind: SourceKind,
    val wordCount: Int,
    val corrected: Boolean,
    val sentToDevice: Boolean,
)

/**
 * Past scans, on this device only: `<id>.meta.json` (the list row) beside `<id>.doc.json`
 * and, when a correction pass changed something, `<id>.raw.json` (the recognizer's own text).
 */
class HistoryStore(private val dir: File, private val maxEntries: Int = MAX_ENTRIES) {

    data class Documents(val document: OcrDocument, val uncorrected: OcrDocument?)

    fun list(): List<HistoryEntry> =
        dir.listFiles { file -> file.name.endsWith(META_SUFFIX) }
            .orEmpty()
            .mapNotNull { runCatching { parseMeta(it.readText()) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    fun load(id: String): Documents? = runCatching {
        val document = OcrDocument.fromJson(docFile(id).readText())
        val raw = rawFile(id).takeIf { it.exists() }?.let { OcrDocument.fromJson(it.readText()) }
        Documents(document, raw)
    }.getOrNull()

    fun save(entry: HistoryEntry, document: OcrDocument, uncorrected: OcrDocument?) {
        dir.mkdirs()
        writeAtomically(docFile(entry.id), document.toJson())
        if (uncorrected != null) writeAtomically(rawFile(entry.id), uncorrected.toJson()) else rawFile(entry.id).delete()
        // The row goes last, so a list never shows an entry whose document isn't there yet.
        writeAtomically(metaFile(entry.id), metaJson(entry))
        prune()
    }

    fun updateMeta(entry: HistoryEntry) {
        if (metaFile(entry.id).exists()) writeAtomically(metaFile(entry.id), metaJson(entry))
    }

    fun delete(id: String) {
        metaFile(id).delete()
        docFile(id).delete()
        rawFile(id).delete()
    }

    fun deleteAll() {
        dir.listFiles().orEmpty().forEach { it.delete() }
    }

    private fun prune() {
        list().drop(maxEntries).forEach { delete(it.id) }
    }

    private fun metaFile(id: String) = File(dir, id + META_SUFFIX)
    private fun docFile(id: String) = File(dir, "$id.doc.json")
    private fun rawFile(id: String) = File(dir, "$id.raw.json")

    private fun writeAtomically(target: File, text: String) {
        val temp = File(dir, target.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Could not write ${target.name}" }
        }
    }

    companion object {
        const val MAX_ENTRIES = 100
        private const val META_SUFFIX = ".meta.json"
        private const val TITLE_LENGTH = 48
        private const val PREVIEW_LENGTH = 90

        fun metaJson(entry: HistoryEntry): String = JSONObject()
            .put("id", entry.id)
            .put("createdAt", entry.createdAt)
            .put("title", entry.title)
            .put("preview", entry.preview)
            .put("sourceKind", entry.sourceKind.name)
            .put("wordCount", entry.wordCount)
            .put("corrected", entry.corrected)
            .put("sentToDevice", entry.sentToDevice)
            .toString()

        fun parseMeta(json: String): HistoryEntry {
            val o = JSONObject(json)
            return HistoryEntry(
                id = o.getString("id"),
                createdAt = o.getLong("createdAt"),
                title = o.getString("title"),
                preview = o.optString("preview"),
                sourceKind = runCatching { SourceKind.valueOf(o.getString("sourceKind")) }
                    .getOrDefault(SourceKind.CAMERA),
                wordCount = o.optInt("wordCount"),
                corrected = o.optBoolean("corrected"),
                sentToDevice = o.optBoolean("sentToDevice"),
            )
        }

        /**
         * A document's own title or heading if the classifier found one, otherwise its opening
         * words; [fileName] (an imported document's name) wins when there is one.
         */
        fun titleFor(document: OcrDocument, fileName: String?): String {
            fileName?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() }?.let {
                return shorten(it, TITLE_LENGTH)
            }
            val block = document.blocks.firstOrNull { it.role == BlockRole.TITLE || it.role == BlockRole.HEADING }
                ?: document.blocks.firstOrNull { it.text.isNotBlank() }
            return shorten(block?.text.orEmpty(), TITLE_LENGTH)
        }

        fun previewFor(document: OcrDocument, title: String): String {
            val text = document.blocks.joinToString(" ") { it.text }.replace(WHITESPACE, " ").trim()
            val body = if (text.startsWith(title.removeSuffix("…"))) {
                text.removePrefix(title.removeSuffix("…")).trim()
            } else {
                text
            }
            return shorten(body.ifEmpty { text }, PREVIEW_LENGTH)
        }

        internal fun shorten(text: String, max: Int): String {
            val clean = text.replace(WHITESPACE, " ").trim()
            if (clean.length <= max) return clean
            val cut = clean.take(max)
            val atWord = cut.substringBeforeLast(' ').takeIf { it.length >= max / 2 } ?: cut
            return atWord.trimEnd(',', '.', ';', ':', ' ') + "…"
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
