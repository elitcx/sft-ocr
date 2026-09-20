package id.dotcode.braille.ocr.app

import kotlin.math.abs
import kotlin.math.max

/** One instruction at a time, in the order a student needs to hear them. */
enum class Instruction {
    NO_TEXT,
    TOO_DARK,
    UNEVEN_LIGHT,
    TILT,
    SINGLE_PAGE,
    MOVE_LEFT,
    MOVE_RIGHT,
    MOVE_UP,
    MOVE_DOWN,
    MOVE_CLOSER,
    MOVE_BACK,
    HOLD_STILL,
    READY,
}

/**
 * What one camera frame looks like, in upright display coordinates with the frame as the
 * unit square. Everything a guidance decision needs and nothing about cameras.
 */
data class FrameReading(
    val hasText: Boolean,
    /** Bounding box of all detected text: left, top, right, bottom in 0..1. */
    val textLeft: Float = 0f,
    val textTop: Float = 0f,
    val textRight: Float = 0f,
    val textBottom: Float = 0f,
    val lineCount: Int = 0,
    /** Mean brightness 0..1. */
    val brightness: Float = 0.5f,
    /** 0 = blurred, 1 = crisp. */
    val sharpness: Float = 1f,
    /** 0 = still, 1 = moving fast. */
    val motion: Float = 0f,
    /** Degrees away from holding the phone flat over the page. */
    val tiltDegrees: Float = 0f,
    /** True when the preview looks like an open book rather than one page - see [PageSplit]. */
    val spreadDetected: Boolean = false,
    /**
     * How unevenly the frame is lit: 0 when every part is equally bright, approaching 1
     * when one region is far darker than another. [brightness] is a mean over the whole
     * frame, so a hand's shadow falling across half the page leaves it perfectly
     * acceptable while costing the recognizer that half.
     */
    val lightSpread: Float = 0f,
) {
    val centerX: Float get() = (textLeft + textRight) / 2
    val centerY: Float get() = (textTop + textBottom) / 2
    val coverage: Float get() = max(0f, (textRight - textLeft)) * max(0f, (textBottom - textTop))
}

/** The instruction to give, how close the student is (0..1) and whether to take the photo. */
data class Guidance(
    val instruction: Instruction,
    val closeness: Float,
    val capture: Boolean,
    /** True once the engine has given up on a perfect frame and takes the best available. */
    val relaxed: Boolean = false,
)

/**
 * Turns a stream of frame readings into spoken guidance and, finally, a photo.
 *
 * Three things keep it usable by someone who cannot see the screen and cannot hold a phone
 * perfectly still: an instruction has to persist before it is given (no "left, right, left"),
 * the requirements are checked coarse-to-fine, and after a while of honest effort the engine
 * relaxes and takes the best frame it can rather than demanding perfection.
 */
class GuidanceEngine(
    private val settleMs: Long = 400,
    private val holdStillMs: Long = 700,
    private val relaxAfterMs: Long = 9_000,
) {
    private var startedAt = 0L
    private var candidate: Instruction? = null
    private var candidateSince = 0L
    private var current: Instruction = Instruction.NO_TEXT
    private var readySince = 0L
    private var captured = false

    fun reset(now: Long) {
        startedAt = now
        candidate = null
        candidateSince = now
        current = Instruction.NO_TEXT
        readySince = 0L
        captured = false
    }

    fun update(reading: FrameReading, now: Long): Guidance {
        if (startedAt == 0L) startedAt = now
        val relaxed = now - startedAt >= relaxAfterMs && reading.hasText
        val wanted = evaluate(reading, relaxed)

        // An instruction has to hold for a moment before it is given, so a shaky hand does
        // not produce a stream of contradictions.
        if (wanted != candidate) {
            candidate = wanted
            candidateSince = now
        }
        val settled = now - candidateSince >= settleMs
        if (settled && wanted != current) {
            current = wanted
            if (wanted != Instruction.READY) readySince = 0L
        }

        if (current == Instruction.READY) {
            if (readySince == 0L) readySince = now
        } else {
            readySince = 0L
        }

        val steady = readySince != 0L && now - readySince >= (if (relaxed) holdStillMs / 2 else holdStillMs)
        val capture = steady && !captured
        if (capture) captured = true

        return Guidance(current, closeness(reading), capture, relaxed)
    }

    private fun evaluate(reading: FrameReading, relaxed: Boolean): Instruction {
        if (!reading.hasText) {
            // Holding the phone flat is the usual fix when nothing is found, but text on a
            // wall or whiteboard is read at any angle - so this is only ever a hint, never a
            // requirement once text is in view.
            return if (reading.tiltDegrees > MAX_TILT) Instruction.TILT else Instruction.NO_TEXT
        }
        if (reading.brightness < DARK) return Instruction.TOO_DARK

        // EVERY framing rule below is inside `if (!relaxed)`, and that is load-bearing
        // rather than tidy. These checks used to return before the relaxation guard, so
        // the engine's only escape hatch could not reach them: a page filling the
        // viewfinder tripped the edge test on both sides, answered "move back" forever,
        // and never took the shot at any distance the student tried. It was an
        // unreachable state, not a slow one. A student who cannot see the screen has no
        // way to discover why nothing is happening, so no rule may outrank the relax.
        if (!relaxed) {
            // An open book is reframed first. A spread spans the frame, so the edge rule
            // below would answer "move back" - which keeps both pages in shot and makes
            // the capture worse. Moving in on one page fixes the framing and removes the
            // facing page together.
            if (reading.spreadDetected) return Instruction.SINGLE_PAGE

            // Text running off an edge loses words, so that is corrected before framing.
            val offLeft = reading.textLeft <= EDGE
            val offRight = reading.textRight >= 1f - EDGE
            val offTop = reading.textTop <= EDGE
            val offBottom = reading.textBottom >= 1f - EDGE
            when {
                offLeft && offRight -> return Instruction.MOVE_BACK
                offTop && offBottom -> return Instruction.MOVE_BACK
                offLeft -> return Instruction.MOVE_LEFT
                offRight -> return Instruction.MOVE_RIGHT
                offTop -> return Instruction.MOVE_UP
                offBottom -> return Instruction.MOVE_DOWN
            }

            if (reading.coverage < MIN_COVERAGE) return Instruction.MOVE_CLOSER
            if (reading.coverage > MAX_COVERAGE) return Instruction.MOVE_BACK
            val offX = reading.centerX - 0.5f
            val offY = reading.centerY - 0.5f
            if (abs(offX) > CENTER_TOLERANCE && abs(offX) >= abs(offY)) {
                return if (offX < 0) Instruction.MOVE_LEFT else Instruction.MOVE_RIGHT
            }
            if (abs(offY) > CENTER_TOLERANCE) {
                return if (offY < 0) Instruction.MOVE_UP else Instruction.MOVE_DOWN
            }

            // Asked for last of the framing rules: a shadow is worth moving out of the way,
            // but not before the page is actually in shot.
            if (reading.lightSpread > MAX_LIGHT_SPREAD) return Instruction.UNEVEN_LIGHT
        }

        val sharpEnough = reading.sharpness >= if (relaxed) RELAXED_SHARPNESS else MIN_SHARPNESS
        val stillEnough = reading.motion <= if (relaxed) RELAXED_MOTION else MAX_MOTION
        if (!sharpEnough || !stillEnough) return Instruction.HOLD_STILL
        return Instruction.READY
    }

    /** How near the frame is to being usable, for the vibration pulse rate. */
    private fun closeness(reading: FrameReading): Float {
        if (!reading.hasText) return 0f
        val framing = 1f - (abs(reading.centerX - 0.5f) + abs(reading.centerY - 0.5f)).coerceIn(0f, 1f)
        val size = when {
            reading.coverage < MIN_COVERAGE -> reading.coverage / MIN_COVERAGE
            reading.coverage > MAX_COVERAGE -> (1f - reading.coverage) / (1f - MAX_COVERAGE)
            else -> 1f
        }.coerceIn(0f, 1f)
        val steadiness = (1f - reading.motion / MAX_MOTION).coerceIn(0f, 1f)
        return (0.4f * framing + 0.4f * size + 0.2f * steadiness).coerceIn(0f, 1f)
    }

    private companion object {
        const val DARK = 0.22f

        /**
         * How uneven the lighting may be before the student is told about it. A page lit
         * from one side is normal and readable; the case worth speaking up about is a hard
         * shadow - a hand, the phone itself, the photographer - lying across the text.
         */
        const val MAX_LIGHT_SPREAD = 0.45f
        const val MAX_TILT = 28f
        /**
         * How close to the frame edge text may sit before the student is told words are
         * being cut off. Deliberately tight ONLY in absolute terms: filling the
         * viewfinder with the page is what gives the recognizer the most pixels, so a
         * 1% margin is a good photo, not a near miss. Measured against the engine's own
         * framing sweep, 0.02 made every framing tighter than a 2% margin unreachable.
         */
        const val EDGE = 0.01f
        const val MIN_COVERAGE = 0.12f
        /**
         * Text may fill almost the whole frame before the student is asked to back off.
         * At 0.88 a page framed with 3% margins - a good photo - waited out the full
         * relax timeout before it was taken.
         */
        const val MAX_COVERAGE = 0.95f
        const val CENTER_TOLERANCE = 0.14f
        const val MIN_SHARPNESS = 0.45f
        const val RELAXED_SHARPNESS = 0.3f
        const val MAX_MOTION = 0.25f
        const val RELAXED_MOTION = 0.45f
    }
}
