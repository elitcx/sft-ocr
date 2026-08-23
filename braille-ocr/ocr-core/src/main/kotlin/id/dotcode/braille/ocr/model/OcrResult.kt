package id.dotcode.braille.ocr.model

/**
 * Why a capture was rejected. Blur and darkness are caught before recognition runs:
 * a blind student reading garbage braille has no way to notice the recognition failed,
 * so refusing a bad capture is safer than emitting confident nonsense.
 */
enum class FailureReason { TooBlurry, TooDark, NoTextFound, ModelUnavailable, Cancelled }

sealed interface OcrResult {
    data class Success(val document: OcrDocument) : OcrResult
    data class Failure(val reason: FailureReason, val detail: String? = null) : OcrResult
}
