package com.dexcom.bgannouncer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dexcom.bgannouncer.data.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (settingsRepository.getSettings().monitoringEnabled) {
            CgmMonitorForegroundService.start(context)
        }
    }
}
