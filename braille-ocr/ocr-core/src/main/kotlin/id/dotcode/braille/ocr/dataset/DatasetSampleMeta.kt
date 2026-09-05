package id.dotcode.braille.ocr.dataset

import id.dotcode.braille.ocr.model.BlockRole
import id.dotcode.braille.ocr.model.OcrDocument
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a sample's ground-truth text came from. This is the field that stops the flywheel
 * from quietly training on its own mistakes: a freshly bootstrapped sample is always
 * [RAW_OCR] until a human has read it against the photo and either fixed it or confirmed it,
 * at which point (and only then) it becomes [HUMAN_CORRECTED]. Nothing in this codebase may
 * treat [RAW_OCR] text as ground truth — see `docs/dataset-schema.md`.
 */
@Serializable
enum class TranscriptionSource { RAW_OCR, HUMAN_CORRECTED }

/**
 * A per-block role, kept alongside a sample as optional weak-supervision for a future block
 * classifier. These come straight from [id.dotcode.braille.ocr.pipeline]'s own (rule-based,
 * unverified) classification unless a human has reviewed and corrected them — [verified]
 * records which is true, the same way [TranscriptionSource] does for the transcript.
 */
@Serializable
data class BlockRoleLabel(
    val blockId: Int,
    val role: BlockRole,
    val marker: String? = null,
    val verified: Boolean = false,
)

/**
 * The provenance and identity record for one labelled sample: everything needed to know
 * *what* an image+text pair is, *where* it came from, and *whether it can be trusted* as
 * training/eval data. See `docs/dataset-schema.md` for the full schema description and a
 * worked example, and `ocr-mlkit/src/androidTest/assets/ground-truth/` for real committed
 * samples using this exact format.
 *
 * A `DatasetSampleMeta` is always stored beside its image and its `.txt` transcript, never
 * embedded in either — the image stays a plain, viewable JPEG/PNG and the transcript stays a
 * plain `.txt` that [id.dotcode.braille.ocr.accuracy]'s harnesses can read with zero parsing.
 */
@Serializable
data class DatasetSampleMeta(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** File name only, not a path — the sample directory is the identity, not this string. */
    val sourceImageFileName: String,
    /** SHA-256 of the source image's raw bytes; see [Sha256]. Survives a rename or a copy. */
    val sourceImageSha256: String,
    /** [id.dotcode.braille.ocr.pipeline.PipelineVersion.CURRENT] at capture time. */
    val pipelineVersion: String,
    val capturedAtEpochMs: Long,
    val transcriptionSource: TranscriptionSource,
    val blockRoleLabels: List<BlockRoleLabel> = emptyList(),
    val notes: String? = null,
) {
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val SCHEMA_VERSION = 1

        private val JSON = Json { prettyPrint = true; encodeDefaults = true }

        fun fromJson(text: String): DatasetSampleMeta = JSON.decodeFromString(serializer(), text)

        /** A fresh, unverified record for OCR output nobody has read yet. */
        fun rawOcr(
            sourceImageFileName: String,
            sourceImageBytes: ByteArray,
            document: OcrDocument,
            capturedAtEpochMs: Long,
        ): DatasetSampleMeta = DatasetSampleMeta(
            sourceImageFileName = sourceImageFileName,
            sourceImageSha256 = Sha256.hex(sourceImageBytes),
            pipelineVersion = id.dotcode.braille.ocr.pipeline.PipelineVersion.CURRENT,
            capturedAtEpochMs = capturedAtEpochMs,
            transcriptionSource = TranscriptionSource.RAW_OCR,
            blockRoleLabels = document.blocks.map { BlockRoleLabel(it.id, it.role, it.marker) },
        )
    }
}
