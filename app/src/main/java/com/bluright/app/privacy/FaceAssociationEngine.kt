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
    @Volatile
    private var lastOptOutObservedTimeMs: Long = 0L

    @Volatile
    private var lastObservedTokenHex: String? = null

    // Tracked associations between active opt-out token hexes and visual face IDs
    private val activeAssociations = mutableMapOf<String, Long>()

    @Synchronized
    fun resetHoldState() {
        lastOptOutObservedTimeMs = 0L
        lastObservedTokenHex = null
        activeAssociations.clear()
    }

    /**
     * Explicitly associates a visible face with the active opt-out beacon.
     * Allows the user or camera operator to tap a face on screen when multiple faces are present.
     */
    @Synchronized
    fun manuallyAssociateFace(faceId: Long, tokenHex: String? = null) {
        val targetToken = tokenHex ?: lastObservedTokenHex ?: MANUAL_BEACON_KEY
        activeAssociations[targetToken] = faceId
    }

    @Synchronized
    fun getAssociatedFaceIds(): Set<Long> = activeAssociations.values.toSet()

    /**
     * Synthesizes current face detections and BLE observations into a deterministic PrivacyDecision.
     */
    @Synchronized
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

        // If a manual association was queued before beacon observation, bind it now
        val pendingManual = activeAssociations.remove(MANUAL_BEACON_KEY)
        if (pendingManual != null) {
            activeAssociations[strongestBeacon.tokenHex] = pendingManual
        }

        // 3. Opt-out beacon detected, but camera sees zero faces
        if (faces.isEmpty()) {
            return PrivacyDecision.OptOutDetectedUnverified(
                activeObservations = observations,
                strongestRssi = strongestBeacon.rssi
            )
        }

        // Prune tracked associations: retain only those where token remains active
        val currentTokenHexes = observations.map { it.tokenHex }.toSet()
        activeAssociations.entries.retainAll { (token, _) ->
            token in currentTokenHexes
        }

        // 4. Single-Face Association Evaluation
        if (faces.size == 1) {
            val singleFace = faces.first()
            if (observations.size == 1) {
                val singleBeacon = observations.first()
                val isStrongProximity = singleBeacon.rssi >= BleOptOutConstants.RSSI_IMMEDIATE_THRESHOLD
                val confidence = calculateConfidence(singleBeacon.rssi, singleFace.confidence)
                return if (isStrongProximity && confidence >= 0.50f) {
                    activeAssociations[singleBeacon.tokenHex] = singleFace.id
                    val disclaimers = listOf(
                        if (confidence >= 0.70f)
                            "High confidence heuristic proximity match (single target line-of-sight)"
                        else
                            "Medium confidence proximity match (likely face, proximity warning)",
                        "Radio multipath and obstruction can alter RSSI by ±15 dBm",
                        "Does not constitute legal or cryptographic identity proof"
                    )
                    PrivacyDecision.AssociationSupported(
                        faceId = singleFace.id,
                        tokenHex = singleBeacon.tokenHex,
                        rssi = singleBeacon.rssi,
                        confidenceScore = confidence,
                        disclaimers = disclaimers,
                        associatedFaceIds = setOf(singleFace.id)
                    )
                } else {
                    activeAssociations.remove(singleBeacon.tokenHex)
                    PrivacyDecision.AmbiguousConservativeMask(
                        reason = if (confidence < 0.50f) {
                            "Low association confidence (${(confidence * 100).toInt()}%). Safe mode active."
                        } else {
                            "Opt-out signal is distant or attenuated (${singleBeacon.rssi} dBm). Line-of-sight unconfirmed."
                        },
                        visibleFacesCount = 1,
                        observationsCount = 1,
                        strongestRssi = singleBeacon.rssi
                    )
                }
            } else {
                // Multiple beacons near single face -> High ambiguity
                return PrivacyDecision.AmbiguousConservativeMask(
                    reason = "Multiple opt-out beacons (${observations.size}) detected near single face.",
                    visibleFacesCount = 1,
                    observationsCount = observations.size,
                    strongestRssi = strongestBeacon.rssi
                )
            }
        }

        // 5. Multi-Face Scene: Evaluate if selective association is reliable
        val matchedFaceIds = observations.mapNotNull { obs ->
            activeAssociations[obs.tokenHex]
        }.filter { faceId -> faces.any { it.id == faceId } }.toSet()

        if (matchedFaceIds.isNotEmpty()) {
            val primaryFaceId = matchedFaceIds.first()
            val primaryFace = faces.first { it.id == primaryFaceId }
            val confidence = calculateConfidence(strongestBeacon.rssi, primaryFace.confidence)
            val disclaimers = if (confidence >= 0.70f) {
                listOf(
                    "High confidence tracked selective match: established line-of-sight maintained",
                    "Non-opted-out bystanders left unmasked under OPT_OUT_ONLY policy"
                )
            } else {
                listOf(
                    "Medium confidence tracked match: likely face",
                    "Warning: proximity estimate, keep subject in frame"
                )
            }
            return PrivacyDecision.AssociationSupported(
                faceId = primaryFaceId,
                tokenHex = strongestBeacon.tokenHex,
                rssi = strongestBeacon.rssi,
                confidenceScore = confidence,
                disclaimers = disclaimers,
                associatedFaceIds = matchedFaceIds
            )
        }

        // Check if one face is clearly dominant in foreground (proximity disparity heuristic)
        if (observations.size == 1 && strongestBeacon.rssi >= BleOptOutConstants.RSSI_IMMEDIATE_THRESHOLD) {
            val sortedByArea = faces.sortedByDescending { it.normWidth * it.normHeight }
            val largest = sortedByArea[0]
            val second = sortedByArea[1]
            val largestArea = largest.normWidth * largest.normHeight
            val secondArea = second.normWidth * second.normHeight

            if (largestArea >= 1.30f * secondArea) {
                val confidence = calculateConfidence(strongestBeacon.rssi, largest.confidence)
                if (confidence >= 0.50f) {
                    activeAssociations[strongestBeacon.tokenHex] = largest.id
                    val disclaimers = if (confidence >= 0.70f) {
                        listOf(
                            "High confidence foreground disparity match (dominant target)",
                            "Tap face on screen to adjust association if needed"
                        )
                    } else {
                        listOf(
                            "Medium confidence foreground match: likely face",
                            "Warning: proximity estimate, tap face to confirm"
                        )
                    }
                    return PrivacyDecision.AssociationSupported(
                        faceId = largest.id,
                        tokenHex = strongestBeacon.tokenHex,
                        rssi = strongestBeacon.rssi,
                        confidenceScore = confidence,
                        disclaimers = disclaimers,
                        associatedFaceIds = setOf(largest.id)
                    )
                }
            }
        }

        // 6. Ambiguous Association Fallback (Conservative Privacy Fail-Safe)
        val ambiguityReason = when {
            faces.size > 1 && observations.size > 1 ->
                "Multiple faces (${faces.size}) and multiple beacons (${observations.size}) present. Low association confidence. Safe mode active."
            faces.size > 1 ->
                "Multiple faces (${faces.size}) present with 1 opt-out beacon. Low association confidence. Safe mode active."
            else ->
                "Low association confidence. All faces masked under safe mode."
        }

        return PrivacyDecision.AmbiguousConservativeMask(
            reason = ambiguityReason,
            visibleFacesCount = faces.size,
            observationsCount = observations.size,
            strongestRssi = strongestBeacon.rssi
        )
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
        private const val MANUAL_BEACON_KEY = "MANUAL_BEACON"
    }
}
