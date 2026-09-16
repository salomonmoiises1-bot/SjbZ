package com.sjbz.aimp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sjbz.aimp.data.AudioSettingsDataStore
import com.sjbz.aimp.service.GlobalAudioService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * BootReceiver: Automatically starts GlobalAudioService upon system boot
 * so the 32-Band EQ and system-wide audio DSP are active immediately without
 * requiring the user to manually launch the app.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SBZ_BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received broadcast action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val dataStore = AudioSettingsDataStore(context)
                    val autoStart = dataStore.autoStartBootFlow.first()
                    val globalEnabled = dataStore.globalEnabledFlow.first()

                    if (autoStart && globalEnabled) {
                        Log.i(TAG, "Auto-starting GlobalAudioService on system boot")
                        GlobalAudioService.start(context)
                    } else {
                        Log.d(TAG, "Auto-start on boot skipped (autoStart=$autoStart, globalEnabled=$globalEnabled)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in BootReceiver: ${e.message}")
                    // Fallback to start service
                    GlobalAudioService.start(context)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
