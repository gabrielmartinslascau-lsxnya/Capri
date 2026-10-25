package com.dot.capri

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.dot.capri.monitor.ForegroundAppDetector
import com.dot.capri.proactivity.ProactivityEngine

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "capri_prefs"
        const val KEY_GEMINI_API_KEY = "user_gemini_api_key"
        const val KEY_PROACTIVITY_ENABLED = "proactivity_enabled"
        const val KEY_DEV_DISABLE_QUIET_HOURS = "dev_disable_quiet_hours"
        const val KEY_QUICK_MODE = "quick_mode"

        fun getApiKey(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val userKey = prefs.getString(KEY_GEMINI_API_KEY, "")?.trim() ?: ""
            if (userKey.isNotBlank()) {
                return userKey
            }
            return BuildConfig.GEMINI_API_KEY.trim()
        }

        fun isProactivityEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_PROACTIVITY_ENABLED, true)
        }

        fun isDevDisableQuietHours(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_DEV_DISABLE_QUIET_HOURS, false)
        }

        fun isQuickMode(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_QUICK_MODE, true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(permissionsToRequest.toTypedArray(), 101)
        }

        val apiKeyInput = findViewById<EditText>(R.id.input_api_key)
        val proactivitySwitch = findViewById<SwitchCompat>(R.id.switch_proactivity)
        val quietHoursSwitch = findViewById<SwitchCompat>(R.id.switch_dev_quiet_hours)
        val quickModeSwitch = findViewById<SwitchCompat>(R.id.switch_dev_reduce_thresholds)
        val resetMuteBtn = findViewById<Button>(R.id.btn_reset_cooldowns).apply {
            text = "Reset mute"
        }
        val resetFireCountsBtn = findViewById<Button>(R.id.btn_reset_fire_counts)
        val usageStatsBtn = findViewById<Button>(R.id.btn_enable_usage_stats)
        val enableBtn = findViewById<Button>(R.id.btn_enable_bubble)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(KEY_GEMINI_API_KEY, "")
        if (!savedKey.isNullOrBlank()) {
            apiKeyInput.setText(savedKey)
        }

        proactivitySwitch.isChecked = prefs.getBoolean(KEY_PROACTIVITY_ENABLED, true)
        proactivitySwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_PROACTIVITY_ENABLED, isChecked).apply()
            updateDebugTriggersInfo()
        }

        quietHoursSwitch.isChecked = prefs.getBoolean(KEY_DEV_DISABLE_QUIET_HOURS, false)
        quietHoursSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_DEV_DISABLE_QUIET_HOURS, isChecked).apply()
            updateDebugTriggersInfo()
        }

        quickModeSwitch.text = "Quick mode (frequent comments). Recommended."
        quickModeSwitch.isChecked = prefs.getBoolean(KEY_QUICK_MODE, true)
        quickModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_QUICK_MODE, isChecked).apply()
            updateDebugTriggersInfo()
        }

        resetMuteBtn.setOnClickListener {
            BubbleService.instance?.getProactivityEngine()?.resetMuteState()
            Toast.makeText(this, "Reset mute state", Toast.LENGTH_SHORT).show()
            updateDebugTriggersInfo()
        }

        resetFireCountsBtn.setOnClickListener {
            BubbleService.instance?.getProactivityEngine()?.resetFireCounts()
            Toast.makeText(this, "Reset all fire counts", Toast.LENGTH_SHORT).show()
            updateDebugTriggersInfo()
        }

        updateUsageStatsButton(usageStatsBtn)
        updateDebugTriggersInfo()

        usageStatsBtn.setOnClickListener {
            if (!ForegroundAppDetector.hasUsageStatsPermission(this)) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        enableBtn.setOnClickListener {
            val keyEntered = apiKeyInput.text.toString().trim()
            prefs.edit().putString(KEY_GEMINI_API_KEY, keyEntered).apply()

            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
            } else {
                startBubbleServiceAndFinish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val usageStatsBtn = findViewById<Button>(R.id.btn_enable_usage_stats)
        updateUsageStatsButton(usageStatsBtn)
        updateDebugTriggersInfo()
    }

    private fun updateUsageStatsButton(button: Button) {
        if (ForegroundAppDetector.hasUsageStatsPermission(this)) {
            button.text = getString(R.string.usage_stats_enabled)
            button.isEnabled = false
        } else {
            button.text = getString(R.string.enable_usage_stats)
            button.isEnabled = true
        }
    }

    private fun updateDebugTriggersInfo() {
        val debugView = findViewById<TextView>(R.id.debug_triggers_info) ?: return
        val now = System.currentTimeMillis()
        val currentApp = ForegroundAppDetector.currentForegroundApp(this)
        val engine = BubbleService.instance?.getProactivityEngine()

        if (engine == null) {
            debugView.text = "Service not running. Enable bubble first."
            return
        }

        val builder = StringBuilder("State: ${engine.getEngineState(now)}\n")
        ProactivityEngine.triggersList.forEach { trigger ->
            val status = engine.getTriggerStatus(trigger, currentApp, now)
            builder.append("${trigger.id}: ${trigger.getFireCountToday()} fired | $status\n")
        }
        debugView.text = builder.toString()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun startBubbleServiceAndFinish() {
        val intent = Intent(this, BubbleService::class.java)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Capri is active!", Toast.LENGTH_SHORT).show()
        finish()
    }
}
