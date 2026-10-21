package com.bluright.app.privacy

import com.bluright.app.ble.BleObservation

/**
 * Result of the privacy evaluation engine combining real-time camera face detections
 * and observed BLE opt-out signals.
 *
 * IMPORTANT PRIVACY ARCHITECTURE PRINCIPLE:
 * Radio frequency RSSI cannot reliably determine physical identity or line-of-sight.
 * Whenever association between an opt-out signal and a visible face is ambiguous,
 * Bluright applies a CONSERVATIVE FAIL-SAFE: all visible faces are masked rather than
 * guessing or leaving a face unmasked.
 */
sealed interface PrivacyDecision {

    /**
     * No compatible BLE opt-out beacons detected in the vicinity.
     * All camera faces are processed under standard camera privacy rules.
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
     * Association Supported: Exactly one face present in camera AND exactly one strong beacon
     * in immediate proximity (RSSI > -60 dBm).
     *
     * Note: Even in this state, Bluright explicitly logs that physical identity matching
     * is an unverified proximity heuristic.
     */
    data class AssociationSupported(
        val faceId: Long,
        val tokenHex: String,
        val rssi: Int,
        val confidenceScore: Float,
        val disclaimers: List<String>
    ) : PrivacyDecision

    /**
     * Bluetooth is disabled, permissions are missing, or hardware BLE is unsupported.
     * Face masking pipeline remains fully active and safe.
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
     */
    val requiresMaskingAllFaces: Boolean
        get() = when (this) {
            is AmbiguousConservativeMask -> true
            is NoOptOutSignal -> true // By default Bluright masks all detected faces
            is OptOutDetectedUnverified -> true
            is AssociationSupported -> true
            is OptOutSignalHold -> true
            is BleUnavailable -> true
        }

    /**
     * Whether an opt-out signal or active protection hold is currently active.
     */
    val isOptOutActive: Boolean
        get() = when (this) {
            is OptOutDetectedUnverified -> true
            is AmbiguousConservativeMask -> true
            is AssociationSupported -> true
            is OptOutSignalHold -> true
            is BleUnavailable -> false
            is NoOptOutSignal -> false
        }

    /**
     * Whether fail-closed policy requires a full-frame blackout if zero faces are detected.
     */
    val shouldApplyFailClosedBlackoutIfNoFaces: Boolean
        get() = when (this) {
            is OptOutDetectedUnverified -> true
            is OptOutSignalHold -> true
            is AmbiguousConservativeMask -> true
            else -> false
        }
}
