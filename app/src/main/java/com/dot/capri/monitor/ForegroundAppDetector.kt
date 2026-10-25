package com.dot.capri.monitor

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log

object ForegroundAppDetector {

    private const val TAG = "CAPRI_WATCH"

    @Suppress("DEPRECATION")
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    @SuppressLint("WrongConstant")
    @Suppress("MissingPermission", "DEPRECATION")
    fun currentForegroundApp(context: Context): String? {
        if (!hasUsageStatsPermission(context)) {
            return null
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()

        try {
            val usageEvents = usm.queryEvents(now - 10 * 60_000, now)
            val event = UsageEvents.Event()
            var currentApp: String? = null
            var lastEventTime = 0L

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                val type = event.eventType
                if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (event.timeStamp >= lastEventTime) {
                        lastEventTime = event.timeStamp
                        currentApp = event.packageName
                    }
                } else if (type == UsageEvents.Event.ACTIVITY_PAUSED) {
                    if (event.packageName == currentApp && event.timeStamp >= lastEventTime) {
                        currentApp = null
                    }
                }
            }

            if (currentApp != null) {
                Log.d(TAG, "Foreground app: $currentApp")
                return currentApp
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 10 * 60_000, now)
            if (!stats.isNullOrEmpty()) {
                val mostRecent = stats.maxByOrNull { it.lastTimeUsed }
                if (mostRecent != null) {
                    Log.d(TAG, "Foreground app (stats): ${mostRecent.packageName}")
                    return mostRecent.packageName
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }
}
