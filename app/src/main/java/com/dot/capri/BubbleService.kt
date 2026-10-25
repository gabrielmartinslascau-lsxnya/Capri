package com.dot.capri

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import com.dot.capri.ai.CloudAiClient
import com.dot.capri.ai.OnDeviceAiClient
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
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var pulseAnimatorX: ObjectAnimator? = null
    private var pulseAnimatorY: ObjectAnimator? = null

    private var chatView: View? = null
    private var bannerView: View? = null
    private var proactivityEngine: ProactivityEngine? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

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
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIFICATION_ID, notification, fgsTypes)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun showBubble() {
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

        bubbleView?.setOnTouchListener { view, event ->
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
                    if (abs(dx) > 12 || abs(dy) > 12) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX - dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(bubbleView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
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

        windowManager?.addView(bubbleView, params)
        bubbleView?.let { startBubblePulseAnimation(it) }
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

    fun showProactiveComment(text: String) {
        if (chatView != null || bannerView != null) return

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

        val currentParams = bubbleParams ?: WindowManager.LayoutParams().apply {
            x = 32
            y = 200
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = currentParams.x
            y = currentParams.y + 160
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
            delay(4000)
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

        windowManager?.addView(chatView, params)
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
        val hasKey = MainActivity.getApiKey(this).isNotBlank()
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

        persistentChatHistory.add(Pair("You", command))
        addMessageView(themedContext, historyContainer, "You", command)

        inputText.isEnabled = false
        sendButton?.isEnabled = false
        thinkingText?.text = Personality.thinking()
        thinkingContainer?.visibility = View.VISIBLE

        serviceScope.launch {
            val response = withContext(Dispatchers.IO) {
                processCommand(command)
            }
            thinkingContainer?.visibility = View.GONE

            persistentChatHistory.add(Pair("Capri", response))
            addMessageView(themedContext, historyContainer, "Capri", response)

            chatScroll?.layoutParams?.height = dpToPx(240)
            chatScroll?.requestLayout()

            chatScroll?.post {
                chatScroll.fullScroll(ScrollView.FOCUS_DOWN)
            }

            triggerSpeakingDoublePulse()
            inputText.isEnabled = true
            sendButton?.isEnabled = true
            inputText.setText("")
        }
    }

    private fun executeEmbeddedActionTag(response: String): String {
        var cleanResponse = response
        val actionRegex = Regex("\\[ACTION:(OPEN|PLAY|CALL|TEXT|SEARCH|VOLUME|TYPE):([^]]+)\\]", RegexOption.IGNORE_CASE)
        val match = actionRegex.find(response)
        if (match != null) {
            val (actionType, rawTarget) = match.destructured
            val cmd = when (actionType.uppercase()) {
                "OPEN" -> Cmd.Open(rawTarget.trim())
                "PLAY" -> {
                    val parts = rawTarget.split(":")
                    if (parts.size >= 2) {
                        Cmd.Play(parts[0].trim(), parts[1].trim())
                    } else {
                        Cmd.Play(rawTarget.trim(), null)
                    }
                }
                "VOLUME" -> {
                    val dir = when (rawTarget.trim().uppercase()) {
                        "UP" -> VolumeDirection.UP
                        "DOWN" -> VolumeDirection.DOWN
                        "MUTE" -> VolumeDirection.MUTE
                        else -> VolumeDirection.UNMUTE
                    }
                    Cmd.Volume(dir)
                }
                "TYPE" -> Cmd.TypeText(rawTarget.trim())
                "CALL" -> Cmd.Call(rawTarget.trim())
                "TEXT" -> {
                    val parts = rawTarget.split(":")
                    if (parts.size >= 2) {
                        Cmd.Text(parts[0].trim(), parts[1].trim())
                    } else {
                        Cmd.Text(rawTarget.trim(), null)
                    }
                }
                "SEARCH" -> Cmd.Search(rawTarget.trim())
                else -> null
            }
            if (cmd != null) {
                IntentExecutor.execute(this, cmd)
            }
            cleanResponse = response.replace(match.value, "").trim()
        }
        return cleanResponse
    }

    private suspend fun processCommand(text: String): String {
        val cmd = IntentParser.parse(text)

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
            val hasKey = MainActivity.getApiKey(this).isNotBlank()
            val isOnline = NetworkUtils.isOnline(this) && hasKey
            if (!isOnline) {
                return "Need api key and internet to see screen pixels, choom~"
            }
            val accessibilityService = CapriAccessibilityService.instance
            if (accessibilityService == null) {
                return "Accessibility service not enabled. Please enable 'Capri' in Android Accessibility settings first, choom~"
            }

            withContext(Dispatchers.Main) {
                chatView?.visibility = View.INVISIBLE
            }
            delay(250)

            val imageBytes = accessibilityService.captureScreenBitmap()

            withContext(Dispatchers.Main) {
                chatView?.visibility = View.VISIBLE
            }

            if (imageBytes != null) {
                val cloudResult = CloudAiClient.generateResponse(this, cmd.prompt.ifBlank { "what is visible on this screen? read any text, identify the photo or tiktok video and describe it thoroughly in character." }, imageBytes)
                if (cloudResult.isSuccess) {
                    val textResponse = cloudResult.getOrNull()
                    if (!textResponse.isNullOrBlank()) {
                        return executeEmbeddedActionTag(textResponse)
                    }
                }
                return "Couldn't analyze the screenshot bytes, choom~"
            } else {
                return "Failed to take screenshot via accessibility service, choom~"
            }
        }

        if (cmd is Cmd.Search) {
            val hasKey = MainActivity.getApiKey(this).isNotBlank()
            val isOnline = NetworkUtils.isOnline(this) && hasKey
            if (isOnline) {
                val cloudResult = CloudAiClient.generateResponse(this, text)
                if (cloudResult.isSuccess) {
                    val cloudText = cloudResult.getOrNull()
                    if (!cloudText.isNullOrBlank()) {
                        return executeEmbeddedActionTag(cloudText)
                    }
                }
            }
        }

        if (cmd !is Cmd.Unknown) {
            val success = IntentExecutor.execute(this, cmd)
            delay(300)
            return Personality.respond(cmd, success)
        }

        if (OnDeviceAiClient.isAvailable(this)) {
            val onDeviceResult = OnDeviceAiClient.generateResponse(this, text)
            if (onDeviceResult.isSuccess) {
                val aiText = onDeviceResult.getOrNull()
                if (!aiText.isNullOrBlank()) {
                    return executeEmbeddedActionTag(aiText)
                }
            }
        }

        val hasKey = MainActivity.getApiKey(this).isNotBlank()
        val isOnline = NetworkUtils.isOnline(this) && hasKey
        if (isOnline) {
            val cloudResult = CloudAiClient.generateResponse(this, text)
            if (cloudResult.isSuccess) {
                val cloudText = cloudResult.getOrNull()
                if (!cloudText.isNullOrBlank()) {
                    return executeEmbeddedActionTag(cloudText)
                }
            }
        }

        val success = IntentExecutor.execute(this, cmd)
        delay(300)
        return Personality.respond(cmd, success)
    }

    override fun onDestroy() {
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
