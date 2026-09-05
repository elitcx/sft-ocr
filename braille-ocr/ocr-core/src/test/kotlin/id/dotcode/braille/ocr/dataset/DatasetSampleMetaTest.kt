package id.dotcode.braille.ocr.dataset

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.model.Alignment
import id.dotcode.braille.ocr.model.BlockRole
import id.dotcode.braille.ocr.model.OcrDocument
import id.dotcode.braille.ocr.model.TextBlock
import id.dotcode.braille.ocr.pipeline.PipelineVersion
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class Sha256Test {
    @Test
    fun `known vector for empty input`() {
        // The canonical SHA-256 of zero bytes.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hex(ByteArray(0)),
        )
    }

    @Test
    fun `hash is 64 lowercase hex characters`() {
        val hash = Sha256.hex("braille worksheet".toByteArray())
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in "0123456789abcdef" })
    }

    @Test
    fun `same bytes always hash the same, different bytes do not collide trivially`() {
        val a = Sha256.hex("sample-a".toByteArray())
        val b = Sha256.hex("sample-a".toByteArray())
        val c = Sha256.hex("sample-b".toByteArray())
        assertEquals(a, b)
        assertTrue(a != c)
    }
}

class DatasetSampleMetaTest {

    private fun sampleDocument() = OcrDocument(
        pageWidth = 1000,
        pageHeight = 1400,
        skewDeg = 0f,
        columnCount = 1,
        blocks = listOf(
            TextBlock(
                id = 0,
                role = BlockRole.TITLE,
                columnIndex = 0,
                marker = null,
                alignment = Alignment.LEFT,
                text = "PARABOLA",
                lines = emptyList(),
                box = BoxF(0f, 0f, 100f, 20f),
            ),
        ),
    )

    @Test
    fun `raw ocr factory marks the sample as not-yet ground truth`() {
        val meta = DatasetSampleMeta.rawOcr(
            sourceImageFileName = "worksheet-01.jpg",
            sourceImageBytes = "fake-jpeg-bytes".toByteArray(),
            document = sampleDocument(),
            capturedAtEpochMs = 1_700_000_000_000L,
        )
        assertEquals(TranscriptionSource.RAW_OCR, meta.transcriptionSource)
        assertEquals(PipelineVersion.CURRENT, meta.pipelineVersion)
        assertEquals(1, meta.blockRoleLabels.size)
        assertEquals(BlockRole.TITLE, meta.blockRoleLabels.first().role)
        assertEquals(false, meta.blockRoleLabels.first().verified)
    }

    @Test
    fun `json round-trip preserves every field including transcription source`() {
        val original = DatasetSampleMeta.rawOcr(
            sourceImageFileName = "worksheet-02.jpg",
            sourceImageBytes = "other-bytes".toByteArray(),
            document = sampleDocument(),
            capturedAtEpochMs = 1_700_000_000_001L,
        ).copy(transcriptionSource = TranscriptionSource.HUMAN_CORRECTED, notes = "checked against photo")

        val restored = DatasetSampleMeta.fromJson(original.toJson())

        assertEquals(original, restored)
        assertEquals(TranscriptionSource.HUMAN_CORRECTED, restored.transcriptionSource)
    }

    @Test
    fun `content hash is stable for identical bytes regardless of file name`() {
        val bytes = "identical-image-bytes".toByteArray()
        val renamed = DatasetSampleMeta.rawOcr("a.jpg", bytes, sampleDocument(), 0L)
        val original = DatasetSampleMeta.rawOcr("b.jpg", bytes, sampleDocument(), 0L)
        assertEquals(original.sourceImageSha256, renamed.sourceImageSha256)
    }
}
