package id.dotcode.braille.ocr.mlkit

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.dotcode.braille.ocr.model.OcrResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The on-demand STRUCTURE batch harness for a corpus of real photos with no ground-truth
 * transcript. This is the structural sibling of [OcrAccuracyHarnessTest]: that harness
 * scores CER/WER against a known-correct transcript per image; this one has no transcript
 * to score against and instead dumps the full [id.dotcode.braille.ocr.model.OcrDocument]
 * (or the failure reason) to a per-image `.json`/`.txt` file next to the source image, so a
 * human (or a later automated check) can read the actual reading order, column count, skew,
 * and per-block roles/markers that the real pipeline produced on an unlabelled photo.
 *
 * Run it with:
 * ```
 * adb shell mkdir -p /sdcard/Download/braille-corpus
 * adb push corpus-images/ /sdcard/Download/braille-corpus/
 * ./gradlew :ocr-mlkit:assembleDebugAndroidTest
 * adb install -r -t ocr-mlkit/build/outputs/apk/androidTest/debug/ocr-mlkit-debug-androidTest.apk
 * adb shell appops set --uid id.dotcode.braille.ocr.mlkit.test MANAGE_EXTERNAL_STORAGE allow
 * ./gradlew :ocr-mlkit:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=id.dotcode.braille.ocr.mlkit.OcrBatchStructureHarnessTest
 * adb pull /sdcard/Download/braille-corpus ./pulled-corpus
 * ```
 * See [OcrAccuracyHarnessTest]'s KDoc for why the `appops` grant is required under scoped
 * storage — the same applies here.
 *
 * Override the directory with
 * `-Pandroid.testInstrumentationRunnerArguments.brailleCorpusDir=/sdcard/...`.
 *
 * With no images present the test SKIPS with a message naming the directory it searched. It
 * never fails on pipeline output — a failed recognition is a real, recorded outcome, not a
 * test failure — it only fails if it cannot write its output files at all.
 */
@RunWith(AndroidJUnit4::class)
class OcrBatchStructureHarnessTest {

    private val tag = "OcrBatchStructureHarness"

    @Test
    fun dumpsStructuredOutputForEveryCorpusImage(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dirPath = InstrumentationRegistry.getArguments()?.getString(ARG_DIR) ?: DEFAULT_DIR
        val dir = File(dirPath)

        val images = findImages(dir)
        val skipMessage = "no corpus images found in $dirPath — push some with " +
            "`adb push photo.jpg $dirPath/`, or point the harness elsewhere with " +
            "-Pandroid.testInstrumentationRunnerArguments.$ARG_DIR=/path/on/device. " +
            "Directory exists=${dir.exists()} readable=${dir.canRead()} " +
            "entries=${dir.listFiles()?.size ?: -1}. If the directory exists and is " +
            "readable but lists nothing, scoped storage is hiding files this app did not " +
            "write: run `adb shell appops set --uid ${context.packageName} " +
            "MANAGE_EXTERNAL_STORAGE allow` and re-run."
        if (images.isEmpty()) Log.w(tag, "SKIPPED: $skipMessage")
        assumeTrue(skipMessage, images.isNotEmpty())

        val engine = OcrEngine(context)
        var succeeded = 0
        var failed = 0
        try {
            for (image in images) {
                val result = engine.recognize(Uri.fromFile(image))
                when (result) {
                    is OcrResult.Success -> {
                        val out = File(dir, "${image.nameWithoutExtension}.json")
                        out.writeText(result.document.toJson())
                        Log.i(
                            tag,
                            "${image.name}: OK columns=${result.document.columnCount} " +
                                "skewDeg=${result.document.skewDeg} blocks=${result.document.blocks.size}",
                        )
                        succeeded++
                    }
                    is OcrResult.Failure -> {
                        val out = File(dir, "${image.nameWithoutExtension}.failure.txt")
                        out.writeText("reason=${result.reason}\ndetail=${result.detail}")
                        Log.w(tag, "${image.name}: FAILED reason=${result.reason} detail=${result.detail}")
                        failed++
                    }
                }
            }
        } finally {
            engine.close()
        }
        Log.i(tag, "=== done: ${images.size} image(s), $succeeded succeeded, $failed failed ===")
    }

    /** Corpus images, in a deterministic order, excluding any prior run's output files. */
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
