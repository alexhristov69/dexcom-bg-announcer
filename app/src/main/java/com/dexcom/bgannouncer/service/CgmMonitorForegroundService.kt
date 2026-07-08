package com.dexcom.bgannouncer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.dexcom.bgannouncer.MainActivity
import com.dexcom.bgannouncer.R
import com.dexcom.bgannouncer.data.SettingsRepository
import com.dexcom.bgannouncer.dexcom.DexcomShareClient
import com.dexcom.bgannouncer.pipeline.GlucoseActionPipeline
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.min

@AndroidEntryPoint
class CgmMonitorForegroundService : LifecycleService() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var dexcomShareClient: DexcomShareClient
    @Inject lateinit var pipeline: GlucoseActionPipeline

    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        settingsRepository.updateRuntimeStatus { copy(serviceRunning = true) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        startMonitoringLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        settingsRepository.updateRuntimeStatus { copy(serviceRunning = false) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startMonitoringLoop() {
        if (monitorJob?.isActive == true) return
        monitorJob = lifecycleScope.launch {
            var errorBackoffMinutes = 0
            while (isActive) {
                val settings = settingsRepository.getSettings()
                val credentials = settingsRepository.getCredentials()
                if (credentials == null) {
                    settingsRepository.updateRuntimeStatus {
                        copy(lastError = "Dexcom credentials are not configured")
                    }
                    delay(settings.pollIntervalMinutes * 60_000L)
                    continue
                }

                val pollResult = runCatching {
                    dexcomShareClient.fetchLatestReading(credentials).getOrThrow()
                }

                pollResult.onSuccess { reading ->
                    errorBackoffMinutes = 0
                    settingsRepository.updateRuntimeStatus {
                        copy(
                            lastPollTime = System.currentTimeMillis(),
                            lastReadingValue = reading.valueMgDl,
                            lastReadingTrend = reading.trend.label,
                            lastReadingTime = reading.timestamp.toEpochMilli(),
                            lastError = null,
                        )
                    }
                    pipeline.processReading(
                        reading = reading,
                        settings = settings,
                        forceAnnounce = false,
                    )
                }.onFailure { error ->
                    errorBackoffMinutes = min(errorBackoffMinutes + 1, 5)
                    settingsRepository.updateRuntimeStatus {
                        copy(lastError = error.message ?: "Polling failed")
                    }
                }

                val intervalMinutes = settings.pollIntervalMinutes + errorBackoffMinutes
                delay(intervalMinutes * 60_000L)
            }
        }
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        settingsRepository.updateRuntimeStatus { copy(serviceRunning = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CgmMonitorForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.monitoring_notification_title))
            .setContentText(getString(R.string.monitoring_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_monitoring),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "com.dexcom.bgannouncer.action.STOP_MONITORING"
        private const val CHANNEL_ID = "cgm_monitoring"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, CgmMonitorForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CgmMonitorForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
