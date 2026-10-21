package com.bluright.app

import com.bluright.app.ble.BleObservation
import com.bluright.app.export.PrivacyReceiptCollector
import com.bluright.app.model.DetectedFace
import com.bluright.app.privacy.PrivacyDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrivacyReceiptCollectorTest {

    private lateinit var collector: PrivacyReceiptCollector

    @Before
    fun setUp() {
        collector = PrivacyReceiptCollector()
        collector.startSession()
    }

    @Test
    fun testRecordFrames_aggregatesCorrectly() {
        val face = DetectedFace(1L, 0.2f, 0.2f, 0.5f, 0.5f, 0.95f)

        // Frame 0: No faces, no beacons
        collector.recordFrame(
            frameIndex = 0,
            timestampMs = 1000L,
            faces = emptyList(),
            maskedFacesCount = 0,
            decision = PrivacyDecision.NoOptOutSignal(visibleFacesCount = 0)
        )

        // Frame 1: 1 face masked under AssociationSupported
        collector.recordFrame(
            frameIndex = 1,
            timestampMs = 1040L,
            faces = listOf(face),
            maskedFacesCount = 1,
            decision = PrivacyDecision.AssociationSupported(
                faceId = 1L,
                tokenHex = "0102030405060708",
                rssi = -55,
                confidenceScore = 0.95f,
                disclaimers = emptyList()
            )
        )

        // Frame 2: Conservative fail-safe (ambiguous multiple faces)
        collector.recordFrame(
            frameIndex = 2,
            timestampMs = 1080L,
            faces = listOf(face, face.copy(id = 2L)),
            maskedFacesCount = 2,
            decision = PrivacyDecision.AmbiguousConservativeMask(
                reason = "Multiple faces present",
                visibleFacesCount = 2,
                observationsCount = 1,
                strongestRssi = -60
            )
        )

        assertEquals("Total frames must be 3", 3, collector.getTotalFrames())
        assertEquals("Masked frames count must be 2", 2, collector.getMaskedFramesCount())
        assertEquals("Opt-out encounter count must be 2", 2, collector.getOptOutEncounterCount())
        assertEquals("Strongest RSSI should be -55", Integer.valueOf(-55), collector.getStrongestRssi())
        assertEquals(3, collector.getTotalFacesDetected())
        assertEquals(3, collector.getTotalFacesMasked())
        assertEquals("AUDIT_VERIFIED_CONSERVATIVE_FAILSAFE", collector.getSessionAuditOutcome())
    }

    @Test
    fun testEmptySession_hasZeroMetrics() {
        assertEquals(0, collector.getTotalFrames())
        assertEquals(0, collector.getMaskedFramesCount())
        assertEquals(0, collector.getOptOutEncounterCount())
        assertEquals(null, collector.getStrongestRssi())
        assertEquals(0L, collector.getSessionDurationMs())
    }
}
