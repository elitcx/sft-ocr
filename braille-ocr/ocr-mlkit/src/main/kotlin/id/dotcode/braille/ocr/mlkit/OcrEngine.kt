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
import id.dotcode.braille.ocr.pipeline.PageAssembler
import id.dotcode.braille.ocr.pipeline.RecognitionCapture
import id.dotcode.braille.ocr.pipeline.StructuringConfig
import id.dotcode.braille.ocr.raw.RawTextResult
import id.dotcode.braille.ocr.spelling.SecondWord
import id.dotcode.braille.ocr.spelling.SpellCorrector
import id.dotcode.braille.ocr.spelling.SpellingDictionary
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

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
    /** Long edge, in pixels, the capture is scaled to before recognition. */
    private val targetLongEdge: Int = ImagePreprocessor.TARGET_LONG_EDGE,
    /**
     * A directory holding a `tessdata/` folder to use instead of the bundled Tesseract
     * models (e.g. the larger "best" models, for comparison). Null uses the bundled ones.
     */
    tesseractDataDir: java.io.File? = null,
) : AutoCloseable {

    /**
     * The offline correction layer: a second recognizer (Tesseract) votes on every word,
     * then [SpellCorrector] fixes what the votes and the dictionary agree is wrong.
     * [extraWords] are subject terms that must never be "corrected". Past
     * [secondReadGraceMs] after ML Kit finishes, the page is corrected without waiting for
     * Tesseract. Read once per scan.
     */
    data class CorrectionSettings(
        val enabled: Boolean = true,
        val extraWords: List<String> = emptyList(),
        val secondReadGraceMs: Long = 4_000L,
    )

    @Volatile
    var correctionSettings: CorrectionSettings = CorrectionSettings()

    /**
     * Both recognizers' raw output from the most recent successful recognition, for test
     * harnesses that save it to replay [PageAssembler] on a PC. Not used by the app.
     */
    @Volatile
    var lastCapture: RecognitionCapture? = null
        private set

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val assembler = PageAssembler(config)
    private val tesseract = TesseractReader(context, tesseractDataDir)
    private val closed = AtomicBoolean(false)

    private val baseDictionary: SpellingDictionary by lazy {
        SpellingDictionary.fromBundled { name ->
            context.assets.open("$DICTIONARY_DIR/$name").bufferedReader().use { it.readLines() }.asSequence()
        }
    }
    private var corrector: Pair<List<String>, SpellCorrector>? = null

    @Synchronized
    private fun correctorFor(extraWords: List<String>): SpellCorrector {
        corrector?.let { (words, cached) -> if (words == extraWords) return cached }
        return SpellCorrector(baseDictionary.withExtraWords(extraWords)).also { corrector = extraWords to it }
    }

    /**
     * Loads the dictionary and Tesseract's models ahead of the first scan, which would
     * otherwise pay for both. Optional; call off the main thread.
     */
    suspend fun warmUp() {
        val settings = correctionSettings
        if (!settings.enabled || closed.get()) return
        correctorFor(settings.extraWords)
        tesseract.warmUp()
    }

    /** The parts of [recognize], in order, reported so a caller can show real progress. */
    enum class Stage { DECODING, PREPROCESSING, RECOGNIZING, SECOND_READ, ASSEMBLING }

    suspend fun recognize(uri: Uri, onStage: (Stage) -> Unit = {}): OcrResult {
        if (closed.get()) return OcrResult.Failure(FailureReason.Cancelled, "engine closed")
        onStage(Stage.DECODING)
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
                bitmap = runCatching { ImagePreprocessor.decodeSampled(bytes, targetLongEdge) }.getOrNull()
            }
        }
        val decoded = bitmap ?: return OcrResult.Failure(FailureReason.NoTextFound, "could not decode image")
        return recognize(decoded, rotation, decodeMs, onStage)
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

    private suspend fun recognize(
        bitmap: Bitmap,
        rotationDegrees: Int,
        decodeMs: Long,
        onStage: (Stage) -> Unit = {},
    ): OcrResult {
        if (closed.get()) return OcrResult.Failure(FailureReason.Cancelled, "engine closed")
        lastCapture = null
        onStage(Stage.PREPROCESSING)

        var scaled: Bitmap = bitmap
        lateinit var gateEvaluation: CaptureQualityGate.Evaluation
        val preprocessMs = measureTimeMillis {
            scaled = ImagePreprocessor.downscale(bitmap, targetLongEdge)
            // The gate's thresholds were tuned at the default size; judge a copy at that size
            // so a larger reading resolution doesn't change what counts as blurry or dark.
            val gateFrame = ImagePreprocessor.downscale(scaled, ImagePreprocessor.TARGET_LONG_EDGE)
            gateEvaluation = qualityGate.evaluate(gateFrame)
            if (gateFrame !== scaled) gateFrame.recycle()
        }
        gateEvaluation.reason?.let { return OcrResult.Failure(it, gateEvaluation.detail) }

        val settings = correctionSettings
        return coroutineScope {
            // Tesseract reads the same frame while ML Kit does; neither waits for the other.
            val secondStart = System.currentTimeMillis()
            val secondRead = if (settings.enabled) {
                async(Dispatchers.Default) { tesseract.read(scaled, rotationDegrees) }
            } else {
                null
            }
            fun abandonSecondRead() {
                secondRead?.cancel()
                tesseract.stop()
            }

            onStage(Stage.RECOGNIZING)
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
                is RecognitionOutcome.Cancelled -> {
                    abandonSecondRead()
                    return@coroutineScope OcrResult.Failure(FailureReason.Cancelled)
                }
                is RecognitionOutcome.Failed -> {
                    abandonSecondRead()
                    return@coroutineScope OcrResult.Failure(FailureReason.ModelUnavailable, outcome.error.message)
                }
                is RecognitionOutcome.Success -> outcome.text
            }
            if (text.textBlocks.isEmpty()) {
                abandonSecondRead()
                return@coroutineScope OcrResult.Failure(FailureReason.NoTextFound)
            }

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

            var secondReadMs = 0L
            var secondReadWords = 0
            var secondWords: List<SecondWord>? = null
            if (secondRead != null) {
                // A slow phone must not hold the page hostage: past the grace period the
                // page is corrected on dictionary evidence alone.
                onStage(Stage.SECOND_READ)
                secondWords = withTimeoutOrNull(settings.secondReadGraceMs) { secondRead.await() }
                if (secondWords == null) abandonSecondRead()
                secondReadMs = System.currentTimeMillis() - secondStart
                secondReadWords = secondWords?.size ?: -1
            }
            lastCapture = RecognitionCapture.of("", rotationDegrees, adapted, secondWords)

            onStage(Stage.ASSEMBLING)
            val corrector = if (settings.enabled) correctorFor(settings.extraWords) else null
            val assembled = assembler.assemble(adapted, rotationDegrees, secondWords, corrector)
            val document = assembled.uncorrected
            val corrected = assembled.document
            val structureMs = assembled.structureMs
            val correctMs = assembled.correctMs
            val timings = Timings(
                decodeMs = decodeMs,
                preprocessMs = preprocessMs,
                recognizeMs = recognizeMs,
                structureMs = structureMs,
                secondReadMs = secondReadMs,
                correctMs = correctMs,
                secondReadWords = secondReadWords,
            )
            OcrResult.Success(
                document = corrected.copy(timings = timings),
                uncorrected = document.takeIf { it != corrected }?.copy(timings = timings),
            )
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            recognizer.close()
            tesseract.stop()
            tesseract.close()
        }
    }

    private companion object {
        const val DICTIONARY_DIR = "spelling"
    }

    /** Distinguishes ML Kit's three possible Task outcomes for the suspending bridge. */
    private sealed interface RecognitionOutcome {
        data class Success(val text: com.google.mlkit.vision.text.Text) : RecognitionOutcome
        data class Failed(val error: Throwable) : RecognitionOutcome
        object Cancelled : RecognitionOutcome
    }
}
