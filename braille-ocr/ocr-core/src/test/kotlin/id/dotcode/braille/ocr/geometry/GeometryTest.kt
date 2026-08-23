package id.dotcode.braille.ocr.geometry

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class GeometryTest {
    @Test
    fun `box exposes derived dimensions`() {
        val box = BoxF(left = 10f, top = 20f, right = 40f, bottom = 60f)
        assertEquals(30f, box.width)
        assertEquals(40f, box.height)
        assertEquals(25f, box.centerX)
        assertEquals(40f, box.centerY)
    }

    @Test
    fun `union spans both boxes`() {
        val a = BoxF(0f, 0f, 10f, 10f)
        val b = BoxF(5f, 20f, 30f, 25f)
        assertEquals(BoxF(0f, 0f, 30f, 25f), a.union(b))
    }

    @Test
    fun `enclosing folds a list`() {
        val boxes = listOf(BoxF(4f, 4f, 6f, 6f), BoxF(0f, 8f, 2f, 9f), BoxF(1f, 1f, 3f, 3f))
        assertEquals(BoxF(0f, 1f, 6f, 9f), BoxF.enclosing(boxes))
    }

    @Test
    fun `enclosing an empty list throws`() {
        assertTrue(runCatching { BoxF.enclosing(emptyList()) }.isFailure)
    }
}
