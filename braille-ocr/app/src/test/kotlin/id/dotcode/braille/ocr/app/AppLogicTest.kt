package id.dotcode.braille.ocr.app

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class AppLogicTest {

    private val esp = BraillePad.Device(Esp32BluetoothSender.DEVICE_NAME, "AA")
    private val otherEsp = BraillePad.Device("My ESP32 board", "BB")
    private val headphones = BraillePad.Device("Headphones", "CC")

    @Test
    fun `a still-paired manual choice wins`() {
        assertEquals(headphones, BraillePad.resolveReceiver(listOf(esp, headphones), "CC"))
    }

    @Test
    fun `without a usable choice the sketch name, then any ESP32, is used`() {
        assertEquals(esp, BraillePad.resolveReceiver(listOf(otherEsp, esp), selectedAddress = "gone"))
        assertEquals(otherEsp, BraillePad.resolveReceiver(listOf(headphones, otherEsp), selectedAddress = null))
        assertNull(BraillePad.resolveReceiver(listOf(headphones), selectedAddress = null))
    }

    @Test
    fun `speech chunks cover the text from the start offset, split at sentence ends`() {
        val text = "Satu dua tiga. ".repeat(40)
        val chunks = SpeechController.chunk(text, from = 15, limit = 100)

        assertEquals(15, chunks.first().first)
        assertEquals(text.substring(15), chunks.joinToString("") { it.second })
        assertTrue(chunks.all { it.second.length <= 100 })
        assertTrue(chunks.dropLast(1).all { it.second.trimEnd().endsWith(".") })
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.first + a.second.length, b.first) }
    }

    @Test
    fun `speech chunking falls back to spaces and hard cuts`() {
        val words = "kata ".repeat(50)
        assertTrue(SpeechController.chunk(words, 0, limit = 32).all { it.second.endsWith(" ") })
        val solid = "x".repeat(70)
        assertEquals(listOf(0, 32, 64), SpeechController.chunk(solid, 0, limit = 32).map { it.first })
    }

    @Test
    fun `relative dates`() {
        val zone: ZoneId = ZoneOffset.UTC
        val today = LocalDate.of(2026, 9, 17)
        fun at(date: LocalDate, hour: Int) = date.atTime(hour, 5).toInstant(ZoneOffset.UTC).toEpochMilli()

        assertEquals("Hari ini, 14:05", formatWhen(at(today, 14), StringsId, zone, today))
        assertEquals("Yesterday, 09:05", formatWhen(at(today.minusDays(1), 9), StringsEn, zone, today))
        assertEquals("12 Sep 2026", formatWhen(at(LocalDate.of(2026, 9, 12), 9), StringsEn, zone, today))
    }

    @Test
    fun `language codes pick the right strings`() {
        assertEquals(StringsEn, stringsFor(AppPrefs.LANG_EN))
        assertEquals(StringsId, stringsFor(AppPrefs.LANG_ID))
        assertEquals(StringsId, stringsFor("xx"))
    }

    @Test
    fun `durations`() {
        assertEquals("0:04", formatDuration(4))
        assertEquals("2:05", formatDuration(125))
    }
}
