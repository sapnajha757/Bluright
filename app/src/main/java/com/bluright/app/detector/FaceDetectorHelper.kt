package com.bluright.app.detector

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.bluright.app.model.DetectedFace
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.facedetector.FaceDetectorResult

/**
 * Manages Google MediaPipe Tasks Vision Face Detector in LIVE_STREAM mode.
 *
 * All inference occurs locally on-device. No images leave this process.
 */
class FaceDetectorHelper(
    private val context: Context,
    private val minConfidence: Float = 0.5f,
    private val listener: DetectorListener
) {
    interface DetectorListener {
        fun onResults(faces: List<DetectedFace>, imageWidth: Int, imageHeight: Int, inferenceTimeMs: Long)
        fun onError(error: String)
    }

    private var faceDetector: FaceDetector? = null
    private var isClosed = false
    private var lastFrameTimestampMs: Long = 0L

    init {
        setupFaceDetector()
    }

    /**
     * Initializes the MediaPipe FaceDetector instance using the local asset.
     */
    @Synchronized
    fun setupFaceDetector() {
        if (isClosed) return

        try {
            // Close existing detector if any
            faceDetector?.close()

            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET_NAME)
                .setDelegate(Delegate.CPU)
                .build()

            val options = FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinDetectionConfidence(minConfidence)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result: FaceDetectorResult, inputImage: MPImage ->
                    handleDetectionResult(result, inputImage)
                }
                .setErrorListener { error: RuntimeException ->
                    Log.e(TAG, "MediaPipe detector error: ${error.message}", error)
                    listener.onError(error.message ?: "MediaPipe face detection runtime error")
                }
                .build()

            faceDetector = FaceDetector.createFromOptions(context, options)
            Log.i(TAG, "MediaPipe Face Detector successfully initialized with asset: $MODEL_ASSET_NAME")
        } catch (e: Exception) {
            val msg = "Failed to initialize MediaPipe Face Detector: ${e.message}"
            Log.e(TAG, msg, e)
            listener.onError(msg)
        }
    }

    /**
     * Feeds a display-oriented frame to the MediaPipe detector asynchronously.
     */
    @Synchronized
    fun detectAsync(mpImage: MPImage, frameTimestampMs: Long) {
        if (isClosed) return
        val detector = faceDetector ?: return

        // LIVE_STREAM mode requires monotonically strictly increasing timestamps
        val monotonicTimestamp = if (frameTimestampMs <= lastFrameTimestampMs) {
            lastFrameTimestampMs + 1
        } else {
            frameTimestampMs
        }
        lastFrameTimestampMs = monotonicTimestamp

        try {
            detector.detectAsync(mpImage, monotonicTimestamp)
        } catch (e: Exception) {
            Log.e(TAG, "Error invoking detectAsync: ${e.message}", e)
            listener.onError("Frame analysis error: ${e.message}")
        }
    }

    private fun handleDetectionResult(result: FaceDetectorResult, inputImage: MPImage) {
        val now = SystemClock.uptimeMillis()
        val inferenceTimeMs = (now - result.timestampMs()).coerceAtLeast(0L)

        val imageW = inputImage.width.toFloat()
        val imageH = inputImage.height.toFloat()

        if (imageW <= 0f || imageH <= 0f) {
            listener.onResults(emptyList(), inputImage.width, inputImage.height, inferenceTimeMs)
            return
        }

        val detectedFaces = mutableListOf<DetectedFace>()

        for (detection in result.detections()) {
            val bbox = detection.boundingBox()
            val score = detection.categories().firstOrNull()?.score() ?: 0.5f

            // Normalize coordinates into [0.0, 1.0] relative to input image dimensions
            val normLeft = (bbox.left / imageW).coerceIn(0f, 1f)
            val normTop = (bbox.top / imageH).coerceIn(0f, 1f)
            val normRight = (bbox.right / imageW).coerceIn(0f, 1f)
            val normBottom = (bbox.bottom / imageH).coerceIn(0f, 1f)

            // Ignore malformed degenerate detections
            if (normRight > normLeft && normBottom > normTop) {
                detectedFaces.add(
                    DetectedFace(
                        id = (bbox.left.toLong() shl 16) xor bbox.top.toLong(),
                        normLeft = normLeft,
                        normTop = normTop,
                        normRight = normRight,
                        normBottom = normBottom,
                        confidence = score,
                        timestampMs = now
                    )
                )
            }
        }

        listener.onResults(detectedFaces, inputImage.width, inputImage.height, inferenceTimeMs)
    }

    /**
     * Releases detector resources safely.
     */
    @Synchronized
    fun close() {
        isClosed = true
        try {
            faceDetector?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing face detector: ${e.message}")
        }
        faceDetector = null
    }

    companion object {
        private const val TAG = "FaceDetectorHelper"
        const val MODEL_ASSET_NAME = "blaze_face_short_range.tflite"
    }
}
