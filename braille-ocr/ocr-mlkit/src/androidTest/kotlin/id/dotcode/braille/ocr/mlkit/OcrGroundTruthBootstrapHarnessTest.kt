package id.dotcode.braille.ocr.mlkit

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.dotcode.braille.ocr.accuracy.DocumentFlattener
import id.dotcode.braille.ocr.dataset.DatasetSampleMeta
import id.dotcode.braille.ocr.model.OcrResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ground-truth BOOTSTRAP harness: turns 139 unlabelled photos into 139 *draft*
 * transcripts a human can correct in minutes each, instead of 139 blank pages someone has
 * to type from scratch. This is step 1 of the workflow documented in
 * `docs/dataset-schema.md`; [OcrAccuracyHarnessTest] is step 3.
 *
 * For every image in the corpus directory this writes, beside the image:
 *  - `<name>.txt` — the real pipeline's raw recognized text, flattened the exact same way
 *    [OcrAccuracyHarnessTest] flattens its "actual" side ([DocumentFlattener]), so a human
 *    can open this file, correct it against the photo, and leave it in place: unmodified, it
 *    is already in the format [OcrAccuracyHarnessTest] expects to read as ground truth.
 *  - `<name>.dataset.json` — a [DatasetSampleMeta] record with `transcriptionSource =
 *    RAW_OCR`. THIS IS THE FIELD THAT MATTERS: uncorrected output is marked, in the file
 *    itself, as not-yet-ground-truth. A human doing the correction pass must flip this to
 *    `HUMAN_CORRECTED` after checking the `.txt` against the photo — nothing does that
 *    automatically, on purpose, because an automatic flip is exactly the "train on your own
 *    mistakes" failure mode this schema exists to prevent.
 *  - `<name>.failure.txt` instead of the two above, if recognition itself failed (rejected
 *    capture) — a real, recorded outcome, not skipped.
 *
 * Workflow:
 * ```
 * adb shell mkdir -p /sdcard/Download/braille-bootstrap
 * adb push "braille testing/"*.jpg /sdcard/Download/braille-bootstrap/
 * ./gradlew :ocr-mlkit:assembleDebugAndroidTest
 * adb install -r -t ocr-mlkit/build/outputs/apk/androidTest/debug/ocr-mlkit-debug-androidTest.apk
 * adb shell appops set --uid id.dotcode.braille.ocr.mlkit.test MANAGE_EXTERNAL_STORAGE allow
 * ./gradlew :ocr-mlkit:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=id.dotcode.braille.ocr.mlkit.OcrGroundTruthBootstrapHarnessTest
 * adb pull /sdcard/Download/braille-bootstrap ./pulled-bootstrap
 * ```
 * Then, per image: open the `.jpg`, open the matching `.txt` beside it, read the photo and
 * fix every mistake in the `.txt` (do not retype from scratch — correcting is far faster
 * than transcribing), and edit the `.dataset.json`'s `"transcriptionSource"` from
 * `"RAW_OCR"` to `"HUMAN_CORRECTED"`. Once corrected, copy the `.jpg`+`.txt` pair into
 * `ocr-mlkit/src/androidTest/assets/ground-truth/` (and its `.dataset.json` alongside, for
 * provenance) to commit it as a permanent, always-run seed sample — see
 * [OcrAccuracyHarnessTest]'s asset-seeding — or `adb push` the pair to
 * `/sdcard/Download/braille-samples/` for a one-off local run.
 *
 * See [OcrAccuracyHarnessTest]'s KDoc for why the `appops` grant is required under scoped
 * storage, and [OcrBatchStructureHarnessTest] for this test's structure-only sibling (no
 * `.txt`, full [id.dotcode.braille.ocr.model.OcrDocument] JSON instead — useful for a future
 * block-role classifier's raw material, not for CER/WER).
 */
@RunWith(AndroidJUnit4::class)
class OcrGroundTruthBootstrapHarnessTest {

    private val tag = "OcrGroundTruthBootstrap"

    @Test
    fun writesDraftTranscriptsForEveryCorpusImage(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dirPath = InstrumentationRegistry.getArguments()?.getString(ARG_DIR) ?: DEFAULT_DIR
        val dir = File(dirPath)

        val images = findImages(dir)
        val skipMessage = "no corpus images found in $dirPath — push the unlabelled corpus " +
            "with `adb push \"braille testing/\"*.jpg $dirPath/`, or point the harness " +
            "elsewhere with -Pandroid.testInstrumentationRunnerArguments.$ARG_DIR=/path. " +
            "Directory exists=${dir.exists()} readable=${dir.canRead()} " +
            "entries=${dir.listFiles()?.size ?: -1}. If the directory exists and is " +
            "readable but lists nothing, scoped storage is hiding files this app did not " +
            "write: run `adb shell appops set --uid ${context.packageName} " +
            "MANAGE_EXTERNAL_STORAGE allow` and re-run."
        if (images.isEmpty()) Log.w(tag, "SKIPPED: $skipMessage")
        assumeTrue(skipMessage, images.isNotEmpty())

        val engine = OcrEngine(context)
        var drafted = 0
        var failed = 0
        var skippedExisting = 0
        try {
            for (image in images) {
                val txtOut = File(dir, "${image.nameWithoutExtension}.txt")
                if (txtOut.isFile) {
                    // Never overwrite a transcript that might already carry a human
                    // correction — re-running the bootstrap must not clobber labelling work.
                    Log.i(tag, "${image.name}: ${txtOut.name} already exists, leaving it alone")
                    skippedExisting++
                    continue
                }
                val result = engine.recognize(Uri.fromFile(image))
                when (result) {
                    is OcrResult.Success -> {
                        val text = DocumentFlattener.flatten(result.document)
                        txtOut.writeText(text)
                        val meta = DatasetSampleMeta.rawOcr(
                            sourceImageFileName = image.name,
                            sourceImageBytes = image.readBytes(),
                            document = result.document,
                            capturedAtEpochMs = System.currentTimeMillis(),
                        )
                        File(dir, "${image.nameWithoutExtension}.dataset.json").writeText(meta.toJson())
                        Log.i(tag, "${image.name}: drafted ${text.length} chars, RAW_OCR")
                        drafted++
                    }
                    is OcrResult.Failure -> {
                        File(dir, "${image.nameWithoutExtension}.failure.txt")
                            .writeText("reason=${result.reason}\ndetail=${result.detail}")
                        Log.w(tag, "${image.name}: FAILED reason=${result.reason} detail=${result.detail}")
                        failed++
                    }
                }
            }
        } finally {
            engine.close()
        }
        Log.i(
            tag,
            "=== done: ${images.size} image(s), $drafted drafted, $failed failed, " +
                "$skippedExisting already had a transcript ===",
        )
    }

    /** Corpus images, in a deterministic order. */
    private fun findImages(dir: File): List<File> {
        if (!dir.isDirectory || !dir.canRead()) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            .sortedBy { it.name }
    }

    private companion object {
        const val DEFAULT_DIR = "/sdcard/Download/braille-bootstrap"
        const val ARG_DIR = "brailleBootstrapDir"
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")
    }
}
