package com.dot.capri.proactivity.triggers

import android.content.Context
import com.dot.capri.proactivity.CommentEvent
import com.dot.capri.proactivity.Trigger

class IdleSpeechTrigger : Trigger {

    override val id: String = "IdleSpeech"

    companion object {
        private const val NORMAL_INTERVAL_MS = 120 * 60 * 1000L // 2 hours
        private const val FAST_INTERVAL_MS = 30 * 60 * 1000L // 30 min

        private val COMMENTS = listOf(
            "boriiinggg",
            "just sitting here. existing.",
            "tap me. or don't. i'm a bubble, not your boss.",
            "watching you watch your phone. riveting."
        )
    }

    private var lastFiredTime: Long = 0L
    private var fireCountToday: Int = 0
    private var poolIndex: Int = 0

    override fun evaluate(context: Context, currentApp: String?, now: Long, fastMode: Boolean): CommentEvent? {
        val interval = if (fastMode) FAST_INTERVAL_MS else NORMAL_INTERVAL_MS

        if (currentApp != null) {
            return null // Only fire when app is null (device idle / home screen)
        }

        if (lastFiredTime == 0L) {
            lastFiredTime = now
            return null
        }

        if (now - lastFiredTime >= interval) {
            val comment = COMMENTS[poolIndex % COMMENTS.size]
            poolIndex = (poolIndex + 1) % COMMENTS.size
            lastFiredTime = now
            fireCountToday++
            return CommentEvent(comment, "none")
        }

        return null
    }

    override fun getFireCountToday(): Int = fireCountToday

    override fun resetFireCount() {
        fireCountToday = 0
    }

    override fun resetCooldown() {
        lastFiredTime = 0L
        poolIndex = 0
    }

    override fun getDetailedStatus(context: Context, currentApp: String?, now: Long, fastMode: Boolean): String {
        if (currentApp != null) return "Idle"
        return "Ready"
    }
}
