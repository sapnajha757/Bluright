package com.bluright.app

import com.bluright.app.audit.MaskingAuditStatus
import com.bluright.app.audit.MaskingQualityAuditor
import com.bluright.app.model.DetectedFace
import com.bluright.app.privacy.PrivacyDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskingQualityAuditorTest {

    @Test
    fun testNoFaces_returnsNoFacesObserved() {
        val result = MaskingQualityAuditor.auditFrame(
            faces = emptyList(),
            decision = PrivacyDecision.NoOptOutSignal(visibleFacesCount = 0),
            isMasked = false
        )

        assertEquals(MaskingAuditStatus.NO_FACES_OBSERVED, result.status)
        assertEquals(0, result.facesAudited)
        assertEquals(1.0f, result.coverageScore, 0.001f)
        assertTrue(result.flags.isEmpty())
    }

    @Test
    fun testRobustFace_returnsVerifiedRobust() {
        // Centered face with high confidence
        val face = DetectedFace(1L, 0.3f, 0.3f, 0.6f, 0.6f, 0.95f)

        val result = MaskingQualityAuditor.auditFrame(
            faces = listOf(face),
            decision = PrivacyDecision.AssociationSupported(
                faceId = 1L,
                tokenHex = "0102030405060708",
                rssi = -60,
                confidenceScore = 0.95f,
                disclaimers = emptyList()
            ),
            isMasked = true
        )

        assertEquals(MaskingAuditStatus.VERIFIED_ROBUST, result.status)
        assertEquals(1, result.facesAudited)
        assertEquals(0, result.facesStrengthenedCount)
        assertEquals(1.0f, result.coverageScore, 0.001f)
        assertEquals(0.15f, MaskingQualityAuditor.calculateAdaptiveMargin(face), 0.001f)
    }

    @Test
    fun testEdgeProximityFace_returnsStrengthenedWithAdaptiveMargin() {
        // Face touching left border (normLeft <= 0.05f)
        val edgeFace = DetectedFace(2L, 0.02f, 0.2f, 0.3f, 0.5f, 0.88f)

        val result = MaskingQualityAuditor.auditFrame(
            faces = listOf(edgeFace),
            decision = PrivacyDecision.NoOptOutSignal(visibleFacesCount = 1),
            isMasked = true
        )

        assertEquals(MaskingAuditStatus.VERIFIED_STRENGTHENED, result.status)
        assertEquals(1, result.facesStrengthenedCount)
        assertEquals(1, result.edgeClampedCount)
        assertTrue(result.flags.any { it.contains("EDGE_PROXIMITY_EXPANDED") })

        // Adaptive margin must expand to 25% for edge safety
        val margin = MaskingQualityAuditor.calculateAdaptiveMargin(edgeFace)
        assertEquals(0.25f, margin, 0.001f)
    }

    @Test
    fun testLowConfidenceFace_returnsStrengthenedWith20PercentMargin() {
        // Centered face but marginal confidence (0.60f < 0.70f)
        val lowConfFace = DetectedFace(3L, 0.3f, 0.3f, 0.6f, 0.6f, 0.60f)

        val result = MaskingQualityAuditor.auditFrame(
            faces = listOf(lowConfFace),
            decision = PrivacyDecision.NoOptOutSignal(visibleFacesCount = 1),
            isMasked = true
        )

        assertEquals(MaskingAuditStatus.VERIFIED_STRENGTHENED, result.status)
        assertEquals(1, result.facesStrengthenedCount)
        assertTrue(result.flags.any { it.contains("LOW_CONFIDENCE_REINFORCED") })

        val margin = MaskingQualityAuditor.calculateAdaptiveMargin(lowConfFace)
        assertEquals(0.20f, margin, 0.001f)
    }

    @Test
    fun testAmbiguousFailsafe_returnsDefensiveAllMasked() {
        val face1 = DetectedFace(1L, 0.2f, 0.2f, 0.4f, 0.4f, 0.90f)
        val face2 = DetectedFace(2L, 0.6f, 0.2f, 0.8f, 0.4f, 0.92f)

        val result = MaskingQualityAuditor.auditFrame(
            faces = listOf(face1, face2),
            decision = PrivacyDecision.AmbiguousConservativeMask(
                reason = "Multiple faces present near beacon",
                visibleFacesCount = 2,
                observationsCount = 1,
                strongestRssi = -65
            ),
            isMasked = true
        )

        assertEquals(MaskingAuditStatus.VERIFIED_DEFENSIVE_ALL_MASKED, result.status)
        assertEquals(2, result.facesAudited)
    }

    @Test
    fun testUnmaskedFaceUnderActiveOptOut_flagsAuditFailure() {
        val face = DetectedFace(1L, 0.3f, 0.3f, 0.6f, 0.6f, 0.95f)

        val result = MaskingQualityAuditor.auditFrame(
            faces = listOf(face),
            decision = PrivacyDecision.AssociationSupported(
                faceId = 1L,
                tokenHex = "0102030405060708",
                rssi = -55,
                confidenceScore = 0.95f,
                disclaimers = emptyList()
            ),
            isMasked = false // Audit failure: Face left unmasked!
        )

        assertEquals(MaskingAuditStatus.AUDIT_INSUFFICIENT_COVERAGE_FLAGGED, result.status)
        assertEquals(0.0f, result.coverageScore, 0.001f)
        assertTrue(result.flags.any { it.contains("AUDIT_FAILED_UNMASKED_FACES") })
    }

    @Test
    fun testHonestLimitationsDisclaimer_isDocumented() {
        val disclaimer = MaskingQualityAuditor.LIMITATIONS_DISCLAIMER
        assertFalse(disclaimer.isEmpty())
        assertTrue("Must mention BlazeFace", disclaimer.contains("BlazeFace"))
        assertTrue("Must mention heuristic limitations", disclaimer.contains("heuristic"))
        assertTrue("Must state cryptographic signature limits", disclaimer.contains("not mathematical impossibility"))
    }
}
