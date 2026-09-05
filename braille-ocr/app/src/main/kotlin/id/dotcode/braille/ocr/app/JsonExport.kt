package id.dotcode.braille.ocr.app

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Stages a document's JSON on disk so it can leave the app as a real file.
 *
 * ACTION_SEND carries a payload one of two ways: `text/plain` in EXTRA_TEXT, or a content
 * URI in EXTRA_STREAM for every other type. A share of `application/json` therefore has to
 * be a URI, and a URI has to point at a file, which is what this writes. Sending the JSON
 * as EXTRA_TEXT under a non-text type resolves to targets that then find nothing attached.
 *
 * The cache is the right home for it: the file only has to outlive the share, and nothing
 * in the app is a long-term owner of an export. Nothing prunes that directory on our behalf
 * though, so each export clears the previous one instead of stacking up a document per tap.
 */
internal object JsonExport {

    /** Subdirectory of the cache; must stay in step with `res/xml/file_paths.xml`. */
    const val DIR = "export"

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun write(cacheDir: File, json: String, now: LocalDateTime = LocalDateTime.now()): File {
        val dir = File(cacheDir, DIR)
        dir.deleteRecursively()
        dir.mkdirs()
        return File(dir, "braille-ocr-${STAMP.format(now)}.json").apply { writeText(json) }
    }
}
