package id.dotcode.braille.ocr.model

/**
 * Why a capture was rejected. Blur and darkness are caught before recognition runs:
 * a blind student reading garbage braille has no way to notice the recognition failed,
 * so refusing a bad capture is safer than emitting confident nonsense.
 */
enum class FailureReason { TooBlurry, TooDark, NoTextFound, ModelUnavailable, Cancelled }

sealed interface OcrResult {
    /**
     * [uncorrected] is the same page before any spelling correction, or null when nothing
     * was corrected - kept so a reader can compare and a training sample stays raw.
     */
    data class Success(val document: OcrDocument, val uncorrected: OcrDocument? = null) : OcrResult
    data class Failure(val reason: FailureReason, val detail: String? = null) : OcrResult
}
