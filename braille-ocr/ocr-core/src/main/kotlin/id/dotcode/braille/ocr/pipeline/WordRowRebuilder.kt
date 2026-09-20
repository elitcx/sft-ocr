package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.geometry.BoxF
import id.dotcode.braille.ocr.geometry.PointF
import id.dotcode.braille.ocr.raw.RawLine
import id.dotcode.braille.ocr.raw.RawTextResult
import id.dotcode.braille.ocr.raw.RawWord
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * Stage 0. Re-derives each printed row from WORD boxes, before any other stage runs.
 *
 * Every other stage in [DocumentStructurer] reorders, filters, joins or merges whole
 * lines. That is sound only while a line's own text is in printed order, and on a curved
 * page it is not. The recognizer groups words into a line by baseline proximity; when the
 * paper curves away from the spine, words at different x sit at different y, so it chains
 * across the curve and emits a line whose words are already interleaved with a
 * neighbouring row's.
 *
 * Evidence (real capture, fixture `real-curved-230858-raw.json` = `20260731_230858.jpg`):
 * the recognizer emits a line reading "puluh panjang yang" whose three word boxes are at
 * x=462, x=964 and x=1058 — it jumped from "puluh" straight to "panjang", skipping
 * "lima tahun adalah sebuah perjalanan" (x=533..949), which is physically between them on
 * the same printed row. Flattened, that page reads "Tujuh puluh panjang yang lima tahun
 * adalah sebuah perjalanan" and dates come out as "Juli 17". No line-level stage can
 * repair this: the damage is inside one line's own `text`.
 *
 * Measured across 14 real pages, 66% of word error was ordering rather than misreading
 * (corpus WER 28.4%, but 9.7% once word order is ignored), so this is the dominant error
 * source on this corpus — and for a braille or speech reader a shuffled sentence is worse
 * than a misread word, because context cannot recover it.
 *
 * ## How rows are rebuilt
 *
 * Words are taken from every line that carries them, sorted left to right, and chained:
 * a word joins the row whose current right-hand end is nearest to its left edge, provided
 * the horizontal gap and the vertical step are both small relative to the page's own
 * median word metrics. Comparing against the row's LAST word rather than a global
 * baseline is what lets a row follow curvature — each step is short, so the drift
 * accumulated across the page never has to be modelled.
 *
 * Two guards keep this from inventing rows that are not there:
 * - a word never joins a row whose end is to its right, so reading order cannot reverse;
 * - a gap wider than [StructuringConfig.wordRowMaxGapCharWidths] median character widths
 *   starts a new row, which is what keeps the two sides of a column gutter apart. Column
 *   detection still happens later in [ColumnSegmenter]; this only avoids welding columns
 *   together before it can.
 *
 * Lines without word boxes are passed through untouched: there is nothing to re-derive
 * from, and dropping them would lose text.
 *
 * ## Why facing-page rejection happens here
 *
 * [FrameEdgeFragmentFilter.filter] identifies an intruding facing page by its stack of
 * SHORT LINES AT THE FRAME EDGE. Rebuilding destroys exactly that signal: the fragments
 * are absorbed into long rows, stop looking short, and survive into the reading order.
 * Measured on `real-spread-223954-raw.json` (`20260919_223954.jpg`, an open-book spread)
 * that let 525 characters of the facing page through and took the page from 38.76% CER
 * to 67.58%. So the judgement is made on WORDS, up front, using the recognizer's own line
 * boxes while they still mean something, and the rejected words never enter the pool the
 * rows are built from. [rotationDegrees] is needed only to ask [FrameGeometry] which
 * recorded dimension bounds the reading direction.
 */
object WordRowRebuilder {

    fun rebuild(
        raw: RawTextResult,
        config: StructuringConfig,
        rotationDegrees: Int,
    ): RawTextResult {
        val withWords = raw.lines.filter { it.words.isNotEmpty() }
        val withoutWords = raw.lines.filter { it.words.isEmpty() }

        // Decide which words belong to an intruding surface BEFORE regrouping, while the
        // recognizer's line boxes still carry the evidence. See the KDoc above.
        val frameWidth = FrameGeometry.uprightWidth(raw, rotationDegrees)
        val edge = FrameEdgeFragmentFilter.edgeWords(withWords, frameWidth, config)

        val words = withWords.flatMap { line ->
            line.words.mapNotNull { if (it in edge) null else it to line }
        }
        if (words.size < 2) return raw

        val heights = words.map { (w, _) -> w.box.bottom - w.box.top }.sorted()
        val medianHeight = heights[heights.size / 2]
        if (medianHeight <= 0f) return raw
        val charWidths = words
            .mapNotNull { (w, _) ->
                val n = w.text.length
                if (n > 0) (w.box.right - w.box.left) / n else null
            }
            .filter { it > 0f }
            .sorted()
        if (charWidths.isEmpty()) return raw
        val medianCharWidth = charWidths[charWidths.size / 2]

        // Only act on a page that actually shows the defect. Rebuilding every page would
        // replace the recognizer's grouping even where it was correct, and the stages after
        // this one are tuned against that grouping - measured, that silently changed
        // column detection, facing-page rejection and marker extraction on captures which
        // had nothing wrong with them.
        if (!isCrossChained(withWords, medianHeight, config)) return raw

        val maxGap = config.wordRowMaxGapCharWidths * medianCharWidth
        val maxStep = config.wordRowMaxVerticalStepFactor * medianHeight
        // A word may start slightly left of the previous word's right edge (kerning, a
        // comma tucked under the letter before it) without meaning it belongs elsewhere.
        val overlapSlack = 0.5f * medianCharWidth

        // One row is grown to completion before the next begins. Letting every row compete
        // for each word in one global left-to-right pass does not work here: a curved row
        // drifts further vertically along its own length (29px on the fixture) than the
        // page's row pitch (about 26px), so neighbouring rows sit inside each other's
        // vertical tolerance and routinely win a word that was not theirs. Seeding a row
        // and extending only that row removes the competition entirely.
        val unused = words.sortedWith(
            compareBy({ (w, _) -> w.box.left }, { (w, _) -> (w.box.top + w.box.bottom) / 2f }),
        ).toMutableList()
        val rows = mutableListOf<Row>()
        while (unused.isNotEmpty()) {
            val (seedWord, seedLine) = unused.removeAt(0)
            val row = Row(mutableListOf(seedWord), seedLine)
            while (true) {
                val last = row.words.last().box
                val lastCenterY = (last.top + last.bottom) / 2f
                var bestIndex = -1
                var bestCost = Float.MAX_VALUE
                for (i in unused.indices) {
                    val box = unused[i].first.box
                    val gap = box.left - last.right
                    if (gap < -overlapSlack || gap > maxGap) continue
                    val step = abs((box.top + box.bottom) / 2f - lastCenterY)
                    if (step > maxStep) continue
                    // A real next word is close in x AND barely steps in y. Weighting the
                    // vertical step above the gap keeps a chain from hopping to a nearer
                    // word one row down instead of the slightly further one on its own row.
                    val cost = max(gap, 0f) + VERTICAL_STEP_WEIGHT * step
                    if (cost < bestCost) {
                        bestCost = cost; bestIndex = i
                    }
                }
                if (bestIndex < 0) break
                row.words += unused.removeAt(bestIndex).first
            }
            rows += row
        }

        val rebuilt = rows.map { it.toLine() }.sortedBy { it.words.minOf { w -> (w.box.top + w.box.bottom) / 2f } }
        return raw.copy(lines = rebuilt + withoutWords)
    }

    /** Vertical drift matters more than horizontal distance when choosing the next word. */
    private const val VERTICAL_STEP_WEIGHT = 2.0f

    /**
     * True when some line skips over text that physically sits inside it — the signature of
     * the recognizer chaining across a row boundary.
     *
     * A wide gap between two words in a line is NOT on its own evidence of anything: a
     * contents page's dotted leaders and a justified line both stretch word spacing far past
     * normal. What distinguishes the defect is that *other* lines' words occupy the gap at
     * the same height, which can only happen if this line jumped over them. Measured against
     * the committed fixtures, this fires on the curved capture (4 gaps, the worst skipping
     * "bawah di nama SMA Kanisius Bagian") and on none of the others.
     */
    private fun isCrossChained(
        lines: List<RawLine>,
        medianHeight: Float,
        config: StructuringConfig,
    ): Boolean {
        val heightTolerance = config.crossChainHeightFactor * medianHeight
        for ((index, line) in lines.withIndex()) {
            val ws = line.words
            for (k in 0 until ws.size - 1) {
                val a = ws[k].box
                val b = ws[k + 1].box
                if (b.left <= a.right) continue
                val gapCenterY = (a.top + a.bottom) / 2f
                var intruders = 0
                for ((other, otherLine) in lines.withIndex()) {
                    if (other == index) continue
                    for (w in otherLine.words) {
                        val centerX = (w.box.left + w.box.right) / 2f
                        if (centerX <= a.right || centerX >= b.left) continue
                        if (abs((w.box.top + w.box.bottom) / 2f - gapCenterY) > heightTolerance) continue
                        intruders++
                        if (intruders >= config.crossChainMinIntruders) return true
                    }
                }
            }
        }
        return false
    }

    private class Row(val words: MutableList<RawWord>, val source: RawLine) {

        fun toLine(): RawLine {
            val left = words.minOf { it.box.left }
            val top = words.minOf { it.box.top }
            val right = words.maxOf { it.box.right }
            val bottom = words.maxOf { it.box.bottom }
            val first = words.first().box
            val last = words.last().box
            // Corners follow the row's own drift rather than the axis-aligned box, so the
            // angle a later stage reads back from them matches the angle recorded here.
            // FoldedPageAngleFilter compares exactly this against the page's dominant
            // angle to spot text lying on a different physical surface.
            val corners = listOf(
                PointF(first.left, first.top),
                PointF(last.right, last.top),
                PointF(last.right, last.bottom),
                PointF(first.left, first.bottom),
            )
            val dx = last.right - first.left
            val dy = ((last.top + last.bottom) / 2f) - ((first.top + first.bottom) / 2f)
            val angle = if (dx != 0f) Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() else 0f
            val confidences = words.mapNotNull { it.confidence }
            return RawLine(
                text = words.joinToString(" ") { it.text },
                box = BoxF(min(left, right), min(top, bottom), max(left, right), max(top, bottom)),
                cornerPoints = corners,
                angleDeg = angle,
                recognizedLanguage = source.recognizedLanguage,
                confidence = if (confidences.isEmpty()) source.confidence else confidences.average().toFloat(),
                words = words.toList(),
            )
        }
    }
}
