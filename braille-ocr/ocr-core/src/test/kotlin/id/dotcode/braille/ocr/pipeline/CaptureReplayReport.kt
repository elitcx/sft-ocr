package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.accuracy.DocumentFlattener
import id.dotcode.braille.ocr.accuracy.ErrorRate
import id.dotcode.braille.ocr.spelling.SpellCorrector
import id.dotcode.braille.ocr.spelling.SpellingDictionary
import java.io.File
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Replays [PageAssembler] on raw recognition saved by the on-device `OcrCaptureHarnessTest`
 * and scores it against the transcripts in `dataset/ai-transcribed/` (tuning set) and
 * `dataset/ai-transcribed/holdout/` (never tuned on). Pipeline changes are judged on both.
 *
 * Skipped unless BRAILLE_CAPTURES names the folder of capture JSON. Writes
 * `build/replay-report.tsv` (one row per page) and prints the per-set totals.
 */
class CaptureReplayReport {

    @Test
    fun `replay captures and score both answer-key sets`() {
        val captures = System.getenv("BRAILLE_CAPTURES")?.let(::File)
        assumeTrue(captures != null && captures.isDirectory, "set BRAILLE_CAPTURES to a folder of capture JSON")

        val assets = File("../ocr-mlkit/src/main/assets/spelling")
        val corrector = SpellCorrector(SpellingDictionary.fromBundled { File(assets, it).readLines().asSequence() })
        val assembler = PageAssembler()
        val sets = mapOf(
            "tuning" to File("../dataset/ai-transcribed"),
            "holdout" to File("../dataset/ai-transcribed/holdout"),
        )
        val report = StringBuilder("set\tpage\tcer_raw\tcer\twer\tchars\n")
        for ((set, dir) in sets) {
            val expectedAll = StringBuilder()
            val rawAll = StringBuilder()
            val actualAll = StringBuilder()
            var pages = 0
            for (transcript in dir.listFiles().orEmpty().filter { it.extension == "txt" }.sortedBy { it.name }) {
                val capture = File(captures, "${transcript.nameWithoutExtension}.json")
                if (!capture.isFile) continue
                val recognized = RecognitionCapture.fromJson(capture.readText())
                val assembled = assembler.assemble(
                    recognized.primary,
                    recognized.rotationDegrees,
                    recognized.secondWordList(),
                    corrector,
                )
                val expected = transcript.readText()
                val raw = DocumentFlattener.flatten(assembled.uncorrected)
                val actual = DocumentFlattener.flatten(assembled.document)
                val rawScore = ErrorRate.compare(expected, raw)
                val score = ErrorRate.compare(expected, actual)
                report.append(
                    "$set\t${transcript.nameWithoutExtension}\t${"%.4f".format(rawScore.cer)}\t" +
                        "${"%.4f".format(score.cer)}\t${"%.4f".format(score.wer)}\t${score.expectedChars}\n",
                )
                File("build/replay-text").apply { mkdirs() }
                    .resolve("$set-${transcript.nameWithoutExtension}.txt").writeText(actual)
                expectedAll.append(expected).append('\n')
                rawAll.append(raw).append('\n')
                actualAll.append(actual).append('\n')
                pages++
            }
            val raw = ErrorRate.compare(expectedAll.toString(), rawAll.toString())
            val total = ErrorRate.compare(expectedAll.toString(), actualAll.toString())
            println(
                "REPLAY $set: $pages pages  CER ${"%.4f".format(raw.cer)} -> ${"%.4f".format(total.cer)}  " +
                    "WER ${"%.4f".format(raw.wer)} -> ${"%.4f".format(total.wer)}",
            )
        }
        File("build/replay-report.tsv").writeText(report.toString())
    }
}
