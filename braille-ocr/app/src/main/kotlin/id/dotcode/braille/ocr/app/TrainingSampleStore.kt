package id.dotcode.braille.ocr.app

import android.content.Context
import android.net.Uri
import id.dotcode.braille.ocr.accuracy.DocumentFlattener
import id.dotcode.braille.ocr.dataset.DatasetSampleMeta
import id.dotcode.braille.ocr.dataset.Sha256
import id.dotcode.braille.ocr.model.OcrDocument
import java.io.File

/**
 * App-private storage for "this result was wrong, keep it" samples — the in-app half of the
 * data flywheel described in `docs/dataset-schema.md`. Every sample this writes lands under
 * `context.filesDir/training-samples/<id>/`, which is private to this app: no other app, and
 * nothing outside the device, can read it. Nothing here ever performs a network call.
 *
 * A saved sample is [id.dotcode.braille.ocr.dataset.TranscriptionSource.RAW_OCR] by
 * construction: the user flagged the *result* as wrong, which is exactly the signal worth
 * keeping, but flagging a result is not the same as typing a correction, so this cannot
 * claim to be ground truth. A human still has to open `text.txt`, read it against `image.jpg`,
 * fix it, and flip `meta.json`'s `transcriptionSource` to `HUMAN_CORRECTED` before it is fit
 * to score accuracy or train anything — same rule as the on-device bootstrap harness.
 */
class TrainingSampleStore(private val context: Context) {

    private val root: File
        get() = File(context.filesDir, ROOT_DIR).apply { mkdirs() }

    /**
     * Copies the current capture into private storage: the source image (re-read from
     * [imageUri], so this works whether the photo came from the camera or the gallery), the
     * full [OcrDocument] JSON, the flattened plain text, and a provenance record. Returns
     * `false` (never throws) if the image can't be read or the write fails, so a UI action
     * can report failure without crashing a result screen over a storage hiccup.
     */
    fun save(imageUri: Uri, document: OcrDocument): Boolean = try {
        val bytes = context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
        if (bytes == null) {
            false
        } else {
            val now = System.currentTimeMillis()
            val id = "$now-${Sha256.hex(bytes).take(SHORT_HASH_LENGTH)}"
            val dir = File(root, id).apply { mkdirs() }
            File(dir, "image.jpg").writeBytes(bytes)
            File(dir, "document.json").writeText(document.toJson())
            File(dir, "text.txt").writeText(DocumentFlattener.flatten(document))
            val meta = DatasetSampleMeta.rawOcr(
                sourceImageFileName = "image.jpg",
                sourceImageBytes = bytes,
                document = document,
                capturedAtEpochMs = now,
            ).copy(notes = "Captured via the in-app \"tandai hasil salah\" control: the user " +
                "reported this result as wrong. Not yet corrected.")
            File(dir, "meta.json").writeText(meta.toJson())
            true
        }
    } catch (e: Exception) {
        false
    }

    /** How many samples are currently stored — surfaced in Pengaturan so storage is never silent. */
    fun count(): Int = root.listFiles()?.count { it.isDirectory } ?: 0

    /** Deletes every stored sample. Returns the number of sample directories removed. */
    fun deleteAll(): Int {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        return dirs.count { it.deleteRecursively() }
    }

    private companion object {
        const val ROOT_DIR = "training-samples"
        const val SHORT_HASH_LENGTH = 12
    }
}
