package com.bluright.app.model

/**
 * Represents the current status of the on-device face detection pipeline.
 */
sealed interface DetectionState {
    /**
     * Camera is streaming and the detector is actively scanning frames.
     */
    data class Scanning(val fps: Float = 0f) : DetectionState

    /**
     * Detector successfully identified one or more faces in the current frame.
     */
    data class FaceDetected(
        val faces: List<DetectedFace>,
        val inferenceTimeMs: Long,
        val fps: Float
    ) : DetectionState

    /**
     * Detector ran successfully on the latest frame, but no faces were present.
     */
    data class ZeroFaces(
        val inferenceTimeMs: Long,
        val fps: Float
    ) : DetectionState

    /**
     * The detector model could not be loaded or encountered a fatal processing failure.
     */
    data class Unavailable(val reason: String) : DetectionState

    /**
     * Stale state: frames haven't arrived or inference stalled for > 500ms.
     * In privacy-critical camera apps, stale detection must fail-safe and inform the user.
     */
    data class Stale(val elapsedSinceLastDetectionMs: Long) : DetectionState
}
