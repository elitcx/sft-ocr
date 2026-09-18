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
 * Saves both recognizers' raw output for every photo in `capture/` as
 * `capture-out/<photo>.json` ([id.dotcode.braille.ocr.pipeline.RecognitionCapture]), so
 * layout and correction changes can be replayed and scored on a PC in seconds
 * (`ocr-core`'s `CaptureReplayReport`) instead of re-running recognition on a device.
 *
 * ```
 * M=/sdcard/Android/media/id.dotcode.braille.ocr.mlkit.test
 * adb shell mkdir -p $M/capture && adb push photos/. $M/capture/
 * adb shell am instrument -w -e class id.dotcode.braille.ocr.mlkit.OcrCaptureHarnessTest \
 *     id.dotcode.braille.ocr.mlkit.test/androidx.test.runner.AndroidJUnitRunner
 * adb pull $M/capture-out
 * ```
 * Photos already captured are skipped, so an interrupted run can simply be started again.
 */
@RunWith(AndroidJUnit4::class)
class OcrCaptureHarnessTest {

    @Test
    fun capturesRawRecognition(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val media = context.externalMediaDirs.first()
        val input = File(media, "capture")
        val output = File(media, "capture-out").apply { mkdirs() }
        val photos = input.listFiles().orEmpty()
            .filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            .filter { File(output, "${it.nameWithoutExtension}.json").length() == 0L }
            .sortedBy { it.name }
        assumeTrue("nothing to capture in $input", photos.isNotEmpty())

        val engine = OcrEngine(context)
        engine.correctionSettings = OcrEngine.CorrectionSettings(enabled = true, secondReadGraceMs = 300_000L)
        engine.warmUp()
        try {
            for (photo in photos) {
                engine.recognize(Uri.fromFile(photo))
                val capture = engine.lastCapture?.copy(photo = photo.name)
                if (capture == null) {
                    Log.w(TAG, "${photo.name}: nothing recognized")
                    continue
                }
                val target = File(output, "${photo.nameWithoutExtension}.json")
                val partial = File(output, "${photo.nameWithoutExtension}.json.part")
                partial.writeText(capture.toJson())
                partial.renameTo(target)
                Log.i(TAG, "${photo.name}: captured ${capture.primary.lines.size} lines, ${capture.secondWords?.size} second words")
            }
        } finally {
            engine.close()
        }
        Log.i(TAG, "done")
    }

    private companion object {
        const val TAG = "OcrCapture"
    }
}
