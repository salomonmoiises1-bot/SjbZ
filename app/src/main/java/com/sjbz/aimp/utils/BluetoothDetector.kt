package com.sjbz.aimp.utils

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Monitors Bluetooth A2DP audio connectivity to automatically adapt
 * the ATS2835P DSP engine (gentle MDRC ratio and limiter bypass).
 */
class BluetoothDetector(
    private val context: Context,
    private val onBluetoothStateChanged: (isConnected: Boolean) -> Unit
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var isRegistered = false

    private val audioDeviceCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                checkBluetoothState()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                checkBluetoothState()
            }
        }
    } else null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            checkBluetoothState()
        }
    }

    fun start() {
        if (isRegistered) return
        isRegistered = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        context.registerReceiver(receiver, filter)

        checkBluetoothState()
    }

    fun stop() {
        if (!isRegistered) return
        isRegistered = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        }

        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {}
    }

    fun isBluetoothA2dpConnected(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (device in devices) {
                if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                ) {
                    return true
                }
            }
        }
        @Suppress("DEPRECATION")
        return audioManager.isBluetoothA2dpOn
    }

    fun checkBluetoothState() {
        val connected = isBluetoothA2dpConnected()
        onBluetoothStateChanged(connected)
    }
}
