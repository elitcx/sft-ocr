package id.dotcode.braille.ocr.app

import id.dotcode.braille.ocr.model.Timings
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ScanProgressTest {

    private val plan = listOf(
        ScanPhase.DECODE to 1_000L,
        ScanPhase.RECOGNIZE to 2_000L,
        ScanPhase.ASSEMBLE to 1_000L,
    )

    @Test
    fun `progress follows the clock within the current phase`() {
        val start = ScanProgress.estimate(plan, ScanPhase.DECODE, 0)
        assertEquals(0f, start.fraction)
        assertEquals(4_000L, start.remainingMs)

        val midRecognize = ScanProgress.estimate(plan, ScanPhase.RECOGNIZE, 1_000)
        // 1000 done + 90% of half of 2000 = 1900 of 4000.
        assertEquals(0.475f, midRecognize.fraction, 0.001f)
        assertEquals(2_000L, midRecognize.remainingMs)
    }

    @Test
    fun `an overrunning phase creeps but never reaches the next phase or 100 percent`() {
        val onTime = ScanProgress.estimate(plan, ScanPhase.ASSEMBLE, 1_000).fraction
        val late = ScanProgress.estimate(plan, ScanPhase.ASSEMBLE, 10_000).fraction
        assertTrue(late > onTime)
        assertTrue(late < 1f)
        assertEquals(0L, ScanProgress.estimate(plan, ScanPhase.ASSEMBLE, 10_000).remainingMs)

        val lateRecognize = ScanProgress.estimate(plan, ScanPhase.RECOGNIZE, 60_000).fraction
        val assembleStart = ScanProgress.estimate(plan, ScanPhase.ASSEMBLE, 0).fraction
        assertTrue(lateRecognize <= assembleStart)
    }

    @Test
    fun `a phase missing from the plan counts from the start`() {
        assertEquals(0f, ScanProgress.estimate(plan, ScanPhase.GEMINI, 0).fraction)
    }

    @Test
    fun `second read only counts the wait after ML Kit`() {
        val phases = ScanProgress.phasesOf(
            Timings(decodeMs = 10, preprocessMs = 20, recognizeMs = 1_000, structureMs = 30, secondReadMs = 1_500, correctMs = 40),
        )
        assertEquals(500L, phases[ScanPhase.SECOND_READ])
        assertEquals(70L, phases[ScanPhase.ASSEMBLE])

        val noSecondRead = ScanProgress.phasesOf(Timings(recognizeMs = 1_000))
        assertNull(noSecondRead[ScanPhase.SECOND_READ])
    }

    @Test
    fun `history uses the median of recent runs and survives a round trip`() {
        val history = PhaseHistory(null)
        assertEquals(ScanProgress.DEFAULT_MS[ScanPhase.RECOGNIZE], history.expected(ScanPhase.RECOGNIZE))

        listOf(1_000L, 9_000L, 2_000L).forEach { history.record(mapOf(ScanPhase.RECOGNIZE to it)) }
        assertEquals(2_000L, history.expected(ScanPhase.RECOGNIZE))

        val restored = PhaseHistory(history.serialize())
        assertEquals(2_000L, restored.expected(ScanPhase.RECOGNIZE))
    }

    @Test
    fun `history keeps only recent runs and ignores junk`() {
        val history = PhaseHistory("RECOGNIZE=x,5;NOPE=1;DECODE=")
        assertEquals(5L, history.expected(ScanPhase.RECOGNIZE))
        assertEquals(ScanProgress.DEFAULT_MS[ScanPhase.DECODE], history.expected(ScanPhase.DECODE))

        repeat(20) { history.record(mapOf(ScanPhase.RECOGNIZE to 100L)) }
        assertEquals(100L, history.expected(ScanPhase.RECOGNIZE))
    }

    @Test
    fun `firmware reply is read only once the line is complete`() {
        val reply = "INFO fw=1.1.0\n"
        assertEquals("1.1.0", Esp32BluetoothSender.parseInfoReply(reply))
        assertEquals("1.1.0", Esp32BluetoothSender.parseInfoReply("noise\nINFO x=1;fw=1.1.0;y=2\n"))
        assertNull(Esp32BluetoothSender.parseInfoReply("INFO fw=1.1"))
        assertNull(Esp32BluetoothSender.parseInfoReply("INFO fw=1.1.0\n"))
        assertNull(Esp32BluetoothSender.parseInfoReply("INFO fw=\n"))
    }

    @Test
    fun `the info request is a single command line`() {
        assertEquals("INFO\n", Esp32BluetoothSender.INFO_REQUEST)
    }
}
