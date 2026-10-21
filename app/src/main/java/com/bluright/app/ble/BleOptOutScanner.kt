package com.bluright.app.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.util.concurrent.ConcurrentHashMap

/**
 * High-efficiency BLE Scanner designed to detect nearby Bluright Opt-Out beacons.
 *
 * Scans exclusively for Bluright's Service UUID (0xB101) to minimize battery drain,
 * records ephemeral nonces with RSSI signal strengths, and continuously prunes stale
 * beacons that have moved out of range.
 */
class BleOptOutScanner(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private var scanner: BluetoothLeScanner? = null

    private val _scannerState = MutableStateFlow<BleScannerState>(BleScannerState.Idle)
    val scannerState: StateFlow<BleScannerState> = _scannerState.asStateFlow()

    private val activeObservations = ConcurrentHashMap<String, BleObservation>()
    private var activeCallback: ScanCallback? = null
    private var pruningJob: Job? = null
    private var isReceiverRegistered = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.i(TAG, "Bluetooth state is ON -> auto-resuming BLE scan")
                        startScanning()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        Log.w(TAG, "Bluetooth state is OFF -> pausing BLE scan")
                        _scannerState.value = BleScannerState.BluetoothDisabled
                    }
                }
            }
        }
    }

    private fun registerBluetoothReceiver() {
        if (!isReceiverRegistered) {
            try {
                val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
                context.registerReceiver(bluetoothReceiver, filter)
                isReceiverRegistered = true
            } catch (e: Exception) {
                Log.w(TAG, "Could not register bluetoothReceiver: ${e.message}")
            }
        }
    }

    private fun unregisterBluetoothReceiver() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(bluetoothReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Could not unregister bluetoothReceiver: ${e.message}")
            }
            isReceiverRegistered = false
        }
    }

    /**
     * Checks if prerequisite permissions and hardware states are satisfied.
     */
    fun checkPrerequisites(): BleScannerState? {
        val adapter = bluetoothAdapter
            ?: return BleScannerState.Error("Bluetooth hardware unavailable")

        if (!adapter.isEnabled) {
            return BleScannerState.BluetoothDisabled
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasScan = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasScan) {
                return BleScannerState.PermissionDenied
            }
        } else {
            val hasLocation = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasLocation) {
                return BleScannerState.PermissionDenied
            }
        }

        return null
    }

    /**
     * Starts scanning for nearby Bluright Opt-Out advertisements.
     */
    @Synchronized
    fun startScanning() {
        registerBluetoothReceiver()

        val prereq = checkPrerequisites()
        if (prereq != null) {
            Log.w(TAG, "Cannot start BLE scan - prerequisite unmet: $prereq")
            _scannerState.value = prereq
            return
        }

        val adapter = bluetoothAdapter ?: return
        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            Log.w(TAG, "BluetoothLeScanner not ready from system adapter")
            _scannerState.value = BleScannerState.Error("BluetoothLeScanner not ready")
            return
        }

        // If already actively scanning with this callback, don't re-register
        if (activeCallback != null && _scannerState.value is BleScannerState.Scanning) {
            return
        }

        scanner = leScanner
        activeObservations.clear()

        // Filter specifically for the Bluright Opt-Out Service UUID to minimize radio overhead
        val filter = ScanFilter.Builder()
            .setServiceUuid(BleOptOutConstants.SERVICE_PARCEL_UUID)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { handleScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                val msg = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "Scan already in progress"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Scanner registration failed"
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scan unsupported by hardware"
                    SCAN_FAILED_INTERNAL_ERROR -> "Bluetooth driver internal error"
                    else -> "Unknown scan error: $errorCode"
                }
                Log.e(TAG, "BLE Scan Failed: $msg ($errorCode)")
                _scannerState.value = BleScannerState.Error(msg)
            }
        }

        activeCallback = callback

        try {
            leScanner.startScan(listOf(filter), settings, callback)
            _scannerState.value = BleScannerState.Scanning(emptyList())
            startPruningStaleObservations()
            Log.i(TAG, "BLE Opt-Out scanner active. Filtering for Service UUID: ${BleOptOutConstants.SERVICE_UUID_STRING}")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting BLE scan: ${e.message}")
            _scannerState.value = BleScannerState.PermissionDenied
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting BLE scan: ${e.message}", e)
            _scannerState.value = BleScannerState.Error(e.message ?: "Failed to start scan")
        }
    }

    private fun handleScanResult(result: ScanResult?) {
        if (result == null) return
        val scanRecord = result.scanRecord ?: return

        // Extract the 8-byte ephemeral nonce from Service Data
        val serviceData = scanRecord.getServiceData(BleOptOutConstants.SERVICE_PARCEL_UUID)
            ?: scanRecord.serviceData.entries.firstOrNull {
                it.key.uuid == BleOptOutConstants.SERVICE_PARCEL_UUID.uuid
            }?.value

        if (serviceData != null && serviceData.isNotEmpty()) {
            val observation = BleObservation(
                token = serviceData,
                rssi = result.rssi,
                timestampMs = System.currentTimeMillis()
            )

            val isNew = !activeObservations.containsKey(observation.tokenHex)
            activeObservations[observation.tokenHex] = observation
            emitActiveObservations()

            if (isNew) {
                Log.i(
                    TAG,
                    "Detected Bluright Opt-Out Beacon! Nonce: ${observation.tokenHex}, RSSI: ${result.rssi} dBm (${observation.distanceCategory})"
                )
            } else {
                Log.d(
                    TAG,
                    "Updated Opt-Out Beacon: Nonce: ${observation.tokenHex}, RSSI: ${result.rssi} dBm"
                )
            }
        }
    }

    private fun startPruningStaleObservations() {
        pruningJob?.cancel()
        pruningJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1200)
                val now = System.currentTimeMillis()
                var removedAny = false

                val iterator = activeObservations.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.value.isStale(now)) {
                        iterator.remove()
                        removedAny = true
                    }
                }

                if (removedAny && _scannerState.value is BleScannerState.Scanning) {
                    emitActiveObservations()
                }
            }
        }
    }

    private fun emitActiveObservations() {
        val sortedList = activeObservations.values
            .sortedByDescending { it.rssi }
            .toList()
        _scannerState.value = BleScannerState.Scanning(sortedList)
    }

    /**
     * Stops the active BLE scan.
     */
    @Synchronized
    fun stopScanning() {
        pruningJob?.cancel()
        pruningJob = null

        val callback = activeCallback
        val leScanner = scanner

        if (callback != null && leScanner != null) {
            try {
                leScanner.stopScan(callback)
                Log.i(TAG, "BLE Opt-Out scanner stopped")
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException stopping BLE scan: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Exception stopping BLE scan: ${e.message}")
            }
        }

        activeCallback = null
        scanner = null
        activeObservations.clear()
        _scannerState.value = BleScannerState.Idle
    }

    fun cleanup() {
        unregisterBluetoothReceiver()
        stopScanning()
    }

    companion object {
        private const val TAG = "BleOptOutScanner"
    }
}
