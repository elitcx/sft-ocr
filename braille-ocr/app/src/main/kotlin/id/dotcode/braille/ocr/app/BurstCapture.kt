package id.dotcode.braille.ocr.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * A hand that cannot hold perfectly still still produces one good frame out of several, so
 * guided capture takes a few photos in a row and keeps the sharpest. The rest are deleted.
 */
object BurstCapture {

    const val DEFAULT_SHOTS = 3
    private const val GAP_MS = 140L

    suspend fun capture(
        imageCapture: ImageCapture,
        executor: Executor,
        newFile: () -> File,
        shots: Int = DEFAULT_SHOTS,
    ): File? {
        val taken = mutableListOf<File>()
        repeat(shots) { index ->
            if (index > 0) delay(GAP_MS)
            val file = newFile()
            if (takePicture(imageCapture, executor, file)) taken += file else file.delete()
        }
        if (taken.isEmpty()) return null
        val best = withContext(Dispatchers.Default) {
            taken.maxByOrNull { sharpness(it) }
        } ?: taken.first()
        taken.filter { it != best }.forEach { it.delete() }
        return best
    }

    private suspend fun takePicture(
        imageCapture: ImageCapture,
        executor: Executor,
        file: File,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            options,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onError(exception: ImageCaptureException) {
                    if (continuation.isActive) continuation.resume(false)
                }
            },
        )
    }

    /**
     * Mean difference between neighbouring pixels on a small copy of the photo: high on a
     * crisp page of text, low on a blurred one. Only used to compare shots of the same page.
     */
    internal fun sharpness(file: File): Double {
        val bitmap = decodeSmall(file) ?: return 0.0
        val width = bitmap.width
        val height = bitmap.height
        if (width < 3 || height < 3) return 0.0
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        var total = 0.0
        for (y in 0 until height) {
            val row = y * width
            for (x in 1 until width) {
                total += abs(luma(pixels[row + x]) - luma(pixels[row + x - 1]))
            }
        }
        return total / (height * (width - 1))
    }

    private fun decodeSmall(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return null
        var sample = 1
        while (longEdge / sample > SCORE_LONG_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeFile(file.path, options) }.getOrNull()
    }

    private fun luma(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private const val SCORE_LONG_EDGE = 640
}
