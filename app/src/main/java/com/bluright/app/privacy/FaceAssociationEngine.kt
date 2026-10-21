package com.bluright.app.privacy

import com.bluright.app.ble.BleObservation
import com.bluright.app.ble.BleOptOutConstants
import com.bluright.app.ble.BleScannerState
import com.bluright.app.model.DetectedFace

/**
 * Evaluates proximity correlation between detected visual faces and observed BLE opt-out signals.
 *
 * CRITICAL SCIENTIFIC & PRIVACY LIMITATIONS:
 * 1. RSSI measures radio power, NOT physical distance or line-of-sight direction.
 * 2. Human bodies attenuate 2.4 GHz radio signals by 10-20 dB.
 * 3. Walls and reflections cause multipath constructive and destructive interference.
 * 4. Two people standing together cannot be differentiated by RSSI alone.
 *
 * CONSERVATIVE PRIVACY PROTOCOL:
 * Whenever an opt-out signal cannot be associated with 100% certainty with a specific face,
 * Bluright falls back to conservative masking: ALL visible faces are masked.
 */
class FaceAssociationEngine(
    private val signalLossHoldDurationMs: Long = DEFAULT_SIGNAL_LOSS_HOLD_MS
) {
    private var lastOptOutObservedTimeMs: Long = 0L
    private var lastObservedTokenHex: String? = null

    fun resetHoldState() {
        lastOptOutObservedTimeMs = 0L
        lastObservedTokenHex = null
    }

    /**
     * Synthesizes current face detections and BLE observations into a deterministic PrivacyDecision.
     */
    fun evaluatePrivacyDecision(
        faces: List<DetectedFace>,
        scannerState: BleScannerState,
        currentTimeMs: Long = System.currentTimeMillis()
    ): PrivacyDecision {
        // 1. Check BLE availability
        when (scannerState) {
            is BleScannerState.BluetoothDisabled -> {
                // If an opt-out was recently active, maintain fail-safe protection
                val elapsedSinceLoss = currentTimeMs - lastOptOutObservedTimeMs
                if (lastOptOutObservedTimeMs > 0L && elapsedSinceLoss < signalLossHoldDurationMs) {
                    return PrivacyDecision.OptOutSignalHold(
                        elapsedSinceLossMs = elapsedSinceLoss,
                        lastObservedTokenHex = lastObservedTokenHex,
                        visibleFacesCount = faces.size
                    )
                }
                return PrivacyDecision.BleUnavailable(
                    reason = "Bluetooth is turned off",
                    visibleFacesCount = faces.size
                )
            }
            is BleScannerState.PermissionDenied -> {
                return PrivacyDecision.BleUnavailable(
                    reason = "Nearby device permissions not granted",
                    visibleFacesCount = faces.size
                )
            }
            is BleScannerState.Error -> {
                return PrivacyDecision.BleUnavailable(
                    reason = scannerState.message,
                    visibleFacesCount = faces.size
                )
            }
            is BleScannerState.Idle -> {
                return PrivacyDecision.NoOptOutSignal(visibleFacesCount = faces.size)
            }
            is BleScannerState.Scanning -> {
                // Proceed to analyze observations
            }
        }

        val observations = (scannerState as BleScannerState.Scanning).observations
            .filter { !it.isStale(currentTimeMs) }

        // 2. Check for signal loss / token rotation hysteresis
        if (observations.isEmpty()) {
            val elapsedSinceLoss = currentTimeMs - lastOptOutObservedTimeMs
            if (lastOptOutObservedTimeMs > 0L && elapsedSinceLoss < signalLossHoldDurationMs) {
                return PrivacyDecision.OptOutSignalHold(
                    elapsedSinceLossMs = elapsedSinceLoss,
                    lastObservedTokenHex = lastObservedTokenHex,
                    visibleFacesCount = faces.size
                )
            }
            return PrivacyDecision.NoOptOutSignal(visibleFacesCount = faces.size)
        }

        val strongestBeacon = observations.maxByOrNull { it.rssi }!!
        lastOptOutObservedTimeMs = currentTimeMs
        lastObservedTokenHex = strongestBeacon.tokenHex

        // 3. Opt-out beacon detected, but camera sees zero faces
        if (faces.isEmpty()) {
            return PrivacyDecision.OptOutDetectedUnverified(
                activeObservations = observations,
                strongestRssi = strongestBeacon.rssi
            )
        }

        // 4. Multiple faces OR multiple beacons present -> High Ambiguity
        // Fail-safe conservative protocol: Blur ALL visible faces
        if (faces.size > 1 || observations.size > 1) {
            val ambiguityReason = when {
                faces.size > 1 && observations.size > 1 ->
                    "Multiple faces (${faces.size}) and multiple beacons (${observations.size}) present. Spatial ambiguity."
                faces.size > 1 ->
                    "Multiple faces (${faces.size}) present with 1 opt-out beacon. Cannot discern which individual opted out."
                else ->
                    "Multiple opt-out beacons (${observations.size}) detected near single face."
            }

            return PrivacyDecision.AmbiguousConservativeMask(
                reason = ambiguityReason,
                visibleFacesCount = faces.size,
                observationsCount = observations.size,
                strongestRssi = strongestBeacon.rssi
            )
        }

        // 5. Exactly 1 face and 1 beacon: Evaluate signal strength
        val singleFace = faces.first()
        val isStrongProximity = strongestBeacon.rssi >= BleOptOutConstants.RSSI_IMMEDIATE_THRESHOLD

        return if (isStrongProximity) {
            // Signal is strong (< 1.5m estimated) and only one person is in view
            PrivacyDecision.AssociationSupported(
                faceId = singleFace.id,
                tokenHex = strongestBeacon.tokenHex,
                rssi = strongestBeacon.rssi,
                confidenceScore = calculateConfidence(strongestBeacon.rssi, singleFace.confidence),
                disclaimers = listOf(
                    "Heuristic proximity match (single target line-of-sight)",
                    "Radio multipath and obstruction can alter RSSI by ±15 dBm",
                    "Does not constitute legal or cryptographic identity proof"
                )
            )
        } else {
            // Weak signal (-65 dBm to -90 dBm): Might be someone in adjacent room or behind camera
            PrivacyDecision.AmbiguousConservativeMask(
                reason = "Opt-out signal is distant or attenuated (${strongestBeacon.rssi} dBm). Line-of-sight unconfirmed.",
                visibleFacesCount = 1,
                observationsCount = 1,
                strongestRssi = strongestBeacon.rssi
            )
        }
    }

    private fun calculateConfidence(rssi: Int, faceDetectionConfidence: Float): Float {
        // Map RSSI [-60, -35] into [0.6, 0.9]
        val clampedRssi = rssi.coerceIn(-90, -35)
        val rssiFactor = (clampedRssi + 90f) / 55f // 0.0 to 1.0
        val baseScore = 0.5f + (rssiFactor * 0.4f)
        return (baseScore * faceDetectionConfidence).coerceIn(0.1f, 0.95f)
    }

    companion object {
        const val DEFAULT_SIGNAL_LOSS_HOLD_MS = 6000L
    }
}
