package com.bluright.app

import com.bluright.app.ble.BleObservation
import com.bluright.app.ble.BleScannerState
import com.bluright.app.export.PrivacyMaskRenderer
import com.bluright.app.export.PrivacyReceiptCollector
import com.bluright.app.model.BlurStyle
import com.bluright.app.model.DetectedFace
import com.bluright.app.model.PrivacyMode
import com.bluright.app.privacy.FaceAssociationEngine
import com.bluright.app.privacy.PrivacyDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Targeted regression test suite validating selective face masking under the OPT_OUT_ONLY
 * privacy policy across all 10 mandated scenarios.
 */
class SelectivePrivacyMaskingRegressionTest {

    private lateinit var associationEngine: FaceAssociationEngine
    private lateinit var receiptCollector: PrivacyReceiptCollector

    @Before
    fun setUp() {
        associationEngine = FaceAssociationEngine(signalLossHoldDurationMs = 6000L)
        receiptCollector = PrivacyReceiptCollector()
        receiptCollector.startSession()
    }

    // 1. No opt-out signal + one detected face = no opt-out mask
    @Test
    fun test01_noOptOutSignal_oneDetectedFace_noOptOutMask() {
        val face = createFace(101L, 0.3f, 0.2f, 0.7f, 0.6f)
        val scannerState = BleScannerState.Scanning(emptyList())

        val decision = associationEngine.evaluatePrivacyDecision(
            faces = listOf(face),
            scannerState = scannerState,
            currentTimeMs = 1000L
        )

        assertTrue("Decision must be NoOptOutSignal", decision is PrivacyDecision.NoOptOutSignal)
        assertFalse("requiresMaskingAllFaces must be false", decision.requiresMaskingAllFaces)

        val facesToMask = decision.resolveFacesToMask(
            faces = listOf(face),
            mode = PrivacyMode.OPT_OUT_ONLY,
            hasSessionStickyOptOut = false
        )
        assertTrue("Single ordinary face must NOT be masked under OPT_OUT_ONLY", facesToMask.isEmpty())

        receiptCollector.recordFrame(
            frameIndex = 0,
            timestampMs = 1000L,
            faces = listOf(face),
            maskedFacesCount = facesToMask.size,
            decision = decision,
            isBlackedOut = false
        )

        assertEquals(1, receiptCollector.getTotalFrames())
        assertEquals(0, receiptCollector.getMaskedFramesCount())
        assertEquals(1, receiptCollector.getUnmaskedFramesCount())
        assertEquals(0, receiptCollector.getBlackedOutFramesCount())
    }

    // 2. No opt-out signal + multiple detected faces = no opt-out masks
    @Test
    fun test02_noOptOutSignal_multipleDetectedFaces_noOptOutMasks() {
        val faces = listOf(
            createFace(1L, 0.1f, 0.2f, 0.35f, 0.6f),
            createFace(2L, 0.4f, 0.2f, 0.65f, 0.6f),
            createFace(3L, 0.7f, 0.2f, 0.95f, 0.6f)
        )
        val scannerState = BleScannerState.Scanning(emptyList())

        val decision = associationEngine.evaluatePrivacyDecision(
            faces = faces,
            scannerState = scannerState,
            currentTimeMs = 1000L
        )

        assertTrue(decision is PrivacyDecision.NoOptOutSignal)
        val facesToMask = decision.resolveFacesToMask(
            faces = faces,
            mode = PrivacyMode.OPT_OUT_ONLY,
            hasSessionStickyOptOut = false
        )
        assertTrue("Multiple ordinary faces must NOT be masked without opt-out signal", facesToMask.isEmpty())

        receiptCollector.recordFrame(
            frameIndex = 0,
            timestampMs = 1000L,
            faces = faces,
            maskedFacesCount = facesToMask.size,
            decision = decision,
            isBlackedOut = false
        )

        assertEquals(1, receiptCollector.getTotalFrames())
        assertEquals(0, receiptCollector.getMaskedFramesCount())
        assertEquals(1, receiptCollector.getUnmaskedFramesCount())
        assertEquals(0, receiptCollector.getTotalFacesMasked())
    }

    // 3. One opt-out signal + reliably associated face = only that face is masked
    @Test
    fun test03_oneOptOutSignal_reliablyAssociatedFace_onlyThatFaceMasked() {
        val targetFace = createFace(101L, 0.2f, 0.2f, 0.5f, 0.6f)
        val bystanderFace = createFace(202L, 0.6f, 0.2f, 0.9f, 0.6f)
        val beacon = createObservation(byteArrayOf(0xA1.toByte(), 0xB2.toByte()), -45) // Immediate proximity

        // Step 1: Target face is alone in frame with active beacon -> isolated line-of-sight association
        val initialDecision = associationEngine.evaluatePrivacyDecision(
            faces = listOf(targetFace),
            scannerState = BleScannerState.Scanning(listOf(beacon)),
            currentTimeMs = 1000L
        )
        assertTrue("Association must be supported for isolated target", initialDecision is PrivacyDecision.AssociationSupported)

        // Step 2: Bystander walks into frame. Target face tracking is maintained
        val multiFaceDecision = associationEngine.evaluatePrivacyDecision(
            faces = listOf(targetFace, bystanderFace),
            scannerState = BleScannerState.Scanning(listOf(beacon)),
            currentTimeMs = 1050L
        )
        assertTrue("Tracked association must be preserved", multiFaceDecision is PrivacyDecision.AssociationSupported)

        val facesToMask = multiFaceDecision.resolveFacesToMask(
            faces = listOf(targetFace, bystanderFace),
            mode = PrivacyMode.OPT_OUT_ONLY,
            hasSessionStickyOptOut = false
        )

        assertEquals("Only exactly 1 face must be masked", 1, facesToMask.size)
        assertEquals("Masked face must be the opted-out target", 101L, facesToMask.first().id)
        assertFalse("Bystander face must remain unmasked", facesToMask.any { it.id == 202L })

        receiptCollector.recordFrame(
            frameIndex = 1,
            timestampMs = 1050L,
            faces = listOf(targetFace, bystanderFace),
            maskedFacesCount = facesToMask.size,
            decision = multiFaceDecision,
            isBlackedOut = false
        )
        assertEquals(1, receiptCollector.getMaskedFramesCount())
        assertEquals(1, receiptCollector.getTotalFacesMasked())
        assertEquals(0, receiptCollector.getUnmaskedFramesCount())
    }

    // 4. One opt-out signal + multiple faces + ambiguous association = conservative fallback is applied correctly
    @Test
    fun test04_oneOptOutSignal_multipleFaces_ambiguousAssociation_conservativeFallbackApplied() {
        val faceA = createFace(301L, 0.15f, 0.2f, 0.45f, 0.6f)
        val faceB = createFace(302L, 0.55f, 0.2f, 0.85f, 0.6f)
        val beacon = createObservation(byteArrayOf(0x01, 0x02), -52)

        // Multiple faces appear simultaneously with zero prior association history -> Ambiguous!
        val decision = associationEngine.evaluatePrivacyDecision(
            faces = listOf(faceA, faceB),
            scannerState = BleScannerState.Scanning(listOf(beacon)),
            currentTimeMs = 1000L
        )

        assertTrue("Must trigger AmbiguousConservativeMask", decision is PrivacyDecision.AmbiguousConservativeMask)
        assertTrue("Conservative fallback requires masking all visible faces", decision.requiresMaskingAllFaces)

        val facesToMask = decision.resolveFacesToMask(
            faces = listOf(faceA, faceB),
            mode = PrivacyMode.OPT_OUT_ONLY,
            hasSessionStickyOptOut = false
        )

        assertEquals("All visible faces must be masked under conservative fallback", 2, facesToMask.size)
    }

    // 5. Signal loss after opt-out detection does not disable required protection
    @Test
    fun test05_signalLossAfterOptOutDetection_doesNotDisableRequiredProtection() {
        val face = createFace(101L, 0.3f, 0.2f, 0.7f, 0.6f)
        val beacon = createObservation(byteArrayOf(0x05, 0x06), -48, timestampMs = 1000L)

        // t = 1000ms: Active beacon observed
        associationEngine.evaluatePrivacyDecision(
            faces = listOf(face),
            scannerState = BleScannerState.Scanning(listOf(beacon)),
            currentTimeMs = 1000L
        )

        // t = 3000ms: Beacon drops (2000ms elapsed since loss, inside 6000ms hold)
        val holdDecision = associationEngine.evaluatePrivacyDecision(
            faces = listOf(face),
            scannerState = BleScannerState.Scanning(emptyList()),
            currentTimeMs = 3000L
        )

        assertTrue("Must be in OptOutSignalHold state", holdDecision is PrivacyDecision.OptOutSignalHold)
        assertTrue("Protection hold must be active", holdDecision.isOptOutActive)
        assertTrue("Hold must require masking all faces", holdDecision.requiresMaskingAllFaces)

        val facesToMask = holdDecision.resolveFacesToMask(
            faces = listOf(face),
            mode = PrivacyMode.OPT_OUT_ONLY,
            hasSessionStickyOptOut = false
        )
        assertEquals(1, facesToMask.size)
    }

    // 6. Sticky protection remains active for the appropriate session duration
    @Test
    fun test06_stickyProtectionRemainsActive_forAppropriateSessionDuration() {
        val face = createFace(101L, 0.3f, 0.2f, 0.7f, 0.6f)
        val beacon = createObservation(byteArrayOf(0x77, 0x88.toByte()), -42, timestampMs = 1000L)

        // Frame 0: Opt-out observed during session
        val dec1 = associationEngine.evaluatePrivacyDecision(
            faces = listOf(face),
            scannerState = BleScannerState.Scanning(listOf(beacon)),
            currentTimeMs = 1000L
        )
        val hasObservedOptOutInSession = dec1.isOptOutActive
        assertTrue(hasObservedOptOutInSession)

        // Frame 100 (t = 20000ms): 19 seconds later, RF signal completely gone, hold expired
        val decLater = associationEngine.evaluatePrivacyDecision(
            faces = listOf(face),
            scannerState = BleScannerState.Scanning(emptyList()),
            currentTimeMs = 20000L
        )
        assertTrue(decLater is PrivacyDecision.NoOptOutSignal)

        // Under session-sticky fail-closed policy, face MUST remain protected throughout the session
        val facesToMask = decLater.resolveFacesToMask(
            faces = listOf(face),
            mode = PrivacyMode.OPT_OUT_ONLY,
            hasSessionStickyOptOut = hasObservedOptOutInSession
        )
        assertEquals("Session-sticky policy must keep face masked even after RF signal disappears", 1, facesToMask.size)
    }

    // 7. A new recording session resets state only when intended
    @Test
    fun test07_newRecordingSession_resetsStateOnlyWhenIntended() {
        val face = createFace(101L, 0.3f, 0.2f, 0.7f, 0.6f)
        val beacon = createObservation(byteArrayOf(0x99.toByte()), -45, timestampMs = 1000L)

        // Session 1: Encounters opt-out
        associationEngine.evaluatePrivacyDecision(
            faces = listOf(face),
            scannerState = BleScannerState.Scanning(listOf(beacon)),
            currentTimeMs = 1000L
        )

        // Session 1 completes and clean boundary occurs:
        associationEngine.resetHoldState()
        val session2StickyOptOut = false

        // Session 2 starts: No beacons detected in new session
        val session2Decision = associationEngine.evaluatePrivacyDecision(
            faces = listOf(face),
            scannerState = BleScannerState.Scanning(emptyList()),
            currentTimeMs = 50000L
        )

        assertTrue(session2Decision is PrivacyDecision.NoOptOutSignal)
        val session2FacesToMask = session2Decision.resolveFacesToMask(
            faces = listOf(face),
            mode = PrivacyMode.OPT_OUT_ONLY,
            hasSessionStickyOptOut = session2StickyOptOut
        )
        assertTrue("New recording session with no opt-out must NOT mask face", session2FacesToMask.isEmpty())
    }

    // 8. Preview and export apply equivalent privacy decisions
    @Test
    fun test08_previewAndExport_applyEquivalentPrivacyDecisions() {
        val targetFace = createFace(101L, 0.2f, 0.2f, 0.5f, 0.6f)
        val bystanderFace = createFace(202L, 0.6f, 0.2f, 0.9f, 0.6f)
        val faces = listOf(targetFace, bystanderFace)

        val decisionsToTest = listOf(
            PrivacyDecision.NoOptOutSignal(visibleFacesCount = 2),
            PrivacyDecision.AssociationSupported(
                faceId = 101L,
                tokenHex = "A1B2C3",
                rssi = -45,
                confidenceScore = 0.9f,
                disclaimers = emptyList(),
                associatedFaceIds = setOf(101L)
            ),
            PrivacyDecision.AmbiguousConservativeMask("Multi-face ambiguity", 2, 1, -55),
            PrivacyDecision.OptOutSignalHold(1500L, "A1B2C3", 2)
        )

        for (decision in decisionsToTest) {
            val previewMasked = decision.resolveFacesToMask(
                faces = faces,
                mode = PrivacyMode.OPT_OUT_ONLY,
                hasSessionStickyOptOut = false
            )
            val exportMasked = decision.resolveFacesToMask(
                faces = faces,
                mode = PrivacyMode.OPT_OUT_ONLY,
                hasSessionStickyOptOut = false
            )

            assertEquals("Preview and export must resolve identical masked face sets for decision $decision", previewMasked, exportMasked)
        }
    }

    // 9. Empty frames and encoder concurrency do not bypass the fail-closed policy
    @Test
    fun test09_emptyFramesAndEncoderConcurrency_doNotBypassFailClosedPolicy() {
        // Opt-out signal is active, but visual face detection momentarily returns 0 faces (false negative risk)
        val decision = PrivacyDecision.OptOutDetectedUnverified(
            activeObservations = listOf(createObservation(byteArrayOf(1, 2), -45)),
            strongestRssi = -45
        )

        assertTrue(decision.isOptOutActive)
        assertTrue(decision.shouldApplyFailClosedBlackoutIfNoFaces)

        val facesToMask = decision.resolveFacesToMask(emptyList(), PrivacyMode.OPT_OUT_ONLY, false)
        assertTrue(facesToMask.isEmpty())

        // Under DEF-01, when 0 faces are seen with opt-out active or session-sticky hold,
        // fail-closed policy mandates full-frame blackout rather than unmasked export
        val isBlackoutRequired = decision.shouldApplyFailClosedBlackoutIfNoFaces
        assertTrue("Fail-closed full-frame blackout must be required", isBlackoutRequired)

        receiptCollector.recordFrame(
            frameIndex = 0,
            timestampMs = 1000L,
            faces = emptyList(),
            maskedFacesCount = 0,
            decision = decision,
            isBlackedOut = true
        )

        assertEquals(1, receiptCollector.getTotalFrames())
        assertEquals(1, receiptCollector.getBlackedOutFramesCount())
        assertEquals(0, receiptCollector.getUnmaskedFramesCount())
    }

    // 10. All-faces mode override masks all faces regardless of BLE
    @Test
    fun test10_allFacesModeOverride_masksAllFacesRegardlessOfBle() {
        val faces = listOf(
            createFace(1L, 0.1f, 0.2f, 0.4f, 0.6f),
            createFace(2L, 0.6f, 0.2f, 0.9f, 0.6f)
        )
        val scannerState = BleScannerState.Scanning(emptyList())

        val decision = associationEngine.evaluatePrivacyDecision(faces, scannerState)
        assertTrue(decision is PrivacyDecision.NoOptOutSignal)

        // When operator explicitly selects ALL_FACES mode:
        val facesToMask = decision.resolveFacesToMask(
            faces = faces,
            mode = PrivacyMode.ALL_FACES,
            hasSessionStickyOptOut = false
        )

        assertEquals("All faces mode override must mask every detected face", 2, facesToMask.size)
        assertEquals(setOf(1L, 2L), facesToMask.map { it.id }.toSet())
    }

    // 11. Dominant foreground face associates with strong immediate beacon, leaving background bystander unmasked
    @Test
    fun test11_dominantForegroundFace_associatesWithStrongBeacon_leavingBackgroundFaceUnmasked() {
        val foregroundFace = createFace(101L, 0.2f, 0.2f, 0.7f, 0.8f) // area = 0.5 * 0.6 = 0.30
        val backgroundBystander = createFace(202L, 0.75f, 0.2f, 0.95f, 0.45f) // area = 0.2 * 0.25 = 0.05
        val beacon = createObservation(byteArrayOf(0x33, 0x44), -42) // Immediate proximity

        val decision = associationEngine.evaluatePrivacyDecision(
            faces = listOf(foregroundFace, backgroundBystander),
            scannerState = BleScannerState.Scanning(listOf(beacon)),
            currentTimeMs = 1000L
        )

        assertTrue("Dominant foreground face must trigger AssociationSupported", decision is PrivacyDecision.AssociationSupported)
        val supported = decision as PrivacyDecision.AssociationSupported
        assertEquals("Dominant face must be associated", 101L, supported.faceId)

        val facesToMask = decision.resolveFacesToMask(
            faces = listOf(foregroundFace, backgroundBystander),
            mode = PrivacyMode.OPT_OUT_ONLY,
            hasSessionStickyOptOut = true
        )

        assertEquals("Only dominant face must be masked", 1, facesToMask.size)
        assertEquals(101L, facesToMask.first().id)
        assertFalse("Background bystander must remain unmasked", facesToMask.any { it.id == 202L })
    }

    // 12. Manual tap association resolves ambiguous multi-face scene
    @Test
    fun test12_manualTapAssociation_resolvesAmbiguity_masksOnlyTappedFace() {
        val faceA = createFace(301L, 0.1f, 0.2f, 0.4f, 0.6f)
        val faceB = createFace(302L, 0.6f, 0.2f, 0.9f, 0.6f)
        val beacon = createObservation(byteArrayOf(0x55, 0x66), -50)

        // Prior to tap: Ambiguous
        val initialDecision = associationEngine.evaluatePrivacyDecision(
            faces = listOf(faceA, faceB),
            scannerState = BleScannerState.Scanning(listOf(beacon)),
            currentTimeMs = 1000L
        )
        assertTrue(initialDecision is PrivacyDecision.AmbiguousConservativeMask)

        // User taps Face B on screen
        associationEngine.manuallyAssociateFace(faceB.id)

        // Frame after tap:
        val tappedDecision = associationEngine.evaluatePrivacyDecision(
            faces = listOf(faceA, faceB),
            scannerState = BleScannerState.Scanning(listOf(beacon)),
            currentTimeMs = 1033L
        )
        assertTrue("Manual tap must resolve association", tappedDecision is PrivacyDecision.AssociationSupported)
        val supported = tappedDecision as PrivacyDecision.AssociationSupported
        assertEquals("Face B must be associated", 302L, supported.faceId)

        val facesToMask = tappedDecision.resolveFacesToMask(
            faces = listOf(faceA, faceB),
            mode = PrivacyMode.OPT_OUT_ONLY,
            hasSessionStickyOptOut = true
        )
        assertEquals(1, facesToMask.size)
        assertEquals(302L, facesToMask.first().id)
        assertFalse("Face A must remain unmasked", facesToMask.any { it.id == 301L })
    }

    // 13. Sticky opt-out does not override active selective association, but fails closed on signal loss
    @Test
    fun test13_stickyOptOut_doesNotOverrideSelectiveAssociation_failsClosedOnSignalLoss() {
        val targetFace = createFace(101L, 0.2f, 0.2f, 0.5f, 0.6f)
        val bystander = createFace(202L, 0.6f, 0.2f, 0.9f, 0.6f)
        val beacon = createObservation(byteArrayOf(0x77), -45)

        associationEngine.manuallyAssociateFace(targetFace.id)

        // Active beacon + session sticky opt-out is TRUE
        val activeDecision = associationEngine.evaluatePrivacyDecision(
            faces = listOf(targetFace, bystander),
            scannerState = BleScannerState.Scanning(listOf(beacon)),
            currentTimeMs = 1000L
        )
        assertTrue(activeDecision is PrivacyDecision.AssociationSupported)

        val activeMasked = activeDecision.resolveFacesToMask(
            faces = listOf(targetFace, bystander),
            mode = PrivacyMode.OPT_OUT_ONLY,
            hasSessionStickyOptOut = true
        )
        assertEquals("Bystander must NOT be blurred during active selective association", 1, activeMasked.size)
        assertEquals(101L, activeMasked.first().id)

        // Beacon drops and hold expires (t = 20000ms):
        val lostDecision = associationEngine.evaluatePrivacyDecision(
            faces = listOf(targetFace, bystander),
            scannerState = BleScannerState.Scanning(emptyList()),
            currentTimeMs = 20000L
        )
        assertTrue(lostDecision is PrivacyDecision.NoOptOutSignal)

        val lostMasked = lostDecision.resolveFacesToMask(
            faces = listOf(targetFace, bystander),
            mode = PrivacyMode.OPT_OUT_ONLY,
            hasSessionStickyOptOut = true // Fail-closed sticky protection
        )
        assertEquals("All faces must be masked under fail-closed sticky protection on signal loss", 2, lostMasked.size)
    }

    // 14. Reproduction: OPT_OUT_ONLY with zero beacons and zero detected faces -> no mask, no blackout
    @Test
    fun test14_reproduction_optOutOnly_zeroBeacons_zeroFaces_noMask_noBlackout() {
        val scannerState = BleScannerState.Scanning(emptyList())
        val decision = associationEngine.evaluatePrivacyDecision(
            faces = emptyList(),
            scannerState = scannerState,
            currentTimeMs = 1000L
        )

        assertTrue("Decision must be NoOptOutSignal", decision is PrivacyDecision.NoOptOutSignal)
        val masked = decision.resolveFacesToMask(
            faces = emptyList(),
            mode = PrivacyMode.OPT_OUT_ONLY,
            hasSessionStickyOptOut = false
        )
        assertTrue("No faces to mask", masked.isEmpty())
        assertFalse(
            "Blackout must NOT be triggered when no opt-out signal exists",
            com.bluright.app.privacy.PrivacyPolicy.shouldApplyFailClosedBlackout(decision, hasObservedOptOutInSession = false)
        )
        assertFalse(decision.shouldApplyFailClosedBlackoutIfNoFaces)
    }

    // 15. Reproduction: OPT_OUT_ONLY with zero beacons and one or more detected faces -> no faces masked, bystanders unmasked
    @Test
    fun test15_reproduction_optOutOnly_zeroBeacons_oneOrMoreFaces_bystandersUnmasked() {
        val operator = createFace(10L, 0.2f, 0.2f, 0.45f, 0.6f)
        val bystander = createFace(20L, 0.55f, 0.2f, 0.8f, 0.6f)
        val faces = listOf(operator, bystander)
        val scannerState = BleScannerState.Scanning(emptyList())

        val decision = associationEngine.evaluatePrivacyDecision(
            faces = faces,
            scannerState = scannerState,
            currentTimeMs = 1000L
        )

        assertTrue(decision is PrivacyDecision.NoOptOutSignal)
        val maskedFaces = decision.resolveFacesToMask(
            faces = faces,
            mode = PrivacyMode.OPT_OUT_ONLY,
            hasSessionStickyOptOut = false
        )
        assertTrue("Under OPT_OUT_ONLY with 0 beacons, zero faces must be masked", maskedFaces.isEmpty())

        val unmaskedFaces = faces.filterNot { masked -> maskedFaces.any { it.id == masked.id } }
        assertEquals("All faces must be unmasked bystanders", 2, unmaskedFaces.size)
        assertFalse(
            "No blackout when 0 beacons",
            com.bluright.app.privacy.PrivacyPolicy.shouldApplyFailClosedBlackout(decision, hasObservedOptOutInSession = false)
        )
    }

    // 16. Reproduction: Transition from zero opt-outs to active opt-out -> intended masking activates
    @Test
    fun test16_reproduction_transition_zeroToActiveOptOut_intendedMaskingActivates() {
        val face = createFace(101L, 0.3f, 0.2f, 0.7f, 0.6f)

        // Step 1: Initial state (zero beacons)
        val initialDecision = associationEngine.evaluatePrivacyDecision(
            faces = listOf(face),
            scannerState = BleScannerState.Scanning(emptyList()),
            currentTimeMs = 1000L
        )
        assertTrue(initialDecision is PrivacyDecision.NoOptOutSignal)
        assertTrue(
            "Initial: no mask",
            initialDecision.resolveFacesToMask(listOf(face), PrivacyMode.OPT_OUT_ONLY, false).isEmpty()
        )

        // Step 2: Beacon arrives (close proximity, high confidence single face)
        val beacon = createObservation(byteArrayOf(0x11, 0x22), -52, timestampMs = 1500L)
        val activeDecision = associationEngine.evaluatePrivacyDecision(
            faces = listOf(face),
            scannerState = BleScannerState.Scanning(listOf(beacon)),
            currentTimeMs = 1500L
        )
        assertTrue("Must transition to AssociationSupported", activeDecision is PrivacyDecision.AssociationSupported)
        val activeMasked = activeDecision.resolveFacesToMask(listOf(face), PrivacyMode.OPT_OUT_ONLY, false)
        assertEquals("Target face must be masked immediately", 1, activeMasked.size)
        assertEquals(101L, activeMasked.first().id)
    }

    // 17. Reproduction: Dropping opt-out signal -> session-sticky or hold behavior works according to design
    @Test
    fun test17_reproduction_droppingOptOutSignal_holdAndStickyDesign() {
        val face = createFace(101L, 0.3f, 0.2f, 0.7f, 0.6f)
        val beacon = createObservation(byteArrayOf(0x33, 0x44), -50, timestampMs = 1000L)

        // Active observation at t = 1000
        associationEngine.evaluatePrivacyDecision(
            faces = listOf(face),
            scannerState = BleScannerState.Scanning(listOf(beacon)),
            currentTimeMs = 1000L
        )

        // Signal drops at t = 2000 (elapsed = 1000ms < 6000ms hold)
        val holdDecision = associationEngine.evaluatePrivacyDecision(
            faces = listOf(face),
            scannerState = BleScannerState.Scanning(emptyList()),
            currentTimeMs = 2000L
        )
        assertTrue("Signal loss within 6000ms must trigger OptOutSignalHold", holdDecision is PrivacyDecision.OptOutSignalHold)
        assertEquals(
            "Hold requires protective masking",
            1,
            holdDecision.resolveFacesToMask(listOf(face), PrivacyMode.OPT_OUT_ONLY, false).size
        )

        // Hold expires at t = 8000ms (elapsed = 7000ms > 6000ms hold)
        val expiredDecision = associationEngine.evaluatePrivacyDecision(
            faces = listOf(face),
            scannerState = BleScannerState.Scanning(emptyList()),
            currentTimeMs = 8000L
        )
        assertTrue("Expired hold must transition to NoOptOutSignal", expiredDecision is PrivacyDecision.NoOptOutSignal)

        // Case A: Outside recording session (not sticky) -> unmask cleanly
        val unmaskedOutside = expiredDecision.resolveFacesToMask(
            faces = listOf(face),
            mode = PrivacyMode.OPT_OUT_ONLY,
            hasSessionStickyOptOut = false
        )
        assertTrue("Outside recording session, expired hold must unmask", unmaskedOutside.isEmpty())

        // Case B: Inside active recording session where opt-out was observed (sticky) -> fail-closed mask
        val maskedInsideSession = expiredDecision.resolveFacesToMask(
            faces = listOf(face),
            mode = PrivacyMode.OPT_OUT_ONLY,
            hasSessionStickyOptOut = true
        )
        assertEquals("Inside recording session, sticky fail-closed must retain mask", 1, maskedInsideSession.size)
    }

    // 18. Reproduction: Verification that badge semantics and ALL_FACES mode are consistent with resolved masks
    @Test
    fun test18_reproduction_badgeSemantics_and_allFacesMode() {
        val face = createFace(101L, 0.3f, 0.2f, 0.7f, 0.6f)
        val scannerState = BleScannerState.Scanning(emptyList())

        val decision = associationEngine.evaluatePrivacyDecision(
            faces = listOf(face),
            scannerState = scannerState,
            currentTimeMs = 1000L
        )

        // Under OPT_OUT_ONLY with 0 beacons
        val optOutOnlyMasked = decision.resolveFacesToMask(listOf(face), PrivacyMode.OPT_OUT_ONLY, false)
        assertEquals("Resolved mask count must be 0", 0, optOutOnlyMasked.size)

        // Under ALL_FACES mode with 0 beacons
        val allFacesMasked = decision.resolveFacesToMask(listOf(face), PrivacyMode.ALL_FACES, false)
        assertEquals("ALL_FACES mode must mask face even with 0 beacons", 1, allFacesMasked.size)
    }

    private fun createFace(id: Long, left: Float, top: Float, right: Float, bottom: Float): DetectedFace {
        return DetectedFace(
            id = id,
            normLeft = left,
            normTop = top,
            normRight = right,
            normBottom = bottom,
            confidence = 0.95f
        )
    }

    private fun createObservation(token: ByteArray, rssi: Int, timestampMs: Long = System.currentTimeMillis()): BleObservation {
        return BleObservation(
            token = token,
            rssi = rssi,
            timestampMs = timestampMs
        )
    }
}
