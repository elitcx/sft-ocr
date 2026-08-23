package id.dotcode.braille.ocr.mlkit

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import id.dotcode.braille.ocr.model.FailureReason
import id.dotcode.braille.ocr.model.OcrResult
import id.dotcode.braille.ocr.model.Timings
import id.dotcode.braille.ocr.pipeline.DocumentStructurer
import id.dotcode.braille.ocr.pipeline.StructuringConfig
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The public entry point of the OCR module.
 *
 * The recognizer instance is long-lived on purpose: construction dominates cost, while
 * recognition on a warm instance is cheap. Callers must [close] it when done.
 */
class OcrEngine(
    private val context: Context,
    config: StructuringConfig = StructuringConfig(),
    private val qualityGate: CaptureQualityGate = CaptureQualityGate(),
) : AutoCloseable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val structurer = DocumentStructurer(config)
    private val closed = AtomicBoolean(false)

    suspend fun recognize(uri: Uri): OcrResult {
        if (closed.get()) return OcrResult.Failure(FailureReason.Cancelled, "engine closed")
        var bitmap: Bitmap? = null
        var rotation = 0
        val decodeMs = measureTimeMillis {
            // One open. The compressed bytes are a few MB; the decoded bitmap would be
            // an order of magnitude larger, so buffering the file is the cheap half and
            // it lets EXIF, the bounds pass and the real decode all share it.
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null) {
                rotation = runCatching {
                    ImagePreprocessor.rotationDegrees(ByteArrayInputStream(bytes))
                }.getOrDefault(0)
                // Subsampled decode: never allocate the full-resolution frame.
                bitmap = runCatching { ImagePreprocessor.decodeSampled(bytes) }.getOrNull()
            }
        }
        val decoded = bitmap ?: return OcrResult.Failure(FailureReason.NoTextFound, "could not decode image")
        return recognize(decoded, rotation, decodeMs)
    }

    suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int = 0): OcrResult =
        recognize(bitmap, rotationDegrees, decodeMs = 0)

    private suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int, decodeMs: Long): OcrResult {
        if (closed.get()) return OcrResult.Failure(FailureReason.Cancelled, "engine closed")

        var scaled: Bitmap = bitmap
        var gateFailure: FailureReason? = null
        val preprocessMs = measureTimeMillis {
            scaled = ImagePreprocessor.downscale(bitmap)
            gateFailure = qualityGate.evaluate(scaled)
        }
        gateFailure?.let { return OcrResult.Failure(it) }

        val recognizeStart = System.currentTimeMillis()
        val input = InputImage.fromBitmap(scaled, rotationDegrees)
        val outcome = suspendCancellableCoroutine { continuation ->
            recognizer.process(input)
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(RecognitionOutcome.Success(it)) }
                .addOnFailureListener { if (continuation.isActive) continuation.resume(RecognitionOutcome.Failed(it)) }
                .addOnCanceledListener { if (continuation.isActive) continuation.resume(RecognitionOutcome.Cancelled) }
        }
        val recognizeMs = System.currentTimeMillis() - recognizeStart

        val text = when (outcome) {
            is RecognitionOutcome.Cancelled -> return OcrResult.Failure(FailureReason.Cancelled)
            is RecognitionOutcome.Failed ->
                return OcrResult.Failure(FailureReason.ModelUnavailable, outcome.error.message)
            is RecognitionOutcome.Success -> outcome.text
        }
        if (text.textBlocks.isEmpty()) return OcrResult.Failure(FailureReason.NoTextFound)

        // Recognition ran on the scaled bitmap, so the document's page space is the
        // scaled space. Coordinates and page dimensions therefore stay consistent.
        //
        // ML Kit reports coordinates in the ROTATED image space, not in the bitmap's own
        // space. For a 90 or 270 degree EXIF capture - the norm for phone photos, and
        // this app's primary path - the page is therefore as wide as the bitmap is tall.
        // Passing the unrotated dimensions transposed the page relative to its boxes,
        // which broke RoleClassifier's topBandFraction/bottomBandFraction bands and
        // shipped wrong pageWidth/pageHeight in the exported JSON.
        val quarterTurn = rotationDegrees % 180 != 0
        val pageWidth = if (quarterTurn) scaled.height else scaled.width
        val pageHeight = if (quarterTurn) scaled.width else scaled.height
        val adapted = MlKitAdapter.toRawTextResult(text, pageWidth, pageHeight)
        var structureMs = 0L
        lateinit var document: id.dotcode.braille.ocr.model.OcrDocument
        structureMs = measureTimeMillis {
            document = structurer.structure(adapted)
        }
        return OcrResult.Success(
            document.copy(
                timings = Timings(
                    decodeMs = decodeMs,
                    preprocessMs = preprocessMs,
                    recognizeMs = recognizeMs,
                    structureMs = structureMs,
                )
            )
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            recognizer.close()
        }
    }

    /** Distinguishes ML Kit's three possible Task outcomes for the suspending bridge. */
    private sealed interface RecognitionOutcome {
        data class Success(val text: com.google.mlkit.vision.text.Text) : RecognitionOutcome
        data class Failed(val error: Throwable) : RecognitionOutcome
        object Cancelled : RecognitionOutcome
    }
}
