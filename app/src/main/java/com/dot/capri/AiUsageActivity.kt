package com.dot.capri

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dot.capri.llm.LlmRouter
import com.dot.capri.llm.UsageTracker

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

        val avgSpeed = UsageTracker.getAverageTokensPerSec(this)
        avgSpeedTv.text = String.format("Avg Speed: %.1f tok/sec (last 10 calls)", avgSpeed)

        totalRequestsTv.text = "Total Requests: ${UsageTracker.getTotalRequests(this)}"
        cumulativeTokensTv.text = "Cumulative Tokens: ${UsageTracker.getCumulativeTokens(this)}"

        val budget = UsageTracker.getDailyBudget(this)
        val tokensToday = UsageTracker.getTokensToday(this)
        if (budget > 0) {
            val pct = (tokensToday.toDouble() / budget.toDouble()) * 100
            budgetStatusTv.text = String.format("Budget status: %d / %d tokens (%.1f%%)", tokensToday, budget, pct)
        } else {
            budgetStatusTv.text = "Budget status: Unlimited (0 set)"
        }

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
