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
        // Under OPT_OUT_ONLY, Bluetooth disabled without a prior opt-out does not trigger masking
        assertFalse(decision.requiresMaskingAllFaces)
        assertFalse(decision.shouldMaskFace(faces[0]))
    }

    @Test
    fun testNoOptOutSignalWhenScanningEmpty() {
        val faces = listOf(createDummyFace(1L))
        val decision = engine.evaluatePrivacyDecision(faces, BleScannerState.Scanning(emptyList()))

        assertTrue(decision is PrivacyDecision.NoOptOutSignal)
        assertEquals(1, (decision as PrivacyDecision.NoOptOutSignal).visibleFacesCount)
        // Under OPT_OUT_ONLY, detecting a face alone must never trigger masking
        assertFalse(decision.requiresMaskingAllFaces)
        assertFalse(decision.shouldMaskFace(faces[0]))
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
    fun testMultipleFacesWithoutOptOutSignal_doesNotMaskFaces() {
        val faces = listOf(createDummyFace(1L), createDummyFace(2L), createDummyFace(3L))
        val decision = engine.evaluatePrivacyDecision(faces, BleScannerState.Scanning(emptyList()))

        assertTrue(decision is PrivacyDecision.NoOptOutSignal)
        assertEquals(3, (decision as PrivacyDecision.NoOptOutSignal).visibleFacesCount)
        assertFalse(decision.requiresMaskingAllFaces)
        for (face in faces) {
            assertFalse(decision.shouldMaskFace(face))
        }
    }

    @Test
    fun testOptOutDisappears_protectiveMaskingPreservedDuringHold() {
        val obs = listOf(createObservation(byteArrayOf(1, 2, 3), -50))
        val face = createDummyFace(1L)
        // 1. Initial observation with face
        val dec1 = engine.evaluatePrivacyDecision(listOf(face), BleScannerState.Scanning(obs), currentTimeMs = 1000L)
        assertTrue(dec1 is PrivacyDecision.AssociationSupported)
        assertTrue(dec1.shouldMaskFace(face))

        // 2. Opt-out signal drops (empty scan) 2 seconds later (within 6000ms hold)
        val dec2 = engine.evaluatePrivacyDecision(listOf(face), BleScannerState.Scanning(emptyList()), currentTimeMs = 3000L)
        assertTrue("Must enter OptOutSignalHold on packet loss", dec2 is PrivacyDecision.OptOutSignalHold)
        assertTrue("Must preserve protective masking during hold", dec2.requiresMaskingAllFaces)
        assertTrue(dec2.shouldMaskFace(face))
    }

    @Test
    fun testBleUnavailableAfterOptOut_preservesProtectiveHold() {
        val obs = listOf(createObservation(byteArrayOf(1, 2, 3), -50))
        val face = createDummyFace(1L)
        // 1. Initial opt-out
        engine.evaluatePrivacyDecision(listOf(face), BleScannerState.Scanning(obs), currentTimeMs = 1000L)

        // 2. Bluetooth turned off 1 second later
        val decOff = engine.evaluatePrivacyDecision(listOf(face), BleScannerState.BluetoothDisabled, currentTimeMs = 2000L)
        assertTrue("Must preserve protection hold when Bluetooth disabled after opt-out", decOff is PrivacyDecision.OptOutSignalHold)
        assertTrue(decOff.requiresMaskingAllFaces)
        assertTrue(decOff.shouldMaskFace(face))

        // 3. Permissions denied after opt-out
        val decPerm = engine.evaluatePrivacyDecision(listOf(face), BleScannerState.PermissionDenied, currentTimeMs = 2500L)
        assertTrue("Must preserve protection hold when permissions denied after opt-out", decPerm is PrivacyDecision.OptOutSignalHold)
        assertTrue(decPerm.requiresMaskingAllFaces)
    }

    @Test
    fun testExplicitSessionReset_clearsPreviousHoldProtection() {
        val obs = listOf(createObservation(byteArrayOf(1, 2, 3), -50))
        val face = createDummyFace(1L)
        // 1. Opt-out active
        engine.evaluatePrivacyDecision(listOf(face), BleScannerState.Scanning(obs), currentTimeMs = 1000L)

        // 2. Explicit session reset / safe release
        engine.resetSession()

        // 3. New evaluation with scanning empty: should return clean NoOptOutSignal without hold
        val decision = engine.evaluatePrivacyDecision(listOf(face), BleScannerState.Scanning(emptyList()), currentTimeMs = 1500L)
        assertTrue(decision is PrivacyDecision.NoOptOutSignal)
        assertFalse(decision.requiresMaskingAllFaces)
        assertFalse(decision.shouldMaskFace(face))
    }

    @Test
    fun testOptOutDetectedWithZeroFacesInView() {
        val obs = listOf(createObservation(byteArrayOf(1, 2, 3), -50))
        val decision = engine.evaluatePrivacyDecision(emptyList(), BleScannerState.Scanning(obs))

        assertTrue(decision is PrivacyDecision.OptOutDetectedUnverified)
        assertTrue(decision.isOptOutActive)
        assertEquals(-50, (decision as PrivacyDecision.OptOutDetectedUnverified).strongestRssi)
        assertEquals(1, decision.activeObservations.size)
    }

    @Test
    fun testMultipleFacesWithSingleOptOutTriggersConservativeMask() {
        val faces = listOf(createDummyFace(1L), createDummyFace(2L))
        val obs = listOf(createObservation(byteArrayOf(1, 2, 3), -50))
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
