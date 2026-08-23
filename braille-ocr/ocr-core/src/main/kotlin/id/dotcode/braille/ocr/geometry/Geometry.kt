package id.dotcode.braille.ocr.geometry

import kotlinx.serialization.Serializable

@Serializable
data class PointF(val x: Float, val y: Float)

@Serializable
data class BoxF(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun union(other: BoxF): BoxF = BoxF(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )

    companion object {
        fun enclosing(boxes: List<BoxF>): BoxF {
            require(boxes.isNotEmpty()) { "cannot enclose an empty list of boxes" }
            return boxes.reduce { acc, box -> acc.union(box) }
        }
    }
}
