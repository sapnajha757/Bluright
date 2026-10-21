package com.bluright.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bluright.app.ui.components.PermissionGate
import com.bluright.app.ui.screens.CameraScreen
import com.bluright.app.ui.theme.BlurightTheme
import com.bluright.app.ui.theme.NavyDarkBackground

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            BlurightTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = NavyDarkBackground
                ) {
                    BlurightApp(
                        hasPermissionInitially = checkCameraPermission(),
                        shouldShowRationale = {
                            ActivityCompat.shouldShowRequestPermissionRationale(
                                this,
                                Manifest.permission.CAMERA
                            )
                        }
                    )
                }
            }
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun BlurightApp(
    hasPermissionInitially: Boolean,
    shouldShowRationale: () -> Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasCameraPermission by remember { mutableStateOf(hasPermissionInitially) }
    var hasRequestedPermission by remember { mutableStateOf(false) }
    var isPermanentlyDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasRequestedPermission = true
        hasCameraPermission = isGranted
        if (!isGranted) {
            isPermanentlyDenied = !shouldShowRationale()
        }
    }

    // BLE Nearby Devices permission launcher for Android 12+ (API 31+)
    val blePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // BLE permissions response handled reactively by scanner and advertiser
    }

    // Auto-prompt camera permission on first launch if not already granted
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Prompt BLE permissions on Android 12+ once camera permission is secured
    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasScan = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            val hasAdv = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasScan || !hasAdv) {
                blePermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                )
            }
        }
    }

    if (hasCameraPermission) {
        CameraScreen(modifier = modifier)
    } else {
        PermissionGate(
            isPermanentlyDenied = isPermanentlyDenied,
            onRequestPermission = {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            },
            modifier = modifier
        )
    }
}
