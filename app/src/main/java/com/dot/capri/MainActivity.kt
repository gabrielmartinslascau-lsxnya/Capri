package com.dot.capri

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dot.capri.llm.LlmRouter
import com.dot.capri.monitor.ForegroundAppDetector
import com.dot.capri.proactivity.ProactivityEngine
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "capri_prefs"
        const val KEY_GEMINI_API_KEY = "user_gemini_api_key"
        const val KEY_PROACTIVITY_ENABLED = "proactivity_enabled"
        const val KEY_DEV_DISABLE_QUIET_HOURS = "dev_disable_quiet_hours"
        const val KEY_QUICK_MODE = "quick_mode"
        const val KEY_ALLOW_TYPING = "allow_typing"
        const val KEY_HIDE_BUBBLE_DOT = "hide_bubble_dot"
        const val KEY_DEFAULT_MUSIC_PLAYER = "default_music_player"
        const val KEY_DEFAULT_REELS_APP = "default_reels_app"

        fun isAllowTyping(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_ALLOW_TYPING, true)
        }

        fun isHideBubbleDot(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_HIDE_BUBBLE_DOT, false)
        }

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

        fun getDefaultMusicPlayer(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_DEFAULT_MUSIC_PLAYER, "spotify") ?: "spotify"
        }

        fun setDefaultMusicPlayer(context: Context, appName: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_DEFAULT_MUSIC_PLAYER, appName.lowercase().trim()).apply()
        }

        fun getDefaultReelsApp(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_DEFAULT_REELS_APP, "tiktok") ?: "tiktok"
        }

        fun setDefaultReelsApp(context: Context, appName: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_DEFAULT_REELS_APP, appName.lowercase().trim()).apply()
        }

        fun isAppInstalled(context: Context, packageName: String): Boolean {
            return try {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private val importLocalModelLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            importLocalModelUri(uri)
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
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            @Suppress("DEPRECATION")
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(permissionsToRequest.toTypedArray(), 101)
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // New Navigation Buttons
        findViewById<Button>(R.id.btn_open_tutorial)?.setOnClickListener {
            startActivity(Intent(this, TutorialActivity::class.java))
        }

        findViewById<Button>(R.id.btn_open_ai_usage).setOnClickListener {
            startActivity(Intent(this, AiUsageActivity::class.java))
        }

        findViewById<Button>(R.id.btn_open_health).setOnClickListener {
            startActivity(Intent(this, HealthActivity::class.java))
        }

        // Provider Options & Key Fields
        val activeProviderSpinner = findViewById<Spinner>(R.id.spinner_active_provider)
        val providerList = listOf("Auto (Priority)", "Gemini", "Anthropic", "OpenRouter")
        val providerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providerList).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        activeProviderSpinner.adapter = providerAdapter

        val currentSavedProvider = prefs.getString(LlmRouter.KEY_ACTIVE_PROVIDER, "auto") ?: "auto"
        val providerIndex = when (currentSavedProvider.lowercase()) {
            "gemini" -> 1
            "anthropic" -> 2
            "openrouter" -> 3
            else -> 0
        }
        activeProviderSpinner.setSelection(providerIndex)

        activeProviderSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedKey = when (position) {
                    1 -> "gemini"
                    2 -> "anthropic"
                    3 -> "openrouter"
                    else -> "auto"
                }
                prefs.edit().putString(LlmRouter.KEY_ACTIVE_PROVIDER, selectedKey).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val apiKeyInput = findViewById<EditText>(R.id.input_api_key)
        val anthropicKeyInput = findViewById<EditText>(R.id.input_anthropic_key)
        val openRouterKeyInput = findViewById<EditText>(R.id.input_openrouter_key)

        apiKeyInput.setText(prefs.getString(KEY_GEMINI_API_KEY, ""))
        anthropicKeyInput.setText(prefs.getString(LlmRouter.KEY_ANTHROPIC_API_KEY, ""))
        openRouterKeyInput.setText(prefs.getString(LlmRouter.KEY_OPENROUTER_API_KEY, ""))

        apiKeyInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val key = s?.toString()?.trim() ?: ""
                prefs.edit().putString(KEY_GEMINI_API_KEY, key).apply()
                android.util.Log.d("CAPRI_KEYS", "Gemini API key updated instantly (length=${key.length}, blank=${key.isBlank()})")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        anthropicKeyInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val key = s?.toString()?.trim() ?: ""
                prefs.edit().putString(LlmRouter.KEY_ANTHROPIC_API_KEY, key).apply()
                android.util.Log.d("CAPRI_KEYS", "Anthropic API key updated instantly (length=${key.length}, blank=${key.isBlank()})")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        openRouterKeyInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val key = s?.toString()?.trim() ?: ""
                prefs.edit().putString(LlmRouter.KEY_OPENROUTER_API_KEY, key).apply()
                android.util.Log.d("CAPRI_KEYS", "OpenRouter API key updated instantly (length=${key.length}, blank=${key.isBlank()})")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Local On-Device LLM Controls
        val switchLocalLlm = findViewById<SwitchCompat>(R.id.switch_local_llm)
        switchLocalLlm?.isChecked = prefs.getBoolean(com.dot.capri.ai.OnDeviceAiClient.KEY_LOCAL_LLM_ENABLED, true)
        switchLocalLlm?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(com.dot.capri.ai.OnDeviceAiClient.KEY_LOCAL_LLM_ENABLED, isChecked).apply()
            updateLocalLlmStatus()
        }

        findViewById<Button>(R.id.btn_import_local_llm)?.setOnClickListener {
            importLocalModelLauncher.launch("*/*")
        }

        val btnDownloadLocalLlm = findViewById<Button>(R.id.btn_download_local_llm)
        btnDownloadLocalLlm?.setOnClickListener {
            btnDownloadLocalLlm.isEnabled = false
            btnDownloadLocalLlm.text = "Downloading 0%..."
            com.dot.capri.ai.OnDeviceAiClient.downloadLocalModel(
                context = this,
                onProgress = { pct ->
                    btnDownloadLocalLlm.text = "Downloading $pct%..."
                },
                onComplete = { success, message ->
                    btnDownloadLocalLlm.isEnabled = true
                    btnDownloadLocalLlm.text = "Auto-Download Model"
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    updateLocalLlmStatus()
                }
            )
        }

        // Force Mood Spinner
        val forcedMoodSpinner = findViewById<Spinner>(R.id.spinner_forced_mood)
        val moodList = listOf("None", "Judging", "Pleased", "Bored", "Neutral", "Lonely")
        val moodAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, moodList).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        forcedMoodSpinner.adapter = moodAdapter

        val savedForcedMood = prefs.getString("forced_mood", "none") ?: "none"
        val forcedMoodIndex = moodList.indexOfFirst { it.equals(savedForcedMood, ignoreCase = true) }.let { if (it >= 0) it else 0 }
        forcedMoodSpinner.setSelection(forcedMoodIndex)

        forcedMoodSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedMood = moodList[position].lowercase()
                prefs.edit().putString("forced_mood", selectedMood).apply()
                updateDebugTriggersInfo()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val proactivitySwitch = findViewById<SwitchCompat>(R.id.switch_proactivity)
        val quietHoursSwitch = findViewById<SwitchCompat>(R.id.switch_dev_quiet_hours)
        val quickModeSwitch = findViewById<SwitchCompat>(R.id.switch_dev_reduce_thresholds)
        val musicSpinner = findViewById<Spinner>(R.id.spinner_default_music)
        val reelsSpinner = findViewById<Spinner>(R.id.spinner_default_reels)
        val resetMuteBtn = findViewById<Button>(R.id.btn_reset_cooldowns).apply {
            text = "Reset mute"
        }
        val resetFireCountsBtn = findViewById<Button>(R.id.btn_reset_fire_counts)
        val usageStatsBtn = findViewById<Button>(R.id.btn_enable_usage_stats)
        val enableBtn = findViewById<Button>(R.id.btn_enable_bubble)

        val candidateMusicApps = listOf(
            "Spotify" to "com.spotify.music",
            "YouTube" to "com.google.android.youtube",
            "YouTube Music" to "com.google.android.apps.youtube.music",
            "Apple Music" to "com.apple.android.music",
            "SoundCloud" to "com.soundcloud.android",
            "Deezer" to "deezer.android.app",
            "Local Player" to ""
        )

        val availableMusicApps = candidateMusicApps.filter { (_, pkg) ->
            pkg.isEmpty() || isAppInstalled(this, pkg)
        }.map { it.first }.toMutableList()

        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val installedApps = pm.queryIntentActivities(launcherIntent, 0)
        for (info in installedApps) {
            val label = info.loadLabel(pm).toString()
            if (label.contains("Mornye", ignoreCase = true) || label.contains("Poweramp", ignoreCase = true) || label.contains("VLC", ignoreCase = true)) {
                if (!availableMusicApps.contains(label)) {
                    availableMusicApps.add(label)
                }
            }
        }

        val musicAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, availableMusicApps).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        musicSpinner.adapter = musicAdapter
        val currentMusic = getDefaultMusicPlayer(this)
        val currentMusicIndex = availableMusicApps.indexOfFirst { it.contains(currentMusic, ignoreCase = true) }.let { if (it >= 0) it else 0 }
        musicSpinner.setSelection(currentMusicIndex)

        musicSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = availableMusicApps[position]
                setDefaultMusicPlayer(this@MainActivity, selected)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val candidateReelsApps = listOf(
            "TikTok" to "com.zhiliaoapp.musically",
            "Instagram" to "com.instagram.android",
            "YouTube" to "com.google.android.youtube"
        )

        val availableReelsApps = candidateReelsApps.filter { (_, pkg) ->
            isAppInstalled(this, pkg)
        }.map { it.first }.ifEmpty { listOf("YouTube") }

        val reelsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, availableReelsApps).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        reelsSpinner.adapter = reelsAdapter
        val currentReels = getDefaultReelsApp(this)
        val currentReelsIndex = availableReelsApps.indexOfFirst { it.contains(currentReels, ignoreCase = true) }.let { if (it >= 0) it else 0 }
        reelsSpinner.setSelection(currentReelsIndex)

        reelsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = availableReelsApps[position]
                setDefaultReelsApp(this@MainActivity, selected)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val allowTypingSwitch = findViewById<SwitchCompat>(R.id.switch_allow_typing)
        allowTypingSwitch.isChecked = prefs.getBoolean(KEY_ALLOW_TYPING, true)
        allowTypingSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_ALLOW_TYPING, isChecked).apply()
        }

        val hideBubbleSwitch = findViewById<SwitchCompat>(R.id.switch_hide_bubble)
        hideBubbleSwitch.isChecked = prefs.getBoolean(KEY_HIDE_BUBBLE_DOT, false)
        hideBubbleSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_HIDE_BUBBLE_DOT, isChecked).apply()
            BubbleService.instance?.refreshBubbleVisibility()
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

        findViewById<Button>(R.id.btn_preview_cursor)?.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
                return@setOnClickListener
            }
            com.dot.capri.agent.CapriCursor.show(this)
            lifecycleScope.launch {
                delay(300)
                val metrics = resources.displayMetrics
                com.dot.capri.agent.CapriCursor.moveTo(metrics.widthPixels / 4, metrics.heightPixels / 2)
                delay(500)
                com.dot.capri.agent.CapriCursor.pulse()
                delay(400)
                com.dot.capri.agent.CapriCursor.moveTo(3 * metrics.widthPixels / 4, metrics.heightPixels / 2)
                delay(500)
                com.dot.capri.agent.CapriCursor.pulse()
                delay(1500)
                com.dot.capri.agent.CapriCursor.hide()
            }
        }

        updateUsageStatsButton(usageStatsBtn)
        updateDebugTriggersInfo()

        usageStatsBtn.setOnClickListener {
            if (!ForegroundAppDetector.hasUsageStatsPermission(this)) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        val notifAccessBtn = findViewById<Button>(R.id.btn_enable_notif_access)
        notifAccessBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        val defaultAssistantBtn = findViewById<Button>(R.id.btn_set_default_assistant)
        defaultAssistantBtn.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            } catch (e: Exception) {
                try {
                    startActivity(Intent("android.settings.VOICE_INPUT_SETTINGS"))
                } catch (ex: Exception) {
                    Toast.makeText(this, "Go to Settings > Apps > Default Apps > Digital Assistant App and select Capri", Toast.LENGTH_LONG).show()
                }
            }
        }

        enableBtn.setOnClickListener {
            val keyEntered = apiKeyInput.text.toString().trim()
            val anthropicEntered = anthropicKeyInput.text.toString().trim()
            val openRouterEntered = openRouterKeyInput.text.toString().trim()

            prefs.edit()
                .putString(KEY_GEMINI_API_KEY, keyEntered)
                .putString(LlmRouter.KEY_ANTHROPIC_API_KEY, anthropicEntered)
                .putString(LlmRouter.KEY_OPENROUTER_API_KEY, openRouterEntered)
                .apply()

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
        updateLocalLlmStatus()
        updateSavedMacrosList()
    }

    private fun updateSavedMacrosList() {
        val container = findViewById<LinearLayout>(R.id.layout_saved_macros) ?: return
        container.removeAllViews()
        val macros = com.dot.capri.macro.MacroStore.all(this)
        if (macros.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No saved macros yet. Say 'record macro <name>' in chat."
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 12f
                setPadding(0, 4, 0, 8)
            }
            container.addView(emptyTv)
            return
        }

        macros.forEach { macro ->
            val btn = Button(this).apply {
                text = "Replay: '${macro.keyword}' (${macro.steps.size} steps)"
                setBackgroundColor(ContextCompat.getColor(context, R.color.input_bg))
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                textSize = 12f
                setOnClickListener {
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        requestOverlayPermission()
                        return@setOnClickListener
                    }
                    lifecycleScope.launch {
                        val result = com.dot.capri.macro.MacroRunner.run(this@MainActivity, macro) { }
                        Toast.makeText(this@MainActivity, result, Toast.LENGTH_LONG).show()
                    }
                }
            }
            container.addView(btn)
        }
    }

    private fun importLocalModelUri(uri: Uri) {
        val progressToast = Toast.makeText(this, "Importing local model file...", Toast.LENGTH_LONG)
        progressToast.show()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val targetFile = com.dot.capri.ai.OnDeviceAiClient.modelFile(this@MainActivity)
                    targetFile.parentFile?.mkdirs()
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Local model imported successfully!", Toast.LENGTH_SHORT).show()
                    updateLocalLlmStatus()
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to import model: ${e.message}", Toast.LENGTH_LONG).show()
                    updateLocalLlmStatus()
                }
            }
        }
    }

    private fun updateLocalLlmStatus() {
        val tvStatus = findViewById<TextView>(R.id.tv_local_llm_status) ?: return
        tvStatus.text = com.dot.capri.ai.OnDeviceAiClient.statusLine(this)
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
