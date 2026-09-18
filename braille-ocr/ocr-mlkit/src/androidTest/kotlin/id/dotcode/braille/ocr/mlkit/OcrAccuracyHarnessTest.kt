package id.dotcode.braille.ocr.mlkit

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.dotcode.braille.ocr.accuracy.AccuracyReport
import id.dotcode.braille.ocr.accuracy.DocumentFlattener
import id.dotcode.braille.ocr.accuracy.ErrorRate
import id.dotcode.braille.ocr.model.OcrResult
import java.io.File
import java.io.IOException
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
 * With no samples present in the device directory the test does NOT skip: it also always
 * scores the small seed dataset bundled at `assets/ground-truth/` (image + `.txt` pairs,
 * committed to the repo — see `docs/dataset-schema.md`), so `connectedDebugAndroidTest`
 * measures real accuracy on every run with no `adb push` required. It only skips if that
 * bundled seed is itself missing or empty AND the device directory has nothing either,
 * which would mean the harness genuinely has nothing to measure. It deliberately does not
 * fail on an unpopulated *device* directory (that alone is not a regression) and equally
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

        val seeded = seedFromAssets(context)
        val pairs = seeded + findPairs(dir)
        val skipMessage = "no sample worksheets found: bundled seed at assets/$ASSET_DIR " +
            "yielded ${seeded.size} pair(s) and $dirPath yielded ${pairs.size - seeded.size} " +
            "more. Populate the device directory with matching pairs (worksheet-01.jpg or " +
            ".png plus worksheet-01.txt) via `adb push samples/worksheet-01.png $dirPath/`, " +
            "or point the harness elsewhere with " +
            "-Pandroid.testInstrumentationRunnerArguments.$ARG_DIR=/path/on/device. " +
            "Directory exists=${dir.exists()} readable=${dir.canRead()} " +
            "entries=${dir.listFiles()?.size ?: -1}. If the directory exists and is " +
            "readable but lists nothing, scoped storage is hiding files this app did not " +
            "write: run `adb shell appops set --uid ${context.packageName} " +
            "MANAGE_EXTERNAL_STORAGE allow` and re-run."
        if (pairs.isEmpty()) Log.w(tag, "SKIPPED: $skipMessage")
        assumeTrue(skipMessage, pairs.isNotEmpty())

        // Every page is read twice - recognizer only, then with the offline correction layer
        // (second recognizer + dictionary) - so the layer's effect is measured, not assumed.
        val engine = OcrEngine(context)
        val expectedAll = StringBuilder()
        val rawAll = StringBuilder()
        val actualAll = StringBuilder()
        var measured = 0
        try {
            // An emulator is far slower than a phone; measure what the votes are worth
            // without the phone's time limit cutting Tesseract off.
            val corrected = OcrEngine.CorrectionSettings(enabled = true, secondReadGraceMs = 300_000L)
            engine.correctionSettings = corrected
            engine.warmUp()
            for ((image, transcript) in pairs) {
                val expected = transcript.readText()

                engine.correctionSettings = OcrEngine.CorrectionSettings(enabled = false)
                val raw = flatten(image, engine.recognize(Uri.fromFile(image)))
                engine.correctionSettings = corrected
                val result = engine.recognize(Uri.fromFile(image))
                val actual = flatten(image, result)

                logReport("${image.name} [recognizer only]", ErrorRate.compare(expected, raw))
                logReport("${image.name} [corrected]", ErrorRate.compare(expected, actual))
                if (result is OcrResult.Success) {
                    val t = result.document.timings
                    Log.i(tag, "${image.name}: ocr=${t.recognizeMs}ms tesseract=${t.secondReadMs}ms (${t.secondReadWords} words) correct=${t.correctMs}ms total=${t.totalMs}ms")
                    result.document.blocks.flatMap { it.corrections }.forEach {
                        Log.i(tag, "${image.name}: corrected '${it.original}' -> '${it.corrected}'")
                    }
                }
                expectedAll.append(expected).append('\n')
                rawAll.append(raw).append('\n')
                actualAll.append(actual).append('\n')
                measured++
            }
        } finally {
            engine.close()
        }

        val aggregate = ErrorRate.compare(expectedAll.toString(), actualAll.toString())
        Log.i(tag, "=== aggregate over $measured image(s) in $dirPath ===")
        logReport("AGGREGATE [recognizer only]", ErrorRate.compare(expectedAll.toString(), rawAll.toString()))
        logReport("AGGREGATE [corrected]", aggregate)

        assertTrue(
            aggregate.characterAccuracy > MIN_CHARACTER_ACCURACY,
            "aggregate character accuracy ${"%.4f".format(aggregate.characterAccuracy)} over " +
                "$measured image(s) is at or below the required $MIN_CHARACTER_ACCURACY " +
                "(CER ${"%.4f".format(aggregate.cer)}, WER ${"%.4f".format(aggregate.wer)})",
        )
    }

    private fun flatten(image: File, result: OcrResult): String = when (result) {
        is OcrResult.Success -> DocumentFlattener.flatten(result.document)
        is OcrResult.Failure -> {
            // A rejected capture is a real accuracy outcome, not an excuse to
            // drop the sample: it scores as zero recognized text.
            Log.w(tag, "${image.name}: recognition failed reason=${result.reason} detail=${result.detail}")
            ""
        }
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

    /**
     * Copies the repo-committed seed dataset out of the test APK's assets into app storage
     * (assets are a `Context.assets` stream, not a `File`, so the rest of this harness — and
     * `findPairs` — can't address them directly) and returns image/transcript pairs from it.
     * Missing or empty `assets/$ASSET_DIR` is a normal state (no seed committed yet, or an
     * old build without one), not an error — returns an empty list either way.
     */
    private fun seedFromAssets(context: Context): List<Pair<File, File>> {
        val assets = context.assets
        val names = try {
            assets.list(ASSET_DIR)?.toList() ?: emptyList()
        } catch (e: IOException) {
            Log.w(tag, "no bundled seed assets under $ASSET_DIR: ${e.message}")
            emptyList()
        }
        // filesDir, not cacheDir: on a nearly full device the system evicts cache files
        // mid-run, which once deleted a transcript between two pages.
        val outDir = File(context.filesDir, "ground-truth-seed").apply { mkdirs() }
        return names
            .filter { it.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
            .sorted()
            .mapNotNull { imageName ->
                val txtName = "${imageName.substringBeforeLast('.')}.txt"
                if (txtName !in names) {
                    Log.w(tag, "asset $imageName: no $txtName beside it in $ASSET_DIR, skipping")
                    return@mapNotNull null
                }
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
        const val ASSET_DIR = "ground-truth"
        const val MIN_CHARACTER_ACCURACY = 0.9
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")
    }
}
