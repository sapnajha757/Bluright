package com.bluright.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluright.app.model.DetectionState
import com.bluright.app.ui.theme.CyanPrimary
import com.bluright.app.ui.theme.NavyCard
import com.bluright.app.ui.theme.PrivacyDanger
import com.bluright.app.ui.theme.PrivacySafe
import com.bluright.app.ui.theme.PrivacyWarning
import com.bluright.app.ui.theme.TextPrimary

@Composable
fun PrivacyStatusBadge(
    state: DetectionState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val (badgeText, badgeColor, icon, detailText) = when (state) {
        is DetectionState.FaceDetected -> {
            val count = state.faces.size
            val label = if (count == 1) "1 FACE MASKED" else "$count FACES MASKED"
            val detail = "${state.inferenceTimeMs}ms • ${"%.0f".format(state.fps)} FPS"
            Quadruple(label, CyanPrimary, Icons.Default.Lock, detail)
        }
        is DetectionState.ZeroFaces -> {
            val detail = "${state.inferenceTimeMs}ms • ${"%.0f".format(state.fps)} FPS"
            Quadruple("NO FACES • SAFE", PrivacySafe, null, detail)
        }
        is DetectionState.Scanning -> {
            val detail = if (state.fps > 0f) "${"%.0f".format(state.fps)} FPS" else "INITIALIZING"
            Quadruple("SCANNING", CyanPrimary, null, detail)
        }
        is DetectionState.Stale -> {
            Quadruple("DETECTION STALE", PrivacyWarning, Icons.Default.Warning, "FAIL-SAFE WARNING")
        }
        is DetectionState.Unavailable -> {
            Quadruple("DETECTOR OFFLINE", PrivacyDanger, Icons.Default.Warning, "FAILED")
        }
    }

    val animatedColor by animateColorAsState(targetValue = badgeColor, label = "badgeColor")

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(NavyCard.copy(alpha = 0.88f))
            .border(1.dp, animatedColor.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Pulsing status dot or icon
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = animatedColor,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(animatedColor)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = badgeText,
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = detailText,
                color = animatedColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
