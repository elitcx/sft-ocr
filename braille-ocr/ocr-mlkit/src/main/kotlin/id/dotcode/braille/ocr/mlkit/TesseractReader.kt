package id.dotcode.braille.ocr.mlkit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.spelling.SecondWord
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The second, independent recognizer: Tesseract (LSTM "fast" models for Indonesian and
 * English, bundled under `assets/tessdata`). Its words only ever serve as votes for
 * [id.dotcode.braille.ocr.spelling.ReadingVoter]; ML Kit's text stays the primary reading.
 *
 * Tesseract has no rotation hint, so it is handed an upright copy of the frame and reports
 * word boxes in that upright frame; the caller decides how they line up with ML Kit's.
 * One native instance is kept warm (loading the models is the slow part) and guarded by a
 * mutex: it is not thread-safe.
 */
internal class TesseractReader(
    context: Context,
    /** A directory containing `tessdata/` to use as-is instead of the bundled models. */
    private val externalDataDir: File? = null,
) : AutoCloseable {

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    @Volatile private var api: TessBaseAPI? = null
    private var unavailable = false

    /**
     * Words with boxes in [bitmap]'s frame turned upright by [rotationDegrees] (so of size
     * height x width for a quarter turn), or an empty list if Tesseract can't run. Never throws:
     * a missing second reading only makes the spelling pass more cautious.
     */
    suspend fun read(bitmap: Bitmap, rotationDegrees: Int): List<SecondWord> = mutex.withLock {
        val tess = ready() ?: return emptyList()
        val upright = upright(bitmap, rotationDegrees)
        try {
            tess.setImage(upright)
            // getUTF8Text() is what actually runs recognition; the iterator only reads results.
            tess.utF8Text
            val words = mutableListOf<SecondWord>()
            val iterator = tess.resultIterator ?: return emptyList()
            try {
                iterator.begin()
                do {
                    val text = iterator.getUTF8Text(WORD)?.trim()
                    if (text.isNullOrEmpty()) continue
                    words += SecondWord(text, iterator.getBoundingRect(WORD).toBoxF(), iterator.confidence(WORD) / 100f)
                } while (iterator.next(WORD))
            } finally {
                iterator.delete()
            }
            words
        } catch (e: RuntimeException) {
            emptyList()
        } finally {
            tess.clear()
            if (upright !== bitmap) upright.recycle()
        }
    }

    /** Copies and loads the models now instead of on the first [read]. */
    suspend fun warmUp() {
        mutex.withLock { ready() }
    }

    /** Asks a running [read] to finish early; safe to call from any thread. */
    fun stop() {
        api?.stop()
    }

    private fun ready(): TessBaseAPI? {
        api?.let { return it }
        if (unavailable) return null
        return try {
            val root = externalDataDir ?: File(appContext.filesDir, "tesseract").also {
                installLanguageData(File(it, "tessdata"))
            }
            val tess = TessBaseAPI()
            if (tess.init(root.absolutePath, LANGUAGES, TessBaseAPI.OEM_LSTM_ONLY)) {
                tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                api = tess
                tess
            } else {
                tess.recycle()
                unavailable = true
                null
            }
        } catch (e: Exception) {
            unavailable = true
            null
        }
    }

    /**
     * Tesseract reads models from a real directory, so the bundled assets are copied once.
     * A marker named after [DATA_VERSION] is written only after a complete copy, so an
     * interrupted copy or an app update with new models copies again.
     */
    private fun installLanguageData(dir: File) {
        val marker = File(dir, "installed-$DATA_VERSION")
        if (marker.exists()) return
        dir.deleteRecursively()
        dir.mkdirs()
        for (language in LANGUAGES.split('+')) {
            val name = "$language.traineddata"
            appContext.assets.open("$ASSET_DIR/$name").use { input ->
                File(dir, name).outputStream().use { input.copyTo(it) }
            }
        }
        marker.createNewFile()
    }

    private fun upright(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val source = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        if (rotationDegrees % 360 == 0) return source
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (source !== bitmap && source !== rotated) source.recycle()
        return rotated
    }

    override fun close() {
        api?.recycle()
        api = null
    }

    private fun Rect.toBoxF() = BoxF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    private companion object {
        const val LANGUAGES = "ind+eng"
        const val ASSET_DIR = "tessdata"
        /** Bump when the bundled .traineddata files change. */
        const val DATA_VERSION = "tessdata_fast-2026-09-16"
        const val WORD = TessBaseAPI.PageIteratorLevel.RIL_WORD
    }
}
