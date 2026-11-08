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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupVisionResolutionControl()

        findViewById<android.view.View>(R.id.btnBrainInspector)?.setOnClickListener {
            startActivity(android.content.Intent(this, BrainInspectorActivity::class.java))
        }

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

        findViewById<View>(R.id.btn_open_customizability)?.setOnClickListener {
            startActivity(Intent(this, CustomizabilityActivity::class.java))
        }

        findViewById<View>(R.id.btnRealTalk)?.setOnClickListener {
            val intent = Intent(this, RealTalkActivity::class.java)
            startActivity(intent)
        }

        // Provider Options & Key Fields
        val activeProviderSpinner = findViewById<Spinner>(R.id.spinner_active_provider)
        val providerList = listOf("Auto (Priority)", "Gemini", "Anthropic", "OpenRouter", "Groq", "Local (On-Device)")
        val providerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providerList).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        activeProviderSpinner.adapter = providerAdapter

        val currentSavedProvider = prefs.getString(LlmRouter.KEY_ACTIVE_PROVIDER, "auto") ?: "auto"
        val providerIndex = when (currentSavedProvider.lowercase()) {
            "gemini" -> 1
            "anthropic" -> 2
            "openrouter" -> 3
            "groq" -> 4
            "local" -> 5
            else -> 0
        }
        activeProviderSpinner.setSelection(providerIndex)

        activeProviderSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedKey = when (position) {
                    1 -> "gemini"
                    2 -> "anthropic"
                    3 -> "openrouter"
                    4 -> "groq"
                    5 -> "local"
                    else -> "auto"
                }
                prefs.edit().putString(LlmRouter.KEY_ACTIVE_PROVIDER, selectedKey).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val apiKeyInput = findViewById<EditText>(R.id.input_api_key)
        val anthropicKeyInput = findViewById<EditText>(R.id.input_anthropic_key)
        val openRouterKeyInput = findViewById<EditText>(R.id.input_openrouter_key)
        val groqKeyInput = findViewById<EditText>(R.id.input_groq_key)

        apiKeyInput.setText(prefs.getString(KEY_GEMINI_API_KEY, ""))
        anthropicKeyInput.setText(prefs.getString(LlmRouter.KEY_ANTHROPIC_API_KEY, ""))
        openRouterKeyInput.setText(prefs.getString(LlmRouter.KEY_OPENROUTER_API_KEY, ""))
        groqKeyInput.setText(prefs.getString(LlmRouter.KEY_GROQ_API_KEY, ""))

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

        groqKeyInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val key = s?.toString()?.trim() ?: ""
                prefs.edit().putString(LlmRouter.KEY_GROQ_API_KEY, key).apply()
                android.util.Log.d("CAPRI_KEYS", "Groq API key updated instantly (length=${key.length}, blank=${key.isBlank()})")
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

        val backendSpinner = findViewById<Spinner>(R.id.spinner_local_backend)
        if (backendSpinner != null) {
            val backendLabels = listOf(
                "Auto GPU (GPU Text + Safe Vision)",
                "Full GPU (OpenCL Text + Vision)",
                "NPU (Snapdragon Hexagon / QNN)",
                "CPU Only (Compatibility Mode)"
            )
            val backendCodes = listOf("AUTO_GPU", "FULL_GPU", "NPU", "CPU")
            val backendAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, backendLabels).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            backendSpinner.adapter = backendAdapter
            val savedCode = com.dot.capri.ai.OnDeviceAiClient.getBackendMode(this)
            val initIdx = backendCodes.indexOf(savedCode).coerceAtLeast(0)
            backendSpinner.setSelection(initIdx)
            backendSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                    val chosenCode = backendCodes.getOrElse(pos) { "AUTO_GPU" }
                    if (chosenCode != com.dot.capri.ai.OnDeviceAiClient.getBackendMode(this@MainActivity)) {
                        com.dot.capri.ai.OnDeviceAiClient.setBackendMode(this@MainActivity, chosenCode)
                        updateLocalLlmStatus()
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
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

        val musicSpinner = findViewById<Spinner>(R.id.spinner_default_music)
        val reelsSpinner = findViewById<Spinner>(R.id.spinner_default_reels)
        val resetMuteBtn = findViewById<Button>(R.id.btn_reset_cooldowns).apply {
            text = "Reset mute"
        }
        val resetFireCountsBtn = findViewById<Button>(R.id.btn_reset_fire_counts)
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

        resetMuteBtn.setOnClickListener {
            BubbleService.instance?.getProactivityEngine()?.resetMuteState()
            Toast.makeText(this, "Reset mute state", Toast.LENGTH_SHORT).show()
            updateDebugTriggersInfo()
        }

        resetFireCountsBtn.setOnClickListener {
            Toast.makeText(this, "Vision proactivity is active with zero limits", Toast.LENGTH_SHORT).show()
            updateDebugTriggersInfo()
        }

        findViewById<Button>(R.id.btn_preview_cursor)?.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
                return@setOnClickListener
            }
            com.dot.capri.agent.DevOverlay.preview(this)
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
                if (!com.dot.capri.agent.DevOverlay.isEnabled(this@MainActivity)) {
                    com.dot.capri.agent.DevOverlay.stop()
                }
            }
        }

        updateDebugTriggersInfo()

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
        updateDebugTriggersInfo()
        updateLocalLlmStatus()
        updateSavedMacrosList()
    }



    private fun updateLocalLlmStatus() {
        val tvStatus = findViewById<TextView>(R.id.tv_local_llm_status) ?: return
        tvStatus.text = com.dot.capri.ai.OnDeviceAiClient.statusLine(this)
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

    private fun updateDebugTriggersInfo() {
        val debugView = findViewById<TextView>(R.id.debug_triggers_info) ?: return
        val now = System.currentTimeMillis()
        val currentApp = ForegroundAppDetector.currentForegroundApp(this)
        val engine = BubbleService.instance?.getProactivityEngine()

        if (engine == null) {
            debugView.text = "Service not running. Enable bubble first."
            return
        }

        val state = engine.getEngineState(now)
        val privacyStatus = if (com.dot.capri.proactivity.ProactivityGenerator.isStrictlySensitivePackage(currentApp)) {
            "Tier A (Sensitive - Complete Silence)"
        } else if (com.dot.capri.proactivity.ProactivityGenerator.isPrivateMessagingPackage(currentApp)) {
            "Tier B (Messaging - Zero Screenshots)"
        } else {
            "Tier C (Authorized - Full Vision)"
        }
        debugView.text = "$state\nForeground App: ${currentApp ?: "none"}\nPrivacy: $privacyStatus"
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

    private fun setupVisionResolutionControl() {
        val tvBadge = findViewById<android.widget.TextView>(R.id.tvVisionPixelBadge) ?: return
        val tvDesc = findViewById<android.widget.TextView>(R.id.tvVisionPixelDesc) ?: return
        val seekBar = findViewById<android.widget.SeekBar>(R.id.seekVisionPixels) ?: return

        val presetButtons = mapOf(
            256 to findViewById<android.widget.Button>(R.id.btnVision256),
            384 to findViewById<android.widget.Button>(R.id.btnVision384),
            512 to findViewById<android.widget.Button>(R.id.btnVision512),
            768 to findViewById<android.widget.Button>(R.id.btnVision768),
            1024 to findViewById<android.widget.Button>(R.id.btnVision1024)
        )

        seekBar.max = com.dot.capri.agent.VisionConfig.sliderMaxSteps()

        fun refreshVisionUi(maxSidePx: Int, updateSlider: Boolean) {
            val info = com.dot.capri.agent.VisionConfig.describeSelection(maxSidePx)
            tvBadge.text = info.title.substringBefore(" (")
            tvDesc.text = info.description

            if (updateSlider) {
                seekBar.progress = com.dot.capri.agent.VisionConfig.sideToSliderProgress(maxSidePx)
            }

            val activeTint = android.content.res.ColorStateList.valueOf(0xFFFF2D6B.toInt())
            val idleTint = android.content.res.ColorStateList.valueOf(0xFF222230.toInt())

            for ((presetPx, btn) in presetButtons) {
                btn ?: continue
                if (presetPx == maxSidePx) {
                    btn.backgroundTintList = activeTint
                    btn.setTextColor(android.graphics.Color.WHITE)
                } else {
                    btn.backgroundTintList = idleTint
                    btn.setTextColor(0xFFE0E0E0.toInt())
                }
            }
        }

        // Initialize from SharedPreferences
        val initialPx = com.dot.capri.agent.VisionConfig.getMaxSide(this)
        refreshVisionUi(initialPx, updateSlider = true)

        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val px = com.dot.capri.agent.VisionConfig.sliderProgressToSide(progress)
                val saved = com.dot.capri.agent.VisionConfig.setMaxSide(this@MainActivity, px)
                refreshVisionUi(saved, updateSlider = false)
            }

            override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {}
        })

        for ((presetPx, btn) in presetButtons) {
            btn?.setOnClickListener {
                val saved = com.dot.capri.agent.VisionConfig.setMaxSide(this, presetPx)
                refreshVisionUi(saved, updateSlider = true)
            }
        }
    }
}
