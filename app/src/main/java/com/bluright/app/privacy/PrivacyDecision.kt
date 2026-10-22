package com.bluright.app.privacy

import com.bluright.app.ble.BleObservation
import com.bluright.app.model.DetectedFace
import com.bluright.app.model.PrivacyMode

/**
 * Result of the privacy evaluation engine combining real-time camera face detections
 * and observed BLE opt-out signals.
 *
 * IMPORTANT PRIVACY ARCHITECTURE PRINCIPLE:
 * Radio frequency RSSI cannot reliably determine physical identity or line-of-sight.
 * Whenever association between an opt-out signal and a visible face is ambiguous,
 * Bluright applies a CONSERVATIVE FAIL-SAFE: all visible faces are masked rather than
 * guessing or leaving a face unmasked.
 *
 * However, when NO opt-out beacon is detected and no opt-out beacon was observed in the session,
 * ordinary face detection does not require privacy masking unless the user explicitly enables
 * all-faces privacy mode.
 */
sealed interface PrivacyDecision {

    /**
     * No compatible BLE opt-out beacons detected in the vicinity.
     * All camera faces are processed under standard camera privacy rules (unmasked unless all-faces mode is active).
     */
    data class NoOptOutSignal(
        val visibleFacesCount: Int
    ) : PrivacyDecision

    /**
     * One or more opt-out beacons detected nearby, but zero faces are visible in the viewfinder.
     * (E.g. Person is behind the camera operator, in another room, or outside the camera frame).
     */
    data class OptOutDetectedUnverified(
        val activeObservations: List<BleObservation>,
        val strongestRssi: Int
    ) : PrivacyDecision

    /**
     * Ambiguous Association: Multiple faces in frame, multiple beacons nearby, or weak/noisy RSSI.
     * CONSERVATIVE FAIL-SAFE ACTIVE: All detected faces in the camera frame MUST be blurred!
     */
    data class AmbiguousConservativeMask(
        val reason: String,
        val visibleFacesCount: Int,
        val observationsCount: Int,
        val strongestRssi: Int
    ) : PrivacyDecision

    /**
     * Association Supported: One or more faces present in camera are reliably associated with
     * active opt-out beacons in immediate proximity (RSSI > -60 dBm).
     *
     * Note: Even in this state, Bluright explicitly logs that physical identity matching
     * is an unverified proximity heuristic.
     */
    data class AssociationSupported(
        val faceId: Long,
        val tokenHex: String,
        val rssi: Int,
        val confidenceScore: Float,
        val disclaimers: List<String>,
        val associatedFaceIds: Set<Long> = setOf(faceId)
    ) : PrivacyDecision {
        val isHighConfidence: Boolean get() = confidenceScore >= 0.70f
        val isMediumConfidence: Boolean get() = confidenceScore in 0.50f..0.699f
        val isLowConfidence: Boolean get() = confidenceScore < 0.50f
    }

    /**
     * Bluetooth is disabled, permissions are missing, or hardware BLE is unsupported.
     * When no opt-out was observed in the session, camera operates without masking.
     */
    data class BleUnavailable(
        val reason: String,
        val visibleFacesCount: Int
    ) : PrivacyDecision

    /**
     * Opt-Out Signal Hold / Hysteresis:
     * A nearby opt-out beacon was recently observed, but signal packets are temporarily
     * lost (e.g. rotating token, Bluetooth packet drop, or human body attenuation).
     *
     * FAIL-CLOSED SAFETY: Protection remains fully active during this hold window.
     * Prevents transient RF dips from silently downgrading to unmasked recording.
     */
    data class OptOutSignalHold(
        val elapsedSinceLossMs: Long,
        val lastObservedTokenHex: String?,
        val visibleFacesCount: Int
    ) : PrivacyDecision

    /**
     * Whether all visible faces must be blurred under this decision.
     * Under OPT_OUT_ONLY policy:
     * - Only ambiguous / uncertain association (AmbiguousConservativeMask) or conservative hold
     *   (OptOutSignalHold) mandates blanket masking of all visible faces.
     * - In the absence of an opt-out signal (NoOptOutSignal, BleUnavailable), detection alone never triggers blanket masking.
     * - In AssociationSupported, only the associated face is masked.
     */
    val requiresMaskingAllFaces: Boolean
        get() = when (this) {
            is AmbiguousConservativeMask -> true
            is OptOutSignalHold -> true
            is NoOptOutSignal -> false
            is OptOutDetectedUnverified -> false
            is AssociationSupported -> false
            is BleUnavailable -> false
        }

    /**
     * True if an opt-out beacon is currently transmitting or held in hysteresis.
     */
    val isOptOutActive: Boolean
        get() = when (this) {
            is OptOutDetectedUnverified -> true
            is AmbiguousConservativeMask -> true
            is AssociationSupported -> true
            is OptOutSignalHold -> true
            is NoOptOutSignal -> false
            is BleUnavailable -> false
        }

    /**
     * Under fail-closed policy, when an opt-out signal is active or held, but zero faces
     * are detected in the frame, full-frame blackout is required because visual coverage
     * cannot be guaranteed.
     */
    val shouldApplyFailClosedBlackoutIfNoFaces: Boolean
        get() = when (this) {
            is OptOutDetectedUnverified -> true
            is OptOutSignalHold -> true
            is AmbiguousConservativeMask -> true
            else -> false
        }

    /**
     * Determines whether a specific detected face must be masked under this privacy decision.
     * Delegates to [PrivacyPolicy.shouldMaskFace].
     */
    fun shouldMaskFace(
        face: DetectedFace,
        hasObservedOptOutInSession: Boolean = false,
        mode: PrivacyMode = PrivacyMode.OPT_OUT_ONLY
    ): Boolean = PrivacyPolicy.shouldMaskFace(face, this, hasObservedOptOutInSession, mode)

    /**
     * Resolves the exact subset of [faces] that must be masked given this [PrivacyDecision],
     * the active [PrivacyMode] (defaulting to [PrivacyMode.OPT_OUT_ONLY]), and whether an opt-out beacon
     * was previously observed in the active recording session ([hasSessionStickyOptOut]).
     */
    fun resolveFacesToMask(
        faces: List<DetectedFace>,
        mode: PrivacyMode = PrivacyMode.OPT_OUT_ONLY,
        hasSessionStickyOptOut: Boolean = false
    ): List<DetectedFace> {
        if (faces.isEmpty()) return emptyList()
        return faces.filter { face ->
            PrivacyPolicy.shouldMaskFace(face, this, hasSessionStickyOptOut, mode)
        }
    }

    /**
     * Convenience overload for boolean all-faces mode toggle.
     */
    fun resolveFacesToMask(
        faces: List<DetectedFace>,
        isAllFacesMode: Boolean,
        hasSessionStickyOptOut: Boolean = false
    ): List<DetectedFace> = resolveFacesToMask(
        faces = faces,
        mode = if (isAllFacesMode) PrivacyMode.ALL_FACES else PrivacyMode.OPT_OUT_ONLY,
        hasSessionStickyOptOut = hasSessionStickyOptOut
    )
}
