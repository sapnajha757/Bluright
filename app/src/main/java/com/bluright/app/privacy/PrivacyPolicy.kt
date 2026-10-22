package com.bluright.app.privacy

import com.bluright.app.model.DetectedFace
import com.bluright.app.model.PrivacyMode

/**
 * Unified privacy policy evaluation used consistently by both the live camera preview
 * (FaceBlurOverlay) and recorded video export (PrivacyExportCoordinator).
 *
 * Enforces OPT_OUT_ONLY baseline with session-sticky fail-safe conservative masking:
 * - Detecting a face alone never triggers masking when no opt-out has been observed.
 * - An active, trustworthy opt-out association masks only the target face.
 * - Ambiguous associations or conservative holds mask all detected faces.
 * - Once an opt-out signal has been observed in an active recording session, protective
 *   masking is maintained across signal drops, hold expirations, and BLE transport disruptions.
 * - Explicit session reset clears session-sticky protection safely.
 */
object PrivacyPolicy {

    /**
     * Determines whether a specific detected face must be masked.
     *
     * @param face The detected face to check.
     * @param decision The current real-time privacy decision from FaceAssociationEngine.
     * @param hasObservedOptOutInSession Whether an opt-out signal or active hold was observed
     *                                   in the current active recording session.
     */
    fun shouldMaskFace(
        face: DetectedFace,
        decision: PrivacyDecision,
        hasObservedOptOutInSession: Boolean = false,
        mode: PrivacyMode = PrivacyMode.OPT_OUT_ONLY
    ): Boolean {
        if (mode == PrivacyMode.ALL_FACES) {
            return true
        }

        // Requirement D: If an opt-out was observed earlier in the active recording session,
        // but current decision lacks an active, trustworthy association (e.g. signal loss
        // after the 6000ms hold expired or BLE became unavailable), preserve protective masking
        // for all detected faces rather than unmasking them.
        if (hasObservedOptOutInSession && !decision.isOptOutActive) {
            return true
        }

        return when (decision) {
            // Requirement C: Ambiguous association or conservative hold -> mask all faces
            is PrivacyDecision.AmbiguousConservativeMask -> true
            is PrivacyDecision.OptOutSignalHold -> true

            // Requirement B: Trustworthy association -> mask only the associated face(s)
            is PrivacyDecision.AssociationSupported -> (face.id == decision.faceId || face.id in decision.associatedFaceIds)

            // OptOutDetectedUnverified with a detected face -> mask conservatively
            is PrivacyDecision.OptOutDetectedUnverified -> true

            // Requirement A: No opt-out observed in session and no active signal -> do not mask
            is PrivacyDecision.NoOptOutSignal -> false
            is PrivacyDecision.BleUnavailable -> false
        }
    }

    /**
     * Determines whether full-frame blackout must be applied when zero faces are detected.
     *
     * Under fail-closed policy, when an opt-out signal is active or has been observed in the session,
     * but zero faces are detected in the camera frame, full-frame blackout is applied because visual
     * coverage cannot be verified.
     */
    fun shouldApplyFailClosedBlackout(
        decision: PrivacyDecision,
        hasObservedOptOutInSession: Boolean = false
    ): Boolean {
        return decision.shouldApplyFailClosedBlackoutIfNoFaces || hasObservedOptOutInSession
    }
}
