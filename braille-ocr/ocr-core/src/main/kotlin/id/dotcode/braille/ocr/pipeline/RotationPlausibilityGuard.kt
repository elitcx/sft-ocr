package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.raw.RawTextResult
import kotlin.math.abs

/**
 * Detects and corrects a DECLARED EXIF rotation that is off by exactly one quarter
 * turn — a real defect found in 5 real corpus photos tagged EXIF orientation 3 (an
 * exact 180-degree rotation). Their true physical capture rotation, confirmed by
 * visually inspecting the raw pixels with EXIF ignored, was actually 90 degrees, not
 * 180 — the source photo's own EXIF metadata is simply wrong.
 *
 * [FrameRotation] applies whatever rotation it is given faithfully and correctly; its
 * geometry was independently re-derived and verified against these same photos (see
 * `real-rotated-230941-raw.json`). The bug is upstream of it: a genuinely wrong EXIF
 * tag leaves every line's re-derived `angleDeg` sitting near +/-90 degrees even after
 * [FrameRotation] correctly applies the (wrong) declared rotation — the same
 * physically-implausible symptom the original 90-degree EXIF bug produced, but here the
 * INPUT rotation value is wrong, not the transform applied to it. Asking [SkewEstimator]
 * — built only for a few degrees of genuine camera tilt — to "correct" a page still
 * tilted ~90 degrees rotates every box about a pivot sized for the wrong (unswapped)
 * page dimensions, corrupting box geometry exactly like the original bug this project
 * already fixed once.
 *
 * This guard runs once, after [FrameRotation] applies the EXIF-declared rotation and
 * before [SkewEstimator] ever sees the lines. It is deliberately conservative in two
 * directions:
 * - It only searches for a correction when the page is ALREADY implausible (median
 *   angle beyond [StructuringConfig.maxPlausibleSkewDeg]), so a genuinely-correct EXIF
 *   tag — the other 134 corpus photos — is never touched.
 * - It only APPLIES a correction when the result is demonstrably better (median angle
 *   back within [StructuringConfig.maxPlausibleSkewDeg]); otherwise it reports the page
 *   unchanged rather than guessing.
 *
 * A quarter-turn miscalibration is direction-sensitive, not just a tilt-magnitude
 * problem: rotating +90 vs -90 from here are NOT interchangeable — one lands the page
 * upright (median angle near 0), the other lands it upside down (median angle near
 * +/-180), and comparing only the FOLDED tilt (how far a line sits from the nearest
 * horizontal/vertical axis) cannot tell those two apart, since both fold to the
 * identical ~90-degree answer. Only the FULL signed angle distinguishes "readable" from
 * "upside down" — proven against the real fixture: a further +90 degrees on top of the
 * declared 180 measured a median angle of ~+3.4 degrees (correct), while -90 degrees
 * measured ~-175.3 degrees (upside down), starting from the exact same geometry.
 */
object RotationPlausibilityGuard {

    fun correct(raw: RawTextResult, config: StructuringConfig): RawTextResult {
        val baseline = medianAngle(raw) ?: return raw
        if (abs(baseline) <= config.maxPlausibleSkewDeg) return raw

        val best = listOf(90, -90)
            .map { FrameRotation.apply(raw, it) }
            .minByOrNull { candidate -> abs(medianAngle(candidate) ?: Float.MAX_VALUE) }
            ?: return raw
        val bestAngle = medianAngle(best) ?: return raw
        return if (abs(bestAngle) <= config.maxPlausibleSkewDeg) best else raw
    }

    /** Median of every non-blank line's signed [id.dotcode.braille.ocr.raw.RawLine.angleDeg]. */
    private fun medianAngle(raw: RawTextResult): Float? =
        PageStats.median(raw.lines.filter { it.text.isNotBlank() }.map { it.angleDeg })
}
