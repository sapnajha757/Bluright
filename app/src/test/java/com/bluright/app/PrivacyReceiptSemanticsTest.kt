package com.bluright.app

import com.bluright.app.ble.BleObservation
import com.bluright.app.export.PrivacyReceiptCollector
import com.bluright.app.model.DetectedFace
import com.bluright.app.privacy.PrivacyDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * Adversarial audit tests verifying counter semantics and token uniqueness in PrivacyReceiptCollector.
 */
class PrivacyReceiptSemanticsTest {

    private lateinit var collector: PrivacyReceiptCollector

    @Before
    fun setUp() {
        collector = PrivacyReceiptCollector()
        collector.startSession()
    }

    @Test
    fun testOptOutEncounterCount_measuresFrameEncountersNotUniqueIndividuals() {
        val face = DetectedFace(1L, 0.2f, 0.2f, 0.5f, 0.5f, 0.95f)

        // Simulate 10 consecutive video frames observing THE SAME opt-out beacon token
        val singleTokenHex = "0102030405060708"
        for (frame in 0 until 10) {
            collector.recordFrame(
                frameIndex = frame,
                timestampMs = 1000L + (frame * 33L),
                faces = listOf(face),
                maskedFacesCount = 1,
                decision = PrivacyDecision.AssociationSupported(
                    faceId = 1L,
                    tokenHex = singleTokenHex,
                    rssi = -55,
                    confidenceScore = 0.95f,
                    disclaimers = emptyList()
                )
            )
        }

        assertEquals("Total frames must be 10", 10, collector.getTotalFrames())
        assertEquals("Masked frames count must be 10", 10, collector.getMaskedFramesCount())

        // AUDIT FINDING: optOutEncounterCount equals 10, reflecting frame count with active signal,
        // NOT 10 unique individuals or 10 unique beacons.
        assertEquals(
            "optOutEncounterCount records frame count with active beacon",
            10,
            collector.getOptOutEncounterCount()
        )
        // BUT unique tokens count correctly isolates the single unique beacon observed
        assertEquals(
            "Unique tokens count must accurately be 1",
            1,
            collector.getUniqueTokensCount()
        )
    }

    @Test
    fun testRotatingTokens_trackedAsDistinctUniqueTokens() {
        val face = DetectedFace(1L, 0.2f, 0.2f, 0.5f, 0.5f, 0.95f)

        // Simulate 3 rotating tokens across 6 frames
        val tokens = listOf("1111111111111111", "2222222222222222", "3333333333333333")
        for (i in 0 until 6) {
            val token = tokens[i / 2]
            collector.recordFrame(
                frameIndex = i,
                timestampMs = 1000L + (i * 33L),
                faces = listOf(face),
                maskedFacesCount = 1,
                decision = PrivacyDecision.AssociationSupported(
                    faceId = 1L,
                    tokenHex = token,
                    rssi = -55,
                    confidenceScore = 0.95f,
                    disclaimers = emptyList()
                )
            )
        }

        assertEquals(6, collector.getTotalFrames())
        assertEquals(6, collector.getOptOutEncounterCount())
        assertEquals(3, collector.getUniqueTokensCount())
    }

    @Test
    fun testZeroFacesWithActiveBeacon_recordsUnmaskedFramesHonesty() {
        // 5 frames where opt-out beacon is detected nearby, but zero faces are detected in view
        val observation = BleObservation(
            token = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            rssi = -50,
            timestampMs = System.currentTimeMillis()
        )

        for (frame in 0 until 5) {
            collector.recordFrame(
                frameIndex = frame,
                timestampMs = 2000L + (frame * 33L),
                faces = emptyList(),
                maskedFacesCount = 0,
                decision = PrivacyDecision.OptOutDetectedUnverified(
                    activeObservations = listOf(observation),
                    strongestRssi = -50
                )
            )
        }

        // 5 frames where 1 face appears and is masked
        val face = DetectedFace(1L, 0.3f, 0.3f, 0.6f, 0.6f, 0.90f)
        for (frame in 5 until 10) {
            collector.recordFrame(
                frameIndex = frame,
                timestampMs = 2000L + (frame * 33L),
                faces = listOf(face),
                maskedFacesCount = 1,
                decision = PrivacyDecision.AssociationSupported(
                    faceId = 1L,
                    tokenHex = observation.tokenHex,
                    rssi = -50,
                    confidenceScore = 0.95f,
                    disclaimers = emptyList()
                )
            )
        }

        assertEquals("Total frames must be 10", 10, collector.getTotalFrames())
        assertEquals("Masked frames count must be 5", 5, collector.getMaskedFramesCount())
        assertEquals("Opt-out encounters must be 10 frames", 10, collector.getOptOutEncounterCount())

        // Difference between total frames and masked frames exactly accounts for the 5 unmasked frames
        assertEquals(5, collector.getTotalFrames() - collector.getMaskedFramesCount())
    }
}
