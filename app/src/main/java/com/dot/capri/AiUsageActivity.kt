package com.dot.capri

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dot.capri.llm.LlmRouter
import com.dot.capri.llm.UsageTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiUsageActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var isRefreshing = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateDashboardUI()
            if (isRefreshing) {
                handler.postDelayed(this, 2000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_usage)

        val budgetInput = findViewById<EditText>(R.id.input_token_budget)
        val saveBudgetBtn = findViewById<Button>(R.id.btn_save_budget)
        val modelSpinner = findViewById<Spinner>(R.id.spinner_model_override)

        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val providerName = LlmRouter.getActiveProviderName(this)
        val modelKey = when (providerName.lowercase()) {
            "anthropic" -> LlmRouter.KEY_ANTHROPIC_MODEL_OVERRIDE
            "openrouter" -> LlmRouter.KEY_OPENROUTER_MODEL_OVERRIDE
            else -> LlmRouter.KEY_GEMINI_MODEL_OVERRIDE
        }

        val modelsList = when (providerName.lowercase()) {
            "anthropic" -> listOf("Default (claude-3-5-sonnet)", "claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022", "claude-3-opus-20240229")
            "openrouter" -> listOf("Default (anthropic/claude-3.5-sonnet)", "anthropic/claude-3.5-sonnet", "google/gemini-flash-1.5", "openai/gpt-4o")
            else -> listOf("Default (gemini-3.5-flash-lite)", "gemini-1.5-flash", "gemini-1.5-pro", "gemini-2.0-flash", "gemini-2.5-flash")
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modelsList).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        modelSpinner.adapter = adapter

        if (providerName.lowercase() == "gemini") {
            lifecycleScope.launch(Dispatchers.IO) {
                val apiKey = MainActivity.getApiKey(this@AiUsageActivity)
                val fetched = com.dot.capri.llm.GeminiModelFetcher.fetchModels(apiKey)
                if (fetched.isNotEmpty()) {
                    val liveList = mutableListOf("Default (gemini-3.5-flash-lite)") + fetched
                    withContext(Dispatchers.Main) {
                        val liveAdapter = ArrayAdapter(this@AiUsageActivity, android.R.layout.simple_spinner_item, liveList).apply {
                            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        }
                        modelSpinner.adapter = liveAdapter
                        val currentModel = prefs.getString(modelKey, "") ?: ""
                        val idx = liveList.indexOfFirst { it.equals(currentModel, ignoreCase = true) }.let { if (it >= 0) it else 0 }
                        modelSpinner.setSelection(idx)
                    }
                }
            }
        }

        val currentModel = prefs.getString(modelKey, "") ?: ""
        val selectionIndex = modelsList.indexOfFirst { it.equals(currentModel, ignoreCase = true) }.let { if (it >= 0) it else 0 }
        modelSpinner.setSelection(selectionIndex)

        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = modelsList[position]
                val valToSave = if (selected.startsWith("Default")) "" else selected
                prefs.edit().putString(modelKey, valToSave).apply()
                updateDashboardUI()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val currentBudget = UsageTracker.getDailyBudget(this)
        if (currentBudget > 0) {
            budgetInput.setText(currentBudget.toString())
        }

        saveBudgetBtn.setOnClickListener {
            val entered = budgetInput.text.toString().trim()
            val budgetVal = entered.toIntOrNull() ?: 0
            UsageTracker.setDailyBudget(this, budgetVal)
            Toast.makeText(this, "Daily token budget set to ${if (budgetVal > 0) budgetVal else "unlimited"}", Toast.LENGTH_SHORT).show()
            updateDashboardUI()
        }

        updateDashboardUI()
    }

    override fun onResume() {
        super.onResume()
        isRefreshing = true
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        isRefreshing = false
        handler.removeCallbacks(refreshRunnable)
    }

    private fun updateDashboardUI() {
        val providerName = LlmRouter.getActiveProviderName(this)
        val activeProvider = LlmRouter.getActiveProviderInstance(this)
        val modelName = activeProvider?.currentModel ?: "None"

        val providerInfoTv = findViewById<TextView>(R.id.tv_active_provider_info)
        val quotaRemainingTv = findViewById<TextView>(R.id.tv_quota_remaining)
        val requestsTodayTv = findViewById<TextView>(R.id.tv_requests_today)
        val tokensTodayTv = findViewById<TextView>(R.id.tv_tokens_today)
        val avgSpeedTv = findViewById<TextView>(R.id.tv_avg_speed)
        val totalRequestsTv = findViewById<TextView>(R.id.tv_total_requests)
        val cumulativeTokensTv = findViewById<TextView>(R.id.tv_cumulative_tokens)
        val budgetStatusTv = findViewById<TextView>(R.id.tv_budget_status)
        val recentLogsTv = findViewById<TextView>(R.id.tv_recent_calls_log)

        providerInfoTv.text = "Provider: $providerName | Model: $modelName"
        requestsTodayTv.text = "Requests Today: ${UsageTracker.getRequestsToday(this)}"
        tokensTodayTv.text = "Tokens Today: ${UsageTracker.getTokensToday(this)}"

        val budget = UsageTracker.getDailyBudget(this)
        val tokensToday = UsageTracker.getTokensToday(this)
        if (budget > 0) {
            val remaining = maxOf(0, budget - tokensToday)
            val pct = (tokensToday.toDouble() / budget.toDouble()) * 100
            quotaRemainingTv.text = "Quota Remaining: $remaining tokens (%.1f%% used)".format(pct)
            budgetStatusTv.text = String.format("Budget status: %d / %d tokens (%.1f%%)", tokensToday, budget, pct)
        } else {
            quotaRemainingTv.text = "Quota Remaining: Unlimited"
            budgetStatusTv.text = "Budget status: Unlimited (0 set)"
        }

        val avgSpeed = UsageTracker.getAverageTokensPerSec(this)
        avgSpeedTv.text = String.format("Avg Speed: %.1f tok/sec (last 10 calls)", avgSpeed)

        totalRequestsTv.text = "Total Requests: ${UsageTracker.getTotalRequests(this)}"
        cumulativeTokensTv.text = "Cumulative Tokens: ${UsageTracker.getCumulativeTokens(this)}"

        val logs = UsageTracker.getCallLogs(this)
        if (logs.isEmpty()) {
            recentLogsTv.text = "No recent calls logged"
        } else {
            val sb = StringBuilder()
            logs.reversed().forEach { call ->
                val timeStr = android.text.format.DateFormat.format("HH:mm:ss", call.timestamp)
                sb.append("[$timeStr] ${call.provider} (${call.model}): in=${call.tokensIn}, out=${call.tokensOut}, latency=${call.latencyMs}ms\n")
            }
            recentLogsTv.text = sb.toString().trim()
        }
    }
}
