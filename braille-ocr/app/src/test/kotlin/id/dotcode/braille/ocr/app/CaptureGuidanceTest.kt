package id.dotcode.braille.ocr.app

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CaptureGuidanceTest {

    /** A frame that needs no correction: text centred, sharp, still, phone flat. */
    private fun goodFrame() = FrameReading(
        hasText = true,
        textLeft = 0.2f, textTop = 0.25f, textRight = 0.8f, textBottom = 0.75f,
        lineCount = 6, brightness = 0.6f, sharpness = 0.9f, motion = 0.02f, tiltDegrees = 5f,
    )

    /** Feeds one reading for [durationMs], returning the last guidance. */
    private fun GuidanceEngine.hold(
        reading: FrameReading,
        from: Long,
        durationMs: Long,
        stepMs: Long = 100,
    ): Pair<Guidance, Long> {
        var now = from
        var guidance = update(reading, now)
        while (now < from + durationMs) {
            now += stepMs
            guidance = update(reading, now)
        }
        return guidance to now
    }

    @Test
    fun `an instruction only appears once it has persisted`() {
        val engine = GuidanceEngine()
        engine.reset(0)
        val dark = goodFrame().copy(brightness = 0.05f)

        // One odd frame does not change what the student is being told.
        assertEquals(Instruction.NO_TEXT, engine.update(dark, 0).instruction)
        assertEquals(Instruction.NO_TEXT, engine.update(dark, 100).instruction)
        assertEquals(Instruction.TOO_DARK, engine.hold(dark, 100, 500).first.instruction)
    }

    @Test
    fun `problems are corrected in order - light, tilt, then steadiness`() {
        fun instructionFor(reading: FrameReading): Instruction {
            val engine = GuidanceEngine()
            engine.reset(0)
            return engine.hold(reading, 0, 600).first.instruction
        }

        assertEquals(Instruction.NO_TEXT, instructionFor(goodFrame().copy(hasText = false, tiltDegrees = 3f)))
        // Nothing found and the phone is upright: the likely fix is to hold it over the page.
        assertEquals(Instruction.TILT, instructionFor(goodFrame().copy(hasText = false, tiltDegrees = 80f)))
        // But text on a wall is read at any angle, so tilt is never blocking once text shows.
        assertEquals(Instruction.READY, instructionFor(goodFrame().copy(tiltDegrees = 80f)))
        assertEquals(Instruction.TOO_DARK, instructionFor(goodFrame().copy(brightness = 0.1f)))
        assertEquals(Instruction.HOLD_STILL, instructionFor(goodFrame().copy(motion = 0.9f)))
        assertEquals(Instruction.HOLD_STILL, instructionFor(goodFrame().copy(sharpness = 0.1f)))
    }

    @Test
    fun `the student is sent towards text that is off to one side`() {
        fun instructionFor(left: Float, top: Float, right: Float, bottom: Float): Instruction {
            val engine = GuidanceEngine()
            engine.reset(0)
            return engine.hold(
                goodFrame().copy(textLeft = left, textTop = top, textRight = right, textBottom = bottom),
                0, 600,
            ).first.instruction
        }

        // Cut off at an edge: move that way.
        assertEquals(Instruction.MOVE_LEFT, instructionFor(0f, 0.3f, 0.5f, 0.7f))
        assertEquals(Instruction.MOVE_RIGHT, instructionFor(0.5f, 0.3f, 1f, 0.7f))
        assertEquals(Instruction.MOVE_UP, instructionFor(0.2f, 0f, 0.8f, 0.5f))
        assertEquals(Instruction.MOVE_DOWN, instructionFor(0.2f, 0.5f, 0.8f, 1f))
        // Cut off on both sides: the page fills the frame, so back off.
        assertEquals(Instruction.MOVE_BACK, instructionFor(0f, 0.3f, 1f, 0.7f))
        // Fully inside but off-centre: nudge towards it.
        assertEquals(Instruction.MOVE_LEFT, instructionFor(0.05f, 0.3f, 0.45f, 0.7f))
    }

    @Test
    fun `text too small or too large asks for distance`() {
        val engine = GuidanceEngine()
        engine.reset(0)
        val small = goodFrame().copy(textLeft = 0.45f, textTop = 0.45f, textRight = 0.55f, textBottom = 0.55f)
        assertEquals(Instruction.MOVE_CLOSER, engine.hold(small, 0, 600).first.instruction)
    }

    @Test
    fun `a good frame held steady takes the photo exactly once`() {
        val engine = GuidanceEngine()
        engine.reset(0)
        val (settled, after) = engine.hold(goodFrame(), 0, 500)
        assertEquals(Instruction.READY, settled.instruction)
        assertFalse(settled.capture, "not yet: READY has to hold")

        var now = after
        var captures = 0
        repeat(20) {
            now += 100
            if (engine.update(goodFrame(), now).capture) captures++
        }
        assertEquals(1, captures)
    }

    @Test
    fun `after a long struggle it relaxes and accepts an imperfect frame`() {
        val engine = GuidanceEngine()
        engine.reset(0)
        // Text present but small, slightly blurred and a shaky hand: never good enough.
        val imperfect = goodFrame().copy(
            textLeft = 0.42f, textTop = 0.42f, textRight = 0.58f, textBottom = 0.58f,
            sharpness = 0.35f, motion = 0.4f,
        )
        val (early, _) = engine.hold(imperfect, 0, 2_000)
        assertFalse(early.capture)
        assertFalse(early.relaxed)

        var now = 2_000L
        var captured = false
        var relaxed = false
        while (now < 20_000 && !captured) {
            now += 100
            val guidance = engine.update(imperfect, now)
            relaxed = relaxed || guidance.relaxed
            captured = guidance.capture
        }
        assertTrue(relaxed, "it should stop demanding a perfect frame")
        assertTrue(captured, "and take the best it can get")
    }

    @Test
    fun `closeness grows as the framing improves`() {
        val engine = GuidanceEngine()
        engine.reset(0)
        val far = engine.update(
            goodFrame().copy(textLeft = 0.05f, textTop = 0.05f, textRight = 0.2f, textBottom = 0.2f, motion = 0.2f),
            0,
        ).closeness
        val near = engine.update(goodFrame(), 100).closeness
        assertTrue(near > far, "near=$near far=$far")
        assertEquals(0f, engine.update(goodFrame().copy(hasText = false), 200).closeness)
    }
}
