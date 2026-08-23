package id.dotcode.braille.ocr.mlkit

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

/**
 * Scales a capture down before recognition.
 *
 * Full-resolution captures cost time without improving accuracy, while dropping below
 * roughly 1000px on the long edge starts to lose small print. Orientation is deliberately
 * NOT baked into the bitmap — it is handed to ML Kit as a rotation degree, which avoids a
 * full-frame copy.
 */
object ImagePreprocessor {

    const val TARGET_LONG_EDGE = 1600

    fun downscale(bitmap: Bitmap, targetLongEdge: Int = TARGET_LONG_EDGE): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= targetLongEdge) return bitmap
        val scale = targetLongEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    /** Reads EXIF orientation as a rotation in degrees, for handing to InputImage. */
    fun rotationDegrees(stream: InputStream): Int = when (
        ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}
