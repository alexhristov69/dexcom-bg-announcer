package com.dexcom.bgannouncer.bluetooth

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class ActiveMediaInfo(
    val title: String?,
    val artist: String?,
    val packageName: String?,
)

@Singleton
class ActiveMediaSessionRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile
    private var listenerService: NotificationListenerService? = null

    @Volatile
    private var latestInfo: ActiveMediaInfo? = null

    fun onListenerConnected(service: NotificationListenerService) {
        listenerService = service
        refresh(service)
    }

    fun onListenerDisconnected() {
        listenerService = null
    }

    fun refresh(service: NotificationListenerService? = listenerService) {
        if ((service ?: listenerService) == null) return
        val controllers = getExternalControllers()

        val playing = controllers.firstOrNull { controller ->
            controller.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()

        latestInfo = playing?.let { controller ->
            val metadata = controller.metadata
            ActiveMediaInfo(
                title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
                artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
                packageName = controller.packageName,
            )
        }
    }

    fun getActiveMediaInfo(): ActiveMediaInfo? = latestInfo

    fun getPlayingController(): MediaController? {
        return getExternalControllers().firstOrNull { controller ->
            val state = controller.playbackState?.state
            state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
        }
    }

    fun getControllerForPackage(packageName: String?): MediaController? {
        if (packageName.isNullOrBlank()) return null
        return getExternalControllers().firstOrNull { it.packageName == packageName }
    }

    fun getActiveController(): MediaController? {
        return getExternalControllers().firstOrNull { controller ->
            controller.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: getExternalControllers().firstOrNull()
    }

    private fun getExternalControllers(): List<MediaController> {
        if (listenerService == null) return emptyList()
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(context, MediaNotificationListener::class.java)
        return try {
            manager.getActiveSessions(component).filter { controller ->
                controller.packageName != context.packageName
            }
        } catch (_: SecurityException) {
            emptyList()
        }
    }
}
