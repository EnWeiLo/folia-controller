package tw.folia.controller

import android.service.notification.NotificationListenerService

// This service does nothing with notifications. Android only lets an app read other apps'
// media sessions (MediaSessionManager.getActiveSessions) while one of its notification
// listeners is enabled, so its existence is the permission.
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaHub.onListenerConnected()
    }
}
