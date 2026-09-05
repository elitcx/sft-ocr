package id.dotcode.braille.ocr.raw

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.geometry.PointF
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Provider-agnostic OCR output. Any recognizer — ML Kit today, a fine-tuned
 * Tesseract in a later phase — produces this and nothing else crosses the boundary.
 *
 * Note that provider-level block grouping is deliberately absent. ML Kit's blocks are
 * geometric clusters rather than semantic units; [id.dotcode.braille.ocr.pipeline.LineMerger]
 * re-derives paragraph boundaries under rules this project controls.
 */
@Serializable
data class RawTextResult(
    val imageWidth: Int,
    val imageHeight: Int,
    val lines: List<RawLine>,
) {
    /**
     * Round-trips through JSON so a real recognizer's output can be captured on-device
     * once and replayed as a JVM test fixture — see
     * `ocr-core/src/test/resources/fixtures/`.
     */
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { prettyPrint = true; encodeDefaults = true }
        fun fromJson(text: String): RawTextResult = JSON.decodeFromString(serializer(), text)
    }
}

@Serializable
data class RawLine(
    val text: String,
    val box: BoxF,
    val cornerPoints: List<PointF> = emptyList(),
    val angleDeg: Float = 0f,
    val recognizedLanguage: String? = null,
    val confidence: Float? = null,
    val words: List<RawWord> = emptyList(),
)

@Serializable
data class RawWord(
    val text: String,
    val box: BoxF,
    val confidence: Float? = null,
)
