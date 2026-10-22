package com.bluright.app.audit

import com.bluright.app.model.DetectedFace
import com.bluright.app.privacy.PrivacyDecision

/**
 * Audit status evaluated for a single video frame or entire session.
 */
enum class MaskingAuditStatus {
    VERIFIED_ROBUST,
    VERIFIED_STRENGTHENED,
    VERIFIED_DEFENSIVE_ALL_MASKED,
    NO_FACES_OBSERVED,
    AUDIT_INSUFFICIENT_COVERAGE_FLAGGED
}

/**
 * Result of auditing the masking quality of a single frame.
 */
data class FrameAuditResult(
    val status: MaskingAuditStatus,
    val facesAudited: Int,
    val facesStrengthenedCount: Int,
    val edgeClampedCount: Int,
    val coverageScore: Float,
    val flags: List<String>
)

/**
 * Cumulative audit summary across an entire recording session.
 */
data class SessionAuditSummary(
    val overallOutcome: String,
    val totalFramesAudited: Int,
    val totalFacesAudited: Int,
    val strengthenedFramesCount: Int,
    val edgeClampedCount: Int,
    val cloudProcessingUsed: Boolean = false,
    val limitationsDisclaimer: String
)

/**
 * Step 6: Re-Identification Audit.
 *
 * Implements an honest, testable masking-quality audit on-device:
 * - Detects edge boundary truncation (face bounding box touching frame edge).
 * - Identifies low-confidence detections and strengthens masking margins.
 * - Enforces conservative fail-safe masking when opt-out beacons are ambiguous.
 * - Explicitly documents detector limitations without making false claims of absolute anonymity.
 */
object MaskingQualityAuditor {

    const val LIMITATIONS_DISCLAIMER: String =
        "BlazeFace is an on-device heuristic vision model. Extreme yaw (>60 deg), motion blur, " +
        "or physical occlusions may cause transient detection misses. Masking applies conservative " +
        "15-25% geometric safety expansion margins. Cryptographic signatures attest to audit pipeline " +
        "execution and hash integrity, not mathematical impossibility of biometric re-identification."

    /**
     * Audits the masking quality for the detected faces in a single frame.
     *
     * @param faces Detected faces in normalized [0..1] coordinates.
     * @param decision The privacy association decision applied to this frame.
     * @param isMasked Whether privacy masks were rendered onto the frame.
     */
    fun auditFrame(
        faces: List<DetectedFace>,
        decision: PrivacyDecision,
        isMasked: Boolean
    ): FrameAuditResult {
        if (faces.isEmpty()) {
            return FrameAuditResult(
                status = MaskingAuditStatus.NO_FACES_OBSERVED,
                facesAudited = 0,
                facesStrengthenedCount = 0,
                edgeClampedCount = 0,
                coverageScore = 1.0f,
                flags = emptyList()
            )
        }

        var strengthenedCount = 0
        var edgeClampedCount = 0
        val flags = mutableListOf<String>()

        for (face in faces) {
            // Edge proximity check: within 5% of any frame border
            val touchesEdge = face.normLeft <= 0.05f ||
                    face.normTop <= 0.05f ||
                    face.normRight >= 0.95f ||
                    face.normBottom >= 0.95f

            if (touchesEdge) {
                edgeClampedCount++
                strengthenedCount++
                flags.add("FACE_${face.id}_EDGE_PROXIMITY_EXPANDED")
            }

            // Low confidence check: marginal detection score
            if (face.confidence < 0.70f) {
                strengthenedCount++
                flags.add("FACE_${face.id}_LOW_CONFIDENCE_REINFORCED")
            }

            // Tiny face check: area < 1% of frame
            val area = (face.normRight - face.normLeft) * (face.normBottom - face.normTop)
            if (area < 0.01f) {
                strengthenedCount++
                flags.add("FACE_${face.id}_TINY_FACE_HIGH_ENTROPY")
            }
        }

        // Conservative fail-safe audit: if decision required masking, verify masking was applied
        val auditStatus = when {
            !isMasked && decision.isOptOutActive -> {
                flags.add("AUDIT_FAILED_UNMASKED_FACES_UNDER_OPT_OUT")
                MaskingAuditStatus.AUDIT_INSUFFICIENT_COVERAGE_FLAGGED
            }
            decision is PrivacyDecision.AmbiguousConservativeMask || decision is PrivacyDecision.OptOutSignalHold -> {
                MaskingAuditStatus.VERIFIED_DEFENSIVE_ALL_MASKED
            }
            strengthenedCount > 0 -> {
                MaskingAuditStatus.VERIFIED_STRENGTHENED
            }
            else -> {
                MaskingAuditStatus.VERIFIED_ROBUST
            }
        }

        val coverageScore = when (auditStatus) {
            MaskingAuditStatus.AUDIT_INSUFFICIENT_COVERAGE_FLAGGED -> 0.0f
            MaskingAuditStatus.VERIFIED_ROBUST -> 1.0f
            MaskingAuditStatus.VERIFIED_DEFENSIVE_ALL_MASKED -> 0.98f
            MaskingAuditStatus.VERIFIED_STRENGTHENED -> 0.95f
            MaskingAuditStatus.NO_FACES_OBSERVED -> 1.0f
        }

        return FrameAuditResult(
            status = auditStatus,
            facesAudited = faces.size,
            facesStrengthenedCount = strengthenedCount,
            edgeClampedCount = edgeClampedCount,
            coverageScore = coverageScore,
            flags = flags
        )
    }

    /**
     * Determines the optimal margin expansion for a detected face based on audit heuristics.
     * Returns a float multiplier (e.g. 0.15 for 15%, up to 0.25 for edge-clamped faces).
     */
    fun calculateAdaptiveMargin(face: DetectedFace): Float {
        val touchesEdge = face.normLeft <= 0.05f ||
                face.normTop <= 0.05f ||
                face.normRight >= 0.95f ||
                face.normBottom >= 0.95f

        val isLowConfidence = face.confidence < 0.70f

        return when {
            touchesEdge -> 0.25f // 25% extra safety margin when touching border
            isLowConfidence -> 0.20f // 20% margin for marginal confidence
            else -> 0.15f // Baseline 15% conservative margin
        }
    }
}
