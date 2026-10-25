package com.dot.capri.proactivity.triggers

import android.content.Context
import com.dot.capri.monitor.AppUsageTotals
import com.dot.capri.proactivity.CommentEvent
import com.dot.capri.proactivity.Trigger
import java.util.Calendar

class WatchedAppDwellTrigger : Trigger {

    override val id: String = "AppDwell"

    companion object {
        private const val NORMAL_THRESHOLD_MS = 10 * 60 * 1000L
        private const val FAST_THRESHOLD_MS = 30 * 1000L

        private val KNOWN_APPS = mapOf(
            "com.spotify.music" to "spotify",
            "com.google.android.youtube" to "youtube",
            "com.zhiliaoapp.musically" to "tiktok",
            "com.instagram.android" to "instagram",
            "com.whatsapp" to "whatsapp",
            "com.android.chrome" to "chrome"
        )

        private val knownGenericPool = listOf(
            "on {app} again. usual.",
            "{time} and it's {app}. shocking.",
            "you and {app}. name a more iconic duo.",
            "{app}. again. no notes."
        )

        private val knownTotalsPool = listOf(
            "{total} minutes of {app} today. a creature of habit.",
            "over an hour on {app}. the data confirms it."
        )

        private val unknownAppPool = listOf(
            "what even is {app}.",
            "{app}. new one. i'll allow it.",
            "10 minutes in {app}. committed to the bit."
        )
    }

    private var appForegroundSince: Long = 0L
    private var previousApp: String? = null
    private var fireCountToday: Int = 0
    private var genericIndex = 0
    private var totalsIndex = 0
    private var unknownIndex = 0

    override fun evaluate(context: Context, currentApp: String?, now: Long, fastMode: Boolean): CommentEvent? {
        val threshold = if (fastMode) FAST_THRESHOLD_MS else NORMAL_THRESHOLD_MS

        if (currentApp != previousApp) {
            appForegroundSince = 0L
            previousApp = currentApp
        }

        if (currentApp == null) {
            appForegroundSince = 0L
            return null
        }

        if (appForegroundSince == 0L) {
            appForegroundSince = now
        }

        if (now - appForegroundSince >= threshold) {
            fireCountToday++
            appForegroundSince = now

            val totalMins = AppUsageTotals.totalTodayMinutes(context, currentApp)
            val isKnown = KNOWN_APPS.containsKey(currentApp)

            val comment = if (isKnown) {
                val appName = KNOWN_APPS[currentApp] ?: "app"
                if (totalMins >= 60 && totalsIndex < 10) { // rotation or condition
                    val template = knownTotalsPool[totalsIndex % knownTotalsPool.size]
                    totalsIndex = (totalsIndex + 1) % knownTotalsPool.size
                    template.replace("{app}", appName).replace("{total}", totalMins.toString())
                } else {
                    val template = knownGenericPool[genericIndex % knownGenericPool.size]
                    genericIndex = (genericIndex + 1) % knownGenericPool.size
                    val cal = Calendar.getInstance()
                    val time = android.text.format.DateFormat.format("HH:mm", cal).toString()
                    template.replace("{app}", appName).replace("{time}", time)
                }
            } else {
                // Unknown app: derive name by stripping common prefixes
                val rawName = currentApp.substringAfterLast('.')
                val template = unknownAppPool[unknownIndex % unknownAppPool.size]
                unknownIndex = (unknownIndex + 1) % unknownAppPool.size
                template.replace("{app}", rawName)
            }

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
        genericIndex = 0
        totalsIndex = 0
        unknownIndex = 0
    }

    override fun getDetailedStatus(context: Context, currentApp: String?, now: Long, fastMode: Boolean): String {
        if (currentApp == null) return "Idle"
        return "Ready"
    }
}
