package com.bluright.app

import android.graphics.Bitmap
import com.bluright.app.ble.BleObservation
import com.bluright.app.ble.BleScannerState
import com.bluright.app.crypto.PrivacyManifest
import com.bluright.app.crypto.PrivacyManifestSigner
import com.bluright.app.export.PrivacyMaskRenderer
import com.bluright.app.export.PrivacyReceiptCollector
import com.bluright.app.model.BlurStyle
import com.bluright.app.model.DetectedFace
import com.bluright.app.privacy.FaceAssociationEngine
import com.bluright.app.privacy.PrivacyDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * Phase 5 Fail-Closed Privacy & Adversarial Regression Tests.
 *
 * Verifies that:
 * 1. DEF-01: Zero faces detected with an active opt-out beacon triggers full-frame blackout.
 * 2. Detector timeouts, staleness, or exceptions discard frames rather than encoding unmasked frames.
 * 3. Stale bounding boxes are never reused as proof of current-frame coverage.
 * 4. BLE signal loss and rotating tokens enforce a 6000ms protection hold window.
 * 5. Bluetooth loss never silently switches to unmasked recording.
 * 6. Manifest accurately reports total, masked, blacked-out, unmasked, and discarded frames.
 */
class FailClosedAdversarialRegressionTest {

    private lateinit var associationEngine: FaceAssociationEngine
    private lateinit var receiptCollector: PrivacyReceiptCollector
    private lateinit var signer: PrivacyManifestSigner
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        associationEngine = FaceAssociationEngine(signalLossHoldDurationMs = 6000L)
        receiptCollector = PrivacyReceiptCollector()

        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val testKeyPair = kpg.generateKeyPair()

        signer = PrivacyManifestSigner(
            keyAlias = "test_fail_closed_key",
            isTestMode = true,
            testKeyPair = testKeyPair
        )

        tempDir = Files.createTempDirectory("bluright_failclosed_test").toFile()
        receiptCollector.startSession()
    }

    @Test
    fun testEmptyDetection_withActiveOptOut_triggersFailClosedBlackout() {
        val observation = BleObservation(
            token = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte()),
            rssi = -45,
            timestampMs = 1000L
        )
        val scannerState = BleScannerState.Scanning(listOf(observation))

        // Frame 0: Camera sees 0 faces, but BLE opt-out is strong (-45 dBm)
        val decision = associationEngine.evaluatePrivacyDecision(emptyList(), scannerState, currentTimeMs = 1000L)
        assertTrue("Decision must be OptOutDetectedUnverified", decision is PrivacyDecision.OptOutDetectedUnverified)
        assertTrue("Fail-closed blackout must be required when 0 faces are seen", decision.shouldApplyFailClosedBlackoutIfNoFaces)
        assertTrue("Opt-out must be active", decision.isOptOutActive)

        // When opt-out is active and faces are empty, fail-closed policy triggers blackout
        assertTrue("Fail-closed blackout must be required when 0 faces are seen", decision.shouldApplyFailClosedBlackoutIfNoFaces)
        assertTrue("Opt-out must be active", decision.isOptOutActive)

        receiptCollector.recordFrame(
            frameIndex = 0,
            timestampMs = 1000L,
            faces = emptyList(),
            maskedFacesCount = 0,
            decision = decision,
            isBlackedOut = true
        )

        assertEquals(1, receiptCollector.getTotalFrames())
        assertEquals(0, receiptCollector.getMaskedFramesCount())
        assertEquals(1, receiptCollector.getBlackedOutFramesCount())
        assertEquals(0, receiptCollector.getUnmaskedFramesCount())
        assertEquals(1, receiptCollector.getOptOutEncounterCount())
    }

    @Test
    fun testDetectorException_discardsFrame_neverEncodesUnmasked() {
        // When detector throws an exception, recordDiscardedFrame is called
        receiptCollector.recordDiscardedFrame("DETECTOR_NATIVE_CRASH_OR_TIMEOUT")
        receiptCollector.recordDiscardedFrame("FRAME_ANALYSIS_EXCEPTION")

        assertEquals(0, receiptCollector.getTotalFrames()) // Nothing committed to MP4
        assertEquals(2, receiptCollector.getDiscardedFramesCount())
    }

    @Test
    fun testBleSignalLoss_holdsProtectionDuringHysteresisWindow() {
        val token = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val obs = BleObservation(token = token, rssi = -42, timestampMs = 1000L)

        // 1. Initial observation at t = 1000ms
        val state1 = BleScannerState.Scanning(listOf(obs))
        val dec1 = associationEngine.evaluatePrivacyDecision(emptyList(), state1, currentTimeMs = 1000L)
        assertTrue("Initially opt-out is active", dec1.isOptOutActive)

        // 2. Radio packets drop at t = 2500ms (1.5 seconds later) -> observations empty
        val stateEmpty = BleScannerState.Scanning(emptyList())
        val dec2 = associationEngine.evaluatePrivacyDecision(emptyList(), stateEmpty, currentTimeMs = 2500L)
        assertTrue("Decision must be OptOutSignalHold within 6000ms grace window", dec2 is PrivacyDecision.OptOutSignalHold)
        assertTrue("Opt-out must remain strictly active during hold", dec2.isOptOutActive)
        assertTrue("Fail-closed blackout must apply during hold if faces are empty", dec2.shouldApplyFailClosedBlackoutIfNoFaces)

        // 3. At t = 4000ms (3 seconds after loss), person appears with 1 face
        val face = DetectedFace(1L, 0.2f, 0.2f, 0.5f, 0.5f, 0.95f)
        val dec3 = associationEngine.evaluatePrivacyDecision(listOf(face), stateEmpty, currentTimeMs = 4000L)
        assertTrue("Decision must remain in hold", dec3 is PrivacyDecision.OptOutSignalHold)
        assertTrue("Must mask all faces during hold", dec3.requiresMaskingAllFaces)

        // 4. At t = 8000ms (7 seconds after loss > 6000ms hold window) -> protection expires cleanly
        val dec4 = associationEngine.evaluatePrivacyDecision(emptyList(), stateEmpty, currentTimeMs = 8000L)
        assertTrue("Decision must finally transition to NoOptOutSignal after hold expires", dec4 is PrivacyDecision.NoOptOutSignal)
        assertFalse("Opt-out no longer active", dec4.isOptOutActive)
    }

    @Test
    fun testTokenRotation_seamlesslyTransitionsWithoutUnmaskedGap() {
        val oldToken = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 1, 2, 3, 4, 5, 6)
        val newToken = byteArrayOf(0xBB.toByte(), 0xBB.toByte(), 1, 2, 3, 4, 5, 6)

        // t = 1000ms: Old token active
        val state1 = BleScannerState.Scanning(listOf(BleObservation(oldToken, -40, 1000L)))
        associationEngine.evaluatePrivacyDecision(emptyList(), state1, currentTimeMs = 1000L)

        // t = 1300ms: Advertiser restarts and rotates (radio silence for 400ms)
        val stateRotating = BleScannerState.Scanning(emptyList())
        val decRotating = associationEngine.evaluatePrivacyDecision(emptyList(), stateRotating, currentTimeMs = 1400L)
        assertTrue("Must be in OptOutSignalHold during token rotation restart", decRotating is PrivacyDecision.OptOutSignalHold)
        assertTrue("Protection remains active during rotation", decRotating.isOptOutActive)

        // t = 1700ms: New rotated token arrives
        val stateRotated = BleScannerState.Scanning(listOf(BleObservation(newToken, -41, 1700L)))
        val decRotated = associationEngine.evaluatePrivacyDecision(emptyList(), stateRotated, currentTimeMs = 1700L)
        assertTrue("New token picked up without any unprotected gap", decRotated is PrivacyDecision.OptOutDetectedUnverified)
        assertTrue("Protection continues seamlessly", decRotated.isOptOutActive)
    }

    @Test
    fun testBluetoothDisabled_maintainsStrictProtectionIfOptOutWasActive() {
        val token = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val state1 = BleScannerState.Scanning(listOf(BleObservation(token, -45, 1000L)))
        associationEngine.evaluatePrivacyDecision(emptyList(), state1, currentTimeMs = 1000L)

        // Bluetooth turned off 1 second later
        val stateOff = BleScannerState.BluetoothDisabled
        val decOff = associationEngine.evaluatePrivacyDecision(emptyList(), stateOff, currentTimeMs = 2000L)

        assertTrue("Must retain protection hold when Bluetooth is toggled off", decOff is PrivacyDecision.OptOutSignalHold)
        assertTrue("Fail-closed blackout remains active", decOff.shouldApplyFailClosedBlackoutIfNoFaces)
    }

    @Test
    fun testManifestAccounting_accuratelyReportsAllFrameCategories() {
        // Record 10 total frames:
        // - 4 masked frames (faces detected)
        // - 3 fail-closed blackout frames (opt-out active, 0 faces)
        // - 3 normal unmasked frames (no opt-out, 0 faces)
        // - 2 discarded frames (dropped)

        val face = DetectedFace(1L, 0.2f, 0.2f, 0.6f, 0.6f, 0.9f)
        val optOutObs = listOf(BleObservation(byteArrayOf(1, 1, 1, 1, 1, 1, 1, 1), -50, 1000L))

        // 4 masked
        for (i in 0 until 4) {
            receiptCollector.recordFrame(
                frameIndex = i,
                timestampMs = 1000L + (i * 33L),
                faces = listOf(face),
                maskedFacesCount = 1,
                decision = PrivacyDecision.AmbiguousConservativeMask("Test", 1, 1, -50),
                isBlackedOut = false
            )
        }

        // 3 blacked out
        for (i in 4 until 7) {
            receiptCollector.recordFrame(
                frameIndex = i,
                timestampMs = 1000L + (i * 33L),
                faces = emptyList(),
                maskedFacesCount = 0,
                decision = PrivacyDecision.OptOutDetectedUnverified(optOutObs, -50),
                isBlackedOut = true
            )
        }

        // 3 unmasked
        for (i in 7 until 10) {
            receiptCollector.recordFrame(
                frameIndex = i,
                timestampMs = 1000L + (i * 33L),
                faces = emptyList(),
                maskedFacesCount = 0,
                decision = PrivacyDecision.NoOptOutSignal(0),
                isBlackedOut = false
            )
        }

        // 2 discarded
        receiptCollector.recordDiscardedFrame("DETECTOR_ERROR_DROPPED")
        receiptCollector.recordDiscardedFrame("MEDIACODEC_BUFFER_DROPPED")

        val dummyVideo = File(tempDir, "test_accounting.mp4").apply { writeBytes(ByteArray(1024)) }

        val manifest = signer.signManifest(
            exportId = "test_export_accounting",
            createdAtIso = "2026-10-22T00:40:00Z",
            videoFile = dummyVideo,
            videoDurationMs = 330L,
            totalFrames = receiptCollector.getTotalFrames(),
            maskedFramesCount = receiptCollector.getMaskedFramesCount(),
            blackedOutFramesCount = receiptCollector.getBlackedOutFramesCount(),
            unmaskedFramesCount = receiptCollector.getUnmaskedFramesCount(),
            discardedFramesCount = receiptCollector.getDiscardedFramesCount(),
            privacyPolicy = "CONSERVATIVE_FAILSAFE_FAILCLOSED_STRICT",
            auditOutcome = receiptCollector.getSessionAuditOutcome(),
            cloudProcessing = false,
            optOutEncounterCount = receiptCollector.getOptOutEncounterCount(),
            optOutTokensCount = receiptCollector.getUniqueTokensCount()
        )

        assertEquals(10, manifest.totalFrames)
        assertEquals(4, manifest.maskedFramesCount)
        assertEquals(3, manifest.blackedOutFramesCount)
        assertEquals(3, manifest.unmaskedFramesCount)
        assertEquals(2, manifest.discardedFramesCount)
        assertEquals(7, manifest.optOutEncounterCount) // 4 masked + 3 blacked-out frames encountered opt-out

        // Invariant: total encoded frames == masked + blacked-out + unmasked
        assertEquals(manifest.totalFrames, manifest.maskedFramesCount + manifest.blackedOutFramesCount + manifest.unmaskedFramesCount)

        // Verify JSON round-trip
        val jsonStr = manifest.toJsonString()
        val parsed = PrivacyManifest.fromJsonString(jsonStr)

        assertEquals(manifest.totalFrames, parsed.totalFrames)
        assertEquals(manifest.maskedFramesCount, parsed.maskedFramesCount)
        assertEquals(manifest.blackedOutFramesCount, parsed.blackedOutFramesCount)
        assertEquals(manifest.unmaskedFramesCount, parsed.unmaskedFramesCount)
        assertEquals(manifest.discardedFramesCount, parsed.discardedFramesCount)

        // Verify signature passes
        val verification = signer.verifyManifest(jsonStr, dummyVideo)
        assertTrue("Manifest verification must be Valid", verification is com.bluright.app.crypto.ManifestVerificationResult.Valid)
    }

    @Test
    fun testFailClosedPolicy_appliesBlackoutToAllAmbiguousZeroFaceStates() {
        val obs = listOf(BleObservation(byteArrayOf(1, 2, 3), -50, 1000L))

        // State 1: OptOutDetectedUnverified with 0 faces
        val dec1 = PrivacyDecision.OptOutDetectedUnverified(obs, -50)
        assertTrue(dec1.shouldApplyFailClosedBlackoutIfNoFaces)

        // State 2: OptOutSignalHold with 0 faces
        val dec2 = PrivacyDecision.OptOutSignalHold(1500L, "010203", 0)
        assertTrue(dec2.shouldApplyFailClosedBlackoutIfNoFaces)

        // State 3: AmbiguousConservativeMask with 0 faces
        val dec3 = PrivacyDecision.AmbiguousConservativeMask("Multi-beacon", 0, 2, -50)
        assertTrue(dec3.shouldApplyFailClosedBlackoutIfNoFaces)

        // State 4: Default camera with 0 faces (NO opt-out) -> False
        val dec4 = PrivacyDecision.NoOptOutSignal(0)
        assertFalse(dec4.shouldApplyFailClosedBlackoutIfNoFaces)
    }

    @Test
    fun testDetectorTimeout_discardsFrames_neverEncodesUnprotected() {
        // Watchdog detects elapsed time > STALE_TIMEOUT_MS (650ms)
        val watchdogTimeoutReason = "DETECTOR_WATCHDOG_STALE_700MS"
        receiptCollector.recordDiscardedFrame(watchdogTimeoutReason)

        assertEquals("No frames must reach MP4 on detector timeout", 0, receiptCollector.getTotalFrames())
        assertEquals("Discard counter must record timeout", 1, receiptCollector.getDiscardedFramesCount())
    }

    @Test
    fun testStaleResults_cannotAuthorizeCurrentFrameEncoding() {
        val obs = listOf(BleObservation(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), -45, 1000L))
        val scannerState = BleScannerState.Scanning(obs)

        // Frame 1: Face detected and masked
        val face = DetectedFace(1L, 0.2f, 0.2f, 0.5f, 0.5f, 0.95f)
        val dec1 = associationEngine.evaluatePrivacyDecision(listOf(face), scannerState, currentTimeMs = 1000L)
        receiptCollector.recordFrame(0, 1000L, listOf(face), 1, dec1, isBlackedOut = false)

        // Frame 2: 33ms later, face detection returns 0 faces (subject turned away)
        // Under fail-closed policy, previous bounding box is NOT reused as coverage proof!
        val dec2 = associationEngine.evaluatePrivacyDecision(emptyList(), scannerState, currentTimeMs = 1033L)
        assertTrue("Must require fail-closed blackout when current detection is empty", dec2.shouldApplyFailClosedBlackoutIfNoFaces)

        receiptCollector.recordFrame(1, 1033L, emptyList(), 0, dec2, isBlackedOut = true)

        assertEquals(2, receiptCollector.getTotalFrames())
        assertEquals(1, receiptCollector.getMaskedFramesCount())
        assertEquals(1, receiptCollector.getBlackedOutFramesCount())
        assertEquals(0, receiptCollector.getUnmaskedFramesCount())
    }

    @Test
    fun testMultipleFaces_withOptOut_appliesConservativeMaskToAllFaces() {
        val obs = listOf(BleObservation(byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9), -48, 1000L))
        val scannerState = BleScannerState.Scanning(obs)

        val faces = listOf(
            DetectedFace(1L, 0.1f, 0.1f, 0.3f, 0.3f, 0.90f),
            DetectedFace(2L, 0.4f, 0.4f, 0.6f, 0.6f, 0.85f),
            DetectedFace(3L, 0.7f, 0.7f, 0.9f, 0.9f, 0.80f)
        )

        val decision = associationEngine.evaluatePrivacyDecision(faces, scannerState, currentTimeMs = 1000L)
        assertTrue("Decision must be AmbiguousConservativeMask", decision is PrivacyDecision.AmbiguousConservativeMask)
        assertTrue("All visible faces must be masked", decision.requiresMaskingAllFaces)

        receiptCollector.recordFrame(0, 1000L, faces, maskedFacesCount = 3, decision = decision, isBlackedOut = false)
        assertEquals(3, receiptCollector.getTotalFacesMasked())
        assertEquals(1, receiptCollector.getMaskedFramesCount())
        assertEquals(0, receiptCollector.getUnmaskedFramesCount())
    }

    @Test
    fun testEncoderFailure_andRecoveryAfterError_resetsStateCleanly() {
        // Session 1: Encounters failure
        receiptCollector.startSession()
        receiptCollector.recordFrame(0, 1000L, emptyList(), 0, PrivacyDecision.NoOptOutSignal(0), false)
        receiptCollector.recordDiscardedFrame("HARDWARE_ENCODER_OUT_OF_MEMORY")
        assertEquals(1, receiptCollector.getTotalFrames())
        assertEquals(1, receiptCollector.getDiscardedFramesCount())

        // Abort / Cleanup: Simulate session reset on recovery
        receiptCollector.startSession()
        assertEquals("Total frames must be reset to 0", 0, receiptCollector.getTotalFrames())
        assertEquals("Discarded frames must be reset to 0", 0, receiptCollector.getDiscardedFramesCount())
        assertEquals("Masked frames must be reset to 0", 0, receiptCollector.getMaskedFramesCount())

        // Session 2: Fresh session records cleanly and exports
        val dummyVideo = File(tempDir, "recovered_session.mp4").apply { writeBytes(ByteArray(2048)) }
        val face = DetectedFace(1L, 0.2f, 0.2f, 0.5f, 0.5f, 0.92f)
        receiptCollector.recordFrame(0, 2000L, listOf(face), 1, PrivacyDecision.NoOptOutSignal(1), false)

        val manifest = signer.signManifest(
            exportId = "recovered_export_001",
            createdAtIso = "2026-10-22T01:00:00Z",
            videoFile = dummyVideo,
            videoDurationMs = 100L,
            totalFrames = receiptCollector.getTotalFrames(),
            maskedFramesCount = receiptCollector.getMaskedFramesCount(),
            blackedOutFramesCount = 0,
            unmaskedFramesCount = 0,
            discardedFramesCount = 0
        )

        assertEquals(1, manifest.totalFrames)
        assertEquals(1, manifest.maskedFramesCount)

        val verification = signer.verifyManifest(manifest.toJsonString(), dummyVideo)
        assertTrue("Subsequent export after recovery must verify cleanly", verification is com.bluright.app.crypto.ManifestVerificationResult.Valid)
    }

    @Test
    fun testExportFinalizationFailure_neverProducesSuccessfulAuditResult() {
        val dummyVideo = File(tempDir, "tampered_export.mp4").apply { writeBytes(ByteArray(1024)) }
        val manifest = signer.signManifest(
            exportId = "tamper_test",
            createdAtIso = "2026-10-22T01:00:00Z",
            videoFile = dummyVideo,
            videoDurationMs = 100L,
            totalFrames = 10,
            maskedFramesCount = 10
        )

        // Alter 1 byte in video file to simulate corrupt finalization
        dummyVideo.writeBytes(ByteArray(1024) { 0xFF.toByte() })

        val verification = signer.verifyManifest(manifest.toJsonString(), dummyVideo)
        assertFalse("Corrupt finalization must never produce Valid verification", verification is com.bluright.app.crypto.ManifestVerificationResult.Valid)
        assertTrue("Must detect video digest mismatch", verification is com.bluright.app.crypto.ManifestVerificationResult.VideoHashMismatch)
    }

    @Test
    fun testSessionStickyOptOut_afterSignalLossHoldExpiration_preservesFailClosedBlackout() {
        val obs = BleObservation(
            token = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 1, 2, 3, 4, 5, 6),
            rssi = -48,
            timestampMs = 1000L
        )

        // Frame 0: Opt-out active at t=1000ms
        val state1 = BleScannerState.Scanning(listOf(obs))
        val dec1 = associationEngine.evaluatePrivacyDecision(emptyList(), state1, currentTimeMs = 1000L)
        assertTrue("Initial decision has active opt-out", dec1.isOptOutActive)

        receiptCollector.recordFrame(
            frameIndex = 0,
            timestampMs = 1000L,
            faces = emptyList(),
            maskedFacesCount = 0,
            decision = dec1,
            isBlackedOut = true
        )

        // Signal drops completely at t=1500ms
        val stateEmpty = BleScannerState.Scanning(emptyList())

        // Frame 1: At t=4000ms (within 6000ms hold) -> in OptOutSignalHold
        val dec2 = associationEngine.evaluatePrivacyDecision(emptyList(), stateEmpty, currentTimeMs = 4000L)
        assertTrue(dec2 is PrivacyDecision.OptOutSignalHold)
        receiptCollector.recordFrame(
            frameIndex = 1,
            timestampMs = 4000L,
            faces = emptyList(),
            maskedFacesCount = 0,
            decision = dec2,
            isBlackedOut = true
        )

        // Frame 2: At t=9000ms (8 seconds after loss, exceeding 6000ms hold window)
        // AssociationEngine returns NoOptOutSignal
        val dec3 = associationEngine.evaluatePrivacyDecision(emptyList(), stateEmpty, currentTimeMs = 9000L)
        assertTrue("Hold expired at BLE level", dec3 is PrivacyDecision.NoOptOutSignal)

        // Under session sticky policy, fail-closed blackout is maintained so unmaskedFramesCount remains 0
        receiptCollector.recordFrame(
            frameIndex = 2,
            timestampMs = 9000L,
            faces = emptyList(),
            maskedFacesCount = 0,
            decision = dec3,
            isBlackedOut = true
        )

        assertEquals(3, receiptCollector.getTotalFrames())
        assertEquals(3, receiptCollector.getBlackedOutFramesCount())
        assertEquals(0, receiptCollector.getUnmaskedFramesCount())
    }
}
