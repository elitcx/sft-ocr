package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.raw.RawLine

/**
 * Drops isolated, narrow, low-confidence fragments that sit entirely beyond the page's
 * own established body-text right margin — OCR noise from a curved or warped page edge,
 * or an intruding second sheet/page at the frame's border.
 *
 * This is a DIFFERENT clutter mechanism from [ColumnSegmenter]'s: that one drops a
 * whole detected COLUMN whose share of column-bound lines falls below
 * [StructuringConfig.minColumnLineShareFraction]. It does not fire here because these
 * fragments never form a detected column at all — a handful of real body lines happen
 * to reach far enough right (one line's right edge came within a fraction of a pixel of
 * the nearest fragment's left edge, on the real photo this was found on) that
 * [ColumnSegmenter]'s interval-merge unions the body's x-range and the fragments'
 * x-range into a single run before any gutter is ever measured. The fragments are true
 * outliers sitting just past that shared run, not a separate run of their own.
 *
 * Evidence (real corpus photo, a curved book page, fixture
 * `real-rotated-230941-raw.json`): 7 fragments — "lseder", "pert", "keadaa", "A",
 * "pandai", "Uga sed", "Ja yar" — all measured narrower than 5 median character widths,
 * all sat at least one full median character width to the right of the rightmost edge
 * any real body line reached anywhere on the page, and all measured recognizer
 * confidence below 0.7 (real body lines on the same page measured 0.76-0.89). An 8th
 * fragment, "fogpro", sits only 0.6 pixels past that same body-line boundary — far
 * inside one character width of genuine ambiguity — and is deliberately left alone by
 * [StructuringConfig.marginFragmentGapFactor] rather than guessed at: under the
 * project's governing priority, corrupting real text is worse than missing one
 * artefact.
 *
 * All three signals are required together, not any one alone:
 * - Width alone fails: a genuine page-number block ("[6]") on the same real page
 *   measured narrower than several of the dropped fragments.
 * - Confidence alone fails: that same real page-number block measured LOWER confidence
 *   (0.49) than several of the dropped fragments (up to 0.69).
 * - Position alone (narrow + past the margin, no confidence check) is kept as a guard
 *   against a rare case this project has not yet observed: a genuine short, high-
 *   confidence word that legitimately extends past the established margin (for example
 *   a justified line's final short word). Requiring low confidence too costs nothing
 *   against the evidence in hand and adds a margin of safety against that case.
 */
object MarginFragmentFilter {

    fun filter(lines: List<RawLine>, stats: PageStats, config: StructuringConfig): List<RawLine> {
        if (lines.size < 2) return lines
        val narrowThreshold = stats.medianCharWidth * config.marginFragmentMaxWidthFactor
        val bodyLines = lines.filter { it.box.width >= narrowThreshold }
        if (bodyLines.isEmpty()) return lines
        val bodyRight = bodyLines.maxOf { it.box.right }
        val gapThreshold = stats.medianCharWidth * config.marginFragmentGapFactor

        return lines.filterNot { line ->
            isIsolatedMarginFragment(line, bodyRight, narrowThreshold, gapThreshold, config)
        }
    }

    private fun isIsolatedMarginFragment(
        line: RawLine,
        bodyRight: Float,
        narrowThreshold: Float,
        gapThreshold: Float,
        config: StructuringConfig,
    ): Boolean {
        if (line.box.width >= narrowThreshold) return false
        if (line.box.left - bodyRight < gapThreshold) return false
        val confidence = line.confidence ?: return false
        return confidence < config.marginFragmentMaxConfidence
    }
}
