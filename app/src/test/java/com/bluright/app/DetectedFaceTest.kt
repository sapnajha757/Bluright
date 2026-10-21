package com.bluright.app

import com.bluright.app.model.DetectedFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectedFaceTest {

    @Test
    fun testBoundingBoxNormalizationAndExpansion() {
        val face = DetectedFace(
            id = 1L,
            normLeft = 0.2f,
            normTop = 0.3f,
            normRight = 0.6f,
            normBottom = 0.7f,
            confidence = 0.95f
        )

        assertEquals(0.4f, face.normWidth, 0.001f)
        assertEquals(0.4f, face.normHeight, 0.001f)

        // Expanded face
        val expanded = face.expanded(horizontalPadding = 0.1f, verticalPadding = 0.1f)
        // normLeft = 0.2 - 0.04 = 0.16
        // normRight = 0.6 + 0.04 = 0.64
        assertEquals(0.16f, expanded.normLeft, 0.001f)
        assertEquals(0.64f, expanded.normRight, 0.001f)
        assertTrue(expanded.normWidth > face.normWidth)
        assertTrue(expanded.normHeight > face.normHeight)
    }

    @Test
    fun testLerpInterpolationEliminatesJitter() {
        val previous = DetectedFace(
            id = 1L,
            normLeft = 0.10f,
            normTop = 0.10f,
            normRight = 0.50f,
            normBottom = 0.50f,
            confidence = 0.90f
        )
        val current = DetectedFace(
            id = 1L,
            normLeft = 0.20f,
            normTop = 0.20f,
            normRight = 0.60f,
            normBottom = 0.60f,
            confidence = 0.90f
        )

        val smoothed = current.lerpWith(previous, alpha = 0.5f)
        assertEquals(0.15f, smoothed.normLeft, 0.001f)
        assertEquals(0.15f, smoothed.normTop, 0.001f)
        assertEquals(0.55f, smoothed.normRight, 0.001f)
        assertEquals(0.55f, smoothed.normBottom, 0.001f)
    }

    @Test
    fun testBoundaryClamping() {
        val faceAtEdge = DetectedFace(
            id = 2L,
            normLeft = 0.05f,
            normTop = 0.05f,
            normRight = 0.98f,
            normBottom = 0.98f,
            confidence = 0.88f
        )

        val expanded = faceAtEdge.expanded(horizontalPadding = 0.2f, verticalPadding = 0.2f)
        assertEquals(0.0f, expanded.normLeft, 0.001f)
        assertEquals(1.0f, expanded.normRight, 0.001f)
        assertEquals(0.0f, expanded.normTop, 0.001f)
        assertEquals(1.0f, expanded.normBottom, 0.001f)
    }
}
