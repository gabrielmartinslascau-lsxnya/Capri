package com.dot.capri

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dot.capri.accessibility.ScreenWriter
import com.dot.capri.accessibility.WriteResult
import com.dot.capri.llm.LlmRouter
import com.dot.capri.llm.UsageTracker
import com.dot.capri.monitor.ForegroundAppDetector

class HealthActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_health)

        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        updateHealthStatus()
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btn_fix_overlay).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        findViewById<Button>(R.id.btn_fix_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btn_fix_usage_stats).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        findViewById<Button>(R.id.btn_fix_notif_listener).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btn_fix_default_assistant).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            } catch (_: Exception) {
                try {
                    startActivity(Intent("android.settings.VOICE_INPUT_SETTINGS"))
                } catch (_: Exception) {
                    Toast.makeText(this, "Open Settings > Apps > Default Apps > Digital Assistant App", Toast.LENGTH_LONG).show()
                }
            }
        }

        findViewById<Button>(R.id.btn_fix_battery_exemption).setOnClickListener {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } catch (_: Exception) {
                Toast.makeText(this, "Ignore battery optimization requested", Toast.LENGTH_SHORT).show()
            }
        }

        val typingTestBtn = findViewById<Button>(R.id.btn_run_typing_test)
        val scratchField = findViewById<EditText>(R.id.scratch_typing_field)
        val testResultTv = findViewById<TextView>(R.id.tv_typing_test_result)

        typingTestBtn.setOnClickListener {
            scratchField.requestFocus()
            val result = ScreenWriter.writeToFocusedField("Capri Self-Test Passed!")
            when (result) {
                is WriteResult.Success -> {
                    testResultTv.text = "Test result: SUCCESS! (Ladder step succeeded)"
                    testResultTv.setTextColor(Color.parseColor("#00E676"))
                }
                is WriteResult.Failed -> {
                    testResultTv.text = "Test result: FAILED (${result.attemptedSteps.joinToString()})"
                    testResultTv.setTextColor(Color.parseColor("#FF1744"))
                }
                is WriteResult.NoField -> {
                    testResultTv.text = "Test result: Field not focused or accessibility service disconnected"
                    testResultTv.setTextColor(Color.parseColor("#FFAB00"))
                }
                is WriteResult.OwnChatFocused -> {
                    testResultTv.text = "Test result: Own chat window focused"
                    testResultTv.setTextColor(Color.parseColor("#FFAB00"))
                }
            }
        }
    }

    private fun updateHealthStatus() {
        // 1. Overlay
        val hasOverlay = Settings.canDrawOverlays(this)
        setRowStatus(R.id.dot_overlay, R.id.status_overlay, R.id.btn_fix_overlay, hasOverlay, "Overlay Permission", if (hasOverlay) "Active" else "Disabled")

        // 2. Accessibility
        val accessibilityServiceConnected = CapriAccessibilityService.instance != null
        setRowStatus(R.id.dot_accessibility, R.id.status_accessibility, R.id.btn_fix_accessibility, accessibilityServiceConnected, "Accessibility Service", if (accessibilityServiceConnected) "Connected & Running" else "Disconnected")

        // 3. Usage Stats
        val hasUsage = ForegroundAppDetector.hasUsageStatsPermission(this)
        setRowStatus(R.id.dot_usage_stats, R.id.status_usage_stats, R.id.btn_fix_usage_stats, hasUsage, "Usage Stats Permission", if (hasUsage) "Granted" else "Disabled")

        // 4. Notification Listener
        val notifEnabled = isNotificationListenerEnabled(this)
        setRowStatus(R.id.dot_notif_listener, R.id.status_notif_listener, R.id.btn_fix_notif_listener, notifEnabled, "Notification Listener", if (notifEnabled) "Enabled" else "Disabled / Blocked")
        findViewById<LinearLayout>(R.id.card_sideload_helper).visibility = if (!notifEnabled) View.VISIBLE else View.GONE

        // 5. Default Assistant
        val defaultAssistant = isCapriDefaultAssistant(this)
        setRowStatus(R.id.dot_default_assistant, R.id.status_default_assistant, R.id.btn_fix_default_assistant, defaultAssistant, "Default Assistant", if (defaultAssistant) "Capri Active" else "Other / None")

        // 6. Battery Exemption
        val isIgnoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(packageName) ?: false
        } else true
        setRowStatus(R.id.dot_battery_exemption, R.id.status_battery_exemption, R.id.btn_fix_battery_exemption, isIgnoringBattery, "Battery Optimization", if (isIgnoringBattery) "Exempted" else "Unoptimized")

        // 7. AI Core
        val activeProviderName = LlmRouter.getActiveProviderName(this)
        val activeInstance = LlmRouter.getActiveProviderInstance(this)
        val modelName = activeInstance?.currentModel ?: "None"
        val health = activeInstance?.health()
        val aiStatusTv = findViewById<TextView>(R.id.status_ai_core)
        val aiDotTv = findViewById<TextView>(R.id.dot_ai_core)

        if (health == null || !health.isConfigured) {
            aiDotTv.setTextColor(Color.parseColor("#FFAB00"))
            aiStatusTv.text = "AI Core: Local-Only Mode (No Key Set)"
        } else if (UsageTracker.isBudgetExhausted(this)) {
            aiDotTv.setTextColor(Color.parseColor("#FF1744"))
            aiStatusTv.text = "AI Core: $activeProviderName ($modelName) - Quota Exhausted"
        } else if (!health.isReachable) {
            aiDotTv.setTextColor(Color.parseColor("#FF1744"))
            aiStatusTv.text = "AI Core: $activeProviderName ($modelName) - Unreachable (${health.lastError ?: "Error"})"
        } else {
            aiDotTv.setTextColor(Color.parseColor("#00E676"))
            aiStatusTv.text = "AI Core: $activeProviderName ($modelName) - Active & Healthy"
        }
    }

    private fun setRowStatus(dotResId: Int, statusResId: Int, btnResId: Int, isOk: Boolean, title: String, okText: String) {
        val dotTv = findViewById<TextView>(dotResId)
        val statusTv = findViewById<TextView>(statusResId)
        val btn = findViewById<Button>(btnResId)

        if (isOk) {
            dotTv.setTextColor(Color.parseColor("#00E676"))
            statusTv.text = "$title: $okText"
            btn.visibility = View.GONE
        } else {
            dotTv.setTextColor(Color.parseColor("#FF1744"))
            statusTv.text = "$title: $okText"
            btn.visibility = View.VISIBLE
        }
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
        return flat.contains(context.packageName)
    }

    private fun isCapriDefaultAssistant(context: Context): Boolean {
        val setting = Settings.Secure.getString(context.contentResolver, "assistant") ?: ""
        return setting.contains(context.packageName)
    }
}
