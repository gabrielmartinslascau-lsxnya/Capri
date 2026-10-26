package com.dot.capri.monitor

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class CapriNotificationListenerService : NotificationListenerService() {

    data class NotifSummary(val appPkg: String, val title: String, val text: String, val timestamp: Long)

    companion object {
        private val recentNotifications = mutableListOf<NotifSummary>()

        fun getRecentSummary(): String {
            val now = System.currentTimeMillis()
            synchronized(recentNotifications) {
                recentNotifications.removeAll { now - it.timestamp > 30 * 60 * 1000L } // 30 min window
                if (recentNotifications.isEmpty()) return "no recent notifications, choom. enjoy the silence."
                val sb = StringBuilder("here is what you missed:\n")
                recentNotifications.takeLast(10).forEach { notif ->
                    val appName = notif.appPkg.substringAfterLast('.').lowercase()
                    sb.append("- [$appName] ${notif.title}: ${notif.text}\n")
                }
                return sb.toString().trim()
            }
        }

        fun checkNotificationGatekeeper(): String? {
            val now = System.currentTimeMillis()
            synchronized(recentNotifications) {
                recentNotifications.removeAll { now - it.timestamp > 10 * 60 * 1000L }
                val distinctApps = recentNotifications.map { it.appPkg }.toSet()
                if (distinctApps.size >= 3) {
                    val totalCount = recentNotifications.size
                    val pool = listOf(
                        "popular today. shame it's not with me.",
                        "your phone is vibrating like it matters.",
                        "$totalCount notifications. it's never anything.",
                        "buzzing all morning for absolute nonsense.",
                        "a symphony of distractions. well played."
                    )
                    return pool.random()
                }
            }
            return null
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == "com.dot.capri") return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "notification"
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        synchronized(recentNotifications) {
            recentNotifications.add(NotifSummary(pkg, title, text, System.currentTimeMillis()))
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
