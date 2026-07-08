package com.dexcom.bgannouncer.bluetooth

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MediaNotificationListener : NotificationListenerService() {
    @Inject
    lateinit var sessionRegistry: ActiveMediaSessionRegistry

    override fun onListenerConnected() {
        super.onListenerConnected()
        sessionRegistry.onListenerConnected(this)
    }

    override fun onListenerDisconnected() {
        sessionRegistry.onListenerDisconnected()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sessionRegistry.refresh(this)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sessionRegistry.refresh(this)
    }
}
