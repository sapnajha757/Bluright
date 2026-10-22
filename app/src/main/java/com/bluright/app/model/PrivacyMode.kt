package com.bluright.app.model

/**
 * Explicit Privacy Modes governing the Bluright face masking behavior.
 */
enum class PrivacyMode(val displayName: String, val description: String) {
    /**
     * Default privacy mode: Faces are only masked when associated with a valid BLE opt-out signal,
     * or when conservative fail-closed safeguards require protecting an ambiguous or recently lost opt-out.
     * Ordinary face detection without an opt-out beacon NEVER masks faces.
     */
    OPT_OUT_ONLY(
        displayName = "Opt-Out Only",
        description = "Selective privacy: Only masks individuals broadcasting a valid BLE opt-out signal"
    ),

    /**
     * Comprehensive privacy override: Every detected face in the camera frame is masked,
     * regardless of whether any BLE opt-out signals are detected.
     */
    ALL_FACES(
        displayName = "All Faces",
        description = "Conservative override: Masks all detected faces in the camera frame"
    )
}
