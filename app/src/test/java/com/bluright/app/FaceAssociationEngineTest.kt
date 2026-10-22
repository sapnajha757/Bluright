package com.bluright.app

import com.bluright.app.ble.BleObservation
import com.bluright.app.ble.BleScannerState
import com.bluright.app.ble.DistanceCategory
import com.bluright.app.model.DetectedFace
import com.bluright.app.privacy.FaceAssociationEngine
import com.bluright.app.privacy.PrivacyDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // With 0 opt-out signals, ordinary faces are not masked unless all-faces mode is active
        assertFalse(decision.requiresMaskingAllFaces)
        assertTrue(decision.resolveFacesToMask(faces).isEmpty())
    }

    @Test
    fun testOrdinaryFace_noOptOut_noPriorSession_notMasked() {
        val operatorFace = createDummyFace(100L)
        val decision = engine.evaluatePrivacyDecision(listOf(operatorFace), BleScannerState.Scanning(emptyList()))

        assertTrue(decision is PrivacyDecision.NoOptOutSignal)
        val facesToMask = decision.resolveFacesToMask(
            faces = listOf(operatorFace),
            isAllFacesMode = false,
            hasSessionStickyOptOut = false
        )
        // Verified: Camera operator face is not masked solely because face detection found it
        assertTrue("Ordinary face must not be masked without opt-out", facesToMask.isEmpty())
    }

    @Test
    fun testAssociationSupported_masksOnlyAssociatedFace() {
        val faceA = createDummyFace(1L)
        val faceB = createDummyFace(2L)
        // When exactly 1 face was present during evaluation:
        val decision = engine.evaluatePrivacyDecision(
            faces = listOf(faceA),
            scannerState = BleScannerState.Scanning(listOf(createObservation(byteArrayOf(0xA, 0xB), -45)))
        )
        assertTrue(decision is PrivacyDecision.AssociationSupported)
        assertFalse("AssociationSupported does not mask all faces indiscriminately", decision.requiresMaskingAllFaces)

        // When multiple faces appear later, only the associated face (faceA) is masked:
        val resolved = decision.resolveFacesToMask(listOf(faceA, faceB))
        assertEquals(1, resolved.size)
        assertEquals(1L, resolved.first().id)
    }

    @Test
    fun testAmbiguousAssociation_masksAllFacesConservatively() {
        val faceA = createDummyFace(1L)
        val faceB = createDummyFace(2L)
        val decision = engine.evaluatePrivacyDecision(
            faces = listOf(faceA, faceB),
            scannerState = BleScannerState.Scanning(listOf(createObservation(byteArrayOf(0xA, 0xB), -50)))
        )
        assertTrue(decision is PrivacyDecision.AmbiguousConservativeMask)
        assertTrue(decision.requiresMaskingAllFaces)

        val resolved = decision.resolveFacesToMask(listOf(faceA, faceB))
        assertEquals(2, resolved.size)
    }

    @Test
    fun testSessionStickyOptOut_preservesMaskingAfterSignalLoss() {
        val face = createDummyFace(1L)
        // Beacon disappears after being observed, scanner reports 0 opt-outs
        val decision = engine.evaluatePrivacyDecision(listOf(face), BleScannerState.Scanning(emptyList()))
        assertTrue(decision is PrivacyDecision.NoOptOutSignal)

        // With session-sticky flag set (hasObservedOptOutInSession = true), conservative protection is locked in
        val resolved = decision.resolveFacesToMask(
            faces = listOf(face),
            isAllFacesMode = false,
            hasSessionStickyOptOut = true
        )
        assertEquals(1, resolved.size)
        assertEquals(1L, resolved.first().id)
    }

    @Test
    fun testAllFacesMode_masksAllFacesEvenWithNoOptOut() {
        val face = createDummyFace(1L)
        val decision = engine.evaluatePrivacyDecision(listOf(face), BleScannerState.Scanning(emptyList()))

        val resolved = decision.resolveFacesToMask(
            faces = listOf(face),
            isAllFacesMode = true,
            hasSessionStickyOptOut = false
        )
        assertEquals(1, resolved.size)
    }

    @Test
    fun testFaceGeometry_reasonableMaskBounds_coversHeadWithoutExploding() {
        val face = DetectedFace(
            id = 1L,
            normLeft = 0.35f,
            normTop = 0.30f,
            normRight = 0.65f,
            normBottom = 0.70f,
            confidence = 0.90f
        )
        val expanded = face.expandedAdaptive()
        // Must safely cover head and chin
        assertTrue(expanded.normLeft < face.normLeft)
        assertTrue(expanded.normRight > face.normRight)
        assertTrue(expanded.normTop < face.normTop)
        assertTrue(expanded.normBottom > face.normBottom)
        // Must not explode past 85% of screen width for a 30% width face
        assertTrue("Expanded width must not balloon excessively", expanded.normWidth < 0.60f)
        assertTrue("Expanded height must not balloon excessively", expanded.normHeight < 0.70f)
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
