package id.dotcode.braille.ocr.pipeline

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class PageStatsTest {
    @Test
    fun `median line height is the middle value`() {
        val lines = listOf(
            line("aaa", x = 0f, y = 0f, w = 60f, h = 20f),
            line("bbb", x = 0f, y = 30f, w = 60f, h = 30f),
            line("ccc", x = 0f, y = 70f, w = 60f, h = 40f),
        )
        assertEquals(30f, PageStats.from(lines).medianLineHeight)
    }

    @Test
    fun `median char width divides width by character count`() {
        val lines = listOf(
            line("abcd", x = 0f, y = 0f, w = 40f, h = 20f),
            line("ab", x = 0f, y = 30f, w = 20f, h = 20f),
        )
        assertEquals(10f, PageStats.from(lines).medianCharWidth)
    }

    @Test
    fun `blank lines do not poison the char width median`() {
        val lines = listOf(
            line("abcd", x = 0f, y = 0f, w = 40f, h = 20f),
            line("", x = 0f, y = 30f, w = 20f, h = 20f),
            line("abcdef", x = 0f, y = 60f, w = 60f, h = 20f),
        )
        assertEquals(10f, PageStats.from(lines).medianCharWidth)
    }
}
