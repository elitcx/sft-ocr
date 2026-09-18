package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.model.OcrDocument
import id.dotcode.braille.ocr.raw.RawTextResult
import id.dotcode.braille.ocr.spelling.ReadingVoter
import id.dotcode.braille.ocr.spelling.SecondWord
import id.dotcode.braille.ocr.spelling.SpellCorrector
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything after recognition, in one place: pairing the second recognizer's words with the
 * primary result, structuring, and spelling correction. The on-device engine and the JVM
 * replay tools run exactly this, so a change measured on a PC is the change the phone gets.
 */
class PageAssembler(config: StructuringConfig = StructuringConfig()) {

    private val structurer = DocumentStructurer(config)

    data class Assembled(val document: OcrDocument, val uncorrected: OcrDocument, val structureMs: Long, val correctMs: Long)

    /**
     * @param primary the primary recognizer's output in its own (capture) frame.
     * @param secondWords the second recognizer's words in the upright frame, or null when it
     * did not run or ran out of time.
     */
    fun assemble(
        primary: RawTextResult,
        rotationDegrees: Int,
        secondWords: List<SecondWord>?,
        corrector: SpellCorrector?,
    ): Assembled {
        var annotated = primary
        if (secondWords != null) {
            // Tesseract's boxes are in the upright frame. Whether ML Kit's bitmap-input boxes
            // are upright or in the capture frame has proven device- and version-dependent,
            // so both placements are tried and the one where the recognizers agree wins (or
            // neither, if both disagree).
            val turnedBack = secondWords.map {
                it.copy(box = FrameRotation.unturnBox(it.box, rotationDegrees, primary.imageWidth, primary.imageHeight))
            }
            annotated = ReadingVoter.annotateBestFrame(primary, listOf(turnedBack, secondWords).distinct())
        }
        val structureStart = System.nanoTime()
        val document = structurer.structure(annotated, rotationDegrees = rotationDegrees)
        val structureMs = (System.nanoTime() - structureStart) / 1_000_000
        val correctStart = System.nanoTime()
        val corrected = corrector?.correct(document) ?: document
        val correctMs = (System.nanoTime() - correctStart) / 1_000_000
        return Assembled(corrected, document, structureMs, correctMs)
    }
}

/**
 * Both recognizers' raw output for one photo, saved on a device so [PageAssembler] can be
 * replayed on a PC without re-running recognition. [secondWords] is null when the second
 * recognizer produced nothing.
 */
@Serializable
data class RecognitionCapture(
    val photo: String,
    val rotationDegrees: Int,
    val primary: RawTextResult,
    val secondWords: List<CapturedWord>? = null,
) {
    @Serializable
    data class CapturedWord(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float, val confidence: Float? = null)

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    fun secondWordList(): List<SecondWord>? = secondWords?.map {
        SecondWord(it.text, id.dotcode.braille.ocr.geometry.BoxF(it.left, it.top, it.right, it.bottom), it.confidence)
    }

    companion object {
        private val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        fun fromJson(text: String): RecognitionCapture = JSON.decodeFromString(serializer(), text)

        fun of(photo: String, rotationDegrees: Int, primary: RawTextResult, secondWords: List<SecondWord>?) =
            RecognitionCapture(
                photo, rotationDegrees, primary,
                secondWords?.map { CapturedWord(it.text, it.box.left, it.box.top, it.box.right, it.box.bottom, it.confidence) },
            )
    }
}
