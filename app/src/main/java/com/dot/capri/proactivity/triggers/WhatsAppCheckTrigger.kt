package com.dot.capri.proactivity.triggers

import android.content.Context
import com.dot.capri.proactivity.CommentEvent
import com.dot.capri.proactivity.Trigger
import java.util.Calendar

class WhatsAppCheckTrigger : Trigger {

    override val id: String = "ChatMarathon"

    companion object {
        private const val NORMAL_THRESHOLD_MS = 60 * 60 * 1000L
        private const val FAST_THRESHOLD_MS = 12 * 60 * 1000L

        private val CHAT_APPS = setOf(
            "com.whatsapp",
            "com.discord",
            "com.telegram.messenger",
            "com.facebook.orca"
        )

        private val COMMENTS = listOf(
            "two hours in there. better not be faraday.",
            "the group chat has you.",
            "that's not texting anymore. that's a residency.",
            "they know you're online. you can't hide.",
            "still typing. write a novel already.",
            "chat marathon in {daypart}. classic."
        )
    }

    private var appForegroundSince: Long = 0L
    private var previousApp: String? = null
    private var fireCountToday: Int = 0
    private var poolIndex: Int = 0

    override fun evaluate(context: Context, currentApp: String?, now: Long, fastMode: Boolean): CommentEvent? {
        val threshold = if (fastMode) FAST_THRESHOLD_MS else NORMAL_THRESHOLD_MS

        if (currentApp != previousApp) {
            appForegroundSince = 0L
            previousApp = currentApp
        }

        if (currentApp == null || !CHAT_APPS.contains(currentApp)) {
            appForegroundSince = 0L
            return null
        }

        if (appForegroundSince == 0L) {
            appForegroundSince = now
        }

        if (now - appForegroundSince >= threshold) {
            val template = COMMENTS[poolIndex % COMMENTS.size]
            poolIndex = (poolIndex + 1) % COMMENTS.size

            val cal = Calendar.getInstance()
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val daypart = when (hour) {
                in 5..10 -> "morning"
                in 11..16 -> "afternoon"
                in 17..22 -> "evening"
                else -> "night"
            }
            val comment = template.replace("{daypart}", daypart)

            fireCountToday++
            appForegroundSince = now

            return CommentEvent(comment, currentApp)
        }

        return null
    }

    override fun getFireCountToday(): Int = fireCountToday

    override fun resetFireCount() {
        fireCountToday = 0
    }

    override fun resetCooldown() {
        appForegroundSince = 0L
        poolIndex = 0
    }

    override fun getDetailedStatus(context: Context, currentApp: String?, now: Long, fastMode: Boolean): String {
        if (currentApp == null || !CHAT_APPS.contains(currentApp)) return "Idle"
        return "Ready"
    }
}
