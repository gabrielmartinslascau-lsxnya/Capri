package com.dot.capri.proactivity.triggers

import android.content.Context
import com.dot.capri.proactivity.CommentEvent
import com.dot.capri.proactivity.Trigger

class ChromeResearchTrigger : Trigger {

    override val id: String = "ChromeResearch"

    companion object {
        private val RESEARCH_PACKAGES = setOf("com.android.chrome", "com.google.android.googlequicksearchbox")
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

    private var researchForegroundStart: Long = 0L
    private var lastTimeInResearch: Long = 0L
    private var fireCountToday: Int = 0
    private var poolIndex: Int = 0

    override fun evaluate(context: Context, currentApp: String?, now: Long, fastMode: Boolean): CommentEvent? {
        val threshold = if (fastMode) FAST_THRESHOLD_MS else NORMAL_THRESHOLD_MS
        val isResearchApp = currentApp != null && RESEARCH_PACKAGES.contains(currentApp)

        if (isResearchApp) {
            if (researchForegroundStart == 0L) {
                researchForegroundStart = now
            } else if (now - lastTimeInResearch > RESET_ABSENCE_MS) {
                researchForegroundStart = now
            }
            lastTimeInResearch = now

            if (now - researchForegroundStart >= threshold) {
                val comment = COMMENTS[poolIndex % COMMENTS.size]
                poolIndex = (poolIndex + 1) % COMMENTS.size
                fireCountToday++
                researchForegroundStart = now // rollover
                return CommentEvent(comment, currentApp)
            }
        } else {
            if (now - lastTimeInResearch > RESET_ABSENCE_MS) {
                researchForegroundStart = newResearchForegroundStart()
            }
        }

        return null
    }

    private fun newResearchForegroundStart(): Long = 0L

    override fun getFireCountToday(): Int = fireCountToday

    override fun resetFireCount() {
        fireCountToday = 0
    }

    override fun resetCooldown() {
        researchForegroundStart = 0L
        poolIndex = 0
    }

    override fun getDetailedStatus(context: Context, currentApp: String?, now: Long, fastMode: Boolean): String {
        val isResearchApp = currentApp != null && RESEARCH_PACKAGES.contains(currentApp)
        if (!isResearchApp) return "Idle"
        return "Ready"
    }
}
