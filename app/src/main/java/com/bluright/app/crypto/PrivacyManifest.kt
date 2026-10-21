package com.bluright.app.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Versioned, cryptographically attestable Privacy Manifest associated with an exported Bluright video.
 *
 * Links an exported MP4 video to an on-device audit record of face detections, opt-out beacon observations,
 * masking quality audit outcomes, and conservative privacy masking decisions via SHA-256 and ECDSA cryptographic signatures.
 */
data class PrivacyManifest(
    val manifestVersion: String = "1.1",
    val exportId: String,
    val createdAtIso: String,
    val videoFileName: String,
    val videoSha256: String,
    val videoSizeBytes: Long,
    val videoDurationMs: Long,
    val totalFrames: Int,
    val maskedFramesCount: Int,
    val blackedOutFramesCount: Int = 0,
    val unmaskedFramesCount: Int = 0,
    val discardedFramesCount: Int = 0,
    val privacyPolicy: String = "CONSERVATIVE_FAILSAFE_FAILCLOSED_STRICT",
    val auditOutcome: String = "AUDIT_VERIFIED_CONSERVATIVE_SUCCESS",
    val cloudProcessing: Boolean = false,
    val optOutEncounterCount: Int,
    val optOutTokensCount: Int,
    val strongestRssiDbm: Int?,
    val maskStylesApplied: List<String>,
    val limitationsDisclaimer: String = "BlazeFace is an on-device heuristic vision model; cryptographic signature proves audit integrity, not absolute biometric re-identification impossibility.",
    val signingAlgorithm: String = "SHA256withECDSA",
    val keyAlias: String = "bluright_privacy_manifest_key_v1",
    val publicKeyBase64: String,
    val signatureBase64: String
) {
    /**
     * Produces a deterministic canonical representation of the manifest payload
     * (strictly excluding the signature itself) for digital signing and verification.
     *
     * Backwards-compatible with version 1.0 manifests exported in Phase 4.
     */
    fun toCanonicalPayload(): String {
        val sortedStyles = maskStylesApplied.sorted()
        return buildString {
            append("auditOutcome:").append(auditOutcome).append("\n")
            if (manifestVersion != "1.0") {
                append("blackedOutFramesCount:").append(blackedOutFramesCount).append("\n")
            }
            append("cloudProcessing:").append(cloudProcessing).append("\n")
            append("createdAtIso:").append(createdAtIso).append("\n")
            if (manifestVersion != "1.0") {
                append("discardedFramesCount:").append(discardedFramesCount).append("\n")
            }
            append("exportId:").append(exportId).append("\n")
            append("manifestVersion:").append(manifestVersion).append("\n")
            append("maskStylesApplied:").append(sortedStyles.joinToString(",")).append("\n")
            append("maskedFramesCount:").append(maskedFramesCount).append("\n")
            append("optOutEncounterCount:").append(optOutEncounterCount).append("\n")
            append("optOutTokensCount:").append(optOutTokensCount).append("\n")
            append("privacyPolicy:").append(privacyPolicy).append("\n")
            append("publicKeyBase64:").append(publicKeyBase64).append("\n")
            append("signingAlgorithm:").append(signingAlgorithm).append("\n")
            append("strongestRssiDbm:").append(strongestRssiDbm ?: "NONE").append("\n")
            append("totalFrames:").append(totalFrames).append("\n")
            if (manifestVersion != "1.0") {
                append("unmaskedFramesCount:").append(unmaskedFramesCount).append("\n")
            }
            append("videoDurationMs:").append(videoDurationMs).append("\n")
            append("videoFileName:").append(videoFileName).append("\n")
            append("videoSha256:").append(videoSha256.lowercase()).append("\n")
            append("videoSizeBytes:").append(videoSizeBytes)
        }
    }

    /**
     * Serializes this manifest to formatted JSON for storage alongside the MP4 export.
     */
    fun toJsonString(indentSpaces: Int = 2): String {
        val json = JSONObject()
        json.put("manifestVersion", manifestVersion)
        json.put("exportId", exportId)
        json.put("createdAtIso", createdAtIso)
        json.put("videoFileName", videoFileName)
        json.put("videoSha256", videoSha256.lowercase())
        json.put("videoSizeBytes", videoSizeBytes)
        json.put("videoDurationMs", videoDurationMs)
        json.put("totalFrames", totalFrames)
        json.put("maskedFramesCount", maskedFramesCount)
        json.put("blackedOutFramesCount", blackedOutFramesCount)
        json.put("unmaskedFramesCount", unmaskedFramesCount)
        json.put("discardedFramesCount", discardedFramesCount)
        json.put("privacyPolicy", privacyPolicy)
        json.put("auditOutcome", auditOutcome)
        json.put("cloudProcessing", cloudProcessing)
        json.put("limitationsDisclaimer", limitationsDisclaimer)

        val optOutObj = JSONObject()
        optOutObj.put("optOutEncounterCount", optOutEncounterCount)
        optOutObj.put("optOutTokensCount", optOutTokensCount)
        if (strongestRssiDbm != null) {
            optOutObj.put("strongestRssiDbm", strongestRssiDbm)
        } else {
            optOutObj.put("strongestRssiDbm", JSONObject.NULL)
        }
        json.put("optOutSummary", optOutObj)

        val stylesArray = JSONArray()
        maskStylesApplied.sorted().forEach { stylesArray.put(it) }
        json.put("maskStylesApplied", stylesArray)

        val cryptoObj = JSONObject()
        cryptoObj.put("algorithm", signingAlgorithm)
        cryptoObj.put("keyAlias", keyAlias)
        cryptoObj.put("publicKeyBase64", publicKeyBase64)
        cryptoObj.put("signatureBase64", signatureBase64)
        json.put("cryptographicProof", cryptoObj)

        return json.toString(indentSpaces)
    }

    companion object {
        /**
         * Parses a PrivacyManifest from a JSON string.
         */
        fun fromJsonString(jsonString: String): PrivacyManifest {
            val json = JSONObject(jsonString)
            val optOutObj = json.optJSONObject("optOutSummary")
            val cryptoObj = json.getJSONObject("cryptographicProof")

            val stylesArray = json.optJSONArray("maskStylesApplied")
            val styles = mutableListOf<String>()
            if (stylesArray != null) {
                for (i in 0 until stylesArray.length()) {
                    styles.add(stylesArray.getString(i))
                }
            }

            val strongestRssi = if (optOutObj != null && !optOutObj.isNull("strongestRssiDbm")) {
                optOutObj.getInt("strongestRssiDbm")
            } else null

            return PrivacyManifest(
                manifestVersion = json.optString("manifestVersion", "1.0"),
                exportId = json.getString("exportId"),
                createdAtIso = json.getString("createdAtIso"),
                videoFileName = json.getString("videoFileName"),
                videoSha256 = json.getString("videoSha256"),
                videoSizeBytes = json.getLong("videoSizeBytes"),
                videoDurationMs = json.getLong("videoDurationMs"),
                totalFrames = json.getInt("totalFrames"),
                maskedFramesCount = json.getInt("maskedFramesCount"),
                blackedOutFramesCount = json.optInt("blackedOutFramesCount", 0),
                unmaskedFramesCount = json.optInt("unmaskedFramesCount", 0),
                discardedFramesCount = json.optInt("discardedFramesCount", 0),
                privacyPolicy = json.optString("privacyPolicy", "CONSERVATIVE_FAILSAFE_STRICT"),
                auditOutcome = json.optString("auditOutcome", "AUDIT_VERIFIED_CONSERVATIVE_SUCCESS"),
                cloudProcessing = json.optBoolean("cloudProcessing", false),
                optOutEncounterCount = optOutObj?.optInt("optOutEncounterCount", 0) ?: 0,
                optOutTokensCount = optOutObj?.optInt("optOutTokensCount", 0) ?: 0,
                strongestRssiDbm = strongestRssi,
                maskStylesApplied = styles,
                limitationsDisclaimer = json.optString(
                    "limitationsDisclaimer",
                    "BlazeFace is an on-device heuristic vision model; cryptographic signature proves audit integrity, not absolute biometric re-identification impossibility."
                ),
                signingAlgorithm = cryptoObj.optString("algorithm", "SHA256withECDSA"),
                keyAlias = cryptoObj.optString("keyAlias", "bluright_privacy_manifest_key_v1"),
                publicKeyBase64 = cryptoObj.getString("publicKeyBase64"),
                signatureBase64 = cryptoObj.getString("signatureBase64")
            )
        }

        /**
         * Calculates SHA-256 digest hex string of any byte array.
         */
        fun calculateSha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(bytes)
            return hash.joinToString("") { "%02x".format(it) }
        }
    }
}
