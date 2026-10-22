package com.bluright.app.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import com.bluright.app.analyzer.FaceBlurImageAnalyzer
import com.bluright.app.ble.BleAdvertiserState
import com.bluright.app.ble.BleOptOutAdvertiser
import com.bluright.app.ble.BleOptOutScanner
import com.bluright.app.ble.BleScannerState
import com.bluright.app.export.ExportState
import com.bluright.app.export.PrivacyExportCoordinator
import com.bluright.app.model.BlurStyle
import com.bluright.app.model.CameraLens
import com.bluright.app.model.DetectionState
import com.bluright.app.privacy.FaceAssociationEngine
import com.bluright.app.ui.components.CameraControls
import com.bluright.app.ui.components.ExportSuccessDialog
import com.bluright.app.ui.components.FaceBlurOverlay
import com.bluright.app.ui.components.OptOutBroadcastDialog
import com.bluright.app.ui.components.PrivacyDecisionBadge
import com.bluright.app.ui.components.PrivacyModeHeader
import com.bluright.app.ui.components.PrivacyStatusBadge
import com.bluright.app.ui.theme.NavyDarkBackground
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

private const val TAG = "CameraScreen"

@Composable
fun CameraScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var currentLens by remember { mutableStateOf(CameraLens.BACK) }
    var blurStyle by remember { mutableStateOf(BlurStyle.PIXELATE) }
    var isTorchOn by remember { mutableStateOf(false) }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }
    var showBroadcastDialog by remember { mutableStateOf(false) }

    // BLE Opt-Out Scanner and Broadcaster
    val bleScanner = remember { BleOptOutScanner(context.applicationContext, coroutineScope) }
    val bleAdvertiser = remember { BleOptOutAdvertiser(context.applicationContext, coroutineScope) }
    val associationEngine = remember { FaceAssociationEngine() }
    val exportCoordinator = remember { PrivacyExportCoordinator(context.applicationContext, coroutineScope) }

    val bleScannerState by bleScanner.scannerState.collectAsState()
    val bleAdvertiserState by bleAdvertiser.advertiserState.collectAsState()
    val exportState by exportCoordinator.exportState.collectAsState()

    // Start/Stop BLE Scanner with screen lifecycle and auto-resume on ON_RESUME
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                bleScanner.startScanning()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        bleScanner.startScanning()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            bleScanner.cleanup()
            bleAdvertiser.cleanup()
        }
    }

    // Analyzer is remembered across recompositions
    val analyzer = remember {
        FaceBlurImageAnalyzer(
            context = context.applicationContext,
            scope = coroutineScope
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            analyzer.shutdown()
        }
    }

    // Keep analyzer updated with current camera lens (for selfie mirroring)
    LaunchedEffect(currentLens) {
        analyzer.isFrontCamera = (currentLens == CameraLens.FRONT)
    }

    val detectionState by analyzer.detectionState.collectAsState()
    val frameAspectRatio by analyzer.frameAspectRatio.collectAsState()

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    // PreviewView holder
    var previewViewInstance by remember { mutableStateOf<PreviewView?>(null) }

    // Rebind CameraX when currentLens or previewViewInstance changes
    LaunchedEffect(currentLens, previewViewInstance) {
        val pView = previewViewInstance ?: return@LaunchedEffect
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(pView.surfaceProvider)
                    }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(analysisExecutor, analyzer)
                    }

                activeCamera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    currentLens.selector,
                    preview,
                    imageAnalysis
                )

                // Reset torch state on lens switch
                isTorchOn = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera use cases: ${e.message}", e)
                Toast.makeText(context, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = NavyDarkBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(NavyDarkBackground)
        ) {
            // 1. Live Camera Preview (CameraX)
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        previewViewInstance = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 2. High-Tech Privacy Face Blur Overlay
            val faces = when (val state = detectionState) {
                is DetectionState.FaceDetected -> state.faces
                else -> emptyList()
            }

            // Real-time Privacy Decision evaluating detected faces against BLE opt-out signals
            val privacyDecision = remember(faces, bleScannerState) {
                associationEngine.evaluatePrivacyDecision(faces, bleScannerState)
            }

            // Stream frames from analyzer to Privacy Video Export Coordinator
            LaunchedEffect(analyzer) {
                analyzer.onFrameAvailable = { bitmap, detectedFaces ->
                    if (exportCoordinator.isRecording) {
                        val frameDecision = associationEngine.evaluatePrivacyDecision(detectedFaces, bleScanner.scannerState.value)
                        exportCoordinator.processFrame(bitmap, detectedFaces, frameDecision)
                    }
                }
                analyzer.onFrameError = { error ->
                    if (exportCoordinator.isRecording) {
                        exportCoordinator.recordDiscardedFrame(error)
                    }
                }
            }

            FaceBlurOverlay(
                faces = faces,
                frameAspectRatio = frameAspectRatio,
                blurStyle = blurStyle,
                isFailClosedBlackoutActive = privacyDecision.shouldApplyFailClosedBlackoutIfNoFaces,
                modifier = Modifier.fillMaxSize()
            )

            // 3. Top Privacy Header
            PrivacyModeHeader(
                currentLens = currentLens,
                isTorchOn = isTorchOn,
                isBroadcasting = bleAdvertiserState is BleAdvertiserState.Advertising,
                onToggleLens = {
                    currentLens = currentLens.toggle()
                },
                onToggleTorch = {
                    val camera = activeCamera
                    if (camera != null && camera.cameraInfo.hasFlashUnit()) {
                        isTorchOn = !isTorchOn
                        camera.cameraControl.enableTorch(isTorchOn)
                    } else {
                        Toast.makeText(context, "Torch not supported on this lens", Toast.LENGTH_SHORT).show()
                    }
                },
                onOpenBroadcastDialog = {
                    showBroadcastDialog = true
                },
                modifier = Modifier.align(Alignment.TopCenter)
            )

            // 4. Floating Status Badges (Under Top Header)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 92.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PrivacyStatusBadge(state = detectionState)
                Spacer(modifier = Modifier.height(6.dp))
                PrivacyDecisionBadge(
                    decision = privacyDecision,
                    onClick = { showBroadcastDialog = true }
                )
            }

            // 5. Bottom Controls Bar
            val isRecording = exportState is ExportState.Recording
            val recordingDurationMs = (exportState as? ExportState.Recording)?.durationMs ?: 0L

            CameraControls(
                currentStyle = blurStyle,
                currentLens = currentLens,
                isRecording = isRecording,
                recordingDurationMs = recordingDurationMs,
                onStyleSelected = { blurStyle = it },
                onToggleLens = {
                    currentLens = currentLens.toggle()
                },
                onShutterClick = {
                    if (isRecording) {
                        exportCoordinator.stopRecording()
                    } else {
                        exportCoordinator.startRecording(blurStyle)
                    }
                },
                onToggleRecording = {
                    if (isRecording) {
                        exportCoordinator.stopRecording()
                    } else {
                        exportCoordinator.startRecording(blurStyle)
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            // 6. BLE Opt-Out Broadcaster / Transmitter Modal
            if (showBroadcastDialog) {
                OptOutBroadcastDialog(
                    state = bleAdvertiserState,
                    onToggleAdvertising = { start ->
                        if (start) {
                            bleAdvertiser.startAdvertising()
                        } else {
                            bleAdvertiser.stopAdvertising()
                        }
                    },
                    onDismiss = { showBroadcastDialog = false }
                )
            }

            // 7. Signed Privacy Export Success Modal
            val success = exportState as? ExportState.ExportSuccess
            if (success != null) {
                ExportSuccessDialog(
                    videoFile = success.videoFile,
                    manifestFile = success.manifestFile,
                    manifest = success.manifest,
                    initialVerification = success.verificationResult,
                    onDismiss = { exportCoordinator.dismissSuccess() }
                )
            }

            LaunchedEffect(exportState) {
                val err = exportState as? ExportState.Error
                if (err != null) {
                    snackbarHostState.showSnackbar("Export Error: ${err.message}")
                }
            }
        }
    }
}
