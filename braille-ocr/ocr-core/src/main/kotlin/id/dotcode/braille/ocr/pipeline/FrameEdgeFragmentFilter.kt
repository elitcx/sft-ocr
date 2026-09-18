package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.raw.RawLine

/**
 * Drops short lines cut off by the left or right edge of the photo - almost always the
 * facing page of an open book ("Sejarah Seko", "Riwayat Sant", "ki", "nai dan kan kan").
 * A reader gets nothing from half-words, and later stages would otherwise join them onto
 * real lines of the page being read.
 *
 * A candidate is a line that starts or ends within [StructuringConfig.frameEdgeMarginFraction]
 * of the frame's side and is narrower than [StructuringConfig.frameEdgeMaxWidthFraction] of
 * it (a tightly cropped worksheet has full-width lines that touch the edge; they stay).
 * Candidates are dropped when either:
 * - at least [StructuringConfig.frameEdgeMinStack] of them line the same side: a facing
 *   page leaves a whole stack of cut-off lines, and it is often read confidently (a table
 *   of contents measured 0.76-0.86);
 * - or a lone one has confidence below [StructuringConfig.frameEdgeLoneMaxConfidence]: a
 *   tightly framed page can end one real short line at the edge ("dan nilai-nilai",
 *   0.77 on a real curved page), and that must stay.
 *
 * Evidence (2026-09-17, 139 real handbook/worksheet photos): this drops 384 lines -
 * facing-page tables of contents and cut-off margins - keeps 4 lone edge lines, and drops
 * nothing on the tightly cropped worksheets. Replayed on 16 transcribed pages, the
 * character error rate fell from 11.8% to 9.5%.
 *
 * Runs on upright lines, before [RowFragmentJoiner] can glue a fragment onto a real line.
 */
object FrameEdgeFragmentFilter {

    private enum class Side { LEFT, RIGHT }

    fun filter(lines: List<RawLine>, frameWidth: Int, config: StructuringConfig): List<RawLine> {
        if (frameWidth <= 0 || lines.size < 2) return lines
        val margin = frameWidth * config.frameEdgeMarginFraction
        val maxWidth = frameWidth * config.frameEdgeMaxWidthFraction

        fun sideOf(line: RawLine): Side? = when {
            line.box.width >= maxWidth -> null
            line.box.left < margin -> Side.LEFT
            line.box.right > frameWidth - margin -> Side.RIGHT
            else -> null
        }

        val stacks = lines.mapNotNull(::sideOf).groupingBy { it }.eachCount()
        val kept = lines.filterNot { line ->
            val side = sideOf(line) ?: return@filterNot false
            val stacked = (stacks[side] ?: 0) >= config.frameEdgeMinStack
            val unsure = (line.confidence ?: 1f) < config.frameEdgeLoneMaxConfidence
            stacked || unsure
        }
        // A page that is nothing but edge fragments is better read badly than not at all.
        return kept.ifEmpty { lines }
    }
}
