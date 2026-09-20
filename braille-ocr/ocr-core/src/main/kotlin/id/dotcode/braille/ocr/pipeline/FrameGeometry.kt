package id.dotcode.braille.ocr.pipeline

import id.dotcode.braille.ocr.raw.RawTextResult

/**
 * The width of the frame as the READER sees it, which is not always
 * [RawTextResult.imageWidth].
 *
 * The recognizer is handed the rotation up front, so its boxes come back in reading
 * orientation while the recorded frame size still describes the unrotated bitmap. On a
 * quarter-turn capture those disagree: the curved handbook fixture records 1600x1200 but
 * no word box exceeds x=1117, because the upright width is the recorded HEIGHT.
 *
 * Every stage that compares a box against "the edge of the page" must go through here.
 * Getting this wrong does not throw - it silently mis-classifies which text is at the
 * frame edge, which is how an intruding facing page survives into the output.
 */
object FrameGeometry {

    fun uprightWidth(raw: RawTextResult, rotationDegrees: Int): Int {
        val turns = ((rotationDegrees % 360) + 360) % 360
        return if (turns == 90 || turns == 270) raw.imageHeight else raw.imageWidth
    }
}
