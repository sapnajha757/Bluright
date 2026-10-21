package com.bluright.app

import android.Manifest
import android.content.pm.PackageManager
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

    // Auto-prompt permission on first launch if not already granted
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
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
