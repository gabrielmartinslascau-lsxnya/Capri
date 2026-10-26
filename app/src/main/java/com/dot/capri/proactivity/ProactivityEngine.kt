package com.dot.capri.proactivity

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.dot.capri.BubbleService
import com.dot.capri.CapriAccessibilityService
import com.dot.capri.MainActivity
import com.dot.capri.monitor.CapriNotificationListenerService
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

    private var currentForegroundApp: String? = null
    private var appContinuousStartTime: Long = System.currentTimeMillis()
    private var lastUserInteractionTime = System.currentTimeMillis()
    private var commandCountToday = 0
    private var commandDay: Int = -1

    private fun todayOfYear(): Int = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

    // Watchdog disconnect detection
    private var nullRootStartTime: Long = 0L
    private var lastServiceRestartAttempt: Long = 0L

    fun recordUserInteraction() {
        lastUserInteractionTime = System.currentTimeMillis()
        if (commandDay != todayOfYear()) {
            commandDay = todayOfYear()
            commandCountToday = 0
        }
        commandCountToday++
    }

    private var heldMood: ProactivityGenerator.Mood? = null
    private var heldSince: Long = 0L
    private val MOOD_HOLD_MS = 10 * 60 * 1000L

    fun getCurrentMood(): ProactivityGenerator.Mood {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val forced = prefs.getString("forced_mood", "none")?.lowercase() ?: "none"

        if (forced != "none") {
            val forcedMood = when (forced) {
                "judging" -> ProactivityGenerator.Mood.JUDGING
                "pleased" -> ProactivityGenerator.Mood.PLEASED
                "bored" -> ProactivityGenerator.Mood.BORED
                "neutral" -> ProactivityGenerator.Mood.NEUTRAL
                "lonely" -> ProactivityGenerator.Mood.LONELY
                else -> null
            }
            if (forcedMood != null) return forcedMood
        }

        val now = System.currentTimeMillis()
        val detectedApp = ForegroundAppDetector.currentForegroundApp(context)

        if (detectedApp != currentForegroundApp) {
            currentForegroundApp = detectedApp
            appContinuousStartTime = now
        }

        val commandsToday = if (commandDay == todayOfYear()) commandCountToday else 0

        val computed: ProactivityGenerator.Mood = when {
            commandsToday >= 3 -> ProactivityGenerator.Mood.PLEASED
            detectedApp != null && (now - appContinuousStartTime >= 20 * 60 * 1000L) -> ProactivityGenerator.Mood.JUDGING
            now - lastUserInteractionTime >= 30 * 60 * 1000L -> ProactivityGenerator.Mood.LONELY
            else -> ProactivityGenerator.Mood.NEUTRAL
        }

        // Hold a mood for at least 10 minutes before switching, so she doesn't flip with every app switch.
        val held = heldMood
        if (held == null || (computed != held && now - heldSince >= MOOD_HOLD_MS)) {
            heldMood = computed
            heldSince = now
            Log.d("CAPRI_MOOD", "mood -> ${computed.name} | commandsToday=$commandsToday")
        }
        return heldMood ?: computed
    }

    private fun runWatchdogCheck(now: Long) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isScreenOn = pm?.isInteractive ?: true

        if (!isScreenOn) {
            nullRootStartTime = 0L
            return
        }

        val service = CapriAccessibilityService.instance
        val root = service?.rootInActiveWindow

        if (root == null) {
            if (nullRootStartTime == 0L) {
                nullRootStartTime = now
            } else if (now - nullRootStartTime >= 60_000L) {
                Log.w("CAPRI_WATCHDOG", "Accessibility Service disconnect detected (null root for 60s while screen on)")
                if (now - lastServiceRestartAttempt >= 300_000L) {
                    lastServiceRestartAttempt = now
                    Log.i("CAPRI_WATCHDOG", "Attempting service restart via ContextCompat.startForegroundService")
                    try {
                        val intent = Intent(context, BubbleService::class.java)
                        ContextCompat.startForegroundService(context, intent)
                    } catch (e: Exception) {
                        Log.e("CAPRI_WATCHDOG", "Service restart attempt failed", e)
                    }
                }
            }
        } else {
            nullRootStartTime = 0L
        }
    }

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

        val moodStr = getCurrentMood().name.lowercase()
        return "Mood: $moodStr | State: Armed"
    }

    fun getTriggerStatus(trigger: Trigger, currentApp: String?, now: Long): String {
        val state = getEngineState(now)
        if (!state.startsWith("Mood")) return state

        val quickMode = MainActivity.isQuickMode(context)
        return trigger.getDetailedStatus(context, currentApp, now, quickMode)
    }

    private var lastCommentAt: Long = 0L
    private val COMMENT_COOLDOWN_MS = 15 * 60 * 1000L
    private var lastMoodCommentAt: Long = 0L
    private val MOOD_QUIET_COOLDOWN_MS = 60 * 60 * 1000L

    // Mood-only comments: she speaks on her own only when nothing app-specific fired,
    // the global cooldown has passed, and she's been lonely (quiet) for a while.
    private fun maybeSpeakMood(now: Long) {
        val mood = getCurrentMood()
        if (mood != ProactivityGenerator.Mood.LONELY) return
        if (now - lastMoodCommentAt < MOOD_QUIET_COOLDOWN_MS) return
        if (now - lastCommentAt < COMMENT_COOLDOWN_MS) return

        val line = ProactivityGenerator.generateComment(mood, "", 0)
        lastMoodCommentAt = now
        lastCommentAt = now
        Log.d(TAG, "FIRED MoodQuiet \"$line\"")
        onTriggerComment(line)
    }

    private fun evaluate() {
        val now = System.currentTimeMillis()

        runWatchdogCheck(now)

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

        val notifComment = CapriNotificationListenerService.checkNotificationGatekeeper()
        if (notifComment != null) {
            Log.d(TAG, "FIRED NotificationGatekeeper \"$notifComment\"")
            lastCommentAt = now
            onTriggerComment(notifComment)
            return
        }

        val currentApp = ForegroundAppDetector.currentForegroundApp(context)
        val quickMode = MainActivity.isQuickMode(context)

        for (trigger in triggersList) {
            val event = trigger.evaluate(context, currentApp, now, quickMode)
            if (event != null) {
                val hourOfDay = cal.get(Calendar.HOUR_OF_DAY)
                val daypart = when {
                    hourOfDay < 12 -> "morning"
                    hourOfDay < 17 -> "afternoon"
                    else -> "evening"
                }
                val mood = getCurrentMood()
                val comment = ProactivityGenerator.generateComment(mood, daypart, trigger.getFireCountToday())
                Log.d(TAG, "FIRED ${trigger.id} \"$comment\"")
                lastCommentAt = now
                onTriggerComment(comment)
                return
            }
        }

        Log.d(TAG, "blocked by noTriggerThresholdMet (app: ${currentApp ?: "none"})")
        maybeSpeakMood(now)
    }
}
