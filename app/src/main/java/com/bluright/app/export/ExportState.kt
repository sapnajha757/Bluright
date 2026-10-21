package com.bluright.app.export

import com.bluright.app.crypto.ManifestVerificationResult
import com.bluright.app.crypto.PrivacyManifest
import java.io.File

/**
 * States representing the real-time privacy video recording and signed manifest export pipeline.
 */
sealed class ExportState {
    object Idle : ExportState()

    data class Recording(
        val durationMs: Long,
        val frameCount: Int,
        val maskedFramesCount: Int
    ) : ExportState()

    object Finalizing : ExportState()

    data class ExportSuccess(
        val videoFile: File,
        val manifestFile: File,
        val manifest: PrivacyManifest,
        val verificationResult: ManifestVerificationResult
    ) : ExportState()

    data class Error(
        val message: String
    ) : ExportState()
}
