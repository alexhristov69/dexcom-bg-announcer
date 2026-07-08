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
import com.dexcom.bgannouncer.announce.GlucoseSpeechFormatter
import com.dexcom.bgannouncer.MainActivity
import com.dexcom.bgannouncer.R
import com.dexcom.bgannouncer.data.MonitorWorkflowRepository
import com.dexcom.bgannouncer.data.RuntimeStatus
import com.dexcom.bgannouncer.data.SettingsRepository
import com.dexcom.bgannouncer.data.WorkflowPhase
import com.dexcom.bgannouncer.data.WorkflowSource
import com.dexcom.bgannouncer.data.WorkflowState
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
    @Inject lateinit var workflowRepository: MonitorWorkflowRepository

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
        workflowRepository.clearMonitoringWorkflow()
        settingsRepository.updateRuntimeStatus {
            copy(serviceRunning = false, nextPollTime = null, isPolling = false)
        }
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
                    val delayMs = settings.pollIntervalMinutes * 60_000L
                    scheduleNextPoll(delayMs)
                    delay(delayMs)
                    continue
                }

                settingsRepository.updateRuntimeStatus {
                    copy(isPolling = true, nextPollTime = System.currentTimeMillis())
                }
                workflowRepository.setActive(
                    phase = WorkflowPhase.FETCHING_READING,
                    message = "Polling Dexcom Share",
                    source = WorkflowSource.MONITORING,
                )
                updateNotification()

                val pollResult = runCatching {
                    dexcomShareClient.fetchLatestReading(credentials).getOrThrow()
                }

                pollResult.onSuccess { reading ->
                    errorBackoffMinutes = 0
                    settingsRepository.updateRuntimeStatus {
                        copy(
                            isPolling = false,
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
                        forceAnnounce = true,
                        onStep = { message ->
                            workflowRepository.updateFromStep(message, WorkflowSource.MONITORING)
                        },
                    )
                    updateNotification()
                }.onFailure { error ->
                    errorBackoffMinutes = min(errorBackoffMinutes + 1, 5)
                    settingsRepository.updateRuntimeStatus {
                        copy(
                            isPolling = false,
                            lastPollTime = System.currentTimeMillis(),
                        )
                    }
                    if (DexcomShareClient.isNoReadingsError(error)) {
                        workflowRepository.setActive(
                            phase = WorkflowPhase.HANDLING_UNAVAILABLE,
                            message = "No glucose data available",
                            source = WorkflowSource.MONITORING,
                        )
                        pipeline.processUnavailableData(settings) { message ->
                            workflowRepository.updateFromStep(message, WorkflowSource.MONITORING)
                        }
                    } else {
                        settingsRepository.updateRuntimeStatus {
                            copy(lastError = error.message ?: "Polling failed")
                        }
                    }
                    updateNotification()
                }

                val intervalMinutes = settings.pollIntervalMinutes + errorBackoffMinutes
                val delayMs = intervalMinutes * 60_000L
                scheduleNextPoll(delayMs)
                workflowRepository.setWaitingForNextPoll()
                delay(delayMs)
            }
        }
    }

    private fun scheduleNextPoll(delayMs: Long) {
        settingsRepository.updateRuntimeStatus {
            copy(nextPollTime = System.currentTimeMillis() + delayMs)
        }
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        workflowRepository.clearMonitoringWorkflow()
        settingsRepository.updateRuntimeStatus {
            copy(serviceRunning = false, nextPollTime = null, isPolling = false)
        }
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
        val status = settingsRepository.getRuntimeStatus()
        val contentText = formatNotificationText(status)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.monitoring_notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun formatNotificationText(status: RuntimeStatus): String {
        if (DexcomShareClient.isNoReadingsMessage(status.lastError)) {
            return GlucoseSpeechFormatter.unavailableDisplayText()
        }

        val readingText = status.lastReadingValue?.let { value ->
            buildString {
                append(value)
                append(" mg/dL")
                status.lastReadingTrend?.takeIf { it.isNotBlank() }?.let { trend ->
                    append(" ")
                    append(trend)
                }
            }
        }

        return when {
            status.isPolling && readingText != null ->
                getString(R.string.monitoring_notification_updating, readingText)
            status.isPolling ->
                getString(R.string.monitoring_notification_polling)
            readingText != null -> readingText
            status.lastError != null -> status.lastError
            else -> getString(R.string.monitoring_notification_text)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
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
