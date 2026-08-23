package id.dotcode.braille.ocr.mlkit

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.dotcode.braille.ocr.accuracy.AccuracyReport
import id.dotcode.braille.ocr.accuracy.ErrorRate
import id.dotcode.braille.ocr.model.OcrDocument
import id.dotcode.braille.ocr.model.OcrResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * The on-demand accuracy harness required by spec section 7 and success criterion 5.
 *
 * Point it at a device directory of worksheet photos, each paired with a same-named
 * `.txt` holding the expected transcription, and it reports character and word error rate
 * per image and in aggregate through a real [OcrEngine].
 *
 * Run it with:
 * ```
 * adb shell mkdir -p /sdcard/Download/braille-samples
 * adb push samples/worksheet-01.png /sdcard/Download/braille-samples/
 * adb push samples/worksheet-01.txt /sdcard/Download/braille-samples/
 * ./gradlew :ocr-mlkit:assembleDebugAndroidTest
 * adb install -r -t ocr-mlkit/build/outputs/apk/androidTest/debug/ocr-mlkit-debug-androidTest.apk
 * adb shell appops set --uid id.dotcode.braille.ocr.mlkit.test MANAGE_EXTERNAL_STORAGE allow
 * ./gradlew :ocr-mlkit:connectedDebugAndroidTest  *     -Pandroid.testInstrumentationRunnerArguments.class=id.dotcode.braille.ocr.mlkit.OcrAccuracyHarnessTest
 * adb logcat -d -s OcrAccuracyHarness
 * ```
 * The `appops` line is required and easy to miss. Under scoped storage the test app can
 * see the shared-storage DIRECTORY but none of the files another process wrote into it,
 * so without the grant the harness finds nothing and skips even though `adb shell ls`
 * plainly shows the samples. Granting all-files access to a *test* apk is why the
 * permission lives in `src/androidTest/AndroidManifest.xml` and not in the library.
 *
 * Override the directory with
 * `-Pandroid.testInstrumentationRunnerArguments.brailleSamplesDir=/sdcard/...`. An
 * app-private directory such as
 * `/sdcard/Android/media/id.dotcode.braille.ocr.mlkit.test/braille-samples` is readable
 * without any grant, if you would rather not use appops.
 *
 * With no samples present the test SKIPS with a message naming the directory it searched.
 * It deliberately does not fail (an unpopulated device is not a regression) and equally
 * deliberately does not pass silently, which would report a green accuracy run that never
 * measured anything.
 */
@RunWith(AndroidJUnit4::class)
class OcrAccuracyHarnessTest {

    private val tag = "OcrAccuracyHarness"

    @Test
    fun measuresCharacterAndWordErrorRateAgainstSampleWorksheets() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dirPath = InstrumentationRegistry.getArguments()?.getString(ARG_DIR) ?: DEFAULT_DIR
        val dir = File(dirPath)

        val pairs = findPairs(dir)
        val skipMessage = "no sample worksheets found in $dirPath — " +
            "populate it with matching pairs (worksheet-01.jpg or .png plus " +
            "worksheet-01.txt) via `adb push samples/worksheet-01.png $dirPath/`, or " +
            "point the harness elsewhere with " +
            "-Pandroid.testInstrumentationRunnerArguments.$ARG_DIR=/path/on/device. " +
            "Directory exists=${dir.exists()} readable=${dir.canRead()} " +
            "entries=${dir.listFiles()?.size ?: -1}. If the directory exists and is " +
            "readable but lists nothing, scoped storage is hiding files this app did not " +
            "write: run `adb shell appops set --uid ${context.packageName} " +
            "MANAGE_EXTERNAL_STORAGE allow` and re-run."
        if (pairs.isEmpty()) Log.w(tag, "SKIPPED: $skipMessage")
        assumeTrue(skipMessage, pairs.isNotEmpty())

        val engine = OcrEngine(context)
        val expectedAll = StringBuilder()
        val actualAll = StringBuilder()
        var measured = 0
        try {
            for ((image, transcript) in pairs) {
                val expected = transcript.readText()
                val result = engine.recognize(Uri.fromFile(image))
                val actual = when (result) {
                    is OcrResult.Success -> flatten(result.document)
                    is OcrResult.Failure -> {
                        // A rejected capture is a real accuracy outcome, not an excuse to
                        // drop the sample: it scores as zero recognized text.
                        Log.w(tag, "${image.name}: recognition failed reason=${result.reason} detail=${result.detail}")
                        ""
                    }
                }
                val report = ErrorRate.compare(expected, actual)
                logReport(image.name, report)
                expectedAll.append(expected).append('\n')
                actualAll.append(actual).append('\n')
                measured++
            }
        } finally {
            engine.close()
        }

        val aggregate = ErrorRate.compare(expectedAll.toString(), actualAll.toString())
        Log.i(tag, "=== aggregate over $measured image(s) in $dirPath ===")
        logReport("AGGREGATE", aggregate)

        assertTrue(
            aggregate.characterAccuracy > MIN_CHARACTER_ACCURACY,
            "aggregate character accuracy ${"%.4f".format(aggregate.characterAccuracy)} over " +
                "$measured image(s) is at or below the required $MIN_CHARACTER_ACCURACY " +
                "(CER ${"%.4f".format(aggregate.cer)}, WER ${"%.4f".format(aggregate.wer)})",
        )
    }

    private fun logReport(label: String, report: AccuracyReport) {
        Log.i(
            tag,
            "$label: CER=${"%.4f".format(report.cer)} WER=${"%.4f".format(report.wer)} " +
                "charAcc=${"%.4f".format(report.characterAccuracy)} " +
                "wordAcc=${"%.4f".format(report.wordAccuracy)} " +
                "expectedChars=${report.expectedChars} expectedWords=${report.expectedWords}",
        )
    }

    /** The document's text as a reader would encounter it: reading order, markers kept. */
    private fun flatten(document: OcrDocument): String =
        document.blocks.joinToString("\n") { block ->
            listOfNotNull(block.marker?.takeIf { it.isNotBlank() }, block.text)
                .joinToString(" ")
                .trim()
        }

    /** Images with a same-named `.txt` beside them, in a deterministic order. */
    private fun findPairs(dir: File): List<Pair<File, File>> {
        if (!dir.isDirectory || !dir.canRead()) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            .sortedBy { it.name }
            .mapNotNull { image ->
                val transcript = File(dir, "${image.nameWithoutExtension}.txt")
                if (transcript.isFile) image to transcript else {
                    Log.w(tag, "${image.name}: no ${transcript.name} beside it, skipping")
                    null
                }
            }
    }

    private companion object {
        const val DEFAULT_DIR = "/sdcard/Download/braille-samples"
        const val ARG_DIR = "brailleSamplesDir"
        const val MIN_CHARACTER_ACCURACY = 0.9
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")
    }
}
