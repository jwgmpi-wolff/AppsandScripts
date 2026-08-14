package com.jerrywolff.phonesyncusbc

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jerrywolff.phonesyncusbc.data.NotificationCaptureStore

class PhoneSyncNotificationListenerService : NotificationListenerService() {
    private val store by lazy { NotificationCaptureStore(this) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching { activeNotifications.orEmpty().forEach(store::record) }
    }

    override fun onNotificationPosted(notification: StatusBarNotification) {
        store.record(notification)
    }

    override fun onNotificationRemoved(notification: StatusBarNotification) {
        store.markRemoved(notification.key)
    }
}