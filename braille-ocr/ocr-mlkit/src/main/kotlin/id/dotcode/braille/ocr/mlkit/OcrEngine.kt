package id.dotcode.braille.ocr.mlkit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import id.dotcode.braille.ocr.model.FailureReason
import id.dotcode.braille.ocr.model.OcrResult
import id.dotcode.braille.ocr.model.Timings
import id.dotcode.braille.ocr.pipeline.DocumentStructurer
import id.dotcode.braille.ocr.pipeline.StructuringConfig
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
            rotation = runCatching {
                context.contentResolver.openInputStream(uri)!!.use { ImagePreprocessor.rotationDegrees(it) }
            }.getOrDefault(0)
            bitmap = runCatching {
                context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
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
        val adapted = MlKitAdapter.toRawTextResult(text, scaled.width, scaled.height)
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
