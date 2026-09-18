package id.dotcode.braille.ocr.spelling

import id.dotcode.braille.ocr.model.OcrDocument
import java.io.File
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Replays the spelling pass on uncorrected pages saved by the on-device
 * `OcrCorrectionCorpusHarnessTest` (its `uncorrected/` folder), so a change to the corrector
 * can be reviewed across a whole photo corpus in seconds, without a device.
 *
 * Skipped unless the BRAILLE_CORPUS_DOCS environment variable names that folder. Writes
 * `build/corpus-replay.tsv`: one row per correction with the recognizer's line around it.
 */
class CorpusReplayReport {

    @Test
    fun `replay corrections on a saved corpus`() {
        val dir = System.getenv("BRAILLE_CORPUS_DOCS")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory, "set BRAILLE_CORPUS_DOCS to a folder of uncorrected page JSON")

        val assets = File("../ocr-mlkit/src/main/assets/spelling")
        val corrector = SpellCorrector(
            SpellingDictionary.fromBundled { File(assets, it).readLines().asSequence() },
        )
        val report = StringBuilder("photo\toriginal\tcorrected\tline\n")
        for (file in dir!!.listFiles().orEmpty().filter { it.extension == "json" && it.length() > 0 }.sortedBy { it.name }) {
            val corrected = corrector.correct(OcrDocument.fromJson(file.readText()))
            for (block in corrected.blocks) {
                for (fix in block.corrections) {
                    val line = block.lines.firstOrNull { fix.original in it.text }?.text.orEmpty()
                    report.append("${file.nameWithoutExtension}\t${fix.original}\t${fix.corrected}\t${line.replace('\t', ' ')}\n")
                }
            }
        }
        File("build/corpus-replay.tsv").writeText(report.toString())
    }
}
