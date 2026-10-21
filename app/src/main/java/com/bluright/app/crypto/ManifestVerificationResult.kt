package com.bluright.app.crypto

/**
 * Result of cryptographically verifying a Bluright Privacy Manifest against an exported MP4 video file.
 */
sealed class ManifestVerificationResult {
    /**
     * The manifest signature is valid, and the video file SHA-256 hash matches exactly.
     */
    data class Valid(
        val exportId: String,
        val videoFileName: String,
        val videoSha256: String,
        val totalFrames: Int,
        val maskedFramesCount: Int,
        val signingAlgorithm: String,
        val disclaimer: String = "Cryptographic signature verifies file integrity and provenance. It does not certify computer-vision detection accuracy under unconstrained visual conditions."
    ) : ManifestVerificationResult()

    /**
     * The video file has been altered, corrupted, or replaced since the manifest was signed.
     */
    data class VideoHashMismatch(
        val expectedSha256: String,
        val actualSha256: String,
        val message: String = "Security Alert: Video file hash does not match the signed manifest. The video content was modified after export."
    ) : ManifestVerificationResult()

    /**
     * The cryptographic signature is invalid. The manifest content was altered after signing.
     */
    data class InvalidSignature(
        val message: String = "Security Alert: Cryptographic signature is invalid. Manifest payload was tampered with or signed with an untrusted key."
    ) : ManifestVerificationResult()

    /**
     * The manifest JSON is corrupted or missing required cryptographic fields.
     */
    data class MalformedManifest(
        val error: String
    ) : ManifestVerificationResult()

    /**
     * The associated MP4 video file could not be located on disk.
     */
    data class MissingVideoFile(
        val path: String
    ) : ManifestVerificationResult()
}
