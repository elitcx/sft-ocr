package id.dotcode.braille.ocr.pipeline

/**
 * A single, hand-bumped identifier for "what the seven-stage structuring pipeline does right
 * now" — [StructuringConfig]'s constants included.
 *
 * This exists so a labelled dataset sample (see `id.dotcode.braille.ocr.dataset`) can record
 * which version of the pipeline produced its raw OCR text or block labels. Accuracy numbers
 * and role labels are only comparable across samples stamped with the same version; a config
 * constant changing invalidates that comparison even though the Kotlin/Gradle version number
 * of the module does not change.
 *
 * Bump [CURRENT] whenever [StructuringConfig]'s defaults change, a structuring stage's
 * algorithm changes, or [id.dotcode.braille.ocr.model.BlockRole] gains/loses a case — anything
 * that would make an old sample's stored output stop matching what the pipeline produces today.
 * A cosmetic refactor with identical behavior does not need a bump.
 */
object PipelineVersion {
    const val CURRENT: String = "2026.09.05-1"
}
