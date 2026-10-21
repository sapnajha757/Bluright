package com.bluright.app.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Manages the BLE Opt-Out Broadcaster (Transmitter Mode).
 *
 * Broadcasts an anonymous, rotating 8-byte pseudorandom nonce over BLE to notify
 * nearby recording devices of an opt-out preference.
 *
 * Privacy Guarantees:
 * - Device name, MAC addresses, and user identifiers are NEVER included in the payload.
 * - Nonces rotate automatically on an interval to hinder persistent radio tracking.
 * - Connections are refused (`isConnectable = false`).
 */
class BleOptOutAdvertiser(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private var advertiser: BluetoothLeAdvertiser? = null

    private val _advertiserState = MutableStateFlow<BleAdvertiserState>(BleAdvertiserState.Idle)
    val advertiserState: StateFlow<BleAdvertiserState> = _advertiserState.asStateFlow()

    private val secureRandom = SecureRandom()
    private var currentToken = ByteArray(BleOptOutConstants.EPHEMERAL_TOKEN_LENGTH_BYTES)
    private var activeCallback: AdvertiseCallback? = null
    private var rotationJob: Job? = null

    init {
        activeInstance = this
    }

    /**
     * Checks if the device has requisite permissions and hardware support for BLE advertising.
     */
    fun checkPrerequisites(): BleAdvertiserState? {
        val adapter = bluetoothAdapter
            ?: return BleAdvertiserState.Unsupported("Bluetooth adapter unavailable on this device")

        if (!adapter.isEnabled) {
            return BleAdvertiserState.BluetoothDisabled
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasAdvertise = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasAdvertise) {
                return BleAdvertiserState.PermissionDenied
            }
        }

        if (!adapter.isMultipleAdvertisementSupported) {
            return BleAdvertiserState.Unsupported("Hardware does not support peripheral BLE advertising")
        }

        return null
    }

    /**
     * Starts advertising an anonymous rotating opt-out token.
     */
    @Synchronized
    fun startAdvertising() {
        val prereq = checkPrerequisites()
        if (prereq != null) {
            _advertiserState.value = prereq
            return
        }

        val adapter = bluetoothAdapter ?: return
        val leAdvertiser = adapter.bluetoothLeAdvertiser
        if (leAdvertiser == null) {
            _advertiserState.value = BleAdvertiserState.Unsupported("BluetoothLeAdvertiser not provided by system")
            return
        }

        advertiser = leAdvertiser

        // Generate initial random nonce
        secureRandom.nextBytes(currentToken)
        val tokenHex = currentToken.joinToString("") { "%02x".format(it) }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .setTimeout(0) // Run until manually stopped or rotated
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // Strict privacy: NEVER broadcast device name
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(BleOptOutConstants.SERVICE_PARCEL_UUID)
            .addServiceData(BleOptOutConstants.SERVICE_PARCEL_UUID, currentToken)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "BLE Opt-Out advertisement started successfully. Nonce: $tokenHex")
                _advertiserState.value = BleAdvertiserState.Advertising(
                    tokenHex = tokenHex,
                    startedAtMs = System.currentTimeMillis()
                )
            }

            override fun onStartFailure(errorCode: Int) {
                val errorMsg = when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED -> "Advertising already active"
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "Packet data exceeds 31-byte limit"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "BLE advertising unsupported by hardware"
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "Bluetooth driver internal error"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "No advertisement slots available"
                    else -> "Unknown error code: $errorCode"
                }
                Log.e(TAG, "BLE Advertising failed: $errorMsg ($errorCode)")
                _advertiserState.value = BleAdvertiserState.Error(errorCode, errorMsg)
            }
        }

        activeCallback = callback

        try {
            leAdvertiser.startAdvertising(settings, data, callback)
            startPeriodicTokenRotation()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting advertising: ${e.message}")
            _advertiserState.value = BleAdvertiserState.PermissionDenied
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting advertising: ${e.message}", e)
            _advertiserState.value = BleAdvertiserState.Error(-1, e.message ?: "Unknown error")
        }
    }

    private fun startPeriodicTokenRotation() {
        rotationJob?.cancel()
        rotationJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(BleOptOutConstants.TOKEN_ROTATION_INTERVAL_MS)
                if (_advertiserState.value is BleAdvertiserState.Advertising) {
                    Log.i(TAG, "Rotating anonymous BLE opt-out token...")
                    stopAdvertising()
                    startAdvertising()
                }
            }
        }
    }

    /**
     * Stops the active opt-out advertisement.
     */
    @Synchronized
    fun stopAdvertising() {
        rotationJob?.cancel()
        rotationJob = null

        val callback = activeCallback
        val leAdvertiser = advertiser

        if (callback != null && leAdvertiser != null) {
            try {
                leAdvertiser.stopAdvertising(callback)
                Log.i(TAG, "BLE Opt-Out advertisement stopped")
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException stopping advertising: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Exception stopping advertising: ${e.message}")
            }
        }

        activeCallback = null
        advertiser = null
        _advertiserState.value = BleAdvertiserState.Idle
    }

    fun cleanup() {
        stopAdvertising()
    }

    companion object {
        private const val TAG = "BleOptOutAdvertiser"
        var activeInstance: BleOptOutAdvertiser? = null
    }
}
