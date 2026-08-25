package id.dotcode.braille.ocr.model

import id.dotcode.braille.ocr.geometry.BoxF
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Semantic role of a block, assigned by RoleClassifier. */
enum class BlockRole { TITLE, HEADING, QUESTION, LIST_ITEM, PARAGRAPH, CAPTION, PAGE_NUMBER }

/** Horizontal alignment of a block within its column. */
enum class Alignment { LEFT, CENTER, RIGHT }

@Serializable
data class Timings(
    val decodeMs: Long = 0,
    val preprocessMs: Long = 0,
    val recognizeMs: Long = 0,
    val structureMs: Long = 0,
) {
    val totalMs: Long get() = decodeMs + preprocessMs + recognizeMs + structureMs
}

@Serializable
data class TextLine(
    val text: String,
    val box: BoxF,
    val confidence: Float? = null,
    val recognizedLanguage: String? = null,
)

@Serializable
data class TextBlock(
    val id: Int,
    val role: BlockRole,
    val columnIndex: Int,
    val marker: String? = null,
    val indentLevel: Int = 0,
    val alignment: Alignment = Alignment.LEFT,
    /**
     * Median line height of this block divided by a LOCAL baseline: the median line
     * height of nearby blocks in reading order (falling back to the whole page's median
     * when too few neighbours exist). This is deliberately local, not page-wide, because
     * a handheld photo's perspective makes lines near the bottom of the page measure
     * taller than lines near the top even at identical printed font size; comparing
     * locally cancels that gradient. 1.0 is body text relative to its neighbours.
     */
    val relativeTextHeight: Float = 1f,
    /** Reflowed text with the marker stripped. Original breaks stay in [lines]. */
    val text: String,
    val lines: List<TextLine>,
    val box: BoxF,
    val confidence: Float? = null,
)

@Serializable
data class OcrDocument(
    val pageWidth: Int,
    val pageHeight: Int,
    val skewDeg: Float,
    val columnCount: Int,
    /** Already sorted into reading order. */
    val blocks: List<TextBlock>,
    val meanConfidence: Float? = null,
    val timings: Timings = Timings(),
) {
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { prettyPrint = true; encodeDefaults = true }
        fun fromJson(text: String): OcrDocument = JSON.decodeFromString(serializer(), text)
    }
}
