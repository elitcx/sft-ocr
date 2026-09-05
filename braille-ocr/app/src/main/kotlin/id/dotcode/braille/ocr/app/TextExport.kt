package id.dotcode.braille.ocr.app

import id.dotcode.braille.ocr.model.OcrDocument
import id.dotcode.braille.ocr.model.TextBlock
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Renders a document as clean plain text for the downstream braille conversion engine,
 * and stages it on disk the same way [JsonExport] stages the diagnostic JSON: a share of
 * `text/plain` still resolves more reliably to a real attachment than a giant EXTRA_TEXT
 * string, and reusing the file-based path keeps both exports consistent.
 *
 * Format: one block per paragraph, separated by a blank line so headings and paragraphs
 * read as distinct units; a block's marker (list/question numbering, stripped out of
 * `text` by the classifier) is restored as a prefix; indentation becomes leading spaces
 * rather than the structural metadata the debug view shows.
 */
internal object TextExport {

    /** Must stay in step with `res/xml/file_paths.xml`, same as [JsonExport.DIR]. */
    const val DIR = "export"

    private const val SPACES_PER_INDENT_LEVEL = 2
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun toPlainText(document: OcrDocument): String =
        document.blocks.joinToString(separator = "\n\n") { it.toPlainTextLine() }

    private fun TextBlock.toPlainTextLine(): String {
        val indent = " ".repeat(indentLevel * SPACES_PER_INDENT_LEVEL)
        val content = if (marker != null) "$marker $text" else text
        return indent + content
    }

    fun write(cacheDir: File, document: OcrDocument, now: LocalDateTime = LocalDateTime.now()): File {
        val dir = File(cacheDir, DIR)
        dir.deleteRecursively()
        dir.mkdirs()
        return File(dir, "braille-ocr-${STAMP.format(now)}.txt").apply {
            writeText(toPlainText(document))
        }
    }
}
