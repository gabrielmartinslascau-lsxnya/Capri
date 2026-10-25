package com.dot.capri.proactivity.triggers

import android.content.Context
import com.dot.capri.proactivity.CommentEvent
import com.dot.capri.proactivity.Trigger
import java.util.Calendar

class DoomScrollTrigger : Trigger {

    override val id: String = "DoomScroll"

    companion object {
        private const val NORMAL_THRESHOLD_MS = 20 * 60 * 1000L
        private const val FAST_THRESHOLD_MS = 2 * 60 * 1000L

        private val WATCHED_APPS = mapOf(
            "com.zhiliaoapp.musically" to "TikTok",
            "com.ss.android.ugc.trill" to "TikTok",
            "com.instagram.android" to "Instagram",
            "com.google.android.youtube" to "YouTube"
        )

        private val COMMENTS = listOf(
            "another one.",
            "scrolling again. groundbreaking.",
            "that video was 22 seconds. you gave it 3.",
            "the algorithm says you're predictable. i agree.",
            "thumb workout looking great today.",
            "you've seen this one before. you don't remember.",
            "20 seconds of content. infinite supply.",
            "somewhere a creator is buying a yacht because of this.",
            "you could blink. just suggesting.",
            "it's the same video with different songs.",
            "{time} and you are still scrolling {app}."
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

        if (currentApp == null || !WATCHED_APPS.containsKey(currentApp)) {
            appForegroundSince = 0L
            return null
        }

        val appName = WATCHED_APPS[currentApp] ?: "app"

        if (appForegroundSince == 0L) {
            appForegroundSince = now
        }

        if (now - appForegroundSince >= threshold) {
            val template = COMMENTS[poolIndex % COMMENTS.size]
            poolIndex = (poolIndex + 1) % COMMENTS.size

            val cal = Calendar.getInstance()
            val time = android.text.format.DateFormat.format("HH:mm", cal).toString()
            val comment = template.replace("{app}", appName).replace("{time}", time)

            fireCountToday++
            appForegroundSince = now // rollover

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
        val isWatched = currentApp != null && WATCHED_APPS.containsKey(currentApp)
        if (!isWatched) return "Idle"
        return "Ready"
    }
}
