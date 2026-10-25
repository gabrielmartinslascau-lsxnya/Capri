package com.dot.capri.proactivity.triggers

import android.content.Context
import com.dot.capri.proactivity.CommentEvent
import com.dot.capri.proactivity.Trigger

class ChromeResearchTrigger : Trigger {

    override val id: String = "ChromeResearch"

    companion object {
        private const val CHROME_PKG = "com.android.chrome"
        private const val NORMAL_THRESHOLD_MS = 30 * 60 * 1000L
        private const val FAST_THRESHOLD_MS = 3 * 60 * 1000L
        private const val RESET_ABSENCE_MS = 60 * 1000L

        private val COMMENTS = listOf(
            "still researching. just pick one.",
            "tab 40. tab 40 of nothing.",
            "you're not reading all of those.",
            "the answer was on the first page. it always is.",
            "researching how to do the thing instead of doing the thing.",
            "another search. another shrug incoming.",
            "opening 15 more tabs will definitely solve it.",
            "half of those tabs are expired wikis."
        )
    }

    private var chromeForegroundStart: Long = 0L
    private var lastTimeInChrome: Long = 0L
    private var fireCountToday: Int = 0
    private var poolIndex: Int = 0

    override fun evaluate(context: Context, currentApp: String?, now: Long, fastMode: Boolean): CommentEvent? {
        val threshold = if (fastMode) FAST_THRESHOLD_MS else NORMAL_THRESHOLD_MS

        if (currentApp == CHROME_PKG) {
            if (chromeForegroundStart == 0L) {
                chromeForegroundStart = now
            } else if (now - lastTimeInChrome > RESET_ABSENCE_MS) {
                chromeForegroundStart = now
            }
            lastTimeInChrome = now

            if (now - chromeForegroundStart >= threshold) {
                val comment = COMMENTS[poolIndex % COMMENTS.size]
                poolIndex = (poolIndex + 1) % COMMENTS.size
                fireCountToday++
                chromeForegroundStart = now // rollover
                return CommentEvent(comment, CHROME_PKG)
            }
        } else {
            if (now - lastTimeInChrome > RESET_ABSENCE_MS) {
                chromeForegroundStart = 0L
            }
        }

        return null
    }

    override fun getFireCountToday(): Int = fireCountToday

    override fun resetFireCount() {
        fireCountToday = 0
    }

    override fun resetCooldown() {
        chromeForegroundStart = 0L
        poolIndex = 0
    }

    override fun getDetailedStatus(context: Context, currentApp: String?, now: Long, fastMode: Boolean): String {
        if (currentApp != CHROME_PKG) return "Idle"
        return "Ready"
    }
}
