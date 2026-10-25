package com.dot.capri.monitor

import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

object AppUsageTotals {
    private var lastCacheTime: Long = 0L
    private val usageMap = mutableMapOf<String, Long>()

    fun totalTodayMinutes(context: Context, packageName: String): Long {
        refreshIfNeeded(context)
        val ms = usageMap[packageName] ?: 0L
        return ms / (60 * 1000L)
    }

    fun getUsageSummaryString(context: Context): String {
        refreshIfNeeded(context)
        val sb = StringBuilder()
        val sorted = usageMap.entries.sortedByDescending { it.value }
        for ((pkg, ms) in sorted.take(20)) {
            val mins = ms / (60 * 1000L)
            if (mins > 0) {
                sb.append("- $pkg: ${mins}m\n")
            }
        }
        return sb.toString().trim()
    }

    private fun refreshIfNeeded(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastCacheTime < 60_000L && usageMap.isNotEmpty()) return

        lastCacheTime = now
        usageMap.clear()

        try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startOfDay = cal.timeInMillis

            val aggregateStats = usageStatsManager.queryAndAggregateUsageStats(startOfDay, now)
            if (aggregateStats != null && aggregateStats.isNotEmpty()) {
                for ((pkg, stats) in aggregateStats) {
                    val totalMs = stats.totalTimeInForeground
                    if (totalMs > 0) {
                        usageMap[pkg] = totalMs
                    }
                }
            } else {
                val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
                if (stats != null) {
                    for (stat in stats) {
                        val pkg = stat.packageName
                        val totalMs = stat.totalTimeInForeground
                        if (totalMs > 0) {
                            usageMap[pkg] = (usageMap[pkg] ?: 0L) + totalMs
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
