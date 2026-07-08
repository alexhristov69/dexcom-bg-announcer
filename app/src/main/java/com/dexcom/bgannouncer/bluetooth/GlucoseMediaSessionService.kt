package com.dexcom.bgannouncer.bluetooth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.dexcom.bgannouncer.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GlucoseMediaSessionService : MediaSessionService() {
    @Inject
    lateinit var sessionHolder: GlucoseMediaSessionHolder

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaSession = sessionHolder.initializeInService(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession ?: sessionHolder.getSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_FLASH) {
            promoteToForeground()
            serviceScope.launch {
                val success = sessionHolder.executePendingFlash()
                stopForeground(STOP_FOREGROUND_REMOVE)
                sessionHolder.finishFlash(success)
                if (!success) {
                    sessionHolder.release()
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        sessionHolder.release()
        mediaSession = null
        super.onDestroy()
    }

    private fun promoteToForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.media_session_name))
            .setContentText(getString(R.string.bluetooth_art_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_bluetooth_art),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_FLASH = "com.dexcom.bgannouncer.action.FLASH_BT_ART"
        private const val CHANNEL_ID = "bluetooth_art_flash"
        private const val NOTIFICATION_ID = 1002
    }
}
