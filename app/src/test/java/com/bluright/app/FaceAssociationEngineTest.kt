package com.bluright.app

import com.bluright.app.ble.BleObservation
import com.bluright.app.ble.BleScannerState
import com.bluright.app.ble.DistanceCategory
import com.bluright.app.model.DetectedFace
import com.bluright.app.privacy.FaceAssociationEngine
import com.bluright.app.privacy.PrivacyDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FaceAssociationEngineTest {

    private lateinit var engine: FaceAssociationEngine

    @Before
    fun setup() {
        engine = FaceAssociationEngine()
    }

    @Test
    fun testBluetoothDisabledReturnsBleUnavailable() {
        val faces = listOf(createDummyFace(1L))
        val decision = engine.evaluatePrivacyDecision(faces, BleScannerState.BluetoothDisabled)

        assertTrue(decision is PrivacyDecision.BleUnavailable)
        assertTrue(decision.requiresMaskingAllFaces)
    }

    @Test
    fun testNoOptOutSignalWhenScanningEmpty() {
        val faces = listOf(createDummyFace(1L))
        val decision = engine.evaluatePrivacyDecision(faces, BleScannerState.Scanning(emptyList()))

        assertTrue(decision is PrivacyDecision.NoOptOutSignal)
        assertEquals(1, (decision as PrivacyDecision.NoOptOutSignal).visibleFacesCount)
        assertTrue(decision.requiresMaskingAllFaces)
    }

    @Test
    fun testOptOutDetectedWithZeroFacesInView() {
        val obs = listOf(createObservation(byteArrayOf(1, 2, 3), -50))
        val decision = engine.evaluatePrivacyDecision(emptyList(), BleScannerState.Scanning(obs))

        assertTrue(decision is PrivacyDecision.OptOutDetectedUnverified)
        assertEquals(-50, (decision as PrivacyDecision.OptOutDetectedUnverified).strongestRssi)
    }

    @Test
    fun testMultipleFacesTriggersConservativeFailSafe() {
        val faces = listOf(createDummyFace(1L), createDummyFace(2L))
        val obs = listOf(createObservation(byteArrayOf(1, 2, 3), -52))
        val decision = engine.evaluatePrivacyDecision(faces, BleScannerState.Scanning(obs))

        assertTrue(decision is PrivacyDecision.AmbiguousConservativeMask)
        val ambiguous = decision as PrivacyDecision.AmbiguousConservativeMask
        assertEquals(2, ambiguous.visibleFacesCount)
        assertEquals(1, ambiguous.observationsCount)
        // Verify fail-safe requires masking all faces!
        assertTrue(decision.requiresMaskingAllFaces)
    }

    @Test
    fun testMultipleBeaconsTriggersConservativeFailSafe() {
        val faces = listOf(createDummyFace(1L))
        val obs = listOf(
            createObservation(byteArrayOf(1, 1), -55),
            createObservation(byteArrayOf(2, 2), -58)
        )
        val decision = engine.evaluatePrivacyDecision(faces, BleScannerState.Scanning(obs))

        assertTrue(decision is PrivacyDecision.AmbiguousConservativeMask)
        assertTrue(decision.requiresMaskingAllFaces)
    }

    @Test
    fun testSingleFaceWithStrongImmediateBeaconSupportsAssociation() {
        val faces = listOf(createDummyFace(1L))
        val obs = listOf(createObservation(byteArrayOf(0xA, 0xB), -48)) // Strong > -60 dBm
        val decision = engine.evaluatePrivacyDecision(faces, BleScannerState.Scanning(obs))

        assertTrue(decision is PrivacyDecision.AssociationSupported)
        val supported = decision as PrivacyDecision.AssociationSupported
        assertEquals(1L, supported.faceId)
        assertEquals(-48, supported.rssi)
        assertTrue(supported.confidenceScore > 0.6f)
        assertTrue(supported.disclaimers.isNotEmpty())
    }

    @Test
    fun testSingleFaceWithWeakDistantBeaconFallsBackToConservative() {
        val faces = listOf(createDummyFace(1L))
        val obs = listOf(createObservation(byteArrayOf(0xA, 0xB), -82)) // Weak < -60 dBm
        val decision = engine.evaluatePrivacyDecision(faces, BleScannerState.Scanning(obs))

        assertTrue(decision is PrivacyDecision.AmbiguousConservativeMask)
        assertTrue(decision.requiresMaskingAllFaces)
    }

    @Test
    fun testDistanceCategorization() {
        val immediate = createObservation(byteArrayOf(1), -55)
        val near = createObservation(byteArrayOf(2), -68)
        val far = createObservation(byteArrayOf(3), -85)

        assertEquals(DistanceCategory.IMMEDIATE, immediate.distanceCategory)
        assertEquals(DistanceCategory.NEAR, near.distanceCategory)
        assertEquals(DistanceCategory.FAR, far.distanceCategory)
    }

    private fun createDummyFace(id: Long): DetectedFace {
        return DetectedFace(
            id = id,
            normLeft = 0.2f,
            normTop = 0.2f,
            normRight = 0.5f,
            normBottom = 0.5f,
            confidence = 0.95f
        )
    }

    private fun createObservation(token: ByteArray, rssi: Int): BleObservation {
        return BleObservation(
            token = token,
            rssi = rssi,
            timestampMs = System.currentTimeMillis()
        )
    }
}
