package com.bluright.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluright.app.ui.theme.CyanAccent
import com.bluright.app.ui.theme.CyanPrimary
import com.bluright.app.ui.theme.NavyCard
import com.bluright.app.ui.theme.NavyCardBorder
import com.bluright.app.ui.theme.NavyDarkBackground
import com.bluright.app.ui.theme.NavyDarkSurface
import com.bluright.app.ui.theme.PrivacySafe
import com.bluright.app.ui.theme.TealAccent
import com.bluright.app.ui.theme.TextMuted
import com.bluright.app.ui.theme.TextPrimary
import com.bluright.app.ui.theme.TextSecondary

@Composable
fun PermissionGate(
    isPermanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NavyDarkBackground)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(NavyDarkSurface)
                .border(1.dp, NavyCardBorder, RoundedCornerShape(24.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Shield Icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(NavyCard)
                    .border(2.dp, CyanPrimary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Bluright",
                color = TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
            )

            Text(
                text = "The Right to Blur",
                color = TealAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isPermanentlyDenied) {
                    "Camera permission is required to detect and blur faces in real-time. Please grant permission in App Settings."
                } else {
                    "Bluright uses your device's camera exclusively for real-time, on-device face detection and privacy preservation."
                },
                color = TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Privacy Guarantees
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(NavyCard.copy(alpha = 0.6f))
                    .padding(12.dp)
            ) {
                PrivacyFeatureItem(text = "100% on-device AI face detection")
                Spacer(modifier = Modifier.height(6.dp))
                PrivacyFeatureItem(text = "Zero cloud processing or network uploads")
                Spacer(modifier = Modifier.height(6.dp))
                PrivacyFeatureItem(text = "Private by design, verified locally")
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (!isPermanentlyDenied) {
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanPrimary,
                        contentColor = NavyDarkBackground
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Grant Camera Permission",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            } else {
                Button(
                    onClick = { openAppSettings(context) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanPrimary,
                        contentColor = NavyDarkBackground
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Open App Settings",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanPrimary),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(brush = Brush.linearGradient(listOf(CyanPrimary, CyanAccent))),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text(text = "Retry Permission Check", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun PrivacyFeatureItem(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = PrivacySafe,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
