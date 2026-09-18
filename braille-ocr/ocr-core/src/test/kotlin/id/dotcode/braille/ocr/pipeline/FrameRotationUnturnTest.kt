package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.raw.RawLine
import id.dotcode.braille.ocr.raw.RawTextResult
import id.dotcode.braille.ocr.raw.RawWord
import kotlin.test.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class FrameRotationUnturnTest {

    @ParameterizedTest
    @ValueSource(ints = [0, 90, 180, 270, -90])
    fun `unturnBox undoes apply`(rotation: Int) {
        val original = BoxF(120f, 40f, 300f, 70f)
        val raw = RawTextResult(
            imageWidth = 800,
            imageHeight = 600,
            lines = listOf(RawLine("x", original, words = listOf(RawWord("x", original)))),
        )

        val upright = FrameRotation.apply(raw, rotation).lines.single().words.single().box

        assertEquals(original, FrameRotation.unturnBox(upright, rotation, 800, 600))
    }
}
