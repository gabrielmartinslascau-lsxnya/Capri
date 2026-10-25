package com.dot.capri.proactivity.triggers

import android.content.Context
import com.dot.capri.proactivity.CommentEvent
import com.dot.capri.proactivity.Trigger

class CameraRetryTrigger : Trigger {

    override val id: String = "CameraRetry"

    companion object {
        private val CAMERA_PACKAGES = setOf(
            "com.google.android.GoogleCamera",
            "com.sec.android.app.camera",
            "com.android.camera"
        )
        private const val NORMAL_THRESHOLD_MS = 5 * 60 * 1000L
        private const val FAST_THRESHOLD_MS = 30 * 1000L

        private val COMMENTS = listOf(
            "taking 40 photos to pick one.",
            "lighting was fine three shots ago."
        )
    }

    private var appForegroundSince: Long = 0L
    private var fireCountToday: Int = 0
    private var poolIndex: Int = 0

    override fun evaluate(context: Context, currentApp: String?, now: Long, fastMode: Boolean): CommentEvent? {
        val threshold = if (fastMode) FAST_THRESHOLD_MS else NORMAL_THRESHOLD_MS

        if (currentApp == null || !CAMERA_PACKAGES.any { currentApp.contains(it, true) }) {
            appForegroundSince = 0L
            return null
        }

        if (appForegroundSince == 0L) {
            appForegroundSince = now
        }

        if (now - appForegroundSince >= threshold) {
            val comment = COMMENTS[poolIndex % COMMENTS.size]
            poolIndex = (poolIndex + 1) % COMMENTS.size
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
        if (currentApp == null || !CAMERA_PACKAGES.any { currentApp.contains(it, true) }) return "Idle"
        return "Ready"
    }
}
