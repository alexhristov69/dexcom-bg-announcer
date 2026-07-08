package com.dexcom.bgannouncer.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build

object BluetoothAudioConnectivity {
    fun isLikelyConnected(context: Context): Boolean {
        if (!BluetoothPermissionHelper.hasConnectPermission(context)) return false

        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        if (!adapter.isEnabled) return false

        val profiles = buildList {
            add(BluetoothProfile.A2DP)
            add(BluetoothProfile.HEADSET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(BluetoothProfile.LE_AUDIO)
            }
        }

        for (profile in profiles) {
            val state = adapter.safeProfileConnectionState(profile) ?: continue
            if (state == BluetoothProfile.STATE_CONNECTED) {
                return true
            }
        }

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return false

        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            if (manager.safeConnectedDevices(profile).isNotEmpty()) {
                return true
            }
        }

        return false
    }

    private fun BluetoothAdapter.safeProfileConnectionState(profile: Int): Int? {
        return try {
            @Suppress("MissingPermission")
            getProfileConnectionState(profile)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
    }

    private fun BluetoothManager.safeConnectedDevices(profile: Int): List<BluetoothDevice> {
        return try {
            @Suppress("MissingPermission")
            getConnectedDevices(profile)
        } catch (_: IllegalArgumentException) {
            emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }
}
