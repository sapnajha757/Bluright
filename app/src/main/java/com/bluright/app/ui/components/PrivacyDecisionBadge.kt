package com.bluright.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluright.app.privacy.PrivacyDecision
import com.bluright.app.ui.theme.CyanPrimary
import com.bluright.app.ui.theme.NavyCard
import com.bluright.app.ui.theme.PrivacyDanger
import com.bluright.app.ui.theme.PrivacySafe
import com.bluright.app.ui.theme.PrivacyWarning
import com.bluright.app.ui.theme.TealAccent
import com.bluright.app.ui.theme.TextMuted
import com.bluright.app.ui.theme.TextPrimary

@Composable
fun PrivacyDecisionBadge(
    decision: PrivacyDecision,
    hasObservedOptOutInSession: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (label, detail, color, icon) = when (decision) {
        is PrivacyDecision.NoOptOutSignal -> {
            if (hasObservedOptOutInSession) {
                Quad(
                    "BLE: 0 OPT-OUTS",
                    "SESSION PROTECTED",
                    PrivacyWarning,
                    Icons.Default.Shield
                )
            } else {
                Quad(
                    "BLE: 0 OPT-OUTS",
                    "SCANNING",
                    TealAccent,
                    Icons.Default.Bluetooth
                )
            }
        }
        is PrivacyDecision.OptOutDetectedUnverified -> {
            val count = decision.activeObservations.size
            Quad(
                if (count == 1) "1 OPT-OUT BEACON" else "$count OPT-OUT BEACONS",
                "${decision.strongestRssi} dBm",
                CyanPrimary,
                Icons.Default.CellTower
            )
        }
        is PrivacyDecision.AmbiguousConservativeMask -> {
            val label = if (decision.visibleFacesCount > 1) {
                "LOW CONFIDENCE • ALL BLURRED (TAP TO SELECT)"
            } else {
                "LOW CONFIDENCE • SAFE MODE ACTIVE"
            }
            Quad(
                label,
                "${decision.strongestRssi} dBm",
                PrivacyWarning,
                Icons.Default.Shield
            )
        }
        is PrivacyDecision.AssociationSupported -> {
            val confPct = (decision.confidenceScore * 100).toInt()
            if (decision.isHighConfidence) {
                Quad(
                    "HIGH CONFIDENCE • SPECIFIC BLUR",
                    "${decision.rssi} dBm • $confPct%",
                    CyanPrimary,
                    Icons.Default.Shield
                )
            } else {
                Quad(
                    "MEDIUM CONFIDENCE • LIKELY FACE",
                    "WARNING • ${decision.rssi} dBm • $confPct%",
                    PrivacyWarning,
                    Icons.Default.Shield
                )
            }
        }
        is PrivacyDecision.OptOutSignalHold -> {
            Quad(
                "OPT-OUT HOLD • FAILSAFE",
                "${decision.elapsedSinceLossMs}ms AGO",
                PrivacyWarning,
                Icons.Default.Shield
            )
        }
        is PrivacyDecision.BleUnavailable -> {
            Quad(
                "BLE: OFF",
                "CAMERA MASK SAFE",
                TextMuted,
                Icons.Default.BluetoothDisabled
            )
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(NavyCard.copy(alpha = 0.88f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp)
            )

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = label,
                color = TextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp
            )

            Spacer(modifier = Modifier.width(6.dp))

            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(color)
            )

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = detail,
                color = color,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private data class Quad<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
