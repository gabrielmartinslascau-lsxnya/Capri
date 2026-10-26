package com.dot.capri.llm

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class CallLogEntry(
    val timestamp: Long,
    val provider: String,
    val model: String,
    val tokensIn: Int,
    val tokensOut: Int,
    val latencyMs: Long
)

object UsageTracker {

    private const val PREFS_NAME = "capri_llm_usage"
    private const val KEY_TOTAL_REQUESTS = "total_requests"
    private const val KEY_REQUESTS_TODAY = "requests_today"
    private const val KEY_TOKENS_IN_TODAY = "tokens_in_today"
    private const val KEY_TOKENS_OUT_TODAY = "tokens_out_today"
    private const val KEY_CUMULATIVE_TOKENS = "cumulative_tokens"
    private const val KEY_LAST_DAY = "last_day_of_year"
    private const val KEY_DAILY_TOKEN_BUDGET = "daily_token_budget"
    private const val KEY_CALL_LOGS_JSON = "call_logs_json"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun checkAndResetDay(context: Context) {
        val prefs = getPrefs(context)
        val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val lastDay = prefs.getInt(KEY_LAST_DAY, -1)
        if (currentDay != lastDay) {
            prefs.edit()
                .putInt(KEY_REQUESTS_TODAY, 0)
                .putInt(KEY_TOKENS_IN_TODAY, 0)
                .putInt(KEY_TOKENS_OUT_TODAY, 0)
                .putInt(KEY_LAST_DAY, currentDay)
                .apply()
        }
    }

    @Synchronized
    fun recordCall(
        context: Context,
        provider: String,
        model: String,
        tokensIn: Int,
        tokensOut: Int,
        latencyMs: Long
    ) {
        checkAndResetDay(context)
        val prefs = getPrefs(context)

        val totalReq = prefs.getInt(KEY_TOTAL_REQUESTS, 0) + 1
        val reqToday = prefs.getInt(KEY_REQUESTS_TODAY, 0) + 1
        val tokInToday = prefs.getInt(KEY_TOKENS_IN_TODAY, 0) + tokensIn
        val tokOutToday = prefs.getInt(KEY_TOKENS_OUT_TODAY, 0) + tokensOut
        val cumTokens = prefs.getInt(KEY_CUMULATIVE_TOKENS, 0) + tokensIn + tokensOut

        // Provider specific metrics
        val pTotalReq = prefs.getInt("p_${provider}_total_req", 0) + 1
        val pTokToday = prefs.getInt("p_${provider}_tok_today", 0) + tokensIn + tokensOut

        val callEntry = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("provider", provider)
            put("model", model)
            put("tokensIn", tokensIn)
            put("tokensOut", tokensOut)
            put("latencyMs", latencyMs)
        }

        val logsJson = prefs.getString(KEY_CALL_LOGS_JSON, "[]") ?: "[]"
        val logsArr = try { JSONArray(logsJson) } catch (_: Exception) { JSONArray() }
        logsArr.put(callEntry)
        while (logsArr.length() > 20) {
            logsArr.remove(0)
        }

        prefs.edit()
            .putInt(KEY_TOTAL_REQUESTS, totalReq)
            .putInt(KEY_REQUESTS_TODAY, reqToday)
            .putInt(KEY_TOKENS_IN_TODAY, tokInToday)
            .putInt(KEY_TOKENS_OUT_TODAY, tokOutToday)
            .putInt(KEY_CUMULATIVE_TOKENS, cumTokens)
            .putInt("p_${provider}_total_req", pTotalReq)
            .putInt("p_${provider}_tok_today", pTokToday)
            .putString(KEY_CALL_LOGS_JSON, logsArr.toString())
            .apply()
    }

    fun getDailyBudget(context: Context): Int {
        return getPrefs(context).getInt(KEY_DAILY_TOKEN_BUDGET, 0)
    }

    fun setDailyBudget(context: Context, budget: Int) {
        getPrefs(context).edit().putInt(KEY_DAILY_TOKEN_BUDGET, budget).apply()
    }

    fun getRequestsToday(context: Context): Int {
        checkAndResetDay(context)
        return getPrefs(context).getInt(KEY_REQUESTS_TODAY, 0)
    }

    fun getTokensToday(context: Context): Int {
        checkAndResetDay(context)
        val prefs = getPrefs(context)
        return prefs.getInt(KEY_TOKENS_IN_TODAY, 0) + prefs.getInt(KEY_TOKENS_OUT_TODAY, 0)
    }

    fun getTotalRequests(context: Context): Int {
        return getPrefs(context).getInt(KEY_TOTAL_REQUESTS, 0)
    }

    fun getCumulativeTokens(context: Context): Int {
        return getPrefs(context).getInt(KEY_CUMULATIVE_TOKENS, 0)
    }

    fun isBudgetExhausted(context: Context): Boolean {
        val budget = getDailyBudget(context)
        if (budget <= 0) return false
        val tokensToday = getTokensToday(context)
        return tokensToday >= budget
    }

    fun isBudgetWarning(context: Context): Boolean {
        val budget = getDailyBudget(context)
        if (budget <= 0) return false
        val tokensToday = getTokensToday(context)
        return tokensToday >= (budget * 0.9) && tokensToday < budget
    }

    fun getAverageTokensPerSec(context: Context): Double {
        val logs = getCallLogs(context)
        if (logs.isEmpty()) return 0.0
        val recentLogs = logs.takeLast(10)
        val totalTokens = recentLogs.sumOf { it.tokensOut }
        val totalSec = recentLogs.sumOf { it.latencyMs } / 1000.0
        if (totalSec <= 0.0) return 0.0
        return totalTokens / totalSec
    }

    fun getCallLogs(context: Context): List<CallLogEntry> {
        val prefs = getPrefs(context)
        val logsJson = prefs.getString(KEY_CALL_LOGS_JSON, "[]") ?: "[]"
        val list = mutableListOf<CallLogEntry>()
        try {
            val arr = JSONArray(logsJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    CallLogEntry(
                        timestamp = obj.optLong("timestamp"),
                        provider = obj.optString("provider"),
                        model = obj.optString("model"),
                        tokensIn = obj.optInt("tokensIn"),
                        tokensOut = obj.optInt("tokensOut"),
                        latencyMs = obj.optLong("latencyMs")
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }
}
