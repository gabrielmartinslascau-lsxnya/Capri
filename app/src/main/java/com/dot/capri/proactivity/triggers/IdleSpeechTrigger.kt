package com.dot.capri.proactivity.triggers

import android.content.Context
import com.dot.capri.proactivity.CommentEvent
import com.dot.capri.proactivity.Trigger

class IdleSpeechTrigger : Trigger {

    override val id: String = "IdleSpeech"

    companion object {
        private const val NORMAL_COOLDOWN_MS = 120 * 60 * 1000L // 2 hours between fires
        private const val FAST_COOLDOWN_MS = 30 * 60 * 1000L // 30 min between fires
        private const val CUMULATIVE_IDLE_THRESHOLD_MS = 15 * 1000L // 15 seconds cumulative on wallpaper

        private val COMMENTS = listOf(
            "boriiinggg",
            "just sitting here. existing.",
            "tap me. or don't. i'm a bubble, not your boss.",
            "watching you watch your phone. riveting."
        )
    }

    private var cumulativeIdleMs: Long = 0L
    private var lastCheckTime: Long = System.currentTimeMillis()
    private var lastFiredTime: Long = 0L
    private var fireCountToday: Int = 0
    private var poolIndex: Int = 0

    override fun evaluate(context: Context, currentApp: String?, now: Long, fastMode: Boolean): CommentEvent? {
        val cooldown = if (fastMode) FAST_COOLDOWN_MS else NORMAL_COOLDOWN_MS

        if (now - lastFiredTime < cooldown) {
            return null
        }

        val delta = now - lastCheckTime
        lastCheckTime = now

        if (currentApp == null) {
            cumulativeIdleMs += delta
        }

        if (cumulativeIdleMs >= CUMULATIVE_IDLE_THRESHOLD_MS) {
            cumulativeIdleMs = 0L
            lastFiredTime = now
            fireCountToday++
            val comment = COMMENTS[poolIndex % COMMENTS.size]
            poolIndex = (poolIndex + 1) % COMMENTS.size
            return CommentEvent(comment, "none")
        }

        return null
    }

    override fun getFireCountToday(): Int = fireCountToday

    override fun resetFireCount() {
        fireCountToday = 0
    }

    override fun resetCooldown() {
        cumulativeIdleMs = 0L
        lastFiredTime = 0L
        lastCheckTime = System.currentTimeMillis()
        poolIndex = 0
    }

    override fun getDetailedStatus(context: Context, currentApp: String?, now: Long, fastMode: Boolean): String {
        return "Ready"
    }
}
