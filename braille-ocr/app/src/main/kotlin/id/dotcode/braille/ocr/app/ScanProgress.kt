package id.dotcode.braille.ocr.app

import id.dotcode.braille.ocr.model.Timings
import kotlin.math.exp

/** The measurable phases of a scan, in the order they run. */
enum class ScanPhase { DECODE, PREPROCESS, RECOGNIZE, SECOND_READ, ASSEMBLE, GEMINI, EXTRACT }

/** What the processing screen shows: how far along, and roughly how long is left. */
data class WorkProgress(val fraction: Float, val remainingMs: Long)

/**
 * Turns "which phase is running and for how long" into a percentage, using how long each
 * phase took in recent scans on this phone. Within a phase the bar moves with the clock but
 * never claims the phase is done before it is: past its usual length it only creeps.
 */
object ScanProgress {

    /** Used until this phone has scanned a few pages. */
    val DEFAULT_MS: Map<ScanPhase, Long> = mapOf(
        ScanPhase.DECODE to 400,
        ScanPhase.PREPROCESS to 300,
        ScanPhase.RECOGNIZE to 2500,
        ScanPhase.SECOND_READ to 1500,
        ScanPhase.ASSEMBLE to 600,
        ScanPhase.GEMINI to 5000,
        // Document extraction scales with file size, so this one is milliseconds per megabyte.
        ScanPhase.EXTRACT to 1500,
    )

    /** A phase's share of the bar that the clock alone may fill. */
    private const val CLOCK_SHARE = 0.9f

    fun estimate(plan: List<Pair<ScanPhase, Long>>, current: ScanPhase, elapsedInCurrent: Long): WorkProgress {
        val total = plan.sumOf { it.second }.coerceAtLeast(1)
        val index = plan.indexOfFirst { it.first == current }.coerceAtLeast(0)
        val done = plan.take(index).sumOf { it.second }
        val expected = plan.getOrNull(index)?.second?.coerceAtLeast(1) ?: 1
        val phaseShare = if (elapsedInCurrent <= expected) {
            CLOCK_SHARE * elapsedInCurrent / expected
        } else {
            val overrun = (elapsedInCurrent - expected).toFloat() / expected
            CLOCK_SHARE + (1 - CLOCK_SHARE) * (1 - exp(-overrun))
        }
        val fraction = ((done + phaseShare * expected) / total).coerceIn(0f, 0.99f)
        val later = plan.drop(index + 1).sumOf { it.second }
        val remaining = (expected - elapsedInCurrent).coerceAtLeast(0) + later
        return WorkProgress(fraction, remaining)
    }

    /** Per-phase durations one finished camera scan contributes to the learned averages. */
    fun phasesOf(timings: Timings): Map<ScanPhase, Long> = buildMap {
        put(ScanPhase.DECODE, timings.decodeMs)
        put(ScanPhase.PREPROCESS, timings.preprocessMs)
        put(ScanPhase.RECOGNIZE, timings.recognizeMs)
        // The second reader runs alongside ML Kit; only the wait after ML Kit finishes shows.
        if (timings.secondReadMs > 0) {
            put(ScanPhase.SECOND_READ, (timings.secondReadMs - timings.recognizeMs).coerceAtLeast(0))
        }
        put(ScanPhase.ASSEMBLE, timings.structureMs + timings.correctMs)
    }

    fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}

/**
 * The last few measured durations per phase, kept in a small preference string so estimates
 * follow this phone's speed. Format: `PHASE=ms,ms,ms;PHASE=...`.
 */
class PhaseHistory(initial: String?) {
    private val samples: MutableMap<ScanPhase, MutableList<Long>> = parse(initial)

    fun expected(phase: ScanPhase): Long =
        ScanProgress.median(samples[phase].orEmpty()) ?: ScanProgress.DEFAULT_MS.getValue(phase)

    fun record(durations: Map<ScanPhase, Long>) {
        durations.forEach { (phase, ms) ->
            val list = samples.getOrPut(phase) { mutableListOf() }
            list += ms.coerceAtLeast(0)
            while (list.size > KEEP) list.removeAt(0)
        }
    }

    fun serialize(): String = samples.entries.joinToString(";") { (phase, values) ->
        "${phase.name}=${values.joinToString(",")}"
    }

    private companion object {
        const val KEEP = 8

        fun parse(text: String?): MutableMap<ScanPhase, MutableList<Long>> {
            val result = mutableMapOf<ScanPhase, MutableList<Long>>()
            text.orEmpty().split(';').forEach { part ->
                val name = part.substringBefore('=', "")
                val phase = ScanPhase.entries.firstOrNull { it.name == name } ?: return@forEach
                result[phase] = part.substringAfter('=').split(',')
                    .mapNotNull { it.trim().toLongOrNull() }
                    .toMutableList()
            }
            return result
        }
    }
}
