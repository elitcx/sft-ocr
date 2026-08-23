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
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.system.measureTimeMillis

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

    suspend fun recognize(uri: Uri): OcrResult {
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
        var scaled: Bitmap = bitmap
        var gateFailure: FailureReason? = null
        val preprocessMs = measureTimeMillis {
            scaled = ImagePreprocessor.downscale(bitmap)
            gateFailure = qualityGate.evaluate(scaled)
        }
        gateFailure?.let { return OcrResult.Failure(it) }

        var raw: com.google.mlkit.vision.text.Text? = null
        var error: Throwable? = null
        val recognizeStart = System.currentTimeMillis()
        val input = InputImage.fromBitmap(scaled, rotationDegrees)
        val outcome = suspendCoroutine { continuation ->
            recognizer.process(input)
                .addOnSuccessListener { continuation.resume(Result.success(it)) }
                .addOnFailureListener { continuation.resume(Result.failure(it)) }
        }
        raw = outcome.getOrNull()
        error = outcome.exceptionOrNull()
        val recognizeMs = System.currentTimeMillis() - recognizeStart

        error?.let { return OcrResult.Failure(FailureReason.ModelUnavailable, it.message) }
        val text = raw ?: return OcrResult.Failure(FailureReason.ModelUnavailable)
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
        recognizer.close()
    }
}
