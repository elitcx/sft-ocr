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

    @Test
    fun `a page that fills the frame is still photographed`() {
        // The defect this pins, reported from a real Galaxy S24 FE: aiming so the page
        // fills the viewfinder - which is exactly what gives the recognizer the most
        // pixels to work with - produced "move back" forever and never took the shot.
        // The edge checks returned before the relaxation guard, so the engine's only
        // escape hatch could not reach them and the state was unreachable, not merely
        // slow. Twenty seconds is well past relaxAfterMs.
        val engine = GuidanceEngine()
        engine.reset(0)
        val fillsFrame = goodFrame().copy(
            textLeft = 0.01f, textTop = 0.01f, textRight = 0.99f, textBottom = 0.99f,
        )
        var now = 0L
        var captured = false
        while (now < 20_000 && !captured) {
            now += 100
            captured = engine.update(fillsFrame, now).capture
        }
        assertTrue(captured, "a page filling the frame was never photographed")
    }

    @Test
    fun `no frame with text can leave the student unable to take a photo`() {
        // The invariant the bug above violated, and the one worth keeping: whatever a
        // reading looks like, if there is text in it the engine must eventually take the
        // shot. A blind student cannot see why nothing is happening, so an unreachable
        // state is the worst failure this class has - strictly worse than a mediocre
        // photo. Any future rule that can block capture indefinitely fails here.
        val deadline = 9_000L + 700L + 1_000L
        for (margin in listOf(0.0f, 0.005f, 0.02f, 0.05f, 0.2f, 0.45f)) {
            for (shiftX in listOf(-0.3f, 0f, 0.3f)) {
                val engine = GuidanceEngine()
                engine.reset(0)
                val reading = goodFrame().copy(
                    textLeft = (margin + shiftX).coerceIn(0f, 1f),
                    textTop = margin,
                    textRight = (1f - margin + shiftX).coerceIn(0f, 1f),
                    textBottom = 1f - margin,
                    sharpness = 0.35f,
                    motion = 0.4f,
                )
                var now = 0L
                var captured = false
                while (now < deadline && !captured) {
                    now += 100
                    captured = engine.update(reading, now).capture
                }
                assertTrue(
                    captured,
                    "no photo for margin=$margin shiftX=$shiftX after ${deadline}ms",
                )
            }
        }
    }


    @Test
    fun `a tightly framed page is photographed promptly, not after the relax`() {
        // Capturing "eventually, once it gives up" is not a fix - it is a 9-second wait
        // with a stream of wrong advice in the student's ear. Filling the frame with the
        // page is the framing that gives the recognizer the most pixels, so it must be
        // taken quickly. Measured: before the retune the instant band started at a 5%
        // margin, and anything tighter than 2% was unreachable entirely.
        for (margin in listOf(0.015f, 0.03f, 0.05f, 0.1f)) {
            val engine = GuidanceEngine()
            engine.reset(0)
            val reading = goodFrame().copy(
                textLeft = margin, textTop = margin,
                textRight = 1f - margin, textBottom = 1f - margin,
            )
            var now = 0L
            var capturedAt = -1L
            while (now < 20_000 && capturedAt < 0) {
                now += 33
                if (engine.update(reading, now).capture) capturedAt = now
            }
            assertTrue(
                capturedAt in 1..3_000,
                "margin=$margin was photographed at ${capturedAt}ms, not promptly",
            )
        }
    }


    @Test
    fun `an open book is reframed onto one page before anything else`() {
        // A spread spans the frame, so the edge rule would otherwise say "move back" -
        // the opposite of what fixes it. Moving closer to one page fixes the framing AND
        // removes the facing page, so it has to outrank the edge advice.
        val engine = GuidanceEngine()
        engine.reset(0)
        val spread = goodFrame().copy(
            textLeft = 0.02f, textRight = 0.98f, spreadDetected = true,
        )
        val (guidance, _) = engine.hold(spread, 0, 1_000)
        assertEquals(Instruction.SINGLE_PAGE, guidance.instruction)
    }

    @Test
    fun `a shadow across the page is called out once the framing is right`() {
        val engine = GuidanceEngine()
        engine.reset(0)
        val shadowed = goodFrame().copy(lightSpread = 0.7f)
        val (guidance, _) = engine.hold(shadowed, 0, 1_000)
        assertEquals(Instruction.UNEVEN_LIGHT, guidance.instruction)
    }

    @Test
    fun `even lighting is never called a shadow`() {
        val engine = GuidanceEngine()
        engine.reset(0)
        val (guidance, _) = engine.hold(goodFrame().copy(lightSpread = 0.15f), 0, 1_000)
        assertEquals(Instruction.READY, guidance.instruction)
    }

    @Test
    fun `neither new warning can stop the photo being taken`() {
        // The invariant from `no frame with text can leave the student unable to take a
        // photo`, applied to the two checks added afterwards. Both are advice, not gates.
        for (reading in listOf(
            goodFrame().copy(spreadDetected = true),
            goodFrame().copy(lightSpread = 0.95f),
            goodFrame().copy(spreadDetected = true, lightSpread = 0.95f),
        )) {
            val engine = GuidanceEngine()
            engine.reset(0)
            var now = 0L
            var captured = false
            while (now < 11_000 && !captured) {
                now += 100
                captured = engine.update(reading, now).capture
            }
            assertTrue(captured, "a warning blocked the photo: $reading")
        }
    }

}
