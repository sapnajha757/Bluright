package com.bluright.app.ble

/**
 * Coarse distance categorization derived from raw Received Signal Strength Indicator (RSSI).
 *
 * NOTE: RSSI is heavily influenced by multipath reflections, walls, body shadowing,
 * phone orientation, and transmit power variance. It provides only an unverified
 * relative proximity hint, NOT a calibrated distance or physical coordinate.
 */
enum class DistanceCategory(val displayName: String) {
    IMMEDIATE("Immediate (< 1.5m)"),
    NEAR("Near (1.5m - 4m)"),
    FAR("Far (> 4m)"),
    UNKNOWN("Unknown")
}

/**
 * Represents a single observed BLE opt-out advertisement packet from a nearby device.
 *
 * @param token Raw ephemeral nonce (typically 8 bytes)
 * @param rssi Received Signal Strength Indicator in dBm
 * @param timestampMs Monotonic arrival timestamp
 */
data class BleObservation(
    val token: ByteArray,
    val rssi: Int,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val tokenHex: String get() = token.joinToString("") { "%02x".format(it) }

    val distanceCategory: DistanceCategory
        get() = when {
            rssi >= BleOptOutConstants.RSSI_IMMEDIATE_THRESHOLD -> DistanceCategory.IMMEDIATE
            rssi >= BleOptOutConstants.RSSI_NEAR_THRESHOLD -> DistanceCategory.NEAR
            else -> DistanceCategory.FAR
        }

    fun isStale(nowMs: Long = System.currentTimeMillis()): Boolean {
        return (nowMs - timestampMs) > BleOptOutConstants.OBSERVATION_STALE_TIMEOUT_MS
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BleObservation
        return token.contentEquals(other.token)
    }

    override fun hashCode(): Int {
        return token.contentHashCode()
    }
}
