package com.bluright.app.ble

import android.os.ParcelUuid
import java.util.UUID

/**
 * Constants governing Bluright's Bluetooth Low Energy (BLE) Opt-Out signaling protocol.
 *
 * Packet specification:
 * - 16-bit Service UUID: 0xB101 (Bluright Opt-Out)
 * - 128-bit full UUID: 0000B101-0000-1000-8000-00805F9B34FB
 * - Service Data Payload: 8-byte rotating pseudonymous nonce
 *
 * This design ensures the entire advertisement fits inside legacy 31-byte advertising
 * packets without requiring BLE 5.0 Extended Advertising hardware support.
 */
object BleOptOutConstants {
    const val SERVICE_UUID_STRING = "0000B101-0000-1000-8000-00805F9B34FB"
    val SERVICE_PARCEL_UUID: ParcelUuid = ParcelUuid(UUID.fromString(SERVICE_UUID_STRING))

    /** Length of the ephemeral rotating nonce in bytes */
    const val EPHEMERAL_TOKEN_LENGTH_BYTES = 8

    /** Interval at which an advertiser rotates its ephemeral nonce (15 minutes) */
    const val TOKEN_ROTATION_INTERVAL_MS = 15 * 60 * 1000L

    /** Maximum age of a received BLE observation before it is pruned as stale */
    const val OBSERVATION_STALE_TIMEOUT_MS = 2500L

    /** RSSI Proximity Thresholds (dBm) for approximate relative distance estimation */
    const val RSSI_IMMEDIATE_THRESHOLD = -60 // Strongest signal, roughly <= 1.5 meters
    const val RSSI_NEAR_THRESHOLD = -75      // Moderate signal, roughly 1.5 - 4 meters
}
