package com.bluright.app

import com.bluright.app.ble.BleObservation
import com.bluright.app.ble.BleScannerState
import com.bluright.app.model.DetectedFace
import com.bluright.app.privacy.FaceAssociationEngine
import com.bluright.app.privacy.PrivacyDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Adversarial audit tests verifying fail-safe privacy decisions under edge cases,
 * sensor ambiguity, Bluetooth outage, and multiple-face scenes.
 */
class DetectionWatchdogAndFailsafeTest {

    private lateinit var engine: FaceAssociationEngine

    @Before
    fun setUp() {
        engine = FaceAssociationEngine()
    }

    @Test
    fun testBluetoothDisabled_activatesSafeDefaultWithoutCrashing() {
        val face = DetectedFace(1L, 0.2f, 0.2f, 0.5f, 0.5f, 0.90f)

        val decision = engine.evaluatePrivacyDecision(
            faces = listOf(face),
            scannerState = BleScannerState.BluetoothDisabled
        )

        assertTrue(
            "Must fall back to BleUnavailable safe mode",
            decision is PrivacyDecision.BleUnavailable
        )
        val unavailable = decision as PrivacyDecision.BleUnavailable
        assertEquals(1, unavailable.visibleFacesCount)
        assertTrue(unavailable.reason.contains("Bluetooth") || unavailable.requiresMaskingAllFaces)
    }

    @Test
    fun testAmbiguousMultiFaceScene_triggersConservativeFailSafeToMaskAll() {
        val faceA = DetectedFace(1L, 0.1f, 0.1f, 0.4f, 0.4f, 0.92f)
        val faceB = DetectedFace(2L, 0.6f, 0.1f, 0.9f, 0.4f, 0.88f)

        val observation = BleObservation(
            token = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80),
            rssi = -52
        )

        val decision = engine.evaluatePrivacyDecision(
            faces = listOf(faceA, faceB),
            scannerState = BleScannerState.Scanning(listOf(observation))
        )

        assertTrue(
            "Multiple faces with single beacon must trigger AmbiguousConservativeMask",
            decision is PrivacyDecision.AmbiguousConservativeMask
        )
        val failSafe = decision as PrivacyDecision.AmbiguousConservativeMask
        assertEquals(2, failSafe.visibleFacesCount)
        assertEquals(1, failSafe.observationsCount)
        assertEquals(Integer.valueOf(-52), failSafe.strongestRssi)
        assertTrue(failSafe.requiresMaskingAllFaces)
        assertTrue(failSafe.reason.contains("Multiple faces"))
    }

    @Test
    fun testBorderlineRssi_refusesAssociationAndTriggersFailSafe() {
        val face = DetectedFace(1L, 0.3f, 0.3f, 0.6f, 0.6f, 0.95f)

        // RSSI of -82 dBm is in FAR zone (< -75 dBm threshold)
        val farObservation = BleObservation(
            token = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            rssi = -82
        )

        val decision = engine.evaluatePrivacyDecision(
            faces = listOf(face),
            scannerState = BleScannerState.Scanning(listOf(farObservation))
        )

        assertTrue(
            "Far RSSI must not claim confident association; must trigger conservative mask",
            decision is PrivacyDecision.AmbiguousConservativeMask
        )
    }

    @Test
    fun testStrongRssiSingleFace_confirmsAssociationWithDisclaimers() {
        val face = DetectedFace(1L, 0.3f, 0.3f, 0.6f, 0.6f, 0.95f)

        // RSSI of -55 dBm is in IMMEDIATE zone (> -60 dBm threshold)
        val immediateObservation = BleObservation(
            token = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            rssi = -55
        )

        val decision = engine.evaluatePrivacyDecision(
            faces = listOf(face),
            scannerState = BleScannerState.Scanning(listOf(immediateObservation))
        )

        assertTrue(
            "Immediate RSSI + 1 face must yield AssociationSupported",
            decision is PrivacyDecision.AssociationSupported
        )
        val supported = decision as PrivacyDecision.AssociationSupported
        assertEquals(1L, supported.faceId)
        assertEquals(-55, supported.rssi)
        // Must contain honest limitations disclaimer
        assertTrue(supported.disclaimers.any { it.contains("Heuristic proximity") || it.contains("cryptographic identity") })
    }
}
