package com.dot.capri

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dot.capri.accessibility.ScreenWriter
import com.dot.capri.accessibility.WriteResult
import com.dot.capri.agent.CapriCursor
import com.dot.capri.agent.PhoneAgent
import com.dot.capri.agent.PhoneControl
import com.dot.capri.ai.CloudAiClient
import com.dot.capri.ai.OnDeviceAiClient
import com.dot.capri.llm.LlmRouter
import com.dot.capri.intents.Cmd
import com.dot.capri.intents.IntentExecutor
import com.dot.capri.intents.IntentParser
import com.dot.capri.intents.VolumeDirection
import com.dot.capri.personality.Personality
import com.dot.capri.proactivity.ProactivityEngine
import com.dot.capri.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class BubbleService : Service() {

    enum class BubbleState {
        IDLE, THINKING, SPEAKING, DRAGGING, COMMENTING
    }

    companion object {
        const val CHANNEL_ID = "bubble_service"
        const val NOTIFICATION_ID = 1001
        var instance: BubbleService? = null

        private val persistentChatHistory = mutableListOf<Pair<String, String>>()
        fun getPersistentChatHistory(): MutableList<Pair<String, String>> = persistentChatHistory
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var pulseAnimatorX: ObjectAnimator? = null
    private var pulseAnimatorY: ObjectAnimator? = null

    private var chatView: View? = null
    private var bannerView: View? = null
    private var proactivityEngine: ProactivityEngine? = null

    private var lastWriteTimestamp = 0L
    private var pendingDraftText: String? = null
    private var pendingSend: Cmd.Text? = null
    private val easterEggCounts = mutableMapOf<String, Int>()

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private suspend fun setChatVisible(visible: Boolean) {
        withContext(Dispatchers.Main) {
            val v = chatView ?: return@withContext
            if (!visible) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
            }
            v.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
        if (!visible) delay(350)
    }

    fun getProactivityEngine(): ProactivityEngine? = proactivityEngine

    private fun resolveBubbleAsset(state: BubbleState): Int {
        return when (state) {
            BubbleState.IDLE -> R.drawable.bg_bubble_circle
            BubbleState.THINKING -> R.drawable.bg_bubble_circle
            BubbleState.SPEAKING -> R.drawable.bg_bubble_circle
            BubbleState.DRAGGING -> R.drawable.bg_bubble_circle
            BubbleState.COMMENTING -> R.drawable.bg_bubble_circle
        }
    }

    private fun resolveChatBackgroundAsset(): Int {
        return R.drawable.bg_chat_window
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundServiceWithNotification()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showBubble()

        proactivityEngine = ProactivityEngine(
            context = this,
            onTriggerComment = { comment -> showProactiveComment(comment) },
            isChatActive = { chatView != null }
        ).apply {
            start()
        }
    }

    private fun startForegroundServiceWithNotification() {
        val channelName = getString(R.string.notification_channel_name)
        val channelDesc = getString(R.string.notification_channel_desc)
        val channel = NotificationChannel(
            CHANNEL_ID,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = channelDesc
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_bubble_chat)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            startForeground(NOTIFICATION_ID, notification, fgsTypes)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun refreshBubbleVisibility() {
        if (MainActivity.isHideBubbleDot(this)) {
            removeBubble()
        } else {
            showBubble()
        }
    }

    fun showBubble() {
        if (MainActivity.isHideBubbleDot(this)) return
        if (bubbleView != null) return

        val themedContext = ContextThemeWrapper(this, R.style.Theme_Capri)
        val inflater = LayoutInflater.from(themedContext)
        bubbleView = inflater.inflate(R.layout.bubble_idle, null)
        bubbleView?.setBackgroundResource(resolveBubbleAsset(BubbleState.IDLE))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 32
            y = 200
        }
        bubbleParams = params

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        val gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isDragging) {
                    triggerHapticFeedback()
                    onBubbleClick()
                    return true
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                if (!isDragging) {
                    triggerHapticFeedback()
                    onBubbleClick()
                }
            }
        })

        bubbleView?.setOnTouchListener { view, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    stopBubblePulseAnimation()
                    bubbleView?.setBackgroundResource(resolveBubbleAsset(BubbleState.DRAGGING))
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 30 || abs(dy) > 30) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX - dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(bubbleView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging && event.action == MotionEvent.ACTION_UP) {
                        view.performClick()
                        triggerHapticFeedback()
                        onBubbleClick()
                    } else {
                        bubbleView?.setBackgroundResource(resolveBubbleAsset(BubbleState.IDLE))
                        startBubblePulseAnimation(view)
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(bubbleView, params)
            bubbleView?.let { startBubblePulseAnimation(it) }
        } catch (e: Exception) {
            Log.e("CAPRI_BUBBLE", "Failed to add bubble view to WindowManager", e)
        }
    }

    private fun startBubblePulseAnimation(view: View) {
        stopBubblePulseAnimation()
        pulseAnimatorX = ObjectAnimator.ofFloat(view, View.SCALE_X, 0.97f, 1.0f).apply {
            duration = 2000
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
        pulseAnimatorY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.97f, 1.0f).apply {
            duration = 2000
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    private fun triggerSpeakingDoublePulse() {
        bubbleView?.let { bView ->
            bView.setBackgroundResource(resolveBubbleAsset(BubbleState.SPEAKING))
            ObjectAnimator.ofFloat(bView, View.SCALE_X, 1.0f, 1.2f).apply {
                duration = 200
                repeatCount = 1
                repeatMode = ObjectAnimator.REVERSE
                start()
            }
            ObjectAnimator.ofFloat(bView, View.SCALE_Y, 1.0f, 1.2f).apply {
                duration = 200
                repeatCount = 1
                repeatMode = ObjectAnimator.REVERSE
                start()
            }
        }
    }

    private fun stopBubblePulseAnimation() {
        pulseAnimatorX?.cancel()
        pulseAnimatorY?.cancel()
        pulseAnimatorX = null
        pulseAnimatorY = null
    }

    private fun postNotificationReminder(text: String) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Capri")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_bubble_chat)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            notificationManager.notify((System.currentTimeMillis() % 10000).toInt(), notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun showProactiveComment(text: String) {
        if (chatView != null || bannerView != null) return

        postNotificationReminder(text)

        bubbleView?.let { bView ->
            bView.setBackgroundResource(resolveBubbleAsset(BubbleState.COMMENTING))
            ObjectAnimator.ofFloat(bView, View.SCALE_X, 1.0f, 1.15f).apply {
                duration = 400
                repeatCount = 5
                repeatMode = ObjectAnimator.REVERSE
                start()
            }
            ObjectAnimator.ofFloat(bView, View.SCALE_Y, 1.0f, 1.15f).apply {
                duration = 400
                repeatCount = 5
                repeatMode = ObjectAnimator.REVERSE
                start()
            }
        }

        val themedContext = ContextThemeWrapper(this, R.style.Theme_Capri)
        val inflater = LayoutInflater.from(themedContext)
        val bView = inflater.inflate(R.layout.banner_proactive, null)
        bannerView = bView

        val bannerText = bView.findViewById<TextView>(R.id.banner_text)
        bannerText.text = text

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        val chipShutUp = bView.findViewById<TextView>(R.id.chip_shut_up)
        val chipMyTotals = bView.findViewById<TextView>(R.id.chip_my_totals)

        chipShutUp?.setOnClickListener {
            proactivityEngine?.muteFor(1)
            removeBannerView()
        }

        chipMyTotals?.setOnClickListener {
            removeBannerView()
            expandToChat()
            val summary = com.dot.capri.monitor.AppUsageTotals.getTopAppsSummary(this)
            val themedContext = ContextThemeWrapper(this, R.style.Theme_Capri)
            val historyContainer = chatView?.findViewById<LinearLayout>(R.id.chat_history_container)
            persistentChatHistory.add(Pair("Capri", summary))
            addMessageView(themedContext, historyContainer, "Capri", summary)
        }

        bView.setOnClickListener {
            removeBannerView()
            expandToChat()
        }

        try {
            windowManager?.addView(bView, params)
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        serviceScope.launch {
            delay(6000)
            bView.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    removeBannerView()
                }
                .start()
        }
    }

    private fun removeBannerView() {
        bannerView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            bannerView = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun triggerHapticFeedback() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeBubble() {
        stopBubblePulseAnimation()
        bubbleView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            bubbleView = null
        }
    }

    private fun onBubbleClick() {
        expandToChat()
    }

    fun expandToChat() {
        removeBannerView()
        removeBubble()
        if (chatView != null) return

        val themedContext = ContextThemeWrapper(this, R.style.Theme_Capri)
        val inflater = LayoutInflater.from(themedContext)
        chatView = inflater.inflate(R.layout.chat_expanded, null)
        chatView?.setBackgroundResource(resolveChatBackgroundAsset())

        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        updateStatusIndicator()

        val historyContainer = chatView?.findViewById<LinearLayout>(R.id.chat_history_container)
        val chatScroll = chatView?.findViewById<ScrollView>(R.id.chat_scroll)
        val expandBtn = chatView?.findViewById<TextView>(R.id.btn_expand)

        var isExpanded = false
        chatScroll?.layoutParams?.height = dpToPx(120)
        chatScroll?.requestLayout()

        expandBtn?.setOnClickListener {
            isExpanded = !isExpanded
            chatScroll?.layoutParams?.height = dpToPx(if (isExpanded) 280 else 120)
            chatScroll?.requestLayout()
            expandBtn.text = if (isExpanded) "[ - ]" else "[ + ]"
        }

        historyContainer?.removeAllViews()
        if (persistentChatHistory.isEmpty()) {
            val greeting = "Sup, choom~ What digital chaos are we starting today?"
            persistentChatHistory.add(Pair("Capri", greeting))
            addMessageView(themedContext, historyContainer, "Capri", greeting)
        } else {
            for ((speaker, msg) in persistentChatHistory) {
                addMessageView(themedContext, historyContainer, speaker, msg)
            }
        }

        chatScroll?.post {
            chatScroll.fullScroll(ScrollView.FOCUS_DOWN)
        }

        val inputText = chatView?.findViewById<EditText>(R.id.input_text)
        val sendButton = chatView?.findViewById<ImageButton>(R.id.send)
        val closeButton = chatView?.findViewById<ImageButton>(R.id.close)

        sendButton?.setOnClickListener {
            triggerHapticFeedback()
            handleSendAction()
        }

        closeButton?.setOnClickListener {
            minimizeToChat()
        }

        inputText?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                triggerHapticFeedback()
                handleSendAction()
                true
            } else {
                false
            }
        }

        try {
            windowManager?.addView(chatView, params)
        } catch (e: Exception) {
            Log.e("CAPRI_BUBBLE", "Failed to add chat view to WindowManager", e)
        }

        inputText?.requestFocus()
        inputText?.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(inputText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun addMessageView(context: Context, container: LinearLayout?, speaker: String, text: String) {
        val tv = TextView(context).apply {
            val prefix = if (speaker == "You") "You: " else "Capri: "
            this.text = prefix + text
            setTextColor(
                if (speaker == "You")
                    ContextCompat.getColor(context, R.color.text_primary)
                else
                    ContextCompat.getColor(context, R.color.accent_red_pink)
            )
            textSize = if (speaker == "You") 14f else 15f
            setPadding(0, 4, 0, if (speaker == "You") 4 else 8)
        }
        container?.addView(tv)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun updateStatusIndicator() {
        val statusView = chatView?.findViewById<TextView>(R.id.status_indicator) ?: return
        val hasOnDeviceAi = OnDeviceAiClient.isAvailable(this)
        val hasKey = LlmRouter.hasAnyApiKey(this)
        val online = NetworkUtils.isOnline(this) && hasKey

        if (hasOnDeviceAi || online) {
            statusView.text = getString(R.string.status_awake)
            statusView.setTextColor(ContextCompat.getColor(this, R.color.accent_red_pink))
        } else {
            statusView.text = getString(R.string.status_snoozing)
            statusView.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    fun minimizeToChat() {
        removeChatWindow()
        showBubble()
    }

    private fun removeChatWindow() {
        chatView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            chatView = null
        }
    }

    private fun handleSendAction() {
        val inputText = chatView?.findViewById<EditText>(R.id.input_text) ?: return
        val command = inputText.text.toString().trim()
        if (command.isEmpty()) return

        val sendButton = chatView?.findViewById<ImageButton>(R.id.send)
        val historyContainer = chatView?.findViewById<LinearLayout>(R.id.chat_history_container)
        val chatScroll = chatView?.findViewById<ScrollView>(R.id.chat_scroll)
        val thinkingContainer = chatView?.findViewById<View>(R.id.thinking_container)
        val thinkingText = chatView?.findViewById<TextView>(R.id.thinking_text)

        val themedContext = ContextThemeWrapper(this, R.style.Theme_Capri)

        proactivityEngine?.recordUserInteraction()

        persistentChatHistory.add(Pair("You", command))
        addMessageView(themedContext, historyContainer, "You", command)
        inputText.setText("")

        inputText.isEnabled = false
        sendButton?.isEnabled = false
        thinkingText?.text = Personality.thinking()
        thinkingContainer?.visibility = View.VISIBLE

        serviceScope.launch {
            val response = try {
                withContext(Dispatchers.IO) { processCommand(command) }
            } catch (e: Exception) {
                Log.e("CAPRI_BUBBLE", "processCommand crashed", e)
                "something glitched on my end, choom~ try that again."
            } finally {
                // Always restore the controls, even if the command failed.
                thinkingContainer?.visibility = View.GONE
                inputText.isEnabled = true
                sendButton?.isEnabled = true
            }

            persistentChatHistory.add(Pair("Capri", response))
            addMessageView(themedContext, historyContainer, "Capri", response)

            // No fixed height jump here any more. The window stays the size the user chose.
            chatScroll?.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }

            triggerSpeakingDoublePulse()
        }
    }

    private fun executeWriteCommand(text: String): String {
        if (!MainActivity.isAllowTyping(this)) {
            return "typing is disabled in my settings."
        }
        if (text.isBlank()) {
            return "write what, exactly."
        }
        val now = System.currentTimeMillis()
        if (now - lastWriteTimestamp < 5000L) {
            return "give the field a second to breathe."
        }

        val result = ScreenWriter.writeToFocusedField(text)
        if (result is WriteResult.Success) {
            lastWriteTimestamp = now
            return "done. send it before you overthink it."
        }
        return when (result) {
            is WriteResult.NoField -> "nowhere to write. tap a text field first."
            is WriteResult.OwnChatFocused -> "i'm not typing into my own chat. that's weird."
            is WriteResult.Failed -> "couldn't type that. the field said no."
            else -> "couldn't type that. the field said no."
        }
    }

    private fun executeClearFieldCommand(): String {
        if (!MainActivity.isAllowTyping(this)) {
            return "typing is disabled in my settings."
        }
        val now = System.currentTimeMillis()
        if (now - lastWriteTimestamp < 5000L) {
            return "give the field a second to breathe."
        }

        val result = ScreenWriter.clearFocusedField()
        if (result is WriteResult.Success) {
            lastWriteTimestamp = now
            return "done. send it before you overthink it."
        }
        return when (result) {
            is WriteResult.NoField -> "nowhere to write. tap a text field first."
            is WriteResult.OwnChatFocused -> "i'm not typing into my own chat. that's weird."
            is WriteResult.Failed -> "couldn't type that. the field said no."
            else -> "couldn't type that. the field said no."
        }
    }

    private suspend fun executeEmbeddedActionTag(response: String): String {
        var cleanResponse = response
        val actionRegex = Regex("\\[ACTION:(OPEN|PLAY|CALL|TEXT|SEARCH|VOLUME|TYPE|WRITE|SCROLL|TAP):([^]]+)\\]", RegexOption.IGNORE_CASE)
        val match = actionRegex.find(response)
        if (match != null) {
            val (actionType, rawTarget) = match.destructured
            val target = rawTarget.trim()
            when (actionType.uppercase()) {
                "SCROLL" -> {
                    val down = !target.equals("UP", ignoreCase = true)
                    PhoneControl.scroll(down)
                }
                "TAP" -> {
                    val root = PhoneControl.foregroundRoot()
                    if (root != null) {
                        val items = PhoneControl.snapshot(root)
                        val matchItem = items.firstOrNull { it.label.contains(target, ignoreCase = true) }
                        if (matchItem != null) {
                            PhoneControl.tap(matchItem)
                        }
                    }
                }
                else -> {
                    val cmd = when (actionType.uppercase()) {
                        "OPEN" -> Cmd.Open(target)
                        "PLAY" -> {
                            val parts = target.split(":")
                            if (parts.size >= 2) Cmd.Play(parts[0].trim(), parts[1].trim()) else Cmd.Play(target, null)
                        }
                        "VOLUME" -> {
                            val dir = when (target.uppercase()) {
                                "UP" -> VolumeDirection.UP
                                "DOWN" -> VolumeDirection.DOWN
                                "MUTE" -> VolumeDirection.MUTE
                                else -> VolumeDirection.UNMUTE
                            }
                            Cmd.Volume(dir)
                        }
                        "TYPE", "WRITE" -> Cmd.Write(target)
                        "CALL" -> Cmd.Call(target)
                        "TEXT" -> {
                            val parts = target.split(":")
                            if (parts.size >= 2) Cmd.Text(parts[0].trim(), parts[1].trim()) else Cmd.Text(target, null)
                        }
                        "SEARCH" -> Cmd.Search(target)
                        else -> null
                    }
                    if (cmd != null) {
                        if (cmd is Cmd.Write) {
                            return executeWriteCommand(cmd.text)
                        }
                        IntentExecutor.execute(this, cmd)
                    }
                }
            }
            cleanResponse = response.replace(match.value, "").trim()
        }
        return cleanResponse
    }

    // 3-way brain: API -> local model -> null (caller falls back to commands / canned lines)
    private suspend fun askBrain(prompt: String, imageBytes: ByteArray? = null): String? {
        var apiError: String? = null

        // Tier 1: cloud API (needs a key and internet)
        if (LlmRouter.hasAnyApiKey(this) && NetworkUtils.isOnline(this)) {
            val result = withContext(Dispatchers.IO) {
                CloudAiClient.generateResponse(this@BubbleService, prompt, imageBytes)
            }
            val text = result.getOrNull()?.takeIf { it.isNotBlank() }
            if (text != null) return executeEmbeddedActionTag(text)
            apiError = result.exceptionOrNull()?.message ?: "empty reply"
            android.util.Log.w("CAPRI_BRAIN", "API tier failed ($apiError), trying local")
        }

        // Tier 2: on-device model (only if imported, enabled, and the phone has the RAM)
        if (OnDeviceAiClient.isAvailable(this)) {
            val local = OnDeviceAiClient.generateResponse(this, prompt)
            val text = local.getOrNull()?.takeIf { it.isNotBlank() }
            if (text != null) return executeEmbeddedActionTag(text)
            android.util.Log.w("CAPRI_BRAIN", "Local tier failed (${local.exceptionOrNull()?.message})")
        }

        // Tier 3: nothing worked. A spent budget is the one case worth saying out loud.
        if (apiError?.contains("budget", ignoreCase = true) == true) return Personality.budgetExhausted()
        return null
    }

    private suspend fun processCommand(text: String): String {
        val trimmedInput = text.trim()

        if (pendingDraftText != null) {
            val draft = pendingDraftText!!
            pendingDraftText = null
            val confirmRegex = Regex("^(?:yes|sure|go|yeah|yep|do it|ok|okay)$", RegexOption.IGNORE_CASE)
            if (confirmRegex.matches(trimmedInput)) {
                return executeWriteCommand(draft)
            }
        }

        val yes = Regex("^(?:yes|yeah|yep|y|sure|ok|okay|go|go ahead|do it|send it|send)$", RegexOption.IGNORE_CASE)
        val no = Regex("^(?:no|nope|n|nah|cancel|stop|don't|dont|drop it)$", RegexOption.IGNORE_CASE)

        pendingSend?.let { p ->
            pendingSend = null
            if (yes.matches(trimmedInput)) {
                setChatVisible(false)
                val ok = try {
                    PhoneControl.pressSend(this)
                } finally {
                    setChatVisible(true)
                }
                if (ok) {
                    delay(300)
                    PhoneControl.home()
                    return "sent to ${p.contact}, choom."
                }
                return "couldn't find the send button. the message is ready in the chat, tap it yourself."
            }
            if (no.matches(trimmedInput)) return "okay, left it as a draft."
        }

        PhoneAgent.pending?.let { p ->
            PhoneAgent.pending = null
            if (yes.matches(trimmedInput)) {
                return PhoneAgent.run(this, p.goal, p) { setChatVisible(it) }
            }
            if (no.matches(trimmedInput)) return "okay, dropped it."
        }

        val taskMatch = Regex("^(?:task|agent|do\\s+this)\\s*[:\\-]\\s*(.+)$", RegexOption.IGNORE_CASE)
            .find(trimmedInput)
        if (taskMatch != null) {
            PhoneAgent.pending = null
            return PhoneAgent.run(this, taskMatch.groupValues[1], null) { setChatVisible(it) }
        }

        val cmd = IntentParser.parse(text)

        if (cmd is Cmd.Text && !cmd.message.isNullOrBlank() && CapriAccessibilityService.instance != null) {
            val opened = IntentExecutor.execute(this, cmd)
            delay(300)
            if (!opened) return Personality.respond(cmd, false)
            pendingSend = cmd
            return "chat with ${cmd.contact} is open with \"${cmd.message}\" ready. send it? (yes/no)"
        }

        if (cmd is Cmd.CheckNotifications) {
            val listenerOn = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
            if (!listenerOn) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "opened notification access for you. flip capri on and come back, choom."
            }
            return com.dot.capri.monitor.CapriNotificationListenerService.getRecentSummary()
        }

        if (cmd is Cmd.EasterEgg) {
            val count = easterEggCounts.getOrPut(cmd.response) { 0 }
            if (count >= 5) return "that's enough of that."
            easterEggCounts[cmd.response] = count + 1
            return cmd.response
        }

        if (cmd is Cmd.Write || cmd is Cmd.TypeText) {
            val writeText = if (cmd is Cmd.Write) cmd.text else (cmd as Cmd.TypeText).text
            val smartComposeRegex = Regex("^(?:about\\s+(.+)|write\\s+about\\s+(.+))$", RegexOption.IGNORE_CASE)
            val match = smartComposeRegex.find(writeText)
            if (match != null) {
                val topic = (match.groups[1]?.value ?: match.groups[2]?.value ?: "").trim()
                val prompt = "write a short message about: $topic. max 2 sentences. match a casual texting tone. no quotes, no preamble, just the message text."
                val draft = withContext(Dispatchers.IO) {
                    CloudAiClient.generateResponse(this@BubbleService, prompt).getOrNull()
                }?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
                if (!draft.isNullOrEmpty()) {
                    pendingDraftText = draft
                    return "$draft\n\nwant me to put it in the field?"
                }
                return "couldn't write about that."
            }
            return executeWriteCommand(writeText)
        }

        if (cmd is Cmd.Scroll) {
            val ok = PhoneControl.scroll(cmd.down)
            return Personality.respond(cmd, ok)
        }

        if (cmd is Cmd.ClearField) return executeClearFieldCommand()

        if (cmd is Cmd.MuteProactivity) {
            proactivityEngine?.muteFor(4)
            return Personality.respond(cmd, true)
        }

        if (cmd is Cmd.UnmuteProactivity) {
            proactivityEngine?.unmute()
            return Personality.respond(cmd, true)
        }

        if (cmd is Cmd.ClearHistory) {
            persistentChatHistory.clear()
            CloudAiClient.clearHistory()
            return Personality.respond(cmd, true)
        }

        if (cmd is Cmd.SeeScreen) {
            if (!OnDeviceAiClient.isAvailable(this) && (!LlmRouter.hasAnyApiKey(this) || !NetworkUtils.isOnline(this))) {
                return "need an api key + internet, or an imported local model, to look at the screen, choom~"
            }
            val accessibilityService = CapriAccessibilityService.instance
                ?: return "accessibility service not enabled. please enable 'Capri' in Android Accessibility settings first, choom~"

            withContext(Dispatchers.Main) { chatView?.visibility = View.INVISIBLE }
            val (imageBytes, screenTextTree) = try {
                delay(250)
                accessibilityService.captureScreenBitmap() to accessibilityService.dumpScreenTextTree()
            } finally {
                // Runs even if the capture throws, so the chat can never stay hidden.
                withContext(Dispatchers.Main) { chatView?.visibility = View.VISIBLE }
            }

            val question = cmd.prompt.ifBlank { "what is visible on this screen? read any text and describe it in character." }
            val userPrompt = if (screenTextTree.isNotEmpty()) {
                "$question\n\n[Extracted On-Screen Text/Elements]:\n$screenTextTree"
            } else {
                question
            }
            return askBrain(userPrompt, imageBytes) ?: "couldn't analyze what's on screen, choom~"
        }

        if (cmd is Cmd.Search) {
            val url = "https://www.google.com/search?q=" + Uri.encode(cmd.query)
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "searching '${cmd.query}' in your browser, choom."
            } catch (e: Exception) {
                "no browser to search with, choom."
            }
        }

        // Conversation goes API -> local model -> canned lines.
        if (cmd is Cmd.Chat || cmd is Cmd.Mention || cmd is Cmd.Compliment || cmd is Cmd.Unknown) {
            askBrain(text)?.let { return it }
            return Personality.respond(cmd, cmd !is Cmd.Unknown)
        }

        // Device action (open, play, call, volume...). If it fails, let the model take a go at it,
        // so "why" doesn't end in a failed "open why" line.
        val success = IntentExecutor.execute(this, cmd)
        delay(300)
        if (success) return Personality.respond(cmd, true)

        askBrain(text)?.let { return it }
        return Personality.respond(cmd, false)
    }

    override fun onDestroy() {
        CapriCursor.hide()
        proactivityEngine?.stop()
        proactivityEngine = null
        instance = null
        removeBannerView()
        removeBubble()
        removeChatWindow()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
