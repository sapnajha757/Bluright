package com.bluright.app.ble

/**
 * State of the BLE Opt-Out Broadcaster / Transmitter.
 */
sealed interface BleAdvertiserState {
    object Idle : BleAdvertiserState
    data class Advertising(val tokenHex: String, val startedAtMs: Long) : BleAdvertiserState
    data class Unsupported(val reason: String) : BleAdvertiserState
    object PermissionDenied : BleAdvertiserState
    object BluetoothDisabled : BleAdvertiserState
    data class Error(val errorCode: Int, val message: String) : BleAdvertiserState
}

/**
 * State of the BLE Opt-Out Scanner.
 */
sealed interface BleScannerState {
    object Idle : BleScannerState
    data class Scanning(val observations: List<BleObservation>) : BleScannerState
    object BluetoothDisabled : BleScannerState
    object PermissionDenied : BleScannerState
    data class Error(val message: String) : BleScannerState
}
