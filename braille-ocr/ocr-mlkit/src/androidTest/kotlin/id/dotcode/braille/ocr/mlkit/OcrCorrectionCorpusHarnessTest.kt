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
 * On-demand review harness for the offline correction layer on a corpus of real photos with
 * no transcript. For each photo it records how well the two recognizers line up, the
 * timings, and every correction with the recognizer's original line around it, so a human
 * can check each change against the photo.
 *
 * Run it with:
 * ```
 * adb shell mkdir -p /sdcard/Android/media/id.dotcode.braille.ocr.mlkit.test/corpus
 * adb push photos/. /sdcard/Android/media/id.dotcode.braille.ocr.mlkit.test/corpus/
 * ./gradlew :ocr-mlkit:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=id.dotcode.braille.ocr.mlkit.OcrCorrectionCorpusHarnessTest
 * adb pull /sdcard/Android/media/id.dotcode.braille.ocr.mlkit.test/corpus-report
 * ```
 * The app-private media directory needs no storage grant. Skips when it holds no photos.
 * `summary.tsv` has one row per photo; `corrections.tsv` one row per change; and
 * `uncorrected/<photo>.json` each page before correction, for replaying the pass on a PC.
 * Pass `-e resume true` to continue an interrupted run (reports get a `-resumed` suffix).
 */
@RunWith(AndroidJUnit4::class)
class OcrCorrectionCorpusHarnessTest {

    private val tag = "OcrCorrectionCorpus"

    @Test
    fun reportsCorrectionsForEveryCorpusPhoto(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val media = context.externalMediaDirs.first()
        val input = File(media, "corpus")
        val output = File(media, "corpus-report").apply { mkdirs() }
        val docs = File(output, "uncorrected").apply { mkdirs() }
        // resume=true skips photos whose page was already saved by an interrupted run.
        val resume = InstrumentationRegistry.getArguments()?.getString("resume") == "true"
        val photos = input.listFiles().orEmpty()
            .filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            .filter { !resume || File(docs, "${it.nameWithoutExtension}.json").length() == 0L }
            .sortedBy { it.name }
        assumeTrue("no photos in $input", photos.isNotEmpty())

        val engine = OcrEngine(context)
        // Phone-speed limits don't apply to an emulator run; measure the full evidence.
        engine.correctionSettings = OcrEngine.CorrectionSettings(enabled = true, secondReadGraceMs = 300_000L)
        engine.warmUp()
        val summary = StringBuilder("photo\tstatus\twords\tpaired\tagreeing\tocr_ms\ttess_ms\tcorrect_ms\tcorrections\n")
        val corrections = StringBuilder("photo\tblock\toriginal\tcorrected\tline\n")
        try {
            for (photo in photos) {
                when (val result = engine.recognize(Uri.fromFile(photo))) {
                    is OcrResult.Failure -> summary.append("${photo.name}\t${result.reason}\t\t\t\t\t\t\t\n")
                    is OcrResult.Success -> {
                        val document = result.document
                        // The pre-correction page with both recognizers' readings: replaying
                        // the spelling pass on these needs no device and no re-recognition.
                        File(docs, "${photo.nameWithoutExtension}.json")
                            .writeText((result.uncorrected ?: document).toJson())
                        val words = document.blocks.flatMap { b -> b.lines.flatMap { it.words } }
                        val paired = words.filter { it.secondReading != null }
                        val agreeing = paired.count { normalize(it.text) == normalize(it.secondReading!!) }
                        val fixes = document.blocks.sumOf { it.corrections.size }
                        val t = document.timings
                        summary.append(
                            "${photo.name}\tok\t${words.size}\t${paired.size}\t$agreeing\t" +
                                "${t.recognizeMs}\t${t.secondReadMs}\t${t.correctMs}\t$fixes\n",
                        )
                        for (block in document.blocks) {
                            for (fix in block.corrections) {
                                val line = block.lines.firstOrNull { fix.original in it.text }?.text.orEmpty()
                                corrections.append(
                                    "${photo.name}\t${block.id}\t${fix.original}\t${fix.corrected}\t" +
                                        "${line.replace('\t', ' ')}\n",
                                )
                            }
                        }
                        Log.i(tag, "${photo.name}: words=${words.size} paired=${paired.size} agree=$agreeing fixes=$fixes")
                    }
                }
            }
        } finally {
            engine.close()
        }
        val suffix = if (resume) "-resumed" else ""
        File(output, "summary$suffix.tsv").writeText(summary.toString())
        File(output, "corrections$suffix.tsv").writeText(corrections.toString())
        Log.i(tag, "wrote ${photos.size} rows to $output")
    }

    private fun normalize(text: String) = text.filter { it.isLetterOrDigit() }.lowercase()
}
