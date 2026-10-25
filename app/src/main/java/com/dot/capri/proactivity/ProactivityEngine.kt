package com.dot.capri.proactivity

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dot.capri.MainActivity
import com.dot.capri.monitor.ForegroundAppDetector
import com.dot.capri.proactivity.triggers.CalculatorRepeatTrigger
import com.dot.capri.proactivity.triggers.CameraRetryTrigger
import com.dot.capri.proactivity.triggers.ChromeResearchTrigger
import com.dot.capri.proactivity.triggers.DoomScrollTrigger
import com.dot.capri.proactivity.triggers.IdleSpeechTrigger
import com.dot.capri.proactivity.triggers.MapsIdleTrigger
import com.dot.capri.proactivity.triggers.MusicLoopTrigger
import com.dot.capri.proactivity.triggers.WatchedAppDwellTrigger
import com.dot.capri.proactivity.triggers.WhatsAppCheckTrigger
import java.util.Calendar

class ProactivityEngine(
    private val context: Context,
    private val onTriggerComment: (text: String) -> Unit,
    private val isChatActive: () -> Boolean
) {

    companion object {
        private const val TAG = "CAPRI_ENGINE"
        private const val NORMAL_CHECK_INTERVAL_MS = 30_000L
        private const val FAST_CHECK_INTERVAL_MS = 10_000L

        val triggersList: List<Trigger> = listOf(
            DoomScrollTrigger(),
            ChromeResearchTrigger(),
            CameraRetryTrigger(),
            MusicLoopTrigger(),
            MapsIdleTrigger(),
            CalculatorRepeatTrigger(),
            WhatsAppCheckTrigger(),
            WatchedAppDwellTrigger(),
            IdleSpeechTrigger()
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var mutedUntil: Long = 0L

    private val checkRunnable = object : Runnable {
        override fun run() {
            evaluate()
            if (isRunning) {
                val quickMode = MainActivity.isQuickMode(context)
                val interval = if (quickMode) FAST_CHECK_INTERVAL_MS else NORMAL_CHECK_INTERVAL_MS
                handler.postDelayed(this, interval)
            }
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        handler.postDelayed(checkRunnable, 3_000L)
        Log.d(TAG, "ProactivityEngine started")
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(checkRunnable)
        Log.d(TAG, "ProactivityEngine stopped")
    }

    fun muteFor(hours: Int) {
        mutedUntil = System.currentTimeMillis() + (hours * 3600_000L)
        Log.d(TAG, "ProactivityEngine muted for $hours hours")
    }

    fun unmute() {
        mutedUntil = 0L
        Log.d(TAG, "ProactivityEngine unmuted")
    }

    fun isMuted(): Boolean {
        return System.currentTimeMillis() < mutedUntil
    }

    fun resetMuteState() {
        mutedUntil = 0L
        triggersList.forEach { it.resetCooldown() }
        Log.d(TAG, "Reset mute state and triggers")
    }

    fun resetFireCounts() {
        triggersList.forEach { it.resetFireCount() }
        Log.d(TAG, "Reset fire counts for all triggers")
    }

    fun getEngineState(now: Long): String {
        if (!MainActivity.isProactivityEnabled(context)) return "Disabled"
        if (now < mutedUntil) return "Muted"

        val disableQuietHours = MainActivity.isDevDisableQuietHours(context)
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        if (!disableQuietHours && (hour >= 23 || hour < 8)) return "QuietHours"

        return "Armed"
    }

    fun getTriggerStatus(trigger: Trigger, currentApp: String?, now: Long): String {
        val state = getEngineState(now)
        if (state != "Armed") return state

        val quickMode = MainActivity.isQuickMode(context)
        return trigger.getDetailedStatus(context, currentApp, now, quickMode)
    }

    private fun evaluate() {
        val now = System.currentTimeMillis()

        if (!MainActivity.isProactivityEnabled(context)) {
            Log.d(TAG, "blocked by engineDisabled")
            return
        }

        if (now < mutedUntil) {
            Log.d(TAG, "blocked by muted")
            return
        }

        val disableQuietHours = MainActivity.isDevDisableQuietHours(context)
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        if (!disableQuietHours && (hour >= 23 || hour < 8)) {
            Log.d(TAG, "blocked by quietHours")
            return
        }

        if (isChatActive()) {
            Log.d(TAG, "blocked by activeChatWindow")
            return
        }

        val currentApp = ForegroundAppDetector.currentForegroundApp(context)
        val quickMode = MainActivity.isQuickMode(context)

        for (trigger in triggersList) {
            val event = trigger.evaluate(context, currentApp, now, quickMode)
            if (event != null) {
                Log.d(TAG, "FIRED ${trigger.id} \"${event.comment}\"")
                onTriggerComment(event.comment)
                return
            }
        }

        Log.d(TAG, "blocked by noTriggerThresholdMet (app: ${currentApp ?: "none"})")
    }
}
