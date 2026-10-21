package com.bluright.app.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.bluright.app.detector.FaceDetectorHelper
import com.bluright.app.model.DetectedFace
import com.bluright.app.model.DetectionState
import com.google.mediapipe.framework.image.BitmapImageBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX ImageAnalysis.Analyzer that converts camera frames into display-upright Bitmaps,
 * feeds them to MediaPipe FaceDetector, tracks frame rates, and manages smooth anti-flicker states.
 */
class FaceBlurImageAnalyzer(
    context: Context,
    private val scope: CoroutineScope
) : ImageAnalysis.Analyzer, FaceDetectorHelper.DetectorListener {

    private val _detectionState = MutableStateFlow<DetectionState>(DetectionState.Scanning(0f))
    val detectionState: StateFlow<DetectionState> = _detectionState.asStateFlow()

    // Aspect ratio of the upright processed frame (width / height)
    private val _frameAspectRatio = MutableStateFlow(9f / 16f)
    val frameAspectRatio: StateFlow<Float> = _frameAspectRatio.asStateFlow()

    @Volatile
    var isFrontCamera: Boolean = false

    private val isBusy = AtomicBoolean(false)
    private var lastAnalyzedTimeMs = 0L
    private var lastResultTimeMs = SystemClock.uptimeMillis()

    // Frame rate calculation
    private var frameCount = 0
    private var lastFpsTimestamp = SystemClock.uptimeMillis()
    private var currentFps = 0f

    // Smoothed faces cache to prevent 1-frame drop flicker
    private var cachedSmoothedFaces = listOf<DetectedFace>()
    private var cachedFacesExpiryMs = 0L

    private val detectorHelper = FaceDetectorHelper(
        context = context,
        minConfidence = 0.5f,
        listener = this
    )

    private var watchdogJob: Job? = null

    init {
        startStalenessWatchdog()
    }

    private fun startStalenessWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(200)
                val elapsedSinceLastResult = SystemClock.uptimeMillis() - lastResultTimeMs
                if (elapsedSinceLastResult > STALE_TIMEOUT_MS) {
                    val currentState = _detectionState.value
                    if (currentState !is DetectionState.Unavailable) {
                        _detectionState.value = DetectionState.Stale(elapsedSinceLastResult)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val now = SystemClock.uptimeMillis()

        // Rate limit analysis to avoid overloading device CPU (target ~25-30 FPS)
        if (now - lastAnalyzedTimeMs < MIN_FRAME_INTERVAL_MS || isBusy.get()) {
            imageProxy.close()
            return
        }

        isBusy.set(true)
        lastAnalyzedTimeMs = now

        // Calculate moving FPS
        frameCount++
        val fpsElapsed = now - lastFpsTimestamp
        if (fpsElapsed >= 1000L) {
            currentFps = (frameCount * 1000f) / fpsElapsed
            frameCount = 0
            lastFpsTimestamp = now
        }

        try {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val bitmap = imageProxy.toBitmap()

            // Close imageProxy immediately so CameraX pipeline is never blocked
            imageProxy.close()

            // Process rotation and mirroring on background coroutine
            scope.launch(Dispatchers.Default) {
                try {
                    val matrix = Matrix().apply {
                        if (rotationDegrees != 0) {
                            postRotate(rotationDegrees.toFloat())
                        }
                        if (isFrontCamera) {
                            // Mirror horizontally for front-facing selfie camera
                            postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
                        }
                    }

                    val uprightBitmap = if (rotationDegrees != 0 || isFrontCamera) {
                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    } else {
                        bitmap
                    }

                    // Update frame aspect ratio for Compose overlay mapping
                    val uprightW = uprightBitmap.width.toFloat()
                    val uprightH = uprightBitmap.height.toFloat()
                    if (uprightW > 0f && uprightH > 0f) {
                        _frameAspectRatio.value = uprightW / uprightH
                    }

                    val mpImage = BitmapImageBuilder(uprightBitmap).build()
                    detectorHelper.detectAsync(mpImage, now)
                } catch (e: Exception) {
                    Log.e(TAG, "Frame preprocessing error: ${e.message}", e)
                } finally {
                    isBusy.set(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image analysis error: ${e.message}", e)
            imageProxy.close()
            isBusy.set(false)
        }
    }

    override fun onResults(
        faces: List<DetectedFace>,
        imageWidth: Int,
        imageHeight: Int,
        inferenceTimeMs: Long
    ) {
        val now = SystemClock.uptimeMillis()
        lastResultTimeMs = now

        if (faces.isNotEmpty()) {
            // Smooth detected faces against previously cached faces to eliminate jitter
            val smoothedFaces = if (cachedSmoothedFaces.isNotEmpty()) {
                faces.map { newFace ->
                    // Find closest previous face by center distance
                    val newCenterX = (newFace.normLeft + newFace.normRight) / 2f
                    val newCenterY = (newFace.normTop + newFace.normBottom) / 2f

                    val closestMatch = cachedSmoothedFaces.minByOrNull { prevFace ->
                        val prevCenterX = (prevFace.normLeft + prevFace.normRight) / 2f
                        val prevCenterY = (prevFace.normTop + prevFace.normBottom) / 2f
                        val dx = newCenterX - prevCenterX
                        val dy = newCenterY - prevCenterY
                        dx * dx + dy * dy
                    }

                    if (closestMatch != null) {
                        newFace.lerpWith(closestMatch, alpha = 0.45f)
                    } else {
                        newFace
                    }
                }
            } else {
                faces
            }

            cachedSmoothedFaces = smoothedFaces
            cachedFacesExpiryMs = now + FACE_DECAY_GRACE_MS
            _detectionState.value = DetectionState.FaceDetected(
                faces = smoothedFaces,
                inferenceTimeMs = inferenceTimeMs,
                fps = currentFps
            )
        } else {
            // If faces drop for 1-2 frames, apply grace period to prevent distracting flicker
            if (now < cachedFacesExpiryMs && cachedSmoothedFaces.isNotEmpty()) {
                _detectionState.value = DetectionState.FaceDetected(
                    faces = cachedSmoothedFaces,
                    inferenceTimeMs = inferenceTimeMs,
                    fps = currentFps
                )
            } else {
                cachedSmoothedFaces = emptyList()
                _detectionState.value = DetectionState.ZeroFaces(
                    inferenceTimeMs = inferenceTimeMs,
                    fps = currentFps
                )
            }
        }
    }

    override fun onError(error: String) {
        Log.e(TAG, "Detection error reported: $error")
        _detectionState.value = DetectionState.Unavailable(error)
    }

    fun shutdown() {
        watchdogJob?.cancel()
        detectorHelper.close()
    }

    companion object {
        private const val TAG = "FaceBlurImageAnalyzer"
        private const val MIN_FRAME_INTERVAL_MS = 33L // ~30 fps cap
        private const val STALE_TIMEOUT_MS = 650L
        private const val FACE_DECAY_GRACE_MS = 240L
    }
}
