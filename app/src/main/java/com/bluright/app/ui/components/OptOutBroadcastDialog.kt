package com.bluright.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bluright.app.ble.BleAdvertiserState
import com.bluright.app.ui.theme.CyanAccent
import com.bluright.app.ui.theme.CyanPrimary
import com.bluright.app.ui.theme.NavyCard
import com.bluright.app.ui.theme.NavyCardBorder
import com.bluright.app.ui.theme.NavyDarkBackground
import com.bluright.app.ui.theme.NavyDarkSurface
import com.bluright.app.ui.theme.PrivacyDanger
import com.bluright.app.ui.theme.PrivacySafe
import com.bluright.app.ui.theme.PrivacyWarning
import com.bluright.app.ui.theme.TealAccent
import com.bluright.app.ui.theme.TextMuted
import com.bluright.app.ui.theme.TextPrimary
import com.bluright.app.ui.theme.TextSecondary

@Composable
fun OptOutBroadcastDialog(
    state: BleAdvertiserState,
    onToggleAdvertising: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(NavyDarkSurface)
                .border(1.dp, NavyCardBorder, RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            // Dialog Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(NavyCard)
                            .border(1.dp, CyanPrimary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CellTower,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "BLE Opt-Out Beacon",
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Anonymous Radio Transmitter",
                            color = TealAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // State card & Switch
            val isAdvertising = state is BleAdvertiserState.Advertising
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(NavyCard)
                    .border(
                        1.dp,
                        if (isAdvertising) PrivacySafe.copy(alpha = 0.6f) else NavyCardBorder,
                        RoundedCornerShape(14.dp)
                    )
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isAdvertising) "BEACON ACTIVE" else "BEACON DISABLED",
                            color = if (isAdvertising) PrivacySafe else TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isAdvertising) {
                                "Broadcasting opt-out signal"
                            } else {
                                "Enable to signal Bluright cameras to blur you"
                            },
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }

                    Switch(
                        checked = isAdvertising,
                        onCheckedChange = { onToggleAdvertising(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NavyDarkBackground,
                            checkedTrackColor = CyanPrimary,
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = NavyDarkBackground
                        )
                    )
                }
            }

            // Error or Status messages
            when (state) {
                is BleAdvertiserState.Advertising -> {
                    Spacer(modifier = Modifier.height(14.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(NavyDarkBackground)
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = CyanAccent,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Rotating Ephemeral Nonce (15m window):",
                                color = TextSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "0x${state.tokenHex}",
                            color = CyanPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                is BleAdvertiserState.BluetoothDisabled -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Bluetooth is turned off on this phone. Please turn on Bluetooth to transmit.",
                        color = PrivacyWarning,
                        fontSize = 11.sp
                    )
                }
                is BleAdvertiserState.PermissionDenied -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Nearby Devices permission is required on Android 12+ to advertise BLE signals.",
                        color = PrivacyDanger,
                        fontSize = 11.sp
                    )
                }
                is BleAdvertiserState.Unsupported -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Hardware limitation: ${state.reason}",
                        color = PrivacyWarning,
                        fontSize = 11.sp
                    )
                }
                is BleAdvertiserState.Error -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Broadcasting error: ${state.message}",
                        color = PrivacyDanger,
                        fontSize = 11.sp
                    )
                }
                else -> Unit
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Privacy Guarantees
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(NavyDarkBackground.copy(alpha = 0.6f))
                    .padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Zero Identity Leakage",
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Bluright transmits an unlinked pseudorandom nonce. Device names, MAC addresses, and account IDs are never transmitted.",
                    color = TextMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NavyCard,
                    contentColor = TextPrimary
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Text(text = "Close", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}
