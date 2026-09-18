package id.dotcode.braille.ocr.app

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.model.BlockRole
import id.dotcode.braille.ocr.model.FailureReason
import id.dotcode.braille.ocr.model.OcrDocument
import id.dotcode.braille.ocr.model.OcrResult
import id.dotcode.braille.ocr.model.TextBlock
import id.dotcode.braille.ocr.model.TextLine
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

/**
 * Pulls text straight out of a born-digital PDF or DOCX instead of running OCR - the text
 * layer is already there. Any embedded images are never touched; only text runs are read.
 */
internal object DocumentTextExtractor {

    const val PDF_MIME = "application/pdf"
    const val DOCX_MIME =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    private var pdfBoxInitialized = false
    private val emptyBox = BoxF(0f, 0f, 0f, 0f)

    fun extract(context: Context, uri: Uri, mimeType: String?): OcrResult {
        val paragraphs = try {
            when (mimeType) {
                PDF_MIME -> extractPdf(context, uri)
                DOCX_MIME -> extractDocx(context, uri)
                else -> return OcrResult.Failure(
                    FailureReason.ModelUnavailable,
                    "Format berkas tidak didukung.",
                )
            }
        } catch (e: IOException) {
            return OcrResult.Failure(FailureReason.NoTextFound, e.message)
        }

        if (paragraphs.isEmpty()) return OcrResult.Failure(FailureReason.NoTextFound)
        return OcrResult.Success(toDocument(paragraphs))
    }

    private fun extractPdf(context: Context, uri: Uri): List<String> {
        if (!pdfBoxInitialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            pdfBoxInitialized = true
        }
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc -> PDFTextStripper().getText(doc) }
        } ?: throw IOException("Tidak dapat membuka berkas.")
        return splitParagraphs(text)
    }

    private fun extractDocx(context: Context, uri: Uri): List<String> {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Tidak dapat membuka berkas.")
        input.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") return parseDocxParagraphs(zip)
                    entry = zip.nextEntry
                }
            }
        }
        throw IOException("Berkas DOCX tidak valid.")
    }

    /** DOCX's `word/document.xml` is WordprocessingML: `<w:p>` paragraphs of `<w:t>` runs. */
    private fun parseDocxParagraphs(input: InputStream): List<String> {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()
        var inRun = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            // Android's parser processes namespaces, so names arrive as "p"/"t", not "w:p"/"w:t".
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "p" -> current.setLength(0)
                    "t" -> inRun = true
                }
                XmlPullParser.TEXT -> if (inRun) current.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name.substringAfter(':')) {
                    "t" -> inRun = false
                    "p" -> {
                        val paragraph = current.toString().trim()
                        if (paragraph.isNotEmpty()) paragraphs.add(paragraph)
                        current.setLength(0)
                    }
                }
            }
            event = parser.next()
        }
        return paragraphs
    }

    private fun splitParagraphs(text: String): List<String> =
        text.split(Regex("\\r?\\n\\s*\\r?\\n"))
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }

    private fun toDocument(paragraphs: List<String>): OcrDocument {
        val blocks = paragraphs.mapIndexed { index, text ->
            TextBlock(
                id = index,
                role = BlockRole.PARAGRAPH,
                columnIndex = 0,
                text = text,
                lines = listOf(TextLine(text = text, box = emptyBox)),
                box = emptyBox,
            )
        }
        return OcrDocument(
            pageWidth = 0,
            pageHeight = 0,
            skewDeg = 0f,
            columnCount = 1,
            blocks = blocks,
        )
    }
}
