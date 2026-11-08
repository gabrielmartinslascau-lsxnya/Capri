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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

class ProactivityEngine(
    private val context: Context,
    private val onTriggerComment: (text: String) -> Unit,
    private val isChatActive: () -> Boolean
) {

    companion object {
        private const val TAG = "CAPRI_VISION_ENGINE"
        // 10 minutes in normal mode, 1 minute in quick mode
        private const val NORMAL_CHECK_INTERVAL_MS = 10 * 60 * 1000L
        private const val FAST_CHECK_INTERVAL_MS = 60 * 1000L

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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var isEvaluating = false
    private var mutedUntil: Long = 0L

    private var lastUserInteractionTime = System.currentTimeMillis()
    private var lastCommentAt: Long = 0L

    // Watchdog disconnect detection
    private var nullRootStartTime: Long = 0L
    private var lastServiceRestartAttempt: Long = 0L

    fun recordUserInteraction() {
        lastUserInteractionTime = System.currentTimeMillis()
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
                Log.w(TAG, "Accessibility Service disconnect detected (null root for 60s while screen on)")
                if (now - lastServiceRestartAttempt >= 300_000L) {
                    lastServiceRestartAttempt = now
                    Log.i(TAG, "Attempting service restart via ContextCompat.startForegroundService")
                    try {
                        val intent = Intent(context, BubbleService::class.java)
                        ContextCompat.startForegroundService(context, intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Service restart attempt failed", e)
                    }
                }
            }
        } else {
            nullRootStartTime = 0L
        }
    }

    private val checkRunnable = object : Runnable {
        override fun run() {
            evaluateVision()
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
        val quickMode = MainActivity.isQuickMode(context)
        val initialDelay = if (quickMode) 10_000L else 30_000L
        handler.postDelayed(checkRunnable, initialDelay)
        Log.d(TAG, "ProactivityEngine started (quickMode=$quickMode)")
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
        if (!disableQuietHours && (hour in 1..6)) return "QuietHours"

        return "State: Armed (Vision Proactivity)"
    }

    fun getTriggerStatus(trigger: Trigger, currentApp: String?, now: Long): String {
        val state = getEngineState(now)
        if (!state.startsWith("State: Armed")) return state

        val quickMode = MainActivity.isQuickMode(context)
        return trigger.getDetailedStatus(context, currentApp, now, quickMode)
    }

    /**
     * Periodic Vision Proactivity Evaluation:
     * - Captures screen in authorized apps (never sensitive/private apps)
     * - Uses exact real-time clock and battery level
     * - Evaluates screenshot through Capri's genuine Project Capricorn persona
     * - Displays authentic remark on the fluid droplet banner
     */
    private fun evaluateVision() {
        val now = System.currentTimeMillis()

        runWatchdogCheck(now)

        if (!MainActivity.isProactivityEnabled(context)) {
            Log.d(TAG, "Skipped: Proactivity is disabled in settings")
            return
        }

        if (now < mutedUntil) {
            Log.d(TAG, "Skipped: Currently muted")
            return
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive == false) {
            Log.d(TAG, "Skipped: Screen is off/sleeping")
            return
        }

        val disableQuietHours = MainActivity.isDevDisableQuietHours(context)
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        if (!disableQuietHours && (hour in 1..6)) {
            Log.d(TAG, "Skipped: Quiet hours (sleeping)")
            return
        }

        if (isChatActive()) {
            Log.d(TAG, "Skipped: Chat window currently active")
            return
        }

        val currentApp = ForegroundAppDetector.currentForegroundApp(context)

        // 1. IRONCLAD PRIVACY GUARD
        if (ProactivityGenerator.isPrivateOrSensitivePackage(currentApp)) {
            Log.d(TAG, "Privacy guard: skipped screenshot on sensitive app ($currentApp)")
            return
        }

        val quickMode = MainActivity.isQuickMode(context)
        val minCooldown = if (quickMode) 45_000L else 5 * 60 * 1000L
        if (now - lastCommentAt < minCooldown) {
            Log.d(TAG, "Skipped: Within cooldown window")
            return
        }

        if (isEvaluating) return
        isEvaluating = true

        scope.launch {
            try {
                val accessibilityService = CapriAccessibilityService.instance
                if (accessibilityService == null) {
                    isEvaluating = false
                    return@launch
                }

                // Capture live screenshot without overlays
                val screenshot = accessibilityService.captureScreenBitmap()
                if (screenshot == null || screenshot.isEmpty()) {
                    isEvaluating = false
                    return@launch
                }

                val time12 = android.text.format.DateFormat.format("h:mm a", cal).toString()
                val time24 = android.text.format.DateFormat.format("HH:mm", cal).toString()
                val battery = try {
                    (context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager)
                        .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                } catch (_: Throwable) { -1 }

                val appName = currentApp?.substringAfterLast('.')?.replaceFirstChar { it.uppercase() } ?: "Phone"
                val prompt = ProactivityGenerator.buildVisionPrompt(time12, time24, battery, appName)

                val reply = BubbleService.instance?.askBrain(prompt, screenshot)
                val cleanRemark = ProactivityGenerator.cleanVisionRemark(reply)

                if (!cleanRemark.isNullOrBlank()) {
                    lastCommentAt = System.currentTimeMillis()
                    handler.post {
                        onTriggerComment(cleanRemark)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Vision proactivity evaluation error", t)
            } finally {
                isEvaluating = false
            }
        }
    }
}
