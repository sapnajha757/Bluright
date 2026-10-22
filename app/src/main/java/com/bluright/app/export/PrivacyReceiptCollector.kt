package com.bluright.app.export

import com.bluright.app.audit.MaskingQualityAuditor
import com.bluright.app.audit.MaskingAuditStatus
import com.bluright.app.model.DetectedFace
import com.bluright.app.privacy.PrivacyDecision
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An individual audit record captured for a single video frame.
 */
data class FrameAuditReceipt(
    val frameIndex: Int,
    val timestampMs: Long,
    val facesDetectedCount: Int,
    val facesMaskedCount: Int,
    val isBlackedOut: Boolean = false,
    val isDiscarded: Boolean = false,
    val activeOptOutCount: Int,
    val strongestRssiDbm: Int?,
    val decisionCode: String,
    val auditStatus: String = "VERIFIED_ROBUST",
    val coverageScore: Float = 1.0f
)

/**
 * Collects per-frame privacy receipts during an active video recording session.
 *
 * Tracks face counts, opt-out beacon observations, masking quality audits,
 * and conservative fail-safe decisions, providing the cryptographic audit trail
 * for the signed Privacy Manifest.
 */
class PrivacyReceiptCollector {

    private val receipts = CopyOnWriteArrayList<FrameAuditReceipt>()
    private val observedTokens = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val discardedFramesCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private var recordingStartMs: Long = 0L

    fun startSession() {
        receipts.clear()
        observedTokens.clear()
        discardedFramesCounter.set(0)
        recordingStartMs = System.currentTimeMillis()
    }

    fun recordDiscardedFrame(reason: String) {
        discardedFramesCounter.incrementAndGet()
    }

    fun recordFrame(
        frameIndex: Int,
        timestampMs: Long,
        faces: List<DetectedFace>,
        maskedFacesCount: Int,
        decision: PrivacyDecision,
        isBlackedOut: Boolean = false
    ) {
        val (decisionCode, activeOptOutCount, strongestRssi) = when (decision) {
            is PrivacyDecision.AssociationSupported -> {
                Triple("ASSOCIATION_SUPPORTED_MASKED", 1, decision.rssi)
            }
            is PrivacyDecision.AmbiguousConservativeMask -> {
                Triple(
                    if (isBlackedOut) "FAIL_CLOSED_FULL_FRAME_BLACKOUT" else "CONSERVATIVE_FAILSAFE_ALL_BLURRED",
                    decision.observationsCount,
                    decision.strongestRssi
                )
            }
            is PrivacyDecision.OptOutDetectedUnverified -> {
                Triple(
                    if (isBlackedOut) "FAIL_CLOSED_FULL_FRAME_BLACKOUT" else "OPT_OUT_UNVERIFIED_NO_FACES",
                    decision.activeObservations.size,
                    decision.strongestRssi
                )
            }
            is PrivacyDecision.OptOutSignalHold -> {
                Triple(
                    if (isBlackedOut) "FAIL_CLOSED_FULL_FRAME_BLACKOUT" else "OPT_OUT_HOLD_MASKED",
                    1,
                    null
                )
            }
            is PrivacyDecision.NoOptOutSignal -> {
                Triple(
                    if (isBlackedOut) "FAIL_CLOSED_FULL_FRAME_BLACKOUT" else "DEFAULT_CAMERA_MASKED",
                    0,
                    null
                )
            }
            is PrivacyDecision.BleUnavailable -> {
                Triple(
                    if (isBlackedOut) "FAIL_CLOSED_FULL_FRAME_BLACKOUT" else "BLE_UNAVAILABLE_SAFE",
                    0,
                    null
                )
            }
        }

        when (decision) {
            is PrivacyDecision.AssociationSupported -> {
                observedTokens.add(decision.tokenHex)
            }
            is PrivacyDecision.OptOutDetectedUnverified -> {
                decision.activeObservations.forEach { observedTokens.add(it.tokenHex) }
            }
            is PrivacyDecision.OptOutSignalHold -> {
                decision.lastObservedTokenHex?.let { observedTokens.add(it) }
            }
            else -> {}
        }

        // Run Step 6 Re-Identification Audit on the frame
        val audit = MaskingQualityAuditor.auditFrame(
            faces = faces,
            decision = decision,
            isMasked = maskedFacesCount > 0 || isBlackedOut
        )

        val receipt = FrameAuditReceipt(
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            facesDetectedCount = faces.size,
            facesMaskedCount = maskedFacesCount,
            isBlackedOut = isBlackedOut,
            isDiscarded = false,
            activeOptOutCount = activeOptOutCount,
            strongestRssiDbm = strongestRssi,
            decisionCode = decisionCode,
            auditStatus = if (isBlackedOut) MaskingAuditStatus.VERIFIED_DEFENSIVE_ALL_MASKED.name else audit.status.name,
            coverageScore = if (isBlackedOut) 1.0f else audit.coverageScore
        )

        receipts.add(receipt)
    }

    fun getTotalFrames(): Int = receipts.size

    fun getMaskedFramesCount(): Int = receipts.count { it.facesMaskedCount > 0 }

    fun getBlackedOutFramesCount(): Int = receipts.count { it.isBlackedOut }

    fun getUnmaskedFramesCount(): Int = receipts.count { it.facesMaskedCount == 0 && !it.isBlackedOut }

    fun getDiscardedFramesCount(): Int = discardedFramesCounter.get()

    fun getOptOutEncounterCount(): Int = receipts.count { it.activeOptOutCount > 0 }

    fun getUniqueTokensCount(): Int = observedTokens.size

    fun getStrongestRssi(): Int? = receipts.mapNotNull { it.strongestRssiDbm }.maxOrNull()

    fun getSessionDurationMs(): Long {
        if (receipts.isEmpty()) return 0L
        val lastTimestamp = receipts.last().timestampMs
        return (lastTimestamp - recordingStartMs).coerceAtLeast(0L)
    }

    fun getSessionAuditOutcome(): String {
        if (receipts.isEmpty()) return "AUDIT_NO_FRAMES_CAPTURED"
        val hasInsufficient = receipts.any { it.auditStatus == MaskingAuditStatus.AUDIT_INSUFFICIENT_COVERAGE_FLAGGED.name }
        if (hasInsufficient) return "AUDIT_FLAGGED_INSUFFICIENT_COVERAGE"
        val hasStrengthened = receipts.any { it.auditStatus == MaskingAuditStatus.VERIFIED_STRENGTHENED.name }
        if (hasStrengthened) return "AUDIT_VERIFIED_STRENGTHENED_COVERAGE"
        val hasFailsafe = receipts.any { it.auditStatus == MaskingAuditStatus.VERIFIED_DEFENSIVE_ALL_MASKED.name }
        if (hasFailsafe) return "AUDIT_VERIFIED_CONSERVATIVE_FAILSAFE"
        return "AUDIT_VERIFIED_ROBUST"
    }

    fun getTotalFacesDetected(): Int = receipts.sumOf { it.facesDetectedCount }

    fun getTotalFacesMasked(): Int = receipts.sumOf { it.facesMaskedCount }

    fun getStrengthenedFramesCount(): Int = receipts.count { it.auditStatus == MaskingAuditStatus.VERIFIED_STRENGTHENED.name }

    fun getAllReceipts(): List<FrameAuditReceipt> = receipts.toList()
}
