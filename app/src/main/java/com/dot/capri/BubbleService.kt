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
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dot.capri.ai.CloudAiClient
import com.dot.capri.ai.OnDeviceAiClient
import com.dot.capri.intents.Cmd
import com.dot.capri.intents.IntentExecutor
import com.dot.capri.intents.IntentParser
import com.dot.capri.monitor.AppUsageTotals
import com.dot.capri.monitor.ForegroundAppDetector
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
        // PLACEHOLDER: per-state gif/png/sprite here.
        return when (state) {
            BubbleState.IDLE -> R.drawable.bg_bubble_circle
            BubbleState.THINKING -> R.drawable.bg_bubble_circle
            BubbleState.SPEAKING -> R.drawable.bg_bubble_circle
            BubbleState.DRAGGING -> R.drawable.bg_bubble_circle
            BubbleState.COMMENTING -> R.drawable.bg_bubble_circle
        }
    }

    private fun resolveChatBackgroundAsset(): Int {
        // PLACEHOLDER: animated or image chat background here.
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
        val responseText = chatView?.findViewById<TextView>(R.id.response_text)
        val thinkingContainer = chatView?.findViewById<View>(R.id.thinking_container)
        val thinkingText = chatView?.findViewById<TextView>(R.id.thinking_text)

        inputText.isEnabled = false
        sendButton?.isEnabled = false
        thinkingText?.text = Personality.thinking()
        thinkingContainer?.visibility = View.VISIBLE
        responseText?.text = ""

        serviceScope.launch {
            val response = withContext(Dispatchers.IO) {
                processCommand(command)
            }
            thinkingContainer?.visibility = View.GONE
            responseText?.text = response
            triggerSpeakingDoublePulse()
            inputText.isEnabled = true
            sendButton?.isEnabled = true
            inputText.setText("")
        }
    }

    private fun executeEmbeddedActionTag(response: String): String {
        var cleanResponse = response
        val actionRegex = Regex("\\[ACTION:(OPEN|PLAY|CALL|SEARCH):([^]]+)\\]", RegexOption.IGNORE_CASE)
        val match = actionRegex.find(response)
        if (match != null) {
            val (actionType, target) = match.destructured
            val cmd = when (actionType.uppercase()) {
                "OPEN" -> Cmd.Open(target.trim())
                "PLAY" -> Cmd.Play(target.trim(), null)
                "CALL" -> Cmd.Call(target.trim())
                "SEARCH" -> Cmd.Search(target.trim())
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

        if (cmd is Cmd.SeeScreen) {
            val hasKey = MainActivity.getApiKey(this).isNotBlank()
            val isOnline = NetworkUtils.isOnline(this) && hasKey
            if (!isOnline) {
                return "Need api key and internet to see screen context, choom~"
            }
            val currentPkg = ForegroundAppDetector.currentForegroundApp(this)
            val appName = currentPkg?.substringAfterLast('.')?.replaceFirstChar { it.uppercase() } ?: "your home screen"
            val mins = if (currentPkg != null) AppUsageTotals.totalTodayMinutes(this, currentPkg) else 0L
            val prompt = "the user asked: '${cmd.prompt}'. they are currently viewing app '$appName' (package: ${currentPkg ?: "none"}), with $mins minutes spent in it today. give a witty, deadpan, in-character commentary about what they are doing."
            
            val cloudResult = CloudAiClient.generateResponse(this, prompt)
            if (cloudResult.isSuccess) {
                val textResponse = cloudResult.getOrNull()
                if (!textResponse.isNullOrBlank()) {
                    return executeEmbeddedActionTag(textResponse)
                }
            }
            return "you're looking at $appName, choom~ ($mins minutes today)"
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
