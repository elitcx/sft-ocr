package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.raw.RawLine
import kotlin.math.abs

/**
 * Drops lines whose own recognizer-reported tilt disagrees sharply with the page's
 * dominant line angle — text photographed on a DIFFERENT physical surface than the one
 * the camera was pointed at, most commonly an open book's FOLDED or CURVED facing page.
 *
 * This is a DIFFERENT, and EARLIER, mechanism from [ColumnSegmenter]'s own facing-page
 * check (see [StructuringConfig.facingPageMaxAngleDiffDeg]). That check compares the
 * median angle of two already-detected COLUMNS, so it only fires when the intruding
 * surface is flat and cohesive enough to form a clean second column in the first place.
 * A folded or curved facing page does not: its lines scatter across many different
 * angles as the paper curves away from the spine, never lining up into one x-range
 * [ColumnSegmenter] would recognize as a column, so its own line-count/share floor and
 * gutter detection let the scattered fragments straight through as noise mixed into the
 * single remaining column — exactly the defect a real user photo hit ("very messy... all
 * just mixed up"). This filter runs BEFORE [ColumnSegmenter] for that reason: a line this
 * filter removes never reaches column detection or reading order at all, regardless of
 * whether it would have formed a column.
 *
 * The two mechanisms are complementary, not redundant: this filter catches a scattered,
 * never-forms-a-column intruder; [ColumnSegmenter]'s check catches a flat, clean-column
 * intruder whose per-line angles agree closely enough with EACH OTHER that this filter's
 * own median-vs-line comparison sees nothing unusual (every one of ITS lines agrees with
 * its neighbours - it only disagrees with the OTHER page). Running both is safe: on the
 * one real fixture that exercises both (`real-facing-page-231108-raw.json`), this filter
 * removes only the widest scattered outliers (evidence below) and leaves the bulk of the
 * facing page's lines, which still cohere enough to form a second column afterwards -
 * [ColumnSegmenter]'s check then removes the rest, as it already did before this filter
 * existed. Neither stage depends on the other having run.
 *
 * Evidence for [StructuringConfig.foldedPageAngleToleranceDeg], measured from each line's
 * own [angleFromCorners] AFTER [SkewEstimator.deskew] (the same post-deskew corner-angle
 * signal [ColumnSegmenter]'s facing-page check already uses, for consistency and because
 * `angleDeg` itself is reset to 0 by deskew — see [LineGeometry]'s KDoc):
 * - The REAL curved single page with the widest genuine per-line spread found in the
 *   corpus (`real-worksheet-exercises.json`, no facing page at all) measures a maximum
 *   deviation from its own median line angle of ~13.5 degrees. This is the ceiling this
 *   threshold must clear, or a real single-page photo starts losing genuine content.
 * - The real folded-facing-page fixture (`real-facing-page-231108-raw.json`) has scattered
 *   outlier lines whose deviation from the dominant (target) page's median reaches into
 *   the high teens and beyond, with the five most extreme outliers measuring 19.95, 20.45,
 *   22.85, 23.76 and 27.27 degrees away.
 *
 * 20 degrees sits just above the worksheet-exercises ceiling — protecting every real
 * single-page photo measured so far — while still catching the most extreme folded-page
 * outliers. It intentionally does NOT catch every scattered fragment on
 * `real-facing-page-231108-raw.json` on its own; the residual is left to
 * [ColumnSegmenter]'s complementary column-based check, described above.
 */
object FoldedPageAngleFilter {

    fun filter(lines: List<RawLine>, config: StructuringConfig): List<RawLine> {
        if (lines.size < 2) return lines
        val angles = lines.mapNotNull { angleFromCorners(it.cornerPoints) }
        // Fewer than two lines report a usable corner-derived angle - there is no
        // meaningful "dominant cluster" to compare against (a synthetic fixture with no
        // cornerPoints, or a page ML Kit barely recognized anything on). Leave every
        // line alone rather than guess.
        if (angles.size < 2) return lines

        val dominantAngle = PageStats.median(angles) ?: return lines
        val tolerance = config.foldedPageAngleToleranceDeg

        val kept = lines.filter { line ->
            // A line with no cornerPoints carries no angle signal at all - keep it
            // unconditionally rather than treat "unknown" as "disagrees".
            val angle = angleFromCorners(line.cornerPoints) ?: return@filter true
            abs(angle - dominantAngle) <= tolerance
        }

        // Never let this filter gut the page. If clustering somehow classifies most of
        // the page as off-angle, that is a sign the "dominant" cluster it found was not
        // actually dominant (e.g. a genuinely and uniformly very skewed real photo this
        // threshold was not tuned against) - the safer failure is to keep everything, per
        // this project's governing priority that dropping real content is worse than
        // leaving an intruder fragment in.
        val retainedShare = kept.size.toFloat() / lines.size
        if (retainedShare < config.minFoldedPageRetainedLineShareFraction) return lines
        return kept
    }
}
