package com.bluright.app.model

import androidx.compose.ui.geometry.Rect

/**
 * Represents a single detected face with normalized coordinates [0.0f, 1.0f]
 * relative to the display-oriented camera frame.
 *
 * @param id Unique identifier or tracker index
 * @param normLeft Left edge normalized [0, 1]
 * @param normTop Top edge normalized [0, 1]
 * @param normRight Right edge normalized [0, 1]
 * @param normBottom Bottom edge normalized [0, 1]
 * @param confidence Detection confidence score [0.0, 1.0]
 * @param timestampMs Monotonic timestamp when detected
 */
data class DetectedFace(
    val id: Long = System.currentTimeMillis(),
    val normLeft: Float,
    val normTop: Float,
    val normRight: Float,
    val normBottom: Float,
    val confidence: Float,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val normWidth: Float get() = (normRight - normLeft).coerceAtLeast(0f)
    val normHeight: Float get() = (normBottom - normTop).coerceAtLeast(0f)

    /**
     * Converts to Compose Rect in normalized units
     */
    fun toNormalizedRect(): Rect = Rect(
        left = normLeft,
        top = normTop,
        right = normRight,
        bottom = normBottom
    )

    /**
     * Expands the bounding box by a margin factor (e.g. 0.15 = 15%)
     * to safely encompass hair, jawline, ears, and chin.
     */
    fun expanded(horizontalPadding: Float = 0.12f, verticalPadding: Float = 0.18f): DetectedFace {
        val extraW = normWidth * horizontalPadding
        val extraH = normHeight * verticalPadding
        return copy(
            normLeft = (normLeft - extraW).coerceAtLeast(0f),
            normTop = (normTop - extraH).coerceAtLeast(0f),
            normRight = (normRight + extraW).coerceAtMost(1f),
            normBottom = (normBottom + extraH).coerceAtMost(1f)
        )
    }

    /**
     * Linear interpolation (LERP) between this box and a previous box
     * to eliminate jitter across consecutive video frames.
     */
    fun lerpWith(previous: DetectedFace, alpha: Float = 0.35f): DetectedFace {
        return copy(
            normLeft = previous.normLeft + (this.normLeft - previous.normLeft) * alpha,
            normTop = previous.normTop + (this.normTop - previous.normTop) * alpha,
            normRight = previous.normRight + (this.normRight - previous.normRight) * alpha,
            normBottom = previous.normBottom + (this.normBottom - previous.normBottom) * alpha,
            confidence = previous.confidence + (this.confidence - previous.confidence) * alpha
        )
    }
}
