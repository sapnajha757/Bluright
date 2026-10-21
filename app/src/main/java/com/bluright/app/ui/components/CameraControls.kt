package com.bluright.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluright.app.model.BlurStyle
import com.bluright.app.model.CameraLens
import com.bluright.app.ui.theme.CyanAccent
import com.bluright.app.ui.theme.CyanPrimary
import com.bluright.app.ui.theme.NavyCard
import com.bluright.app.ui.theme.NavyDarkBackground
import com.bluright.app.ui.theme.NavyDarkSurface
import com.bluright.app.ui.theme.TealAccent
import com.bluright.app.ui.theme.TextMuted
import com.bluright.app.ui.theme.TextPrimary
import com.bluright.app.ui.theme.TextSecondary

@Composable
fun CameraControls(
    currentStyle: BlurStyle,
    currentLens: CameraLens,
    onStyleSelected: (BlurStyle) -> Unit,
    onToggleLens: () -> Unit,
    onShutterClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        NavyDarkSurface.copy(alpha = 0.92f),
                        NavyDarkBackground
                    )
                )
            )
            .border(
                1.dp,
                NavyCard,
                RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Blur Style Selector Tabs
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(NavyCard)
                .padding(3.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            BlurStyle.values().forEach { style ->
                val isSelected = style == currentStyle
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (isSelected) CyanPrimary else Color.Transparent)
                        .clickable { onStyleSelected(style) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = style.displayName,
                        color = if (isSelected) NavyDarkBackground else TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Center Action Row (Lens switch, Shutter, Recording Info)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Front/Back Switch Button
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(NavyCard)
                    .border(1.dp, TealAccent.copy(alpha = 0.4f), CircleShape)
                    .clickable { onToggleLens() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Cameraswitch,
                    contentDescription = "Switch Lens",
                    tint = CyanPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Central Shutter Button
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .border(3.dp, CyanPrimary, CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(CyanPrimary, CyanAccent)
                        )
                    )
                    .clickable { onShutterClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "Bluright Shutter",
                    tint = NavyDarkBackground,
                    modifier = Modifier.size(34.dp)
                )
            }

            // Phase 2 Video Recording Placeholder
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(NavyCard)
                    .border(1.dp, NavyCard, CircleShape)
                    .clickable { onShutterClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = "Phase 2 Recording",
                    tint = TextMuted,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Phase distinction caption
        Text(
            text = "Phase 1: Real-time on-device preview blur. Protected video encoding in Phase 2.",
            color = TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Normal
        )
    }
}
