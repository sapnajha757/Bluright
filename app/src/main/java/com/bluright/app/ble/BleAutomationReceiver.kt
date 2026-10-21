package com.bluright.app.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bluright.app.export.PrivacyExportCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Manifest-registered broadcast receiver allowing ADB command automation
 * for cross-device BLE opt-out broadcast testing and automated video export.
 */
class BleAutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "BleAutomationReceiver received action: $action")

        val appContext = context.applicationContext
        val scope = CoroutineScope(Dispatchers.Default)

        when (action) {
            "com.bluright.app.ACTION_START_BEACON" -> {
                val advertiser = BleOptOutAdvertiser.activeInstance ?: BleOptOutAdvertiser(appContext, scope).also {
                    BleOptOutAdvertiser.activeInstance = it
                }
                Log.i(TAG, "Starting BLE Opt-Out advertisement via BleAutomationReceiver")
                advertiser.startAdvertising()
            }
            "com.bluright.app.ACTION_STOP_BEACON" -> {
                Log.i(TAG, "Stopping BLE Opt-Out advertisement via BleAutomationReceiver")
                BleOptOutAdvertiser.activeInstance?.stopAdvertising()
            }
            "com.bluright.app.ACTION_START_RECORDING" -> {
                Log.i(TAG, "Starting Privacy Video Recording via BleAutomationReceiver")
                PrivacyExportCoordinator.activeInstance?.startRecording()
            }
            "com.bluright.app.ACTION_STOP_RECORDING" -> {
                Log.i(TAG, "Stopping Privacy Video Recording via BleAutomationReceiver")
                PrivacyExportCoordinator.activeInstance?.stopRecording()
            }
        }
    }

    companion object {
        private const val TAG = "BleAutomationReceiver"
    }
}
