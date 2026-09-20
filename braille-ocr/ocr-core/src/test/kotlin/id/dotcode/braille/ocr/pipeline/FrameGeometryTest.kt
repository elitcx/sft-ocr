package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.raw.RawTextResult
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameGeometryTest {

    private fun frame(w: Int, h: Int) = RawTextResult(imageWidth = w, imageHeight = h, lines = emptyList())

    @Test
    fun `a quarter turn swaps which dimension bounds reading direction`() {
        assertEquals(1200, FrameGeometry.uprightWidth(frame(1600, 1200), 90))
        assertEquals(1200, FrameGeometry.uprightWidth(frame(1600, 1200), 270))
    }

    @Test
    fun `an upright or inverted frame keeps its own width`() {
        assertEquals(1600, FrameGeometry.uprightWidth(frame(1600, 1200), 0))
        assertEquals(1600, FrameGeometry.uprightWidth(frame(1600, 1200), 180))
    }

    @Test
    fun `a negative or over-full rotation normalises`() {
        assertEquals(1200, FrameGeometry.uprightWidth(frame(1600, 1200), -90))
        assertEquals(1200, FrameGeometry.uprightWidth(frame(1600, 1200), 450))
    }
}
