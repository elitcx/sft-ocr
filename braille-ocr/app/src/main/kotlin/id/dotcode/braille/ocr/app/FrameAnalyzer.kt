package id.dotcode.braille.ocr.app

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Reads the camera preview while the student aims: how bright and sharp the frame is, how
 * much the phone is moving, and where the text sits. Text recognition is the expensive part,
 * so it runs a few times a second on the same frames and its last answer is reused between
 * runs.
 */
class FrameAnalyzer(
    private val tilt: () -> Float,
    private val onReading: (FrameReading) -> Unit,
) : ImageAnalysis.Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val recognizing = AtomicBoolean(false)
    private var lastRecognizedAt = 0L

    @Volatile private var text: TextBounds? = null

    /**
     * How long the last recognition stays trusted. A fast phone answers in well under a
     * second; a slow one takes longer, and dropping its answer after a fixed two seconds
     * would leave the student hearing "point the camera at text" over real text.
     */
    @Volatile private var textTtlMs = TEXT_TTL_MIN_MS
    private var previousLuma: IntArray? = null

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        val stats = lumaStats(image)
        val upright = uprightSize(image)

        val recognized = text?.takeIf { now - it.at <= textTtlMs }
        onReading(
            FrameReading(
                hasText = recognized != null && recognized.lineCount > 0,
                textLeft = recognized?.left ?: 0f,
                textTop = recognized?.top ?: 0f,
                textRight = recognized?.right ?: 0f,
                textBottom = recognized?.bottom ?: 0f,
                lineCount = recognized?.lineCount ?: 0,
                brightness = stats.brightness,
                sharpness = stats.sharpness,
                motion = stats.motion,
                tiltDegrees = tilt(),
                spreadDetected = recognized?.spread == true,
                lightSpread = stats.lightSpread,
            ),
        )

        val due = now - lastRecognizedAt >= RECOGNIZE_EVERY_MS
        if (!due || !recognizing.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastRecognizedAt = now
        val startedAt = now
        val rotation = image.imageInfo.rotationDegrees
        // Recognition works on a copy: holding the camera frame until it finishes would
        // stall the preview's analysis pipeline whenever the recognizer is slow.
        val frame = runCatching { image.toBitmap() }.getOrNull()
        image.close()
        if (frame == null) {
            recognizing.set(false)
            return
        }
        recognizer.process(InputImage.fromBitmap(frame, rotation))
            .addOnSuccessListener { result ->
                val lines = result.textBlocks.flatMap { it.lines }
                text = if (lines.isEmpty()) {
                    TextBounds(0f, 0f, 0f, 0f, lineCount = 0, at = System.currentTimeMillis())
                } else {
                    var left = Float.MAX_VALUE
                    var top = Float.MAX_VALUE
                    var right = 0f
                    var bottom = 0f
                    lines.forEach { line ->
                        line.boundingBox?.let { box ->
                            left = minOf(left, box.left.toFloat())
                            top = minOf(top, box.top.toFloat())
                            right = maxOf(right, box.right.toFloat())
                            bottom = maxOf(bottom, box.bottom.toFloat())
                        }
                    }
                    // Per-line spans, not just their union: an open book and a single
                    // page have the SAME bounding box, and only the gap between the lines
                    // tells them apart. See PageSplit.
                    val spans = lines.mapNotNull { line ->
                        line.boundingBox?.let {
                            LineSpan(
                                left = (it.left / upright.first).coerceIn(0f, 1f),
                                right = (it.right / upright.first).coerceIn(0f, 1f),
                            )
                        }
                    }
                    TextBounds(
                        left = (left / upright.first).coerceIn(0f, 1f),
                        top = (top / upright.second).coerceIn(0f, 1f),
                        right = (right / upright.first).coerceIn(0f, 1f),
                        bottom = (bottom / upright.second).coerceIn(0f, 1f),
                        lineCount = lines.size,
                        spread = PageSplit.looksLikeTwoPages(spans),
                        at = System.currentTimeMillis(),
                    )
                }
            }
            .addOnCompleteListener {
                val took = System.currentTimeMillis() - startedAt
                textTtlMs = (took * 3).coerceIn(TEXT_TTL_MIN_MS, TEXT_TTL_MAX_MS)
                frame.recycle()
                recognizing.set(false)
            }
    }

    fun close() {
        runCatching { recognizer.close() }
    }

    /** ML Kit reports boxes in the upright image, so width and height swap on portrait frames. */
    private fun uprightSize(image: ImageProxy): Pair<Float, Float> =
        if (image.imageInfo.rotationDegrees % 180 == 0) {
            image.width.toFloat() to image.height.toFloat()
        } else {
            image.height.toFloat() to image.width.toFloat()
        }

    /**
     * Brightness, sharpness and motion from the luma plane on a coarse grid - a few thousand
     * samples per frame, which is cheap enough to run on every frame.
     */
    private fun lumaStats(image: ImageProxy): Stats {
        val plane = image.planes.firstOrNull() ?: return Stats(0.5f, 1f, 0f)
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val columns = minOf(GRID, image.width / maxOf(pixelStride, 1))
        val rows = minOf(GRID, image.height)
        if (columns < 3 || rows < 3) return Stats(0.5f, 1f, 0f)

        val samples = IntArray(columns * rows)
        val stepX = image.width / columns
        val stepY = image.height / rows
        var sum = 0L
        for (y in 0 until rows) {
            val rowStart = (y * stepY) * rowStride
            for (x in 0 until columns) {
                val index = rowStart + (x * stepX) * pixelStride
                val value = if (index < buffer.limit()) buffer.get(index).toInt() and 0xFF else 0
                samples[y * columns + x] = value
                sum += value
            }
        }
        val mean = sum.toDouble() / samples.size

        // Sharpness: how strong neighbouring differences are, relative to the frame's own
        // contrast, so a dark page is not called blurry just for being dark.
        var gradient = 0.0
        var variance = 0.0
        for (y in 0 until rows) {
            for (x in 1 until columns) {
                gradient += abs(samples[y * columns + x] - samples[y * columns + x - 1]).toDouble()
            }
        }
        samples.forEach { value -> variance += (value - mean) * (value - mean) }
        val contrast = sqrt(variance / samples.size)
        val meanGradient = gradient / (rows * (columns - 1))
        val sharpness = (meanGradient / SHARP_GRADIENT).coerceIn(0.0, 1.0)

        val previous = previousLuma
        val motion = if (previous != null && previous.size == samples.size) {
            var diff = 0.0
            for (i in samples.indices) diff += abs(samples[i] - previous[i]).toDouble()
            (diff / samples.size / MOTION_SCALE).coerceIn(0.0, 1.0)
        } else {
            1.0
        }
        previousLuma = samples

        return Stats(
            brightness = (mean / 255.0).toFloat(),
            // A flat, textureless frame is not "sharp"; it just has nothing in it.
            sharpness = if (contrast < MIN_CONTRAST) 0f else sharpness.toFloat(),
            motion = motion.toFloat(),
            lightSpread = lightSpread(samples, columns, rows),
        )
    }

    /**
     * How unevenly the frame is lit, from the brightest and darkest ninth of it.
     *
     * Mean brightness cannot see this: a hard shadow lying across half the page leaves the
     * mean perfectly healthy while costing the recognizer that half. One of the corpus's
     * worst captures is a well-exposed contents page with the photographer's own shadow
     * down the middle of it.
     */
    private fun lightSpread(samples: IntArray, columns: Int, rows: Int): Float {
        var darkest = Double.MAX_VALUE
        var brightest = 0.0
        for (by in 0 until BLOCKS) {
            for (bx in 0 until BLOCKS) {
                var sum = 0L
                var count = 0
                for (y in (by * rows / BLOCKS) until ((by + 1) * rows / BLOCKS)) {
                    for (x in (bx * columns / BLOCKS) until ((bx + 1) * columns / BLOCKS)) {
                        sum += samples[y * columns + x]
                        count++
                    }
                }
                if (count == 0) continue
                val blockMean = sum.toDouble() / count
                darkest = minOf(darkest, blockMean)
                brightest = maxOf(brightest, blockMean)
            }
        }
        if (brightest <= 0.0 || darkest == Double.MAX_VALUE) return 0f
        return ((brightest - darkest) / brightest).coerceIn(0.0, 1.0).toFloat()
    }

    private data class Stats(
        val brightness: Float,
        val sharpness: Float,
        val motion: Float,
        val lightSpread: Float = 0f,
    )

    private data class TextBounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val lineCount: Int,
        val spread: Boolean = false,
        val at: Long,
    )

    private companion object {
        const val RECOGNIZE_EVERY_MS = 700L
        const val TEXT_TTL_MIN_MS = 2_000L
        const val TEXT_TTL_MAX_MS = 8_000L
        const val GRID = 48
        const val SHARP_GRADIENT = 14.0
        const val MOTION_SCALE = 12.0
        const val MIN_CONTRAST = 8.0

        /** The frame is divided into this many blocks per side to measure uneven light. */
        const val BLOCKS = 3
    }
}

/**
 * How far the phone is from lying flat over the page, in degrees. Used to ask a student to
 * straighten the phone before worrying about framing.
 */
class TiltSensor(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile var degrees: Float = 0f
        private set

    fun start() {
        sensor ?: return
        manager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        // Camera pointing straight down puts all of gravity on -z; the angle away from that
        // is how far the phone is tipped.
        val angle = Math.toDegrees(atan2(hypot(x, y).toDouble(), -z.toDouble())).toFloat()
        degrees = abs(angle)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
