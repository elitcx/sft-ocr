package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.model.BlockRole

/**
 * Stage 6. Assigns a semantic role from relative text height, marker kind, and page position.
 *
 * The "at least three numbered blocks" guard on QUESTION exists so that a lone numbered
 * bullet inside a prose passage is not mistaken for an exam question.
 */
class RoleClassifier(private val config: StructuringConfig) {

    fun classify(groups: List<LineGroup>, stats: PageStats, pageHeight: Int): List<BlockRole> {
        val numericCount = groups.count { MarkerParser.parse(it.reflowed())?.kind == MarkerKind.NUMERIC }
        val questionsLikely = numericCount >= config.minNumberedBlocksForQuestions

        return groups.map { group -> roleOf(group, stats, pageHeight, questionsLikely) }
    }

    private fun roleOf(
        group: LineGroup,
        stats: PageStats,
        pageHeight: Int,
        questionsLikely: Boolean,
    ): BlockRole {
        val text = group.reflowed()
        val relativeHeight = relativeHeight(group, stats)

        MarkerParser.parse(text)?.let { marker ->
            return if (marker.kind == MarkerKind.NUMERIC && questionsLikely) {
                BlockRole.QUESTION
            } else {
                BlockRole.LIST_ITEM
            }
        }

        if (isPageNumber(group, text, pageHeight)) return BlockRole.PAGE_NUMBER

        val inTopBand = group.box.centerY <= pageHeight * config.topBandFraction
        return when {
            relativeHeight >= config.titleHeightRatio -> BlockRole.TITLE
            relativeHeight >= config.headingHeightRatio && inTopBand -> BlockRole.TITLE
            relativeHeight >= config.headingHeightRatio -> BlockRole.HEADING
            relativeHeight <= config.captionHeightRatio -> BlockRole.CAPTION
            else -> BlockRole.PARAGRAPH
        }
    }

    private fun isPageNumber(group: LineGroup, text: String, pageHeight: Int): Boolean =
        group.lines.size == 1 &&
            group.box.top >= pageHeight * config.bottomBandFraction &&
            text.trim().matches(PAGE_NUMBER_REGEX)

    internal fun relativeHeight(group: LineGroup, stats: PageStats): Float {
        val groupMedian = PageStats.median(group.lines.map { it.box.height }) ?: return 1f
        return if (stats.medianLineHeight <= 0f) 1f else groupMedian / stats.medianLineHeight
    }

    companion object {
        private val PAGE_NUMBER_REGEX = Regex("^\\d{1,4}$")
    }
}

internal fun LineGroup.reflowed(): String = LineMerger.reflow(lines)
