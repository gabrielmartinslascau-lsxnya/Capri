package com.dot.capri.monitor

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class CapriNotificationListenerService : NotificationListenerService() {

    data class NotificationItem(
        val key: String,
        val appPkg: String,
        val senderOrTitle: String,
        val messageText: String,
        val timestamp: Long,
        val replyAction: Notification.Action? = null,
        val remoteInput: RemoteInput? = null
    )

    companion object {
        @Volatile
        var instance: CapriNotificationListenerService? = null

        private val recentNotifications = mutableListOf<NotificationItem>()

        fun getRecentNotifications(): List<NotificationItem> {
            val now = System.currentTimeMillis()
            synchronized(recentNotifications) {
                recentNotifications.removeAll { now - it.timestamp > 60 * 60 * 1000L } // 1 hour window
                return recentNotifications.toList()
            }
        }

        fun getLatestMessageNotification(): NotificationItem? {
            val now = System.currentTimeMillis()
            synchronized(recentNotifications) {
                recentNotifications.removeAll { now - it.timestamp > 60 * 60 * 1000L }
                return recentNotifications.lastOrNull { it.replyAction != null && it.remoteInput != null }
                    ?: recentNotifications.lastOrNull()
            }
        }

        fun findNotificationBySender(query: String): NotificationItem? {
            val q = query.trim().lowercase()
            val now = System.currentTimeMillis()
            synchronized(recentNotifications) {
                recentNotifications.removeAll { now - it.timestamp > 60 * 60 * 1000L }
                return recentNotifications.lastOrNull {
                    it.senderOrTitle.lowercase().contains(q) || it.appPkg.lowercase().contains(q)
                }
            }
        }

        fun replyToNotification(context: Context, notif: NotificationItem, replyText: String): Boolean {
            val action = notif.replyAction ?: return false
            val remoteInput = notif.remoteInput ?: return false
            return try {
                val intent = Intent()
                val bundle = Bundle()
                bundle.putCharSequence(remoteInput.resultKey, replyText)
                RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
                action.actionIntent.send(context, 0, intent)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

        fun getRecentSummary(): String {
            val notifs = getRecentNotifications()
            if (notifs.isEmpty()) return "no recent notifications, choom. enjoy the silence."
            val sb = StringBuilder("here is what you missed:\n")
            notifs.takeLast(6).forEach { notif ->
                val appName = notif.appPkg.substringAfterLast('.').lowercase()
                sb.append("- [$appName] ${notif.senderOrTitle}: ${notif.messageText}\n")
            }
            return sb.toString().trim()
        }

        fun getCurrentPlayingSong(): String? {
            val notifs = getRecentNotifications()
            val mediaNotif = notifs.lastOrNull { notif ->
                val pkg = notif.appPkg.lowercase()
                pkg.contains("spotify") || pkg.contains("youtube") || pkg.contains("music") ||
                pkg.contains("soundcloud") || pkg.contains("pandora") || pkg.contains("deezer") ||
                notif.senderOrTitle.contains("play", ignoreCase = true) || notif.messageText.contains("play", ignoreCase = true)
            }
            if (mediaNotif != null) {
                return "\"${mediaNotif.senderOrTitle}\" by ${mediaNotif.messageText}"
            }
            return null
        }

        fun checkNotificationGatekeeper(): String? {
            val notifs = getRecentNotifications()
            val distinctApps = notifs.map { it.appPkg }.toSet()
            if (distinctApps.size >= 3) {
                val totalCount = notifs.size
                val pool = listOf(
                    "popular today. shame it's not with me.",
                    "your phone is vibrating like it matters.",
                    "$totalCount notifications. it's never anything.",
                    "buzzing all morning for absolute nonsense.",
                    "a symphony of distractions. well played."
                )
                return pool.random()
            }
            return null
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == "com.dot.capri") return

        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?: "notification"
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""

        // Extract direct-reply Quick Reply action if available
        var replyAction: Notification.Action? = null
        var replyRemoteInput: RemoteInput? = null

        val actions = notif.actions
        if (actions != null) {
            for (act in actions) {
                val inputs = act.remoteInputs
                if (inputs != null && inputs.isNotEmpty()) {
                    replyAction = act
                    replyRemoteInput = inputs[0]
                    break
                }
            }
        }

        val item = NotificationItem(
            key = sbn.key ?: "",
            appPkg = pkg,
            senderOrTitle = title,
            messageText = text,
            timestamp = System.currentTimeMillis(),
            replyAction = replyAction,
            remoteInput = replyRemoteInput
        )

        synchronized(recentNotifications) {
            recentNotifications.removeAll { it.key == item.key }
            recentNotifications.add(item)
            while (recentNotifications.size > 50) {
                recentNotifications.removeAt(0)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
