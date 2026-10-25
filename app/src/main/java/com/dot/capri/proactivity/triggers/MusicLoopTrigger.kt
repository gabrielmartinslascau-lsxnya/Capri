package com.dot.capri.proactivity.triggers

import android.content.Context
import com.dot.capri.proactivity.CommentEvent
import com.dot.capri.proactivity.Trigger
import java.util.Calendar

class MusicLoopTrigger : Trigger {

    override val id: String = "MusicLoop"

    companion object {
        private const val NORMAL_THRESHOLD_MS = 120 * 60 * 1000L
        private const val FAST_THRESHOLD_MS = 12 * 60 * 1000L

        private val MUSIC_APPS = setOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.soundcloud.android",
            "deezer.android.app"
        )

        private val COMMENTS = listOf(
            "same playlist for two hours. i respect the commitment.",
            "this is less a playlist, more a lifestyle now.",
            "you know there are other songs.",
            "track 34. the vibe has not changed.",
            "on loop again during the {daypart}."
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

        if (currentApp == null || !MUSIC_APPS.contains(currentApp)) {
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
        if (currentApp == null || !MUSIC_APPS.contains(currentApp)) return "Idle"
        return "Ready"
    }
}
