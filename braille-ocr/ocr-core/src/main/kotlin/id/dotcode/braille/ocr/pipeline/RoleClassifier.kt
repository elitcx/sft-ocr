package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.model.BlockRole

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
    ): List<BlockRole> {
        val numericCount = groups.count { MarkerParser.parse(it.reflowed())?.kind == MarkerKind.NUMERIC }
        val questionsLikely = numericCount >= config.minNumberedBlocksForQuestions
        val baselines = localBaselines(groups, stats)

        return groups.mapIndexed { index, group ->
            roleOf(group, stats, pageHeight, questionsLikely, baselines[index], columnRightMargins)
        }
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
            isCaption(group, relativeHeight, columnRightMargins, stats) -> BlockRole.CAPTION
            else -> BlockRole.PARAGRAPH
        }
    }

    private fun isPageNumber(group: LineGroup, text: String, pageHeight: Int): Boolean =
        group.lines.size == 1 &&
            group.box.top >= pageHeight * config.bottomBandFraction &&
            text.trim().matches(PAGE_NUMBER_REGEX)

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
     * A CAPTION requires all three of: shorter than the local baseline, a single line,
     * and stopping well short of its column's right margin. A short block that still
     * reaches the margin, or that wraps, is body text that merely happens to be brief -
     * calling it a caption would be a false, misleading label.
     */
    private fun isCaption(
        group: LineGroup,
        relativeHeight: Float,
        columnRightMargins: Map<Int, Float>,
        stats: PageStats,
    ): Boolean {
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
     * the window holds fewer than [StructuringConfig.minNeighboursForLocalBaseline]
     * blocks - too little local context to trust, e.g. a title alone at the top of an
     * otherwise single-block-per-side page.
     */
    internal fun localBaselines(groups: List<LineGroup>, stats: PageStats): List<Float> {
        val groupHeights = groups.map { group ->
            PageStats.median(group.lines.map { it.box.height }) ?: stats.medianLineHeight
        }
        return groups.indices.map { index ->
            val lo = (index - config.localHeightWindowSize).coerceAtLeast(0)
            val hi = (index + config.localHeightWindowSize).coerceAtMost(groups.size - 1)
            val window = groupHeights.subList(lo, hi + 1)
            if (window.size < config.minNeighboursForLocalBaseline) {
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
