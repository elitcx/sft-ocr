package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.model.BlockRole
import kotlin.math.roundToInt

/**
 * Stage 6. Assigns a semantic role from relative text height, marker kind, and page position.
 *
 * The "at least three numbered blocks" guard on QUESTION exists so that a lone numbered
 * bullet inside a prose passage is not mistaken for an exam question.
 *
 * Text height alone is an unreliable signal on a handheld photo: perspective makes lines
 * near the bottom of the page measure taller than lines near the top even when the
 * printed font size never changes. Two defenses keep that distortion from corrupting
 * role labels — see [LineMerger]-derived column margins used below, and
 * [localBaselines], which compares a block's height to its reading-order neighbours
 * instead of the whole page. A wrong HEADING is worse than no label at all, so both
 * defenses are deliberately conservative: when the signal is ambiguous the result is
 * PARAGRAPH.
 */
class RoleClassifier(private val config: StructuringConfig) {

    fun classify(
        groups: List<LineGroup>,
        stats: PageStats,
        pageHeight: Int,
        columnRightMargins: Map<Int, Float>,
        columnBounds: List<ClosedFloatingPointRange<Float>> = emptyList(),
    ): List<BlockRole> {
        val numericCount = groups.count { MarkerParser.parse(it.reflowed())?.kind == MarkerKind.NUMERIC }
        val questionsLikely = numericCount >= config.minNumberedBlocksForQuestions
        val baselines = localBaselines(groups, stats)

        val preliminary = groups.mapIndexed { index, group ->
            roleOf(group, stats, pageHeight, questionsLikely, baselines[index], columnRightMargins)
        }

        // A large recognized height can promote an ordinary numbered question - or, on a
        // real photo, one whose leading marker digit OCR simply failed to recognize - to
        // TITLE or HEADING. See StructuringConfig.questionRunAdjacencyWindow: a block
        // sitting among a run of QUESTION blocks at the same indent is demoted back to
        // PARAGRAPH rather than trusted as a structural heading. This is a second pass
        // because it needs every block's PRELIMINARY role decided first - the run a block
        // belongs to must be judged by its neighbours' real roles, not by a partial
        // classification still in progress.
        val indentLevels = groups.map { group ->
            val bounds = columnBounds.getOrElse(group.columnIndex) { columnBounds.firstOrNull() }
            indentLevelOf(group, bounds?.start ?: 0f, stats)
        }
        return preliminary.mapIndexed { index, role ->
            if ((role == BlockRole.TITLE || role == BlockRole.HEADING) &&
                isAdjacentToQuestionRun(index, groups, preliminary, indentLevels)
            ) {
                BlockRole.PARAGRAPH
            } else {
                role
            }
        }
    }

    /**
     * True when a QUESTION block sits within [StructuringConfig.questionRunAdjacencyWindow]
     * blocks BEFORE [index] in reading order, in the same column, at the same indent
     * level (see [indentLevelOf]) as the block being judged. A short "Reason: ______"
     * answer line commonly sits between one question and the next, so the check looks
     * past a fixed number of intervening blocks rather than requiring an immediate
     * predecessor.
     *
     * Deliberately backward-only, not a symmetric neighbourhood. A worksheet's own TITLE
     * commonly sits immediately ABOVE its first question, at the same indent - looking
     * forward as well would demote that genuine title to PARAGRAPH the moment a question
     * run starts right after it. "Sitting in a run of questions" means coming during or
     * after the run has already been established, not merely sitting next to its start.
     */
    private fun isAdjacentToQuestionRun(
        index: Int,
        groups: List<LineGroup>,
        roles: List<BlockRole>,
        indentLevels: List<Int>,
    ): Boolean {
        val column = groups[index].columnIndex
        val indent = indentLevels[index]
        val lo = (index - config.questionRunAdjacencyWindow).coerceAtLeast(0)
        for (j in lo until index) {
            if (groups[j].columnIndex != column) continue
            if (indentLevels[j] != indent) continue
            if (roles[j] == BlockRole.QUESTION) return true
        }
        return false
    }

    /**
     * The indent level (in [StructuringConfig.indentQuantumFactor]-sized steps, capped at
     * [StructuringConfig.maxIndentLevel]) of [group]'s left edge relative to its column's
     * left bound [columnLeft]. Shared with [id.dotcode.braille.ocr.pipeline
     * .DocumentStructurer], which reports it on [id.dotcode.braille.ocr.model.TextBlock],
     * so both places agree on what "the same indent" means rather than keeping two
     * independent formulas in sync by hand.
     */
    internal fun indentLevelOf(group: LineGroup, columnLeft: Float, stats: PageStats): Int {
        val quantum = (stats.medianCharWidth * config.indentQuantumFactor).coerceAtLeast(1f)
        val level = ((group.box.left - columnLeft) / quantum).roundToInt()
        return level.coerceIn(0, config.maxIndentLevel)
    }

    private fun roleOf(
        group: LineGroup,
        stats: PageStats,
        pageHeight: Int,
        questionsLikely: Boolean,
        localBaseline: Float,
        columnRightMargins: Map<Int, Float>,
    ): BlockRole {
        val text = group.reflowed()

        MarkerParser.parse(text)?.let { marker ->
            return if (marker.kind == MarkerKind.NUMERIC && questionsLikely) {
                BlockRole.QUESTION
            } else {
                BlockRole.LIST_ITEM
            }
        }

        if (isPageNumber(group, text, pageHeight)) return BlockRole.PAGE_NUMBER

        // A bare number that is not a page number is a table row-number gutter cell (a
        // "No." column entry) that OCR happened to recognize on its own, separate from
        // its row's other cells. It carries no size or position information a reader
        // could trust: CAPTION, HEADING and TITLE all assert something specific about
        // the text's role on the page, and "22" on its own asserts nothing. PARAGRAPH -
        // plain, unlabelled text - is the honest fallback, exactly as for any other
        // ambiguous block.
        if (isBareNumericLabel(group, text)) return BlockRole.PARAGRAPH

        // A block with more than one line that wraps out to its column's right margin is
        // body text by construction — headings and captions do not wrap across multiple
        // full-width lines. This is decided before any height estimate is even
        // consulted, so it holds regardless of how distorted the local baseline is.
        if (isWrappedFullWidth(group, columnRightMargins, stats)) return BlockRole.PARAGRAPH

        val relativeHeight = relativeHeight(group, localBaseline)
        val inTopBand = group.box.centerY <= pageHeight * config.topBandFraction
        return when {
            relativeHeight >= config.titleHeightRatio -> BlockRole.TITLE
            relativeHeight >= config.headingHeightRatio && inTopBand -> BlockRole.TITLE
            relativeHeight >= config.headingHeightRatio -> BlockRole.HEADING
            isCaption(group, relativeHeight, columnRightMargins, stats, inTopBand) -> BlockRole.CAPTION
            else -> BlockRole.PARAGRAPH
        }
    }

    private fun isPageNumber(group: LineGroup, text: String, pageHeight: Int): Boolean =
        group.lines.size == 1 &&
            group.box.top >= pageHeight * config.bottomBandFraction &&
            text.trim().matches(PAGE_NUMBER_REGEX)

    private fun isBareNumericLabel(group: LineGroup, text: String): Boolean =
        group.lines.size == 1 && text.trim().matches(PAGE_NUMBER_REGEX)

    private fun isWrappedFullWidth(
        group: LineGroup,
        columnRightMargins: Map<Int, Float>,
        stats: PageStats,
    ): Boolean {
        if (group.lines.size <= 1) return false
        val margin = columnRightMargins[group.columnIndex] ?: return false
        val tolerance = stats.medianCharWidth * config.lineEndToleranceFactor
        // The last line of a paragraph is expected to fall short of the margin (that's
        // how a reader tells it's over); only the lines before it need to reach it.
        val linesToCheck = group.lines.dropLast(1)
        if (linesToCheck.isEmpty()) return false
        return linesToCheck.all { margin - it.box.right <= tolerance }
    }

    /**
     * A CAPTION requires all four of: shorter than the local baseline, a single line,
     * stopping well short of its column's right margin, and NOT sitting in the page's
     * own top band. A short block that still reaches the margin, or that wraps, is body
     * text that merely happens to be brief - calling it a caption would be a false,
     * misleading label. The top-band exemption exists because a caption is subordinate
     * text that belongs to something ABOVE it; at the very top of the page there is
     * nothing for it to be subordinate to, so CAPTION there is never plausible - the
     * same [StructuringConfig.topBandFraction] the TITLE branch above already uses for
     * the mirror-image reasoning (a large block up there is promoted to TITLE rather
     * than HEADING).
     */
    private fun isCaption(
        group: LineGroup,
        relativeHeight: Float,
        columnRightMargins: Map<Int, Float>,
        stats: PageStats,
        inTopBand: Boolean,
    ): Boolean {
        if (inTopBand) return false
        if (relativeHeight > config.captionHeightRatio) return false
        if (group.lines.size != 1) return false
        val margin = columnRightMargins[group.columnIndex] ?: return false
        val minShortfall = stats.medianCharWidth * config.captionMinShortfallFactor
        val shortfall = margin - group.box.right
        return shortfall >= minShortfall
    }

    internal fun relativeHeight(group: LineGroup, localBaseline: Float): Float {
        val groupMedian = PageStats.median(group.lines.map { it.box.height }) ?: return 1f
        return if (localBaseline <= 0f) 1f else groupMedian / localBaseline
    }

    /**
     * For each block, the median line height of a window of [StructuringConfig
     * .localHeightWindowSize] neighbouring blocks on either side (in reading order),
     * including the block itself. Falls back to the whole page's median line height when
     * the block has fewer than [StructuringConfig.minNeighboursForLocalBaseline] actual
     * NEIGHBOURS - too little local context to trust, e.g. a title alone at the top of an
     * otherwise single-block-per-side page.
     *
     * The neighbour count excludes the block itself ([window]`.size - 1`), not the raw
     * window size. Comparing the window size directly against the threshold (the original
     * bug) meant the check almost never failed: at [StructuringConfig.minNeighboursForLocalBaseline]
     * `= 3` a window holding only the block plus ONE real neighbour (size 2) already fell
     * below it, but a window holding the block plus two neighbours ALL ON ONE SIDE (size
     * 3) satisfied it, even though "one side" is precisely the situation that matters
     * most: the top and bottom of a photographed page, where a window can only look in one
     * direction (there is nothing above the first block or below the last), and a
     * perspective gradient makes every block on that one available side systematically
     * shorter or taller than the block being judged. A one-sided window with too few real
     * neighbours is the least trustworthy case of all, and is exactly what this guard
     * exists to catch.
     */
    internal fun localBaselines(groups: List<LineGroup>, stats: PageStats): List<Float> {
        val groupHeights = groups.map { group ->
            PageStats.median(group.lines.map { it.box.height }) ?: stats.medianLineHeight
        }
        return groups.indices.map { index ->
            val lo = (index - config.localHeightWindowSize).coerceAtLeast(0)
            val hi = (index + config.localHeightWindowSize).coerceAtMost(groups.size - 1)
            val window = groupHeights.subList(lo, hi + 1)
            val neighbourCount = window.size - 1
            if (neighbourCount < config.minNeighboursForLocalBaseline) {
                stats.medianLineHeight
            } else {
                PageStats.median(window) ?: stats.medianLineHeight
            }
        }
    }

    companion object {
        private val PAGE_NUMBER_REGEX = Regex("^\\d{1,4}$")
    }
}

internal fun LineGroup.reflowed(): String = LineMerger.reflow(lines)
