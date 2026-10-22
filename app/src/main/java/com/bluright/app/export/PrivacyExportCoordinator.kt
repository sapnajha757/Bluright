package com.bluright.app.export

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.bluright.app.crypto.PrivacyManifestSigner
import com.bluright.app.model.BlurStyle
import com.bluright.app.model.DetectedFace
import com.bluright.app.model.PrivacyMode
import com.bluright.app.privacy.PrivacyDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Orchestrates the privacy-preserving video recording, frame-by-frame mask burning,
 * audit receipt tracking, Android KeyStore signing, and manifest export workflow.
 */
class PrivacyExportCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val signer: PrivacyManifestSigner = PrivacyManifestSigner()
) {
    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    private var activeEncoder: PrivacyVideoEncoder? = null
    private val receiptCollector = PrivacyReceiptCollector()
    private val exportDispatcher = Dispatchers.Default.limitedParallelism(1)

    private var sessionStartTimeMs = 0L
    private var sessionStartUptimeUs = 0L
    private var frameCounter = 0
    private var currentStyle = BlurStyle.PIXELATE

    @Volatile
    var privacyMode: PrivacyMode = PrivacyMode.OPT_OUT_ONLY

    var isAllFacesMode: Boolean
        get() = privacyMode == PrivacyMode.ALL_FACES
        set(value) {
            privacyMode = if (value) PrivacyMode.ALL_FACES else PrivacyMode.OPT_OUT_ONLY
        }

    val hasObservedOptOutInSession: Boolean
        get() = _hasObservedOptOutInSession

    @Volatile
    private var _hasObservedOptOutInSession: Boolean = false

    private var currentVideoFile: File? = null
    private var currentManifestFile: File? = null
    private var currentExportId: String = ""

    init {
        activeInstance = this
    }

    val isRecording: Boolean
        get() = _exportState.value is ExportState.Recording

    /**
     * Starts a new privacy video recording session.
     */
    @Synchronized
    fun startRecording(style: BlurStyle = BlurStyle.PIXELATE) {
        if (isRecording || _exportState.value is ExportState.Finalizing) return

        try {
            val exportDir = File(context.filesDir, "bluright_exports").apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val exportId = UUID.randomUUID().toString()

            val videoFile = File(exportDir, "bluright_${timestamp}.mp4")
            val manifestFile = File(exportDir, "bluright_${timestamp}.manifest.json")

            currentVideoFile = videoFile
            currentManifestFile = manifestFile
            currentExportId = exportId
            currentStyle = style

            activeEncoder = PrivacyVideoEncoder(
                outputFile = videoFile,
                targetWidth = 480,
                targetHeight = 640,
                frameRate = 24
            )

            receiptCollector.startSession()
            sessionStartTimeMs = System.currentTimeMillis()
            sessionStartUptimeUs = SystemClock.uptimeMillis() * 1000L
            frameCounter = 0
            _hasObservedOptOutInSession = false

            _exportState.value = ExportState.Recording(
                durationMs = 0L,
                frameCount = 0,
                maskedFramesCount = 0
            )

            Log.i(TAG, "Privacy video recording session started: ${videoFile.name} (ExportId: $exportId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording session: ${e.message}", e)
            _exportState.value = ExportState.Error("Failed to start recording: ${e.message}")
        }
    }

    fun recordDiscardedFrame(reason: String) {
        if (isRecording) {
            receiptCollector.recordDiscardedFrame(reason)
        }
    }

    /**
     * Feeds an analyzed camera frame into the recording pipeline.
     * The mask is burned directly into the bitmap before encoding.
     */
    fun processFrame(
        bitmap: Bitmap,
        faces: List<DetectedFace>,
        decision: PrivacyDecision
    ) {
        if (!isRecording) return
        val encoder = activeEncoder ?: return

        scope.launch(exportDispatcher) {
            if (!isRecording) return@launch
            try {
                val frameIndex = frameCounter++
                val presentationTimeUs = (SystemClock.uptimeMillis() * 1000L) - sessionStartUptimeUs

                if (decision.isOptOutActive) {
                    _hasObservedOptOutInSession = true
                }

                // Create a mutable copy of the bitmap to burn masks in-place
                val mutableFrame = bitmap.copy(Bitmap.Config.ARGB_8888, true)

                var maskedFacesCount = 0
                var isBlackedOut = false

                val facesToMask = decision.resolveFacesToMask(
                    faces = faces,
                    mode = privacyMode,
                    hasSessionStickyOptOut = _hasObservedOptOutInSession
                )

                if (facesToMask.isNotEmpty()) {
                    // Burn masks onto faces requiring privacy protection
                    maskedFacesCount = PrivacyMaskRenderer.burnMask(mutableFrame, facesToMask, currentStyle)
                } else if (faces.isEmpty() && (decision.isOptOutActive || _hasObservedOptOutInSession)) {
                    // FAIL-CLOSED PRIVACY POLICY (DEF-01):
                    // Opt-out signal is active or was observed in this session, but zero faces detected.
                    // Visual coverage cannot be trusted (face detector false negative risk).
                    // Burn full-frame opaque blackout to guarantee zero pixel leakage!
                    val reason = if (decision.isOptOutActive) {
                        "OPT-OUT SIGNAL ACTIVE // COVERAGE UNVERIFIED"
                    } else {
                        "OPT-OUT OBSERVED IN SESSION // COVERAGE UNVERIFIED"
                    }
                    PrivacyMaskRenderer.burnFullFrameBlackout(
                        mutableFrame,
                        reason = reason
                    )
                    isBlackedOut = true
                } else {
                    // Default / no opt-out signal ever observed and no faces requiring mask: normal unmasked frame
                }

                // Send the masked frame to the hardware video encoder
                encoder.encodeFrame(mutableFrame, presentationTimeUs)
                mutableFrame.recycle()

                // Record audit receipt
                receiptCollector.recordFrame(
                    frameIndex = frameIndex,
                    timestampMs = System.currentTimeMillis(),
                    faces = faces,
                    maskedFacesCount = maskedFacesCount,
                    decision = decision,
                    isBlackedOut = isBlackedOut
                )

                val elapsedMs = System.currentTimeMillis() - sessionStartTimeMs
                _exportState.value = ExportState.Recording(
                    durationMs = elapsedMs,
                    frameCount = frameCounter,
                    maskedFramesCount = receiptCollector.getMaskedFramesCount()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Frame recording error: ${e.message}", e)
                receiptCollector.recordDiscardedFrame("FRAME_ENCODE_EXCEPTION: ${e.message}")
            }
        }
    }

    /**
     * Finalizes the MP4 video export, signs the cryptographic Privacy Manifest,
     * verifies file integrity, and emits [ExportState.ExportSuccess].
     */
    @Synchronized
    fun stopRecording() {
        if (!isRecording) return
        _exportState.value = ExportState.Finalizing

        val videoFile = currentVideoFile ?: return
        val manifestFile = currentManifestFile ?: return
        val exportId = currentExportId
        val encoder = activeEncoder

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Finish MP4 Video Encoding
                encoder?.finish()
                activeEncoder = null

                val durationMs = receiptCollector.getSessionDurationMs()
                val totalFrames = receiptCollector.getTotalFrames()
                val maskedCount = receiptCollector.getMaskedFramesCount()
                val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())

                // 2. Cryptographically Sign the Manifest with Android KeyStore EC key
                val manifest = signer.signManifest(
                    exportId = exportId,
                    createdAtIso = isoDate,
                    videoFile = videoFile,
                    videoDurationMs = durationMs,
                    totalFrames = totalFrames,
                    maskedFramesCount = maskedCount,
                    blackedOutFramesCount = receiptCollector.getBlackedOutFramesCount(),
                    unmaskedFramesCount = receiptCollector.getUnmaskedFramesCount(),
                    discardedFramesCount = receiptCollector.getDiscardedFramesCount(),
                    privacyPolicy = "CONSERVATIVE_FAILSAFE_FAILCLOSED_STRICT",
                    auditOutcome = receiptCollector.getSessionAuditOutcome(),
                    cloudProcessing = false,
                    optOutEncounterCount = receiptCollector.getOptOutEncounterCount(),
                    optOutTokensCount = receiptCollector.getUniqueTokensCount(),
                    strongestRssiDbm = receiptCollector.getStrongestRssi(),
                    maskStylesApplied = listOf(currentStyle.name)
                )

                // 3. Write Signed Manifest JSON
                val manifestJson = manifest.toJsonString(indentSpaces = 2)
                manifestFile.writeText(manifestJson, Charsets.UTF_8)

                // 4. Verify Cryptographic Integrity
                val verificationResult = signer.verifyManifest(manifestJson, videoFile)

                Log.i(TAG, "Export completed! Manifest signed with KeyStore. Verification: $verificationResult")

                withContext(Dispatchers.Main) {
                    _exportState.value = ExportState.ExportSuccess(
                        videoFile = videoFile,
                        manifestFile = manifestFile,
                        manifest = manifest,
                        verificationResult = verificationResult
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export finalization failed: ${e.message}", e)
                try {
                    if (videoFile.exists()) videoFile.delete()
                    if (manifestFile.exists()) manifestFile.delete()
                } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    _exportState.value = ExportState.Error("Export failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Cancels an in-progress recording and purges partial files.
     */
    @Synchronized
    fun cancelRecording() {
        _hasObservedOptOutInSession = false
        activeEncoder?.cleanup()
        activeEncoder = null
        currentVideoFile?.delete()
        currentManifestFile?.delete()
        _exportState.value = ExportState.Idle
    }

    fun dismissSuccess() {
        _exportState.value = ExportState.Idle
    }

    companion object {
        private const val TAG = "PrivacyExportCoordinator"
        var activeInstance: PrivacyExportCoordinator? = null
    }
}
