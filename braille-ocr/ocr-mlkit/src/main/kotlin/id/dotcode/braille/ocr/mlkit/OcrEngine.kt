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
import id.dotcode.braille.ocr.raw.RawTextResult
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

    /**
     * Debug/fixture-capture only: returns the recognizer's raw output (before
     * [DocumentStructurer] ever sees it) together with the EXIF rotation, bypassing
     * [CaptureQualityGate] entirely. Used to capture real on-device recognizer output as a
     * JVM test fixture under `ocr-core/src/test/resources/fixtures/` via
     * [RawTextResult.toJson] — see that class's KDoc. Not used by [recognize] or by any
     * production path.
     */
    suspend fun recognizeRawDebug(uri: Uri): Pair<RawTextResult, Int>? {
        if (closed.get()) return null
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        }.getOrNull() ?: return null
        val rotation = runCatching {
            ImagePreprocessor.rotationDegrees(ByteArrayInputStream(bytes))
        }.getOrDefault(0)
        val bitmap = runCatching { ImagePreprocessor.decodeSampled(bytes) }.getOrNull() ?: return null
        val scaled = ImagePreprocessor.downscale(bitmap)
        val input = InputImage.fromBitmap(scaled, rotation)
        val outcome = suspendCancellableCoroutine { continuation ->
            recognizer.process(input)
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(RecognitionOutcome.Success(it)) }
                .addOnFailureListener { if (continuation.isActive) continuation.resume(RecognitionOutcome.Failed(it)) }
                .addOnCanceledListener { if (continuation.isActive) continuation.resume(RecognitionOutcome.Cancelled) }
        }
        val text = (outcome as? RecognitionOutcome.Success)?.text ?: return null
        return MlKitAdapter.toRawTextResult(text, scaled.width, scaled.height) to rotation
    }

    suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int = 0): OcrResult =
        recognize(bitmap, rotationDegrees, decodeMs = 0)

    private suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int, decodeMs: Long): OcrResult {
        if (closed.get()) return OcrResult.Failure(FailureReason.Cancelled, "engine closed")

        var scaled: Bitmap = bitmap
        lateinit var gateEvaluation: CaptureQualityGate.Evaluation
        val preprocessMs = measureTimeMillis {
            scaled = ImagePreprocessor.downscale(bitmap)
            gateEvaluation = qualityGate.evaluate(scaled)
        }
        gateEvaluation.reason?.let { return OcrResult.Failure(it, gateEvaluation.detail) }

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

        // ML Kit's Text.Line coordinates are relative to the bitmap that was actually
        // decoded (`scaled`) - the ORIGINAL, un-rotated frame - regardless of the
        // rotationDegrees hint passed to InputImage.fromBitmap above. A prior version of
        // this code passed the EXIF-rotated (width/height swapped) page dimensions here
        // while these coordinates stayed in the original frame, which desynchronized
        // every downstream stage's notion of page space from the boxes it was handed.
        // Measured on real 90-degree-rotated photographs: SkewEstimator (fed a pivot
        // built from the wrong, swapped dimensions) reported the page as skewed 85-91
        // degrees, and boxes spilled outside the declared page bounds. Handing
        // FrameRotation the SAME frame for both dimensions and coordinates, and letting
        // it - not this call site - produce the upright, swapped page space, is what
        // keeps everything downstream self-consistent. See FrameRotation's KDoc.
        val adapted = MlKitAdapter.toRawTextResult(text, scaled.width, scaled.height)
        var structureMs = 0L
        lateinit var document: id.dotcode.braille.ocr.model.OcrDocument
        structureMs = measureTimeMillis {
            document = structurer.structure(adapted, rotationDegrees = rotationDegrees)
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
