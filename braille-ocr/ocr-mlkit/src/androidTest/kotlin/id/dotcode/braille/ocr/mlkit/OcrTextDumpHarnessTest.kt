package id.dotcode.braille.ocr.mlkit

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.dotcode.braille.ocr.accuracy.DocumentFlattener
import id.dotcode.braille.ocr.accuracy.ErrorRate
import id.dotcode.braille.ocr.model.OcrResult
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic companion to [OcrAccuracyHarnessTest]: same pipeline, same pairs, but it
 * writes the actual TEXT out instead of only logging an error rate.
 *
 * A CER number says a page scored badly; it never says *why*. Reading the recognizer's
 * own words against the reference is what separates a genuine misread from an artifact —
 * a facing page that leaked in, a run of contents-page leader dots the transcript
 * deliberately omits, or a reading-order scramble. Every such question in this project
 * was previously answered by guessing at thumbnails; this harness answers them from the
 * app's real output.
 *
 * Never asserts. It is a reporting tool, so a bad page is data, not a failure.
 *
 * Writes `<externalMediaDir>/text-dump/dump.json` — one record per page holding the
 * reference, the recognizer-only text, the corrected text, and both error rates. The
 * app itself creates that directory, so it is readable back without any storage grant:
 * ```
 * adb pull /sdcard/Android/media/id.dotcode.braille.ocr.mlkit.test/text-dump
 * ```
 */
@RunWith(AndroidJUnit4::class)
class OcrTextDumpHarnessTest {

    private val tag = "OcrTextDump"

    @Test
    fun dumpsReferenceAndRecognizedTextForEveryPair(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dirPath = InstrumentationRegistry.getArguments()?.getString(ARG_DIR) ?: DEFAULT_DIR
        val pairs = seedFromAssets(context) + findPairs(File(dirPath))
        assumeTrue("no pairs to dump (assets + $dirPath both empty)", pairs.isNotEmpty())

        val outDir = File(context.externalMediaDirs.first(), "text-dump").apply { mkdirs() }
        val engine = OcrEngine(context)
        val records = JSONArray()
        try {
            // Emulator speed is not the phone's; give the second recognizer room to finish
            // so the dump reflects what the correction layer actually had to work with.
            val corrected = OcrEngine.CorrectionSettings(enabled = true, secondReadGraceMs = 300_000L)
            engine.warmUp()
            for ((image, transcript) in pairs) {
                val expected = transcript.readText()

                engine.correctionSettings = OcrEngine.CorrectionSettings(enabled = false)
                val rawText = flatten(engine.recognize(Uri.fromFile(image)))
                engine.correctionSettings = corrected
                val result = engine.recognize(Uri.fromFile(image))
                val correctedText = flatten(result)

                val rawScore = ErrorRate.compare(expected, rawText)
                val corrScore = ErrorRate.compare(expected, correctedText)
                val record = JSONObject().apply {
                    put("image", image.name)
                    put("reference", expected)
                    put("raw", rawText)
                    put("corrected", correctedText)
                    put("raw_cer", rawScore.cer)
                    put("corrected_cer", corrScore.cer)
                    put("raw_wer", rawScore.wer)
                    put("corrected_wer", corrScore.wer)
                    put("expected_chars", rawScore.expectedChars)
                    if (result is OcrResult.Success) {
                        put("blocks", result.document.blocks.size)
                        put("corrections", JSONArray().apply {
                            result.document.blocks.flatMap { it.corrections }.forEach {
                                put(JSONObject().put("from", it.original).put("to", it.corrected))
                            }
                        })
                    } else if (result is OcrResult.Failure) {
                        put("failure", result.reason.name)
                    }
                }
                records.put(record)
                Log.i(tag, "${image.name}: raw CER=${"%.4f".format(rawScore.cer)} " +
                    "corrected CER=${"%.4f".format(corrScore.cer)} refChars=${rawScore.expectedChars}")
            }
        } finally {
            engine.close()
        }
        File(outDir, "dump.json").writeText(records.toString(2))
        Log.i(tag, "wrote ${records.length()} record(s) to ${File(outDir, "dump.json").absolutePath}")
    }

    private fun flatten(result: OcrResult): String = when (result) {
        is OcrResult.Success -> DocumentFlattener.flatten(result.document)
        is OcrResult.Failure -> ""
    }

    /** Same asset seeding as [OcrAccuracyHarnessTest]; assets always reach the APK. */
    private fun seedFromAssets(context: Context): List<Pair<File, File>> {
        val assets = context.assets
        val names = try {
            assets.list(ASSET_DIR)?.toList() ?: emptyList()
        } catch (e: IOException) {
            Log.w(tag, "no bundled seed assets under $ASSET_DIR: ${e.message}")
            emptyList()
        }
        val outDir = File(context.filesDir, "text-dump-seed").apply { mkdirs() }
        return names
            .filter { it.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
            .sorted()
            .mapNotNull { imageName ->
                val txtName = "${imageName.substringBeforeLast('.')}.txt"
                if (txtName !in names) return@mapNotNull null
                val imageOut = File(outDir, imageName)
                assets.open("$ASSET_DIR/$imageName").use { input ->
                    imageOut.outputStream().use { input.copyTo(it) }
                }
                val txtOut = File(outDir, txtName)
                assets.open("$ASSET_DIR/$txtName").use { input ->
                    txtOut.outputStream().use { input.copyTo(it) }
                }
                imageOut to txtOut
            }
    }

    private fun findPairs(dir: File): List<Pair<File, File>> {
        if (!dir.isDirectory || !dir.canRead()) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            .sortedBy { it.name }
            .mapNotNull { image ->
                val transcript = File(dir, "${image.nameWithoutExtension}.txt")
                if (transcript.isFile) image to transcript else null
            }
    }

    private companion object {
        const val DEFAULT_DIR = "/sdcard/Download/braille-samples"
        const val ARG_DIR = "brailleSamplesDir"
        const val ASSET_DIR = "ground-truth"
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")
    }
}
