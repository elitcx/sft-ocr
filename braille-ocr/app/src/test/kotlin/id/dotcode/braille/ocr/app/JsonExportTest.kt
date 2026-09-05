package id.dotcode.braille.ocr.app

import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JsonExportTest {

    @Test
    fun `writes the document json into a shareable file`(@TempDir cacheDir: File) {
        val json = """{"pageWidth":100}"""

        val file = JsonExport.write(cacheDir, json)

        assertEquals(json, file.readText())
        assertTrue(file.name.endsWith(".json"), "share targets key off the extension: ${file.name}")
    }

    @Test
    fun `keeps the export inside the directory FileProvider is configured to serve`(
        @TempDir cacheDir: File,
    ) {
        val file = JsonExport.write(cacheDir, "{}")

        assertEquals(File(cacheDir, JsonExport.DIR).canonicalFile, file.parentFile.canonicalFile)
    }

    /**
     * Exports land in the cache, which nothing else prunes. Without this the directory grows
     * by a document per tap for the life of the install.
     */
    @Test
    fun `replaces the previous export rather than accumulating files`(@TempDir cacheDir: File) {
        val first = JsonExport.write(cacheDir, """{"n":1}""")

        val second = JsonExport.write(cacheDir, """{"n":2}""")

        assertTrue(!first.exists() || first == second, "stale export left behind: ${first.name}")
        assertContentEquals(
            listOf(second.name),
            second.parentFile.listFiles().orEmpty().map { it.name }.sorted(),
        )
    }
}
