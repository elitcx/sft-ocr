package id.dotcode.braille.ocr.mlkit

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-demand harness that dumps the RAW recognizer output (before [id.dotcode.braille.ocr
 * .pipeline.DocumentStructurer] runs) for every corpus image, as `<name>-raw.json`. This is
 * how the fixtures under `ocr-core/src/test/resources/fixtures` (named `*-raw.json`) are
 * captured: a
 * real device/emulator run's [id.dotcode.braille.ocr.raw.RawTextResult] is serialized once
 * and then replayed as a JVM test fixture with no emulator required — see
 * [id.dotcode.braille.ocr.mlkit.OcrEngine.recognizeRawDebug]'s KDoc.
 *
 * Run the same way as [OcrBatchStructureHarnessTest], substituting this class name for
 * `-Pandroid.testInstrumentationRunnerArguments.class=`.
 */
@RunWith(AndroidJUnit4::class)
class OcrBatchRawDumpHarnessTest {

    private val tag = "OcrBatchRawDumpHarness"

    @Test
    fun dumpsRawOutputForEveryCorpusImage(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dirPath = InstrumentationRegistry.getArguments()?.getString(ARG_DIR) ?: DEFAULT_DIR
        val dir = File(dirPath)

        val images = findImages(dir)
        val skipMessage = "no corpus images found in $dirPath"
        if (images.isEmpty()) Log.w(tag, "SKIPPED: $skipMessage")
        assumeTrue(skipMessage, images.isNotEmpty())

        val engine = OcrEngine(context)
        var succeeded = 0
        var failed = 0
        try {
            for (image in images) {
                val result = engine.recognizeRawDebug(Uri.fromFile(image))
                if (result != null) {
                    val (raw, rotation) = result
                    val out = File(dir, "${image.nameWithoutExtension}-raw.json")
                    out.writeText(raw.toJson())
                    Log.i(tag, "${image.name}: OK rotation=$rotation lines=${raw.lines.size}")
                    succeeded++
                } else {
                    Log.w(tag, "${image.name}: FAILED to recognize")
                    failed++
                }
            }
        } finally {
            engine.close()
        }
        Log.i(tag, "=== done: ${images.size} image(s), $succeeded succeeded, $failed failed ===")
    }

    private fun findImages(dir: File): List<File> {
        if (!dir.isDirectory || !dir.canRead()) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            .sortedBy { it.name }
    }

    private companion object {
        const val DEFAULT_DIR = "/sdcard/Download/braille-corpus"
        const val ARG_DIR = "brailleCorpusDir"
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")
    }
}
