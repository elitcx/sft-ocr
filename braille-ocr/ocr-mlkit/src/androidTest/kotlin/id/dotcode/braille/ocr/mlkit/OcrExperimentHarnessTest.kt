package id.dotcode.braille.ocr.mlkit

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.dotcode.braille.ocr.accuracy.DocumentFlattener
import id.dotcode.braille.ocr.accuracy.ErrorRate
import id.dotcode.braille.ocr.model.OcrResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-demand comparison of pipeline settings on photos with transcripts (e.g.
 * `dataset/ai-transcribed/`): every photo is read under each [Variant] and scored, so a
 * change like a higher reading resolution is judged on measured error rates.
 *
 * Run it with:
 * ```
 * M=/sdcard/Android/media/id.dotcode.braille.ocr.mlkit.test
 * adb shell mkdir -p $M/experiment
 * adb push <photo>.jpg <photo>.txt ... $M/experiment/
 * # optional: the larger Tesseract models, to compare against the bundled "fast" ones
 * adb push ind.traineddata eng.traineddata $M/tessdata-best/tessdata/
 * ./gradlew :ocr-mlkit:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=id.dotcode.braille.ocr.mlkit.OcrExperimentHarnessTest
 * adb pull $M/experiment-report
 * ```
 * Skips when `experiment/` holds no photo+transcript pairs. Timings from an emulator say
 * little about a phone; the error rates are what this is for.
 */
@RunWith(AndroidJUnit4::class)
class OcrExperimentHarnessTest {

    private val tag = "OcrExperiment"

    private data class Variant(
        val name: String,
        val longEdge: Int,
        val correction: Boolean,
        val bestTesseract: Boolean = false,
    )

    @Test
    fun comparesPipelineSettings(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val media = context.externalMediaDirs.first()
        val input = File(media, "experiment")
        val output = File(media, "experiment-report").apply { mkdirs() }
        val bestDir = File(media, "tessdata-best").takeIf { File(it, "tessdata").isDirectory }
        val pairs = input.listFiles().orEmpty()
            .filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            .mapNotNull { image -> File(input, "${image.nameWithoutExtension}.txt").takeIf { it.isFile }?.let { image to it } }
            .sortedBy { it.first.name }
        assumeTrue("no photo+transcript pairs in $input", pairs.isNotEmpty())

        val allVariants = buildList {
            for (edge in listOf(1600, 2400, 3200)) {
                add(Variant("ocr-$edge", edge, correction = false))
                add(Variant("corrected-$edge", edge, correction = true))
            }
            if (bestDir != null) add(Variant("corrected-1600-best", 1600, correction = true, bestTesseract = true))
        }
        // -e variants corrected-1600,corrected-3200 runs just those.
        val wanted = InstrumentationRegistry.getArguments()?.getString("variants")?.split(',')?.map { it.trim() }
        val variants = if (wanted == null) allVariants else allVariants.filter { it.name in wanted }

        val rows = StringBuilder("variant\tphoto\tcer\twer\tchars\ttotal_ms\ttess_ms\tfixes\n")
        val summary = StringBuilder("variant\tcer\twer\tmedian_total_ms\n")
        for (variant in variants) {
            val engine = OcrEngine(
                context,
                targetLongEdge = variant.longEdge,
                tesseractDataDir = if (variant.bestTesseract) bestDir else null,
            )
            engine.correctionSettings = OcrEngine.CorrectionSettings(
                enabled = variant.correction,
                secondReadGraceMs = 300_000L,
            )
            engine.warmUp()
            val expectedAll = StringBuilder()
            val actualAll = StringBuilder()
            val times = mutableListOf<Long>()
            try {
                for ((image, transcript) in pairs) {
                    val expected = transcript.readText()
                    val result = engine.recognize(Uri.fromFile(image))
                    val document = (result as? OcrResult.Success)?.document
                    val actual = document?.let(DocumentFlattener::flatten).orEmpty()
                    val report = ErrorRate.compare(expected, actual)
                    val t = document?.timings
                    times += t?.totalMs ?: 0L
                    rows.append(
                        "${variant.name}\t${image.name}\t${"%.4f".format(report.cer)}\t${"%.4f".format(report.wer)}\t" +
                            "${report.expectedChars}\t${t?.totalMs}\t${t?.secondReadMs}\t" +
                            "${document?.blocks?.sumOf { it.corrections.size }}\n",
                    )
                    expectedAll.append(expected).append('\n')
                    actualAll.append(actual).append('\n')
                }
            } finally {
                engine.close()
            }
            val aggregate = ErrorRate.compare(expectedAll.toString(), actualAll.toString())
            val median = times.sorted()[times.size / 2]
            summary.append("${variant.name}\t${"%.4f".format(aggregate.cer)}\t${"%.4f".format(aggregate.wer)}\t$median\n")
            Log.i(tag, "${variant.name}: CER=${"%.4f".format(aggregate.cer)} WER=${"%.4f".format(aggregate.wer)} median=${median}ms")
        }
        File(output, "rows.tsv").writeText(rows.toString())
        File(output, "summary.tsv").writeText(summary.toString())
        Log.i(tag, "done: ${pairs.size} photos x ${variants.size} variants")
    }
}
