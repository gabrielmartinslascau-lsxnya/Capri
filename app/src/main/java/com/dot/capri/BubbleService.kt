package com.dot.capri

import com.dot.capri.agent.DevOverlay

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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dot.capri.accessibility.ScreenWriter
import com.dot.capri.accessibility.WriteResult
import com.dot.capri.agent.CapriCursor
import com.dot.capri.agent.ControlRegions
import com.dot.capri.agent.DeviceTasks
import com.dot.capri.agent.PhoneAgent
import com.dot.capri.agent.PhoneControl
import com.dot.capri.ai.CloudAiClient
import com.dot.capri.ai.OnDeviceAiClient
import com.dot.capri.llm.LlmRouter
import com.dot.capri.intents.Cmd
import com.dot.capri.intents.IntentExecutor
import com.dot.capri.intents.AppChooser
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

        /**
         * Temporarily hides Capri's floating bubble, chat window, and proactive banner during
         * screenshot capture so Capri never sees her own UI or mistakes it for screen changes.
         */
        fun setCaptureHidden(hidden: Boolean) {
            val svc = instance ?: return
            val runnable = Runnable {
                val a = if (hidden) 0f else 1f
                svc.bubbleView?.alpha = a
                svc.chatView?.alpha = a
                svc.bannerView?.alpha = a
            }
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                runnable.run()
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).post(runnable)
            }
        }
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
                v.visibility = View.GONE
            } else {
                v.visibility = View.VISIBLE
            }
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

        // Android 14+ requires a declared type. This is a floating assistant, so specialUse.
        // mediaPlayback is not used: Android 15+ restricts it and the app does not play media.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
        val realTalkBtn = chatView?.findViewById<TextView>(R.id.btn_real_talk)
        val addMbBtn = chatView?.findViewById<TextView>(R.id.btn_add_mb)
        val toggleMbBtn = chatView?.findViewById<TextView>(R.id.btn_toggle_mb)

        addMbBtn?.setOnClickListener {
            triggerHapticFeedback()
            val mb = ControlRegions.addNextMacroButton(this)
            Toast.makeText(
                this,
                "Spawned ${mb.name.uppercase()}! Drag onto button, tap to tag role, or hold to delete.",
                Toast.LENGTH_SHORT
            ).show()
            minimizeToChat()
        }

        addMbBtn?.setOnLongClickListener {
            triggerHapticFeedback()
            ControlRegions.clearAll(this)
            Toast.makeText(this, "Cleared all Macro Buttons (MBs)", Toast.LENGTH_SHORT).show()
            true
        }

        toggleMbBtn?.setOnClickListener {
            triggerHapticFeedback()
            val shown = ControlRegions.toggleOverlays(this)
            Toast.makeText(
                this,
                if (shown) "Showing Macro Buttons (MBs)" else "Hidden Macro Buttons (positions saved)",
                Toast.LENGTH_SHORT
            ).show()
            if (shown) minimizeToChat()
        }

        realTalkBtn?.setOnClickListener {
            minimizeToChat()
            val intent = Intent(this, RealTalkActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

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
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            removeChatWindow()
            showBubble()
        }
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
            streamMessageView(themedContext, historyContainer, response, chatScroll)

            triggerSpeakingDoublePulse()
        }
    }

    private suspend fun streamMessageView(context: Context, container: LinearLayout?, message: String, chatScroll: ScrollView?) {
        val tv = TextView(context).apply {
            this.text = "Capri: "
            setTextColor(ContextCompat.getColor(context, R.color.accent_red_pink))
            textSize = 15f
            setPadding(0, 4, 0, 8)
        }
        withContext(Dispatchers.Main) {
            container?.addView(tv)
        }
        val sb = StringBuilder("Capri: ")
        for (char in message) {
            sb.append(char)
            withContext(Dispatchers.Main) {
                tv.text = sb.toString()
                chatScroll?.fullScroll(ScrollView.FOCUS_DOWN)
            }
            delay(12)
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
        val actionRegex = Regex("\\[?ACTION[=:]\\s*([A-Z_]+)(?:[=:]\\s*([^]\\n]*))?\\]?", RegexOption.IGNORE_CASE)
        val matches = actionRegex.findAll(response).toList()
        if (matches.isEmpty()) return response

        var cleanResponse = actionRegex.replace(response, "").trim()

        for (match in matches) {
            val (actionType, rawTarget) = match.destructured
            val target = rawTarget.trim()
            when (actionType.uppercase()) {
                "MOVE_CURSOR", "SPAWN_CURSOR" -> {
                    val parts = target.split(",").mapNotNull { it.trim().toIntOrNull() }
                    val (tx, ty) = when {
                        parts.size >= 3 -> Pair(parts[1], parts[2])
                        parts.size >= 2 -> Pair(parts[0], parts[1])
                        else -> PhoneControl.screenSize()?.let { (w, h) -> Pair(w / 2, h / 2) } ?: Pair(540, 1200)
                    }
                    setChatVisible(false)
                    try {
                        CapriCursor.show(this@BubbleService)
                        CapriCursor.moveTo(tx, ty)
                        CapriCursor.pulse()
                        delay(700)
                    } finally {
                        CapriCursor.hide()
                        setChatVisible(true)
                    }
                }
                "REMOVE_CURSOR", "CLEAR_CURSORS" -> {
                    CapriCursor.hide()
                }
                "MULTITAP", "MULTI_TOUCH", "MULTITOUCH" -> {
                    val pairs = target.split("|").mapNotNull { pt ->
                        val xy = pt.split(",").mapNotNull { it.trim().toIntOrNull() }
                        if (xy.size >= 2) Pair(xy[0], xy[1]) else null
                    }
                    if (pairs.isNotEmpty()) {
                        PhoneControl.tapMulti(pairs)
                    }
                }
                "PINCH" -> {
                    minimizeToChat()
                    delay(300)
                    val zoomIn = target.contains("in", ignoreCase = true) || target.contains("zoom_in", ignoreCase = true)
                    if (CapriAccessibilityService.instance != null) {
                        PhoneControl.pinch(zoomIn)
                    }
                }
                "DUALTAP", "DUAL_TAP" -> {
                    minimizeToChat()
                    delay(300)
                    val coords = target.split(",").mapNotNull { it.trim().toIntOrNull() }
                    if (coords.size >= 4 && CapriAccessibilityService.instance != null) {
                        CapriCursor.show(this@BubbleService)
                        CapriCursor.moveTo(coords[0], coords[1])
                        CapriCursor.pulse()
                        delay(200)
                        PhoneControl.dualTap(coords[0], coords[1], coords[2], coords[3])
                        delay(500)
                        CapriCursor.hide()
                    }
                }
                "SCROLL" -> {
                    minimizeToChat()
                    delay(300)
                    val parts = target.split(":")
                    val dirStr = parts[0].trim().uppercase()
                    val count = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 1
                    val dir = when (dirStr) {
                        "UP" -> com.dot.capri.intents.ScrollDirection.UP
                        "DOWN" -> com.dot.capri.intents.ScrollDirection.DOWN
                        "LEFT" -> com.dot.capri.intents.ScrollDirection.LEFT
                        "RIGHT" -> com.dot.capri.intents.ScrollDirection.RIGHT
                        else -> com.dot.capri.intents.ScrollDirection.DOWN
                    }
                    if (CapriAccessibilityService.instance != null) {
                        repeat(count) {
                            PhoneControl.scroll(dir)
                            if (count > 1) delay(200)
                        }
                    }
                }
                "GO_HOME", "HOME" -> {
                    minimizeToChat()
                    PhoneControl.home()
                }
                "BACK" -> {
                    minimizeToChat()
                    PhoneControl.back()
                }
                "TAP" -> {
                    val directMb = ControlRegions.tryExecuteUserMbCommand(this, "tap $target") { setChatVisible(it) }
                    if (directMb != null) return directMb
                    minimizeToChat()
                    delay(300)
                    val root = PhoneControl.foregroundRoot()
                    var done = false
                    if (root != null) {
                        val items = PhoneControl.snapshot(root)
                        val matchItem = items.firstOrNull { it.label.contains(target, ignoreCase = true) }
                        if (matchItem != null) {
                            CapriCursor.show(this@BubbleService)
                            CapriCursor.moveTo(matchItem.cx, matchItem.cy)
                            CapriCursor.pulse()
                            delay(200)
                            done = PhoneControl.tap(matchItem)
                            delay(400)
                            CapriCursor.hide()
                        }
                    }
                    if (!done) {
                        return PhoneAgent.run(this, "click on $target", null) { setChatVisible(it) }
                    }
                }
                "LONG_PRESS", "LONGPRESS" -> {
                    val directMb = ControlRegions.tryExecuteUserMbCommand(this, "hold $target") { setChatVisible(it) }
                    if (directMb != null) return directMb
                    minimizeToChat()
                    delay(300)
                    val root = PhoneControl.foregroundRoot()
                    var done = false
                    if (root != null) {
                        val items = PhoneControl.snapshot(root)
                        val matchItem = items.firstOrNull { it.label.contains(target, ignoreCase = true) }
                        if (matchItem != null) {
                            CapriCursor.show(this@BubbleService)
                            CapriCursor.moveTo(matchItem.cx, matchItem.cy)
                            CapriCursor.pulse()
                            delay(200)
                            done = PhoneControl.longPress(matchItem)
                            delay(400)
                            CapriCursor.hide()
                        }
                    }
                    if (!done) {
                        return PhoneAgent.run(this, "long press $target", null) { setChatVisible(it) }
                    }
                }
                "TASK", "AGENT", "DO", "EXECUTE" -> {
                    return PhoneAgent.run(this, target.ifBlank { response }, null) { setChatVisible(it) }
                }
                "MOVE", "WALK", "RUN", "GOTO", "GO", "MINE", "CHOP", "BREAK", "DIG",
                "ATTACK", "HIT", "HOLD", "PRESS", "CLICK", "USE", "LOOK", "SWIPE", "DRAG" -> {
                    // If the LLM invented a custom action tag like ACTION:MOVE:tree or ACTION:MINE:wood,
                    // execute it via PhoneAgent instead of leaking raw ACTION:... text into chat!
                    val verb = actionType.lowercase(java.util.Locale.US)
                    val goalFromTag = if (target.isNotBlank()) "$verb ${target.replace(':', ' ')}" else verb
                    return PhoneAgent.run(this, goalFromTag, null) { setChatVisible(it) }
                }
                "OPEN" -> {
                    return PhoneAgent.run(this, "open $target", null) { setChatVisible(it) }
                }
                "PLAY" -> {
                    val gameOpt = com.dot.capri.agent.DeviceTasks.resolveInstalledGame(this, target)
                    if (gameOpt != null) {
                        com.dot.capri.intents.AppChooser.open(this, gameOpt.pkg)
                        delay(2000)
                        return PhoneAgent.run(this, "play ${gameOpt.label} and win", null) { setChatVisible(it) }
                    }
                    val query = target.replace(':', ' ').trim()
                    return PhoneAgent.run(this, "play $query", null) { setChatVisible(it) }
                }
                "SEARCH" -> {
                    return PhoneAgent.run(this, "search for $target", null) { setChatVisible(it) }
                }
                else -> {
                    val cmd = when (actionType.uppercase()) {
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
                        else -> null
                    }
                    if (cmd != null) {
                        if (cmd is Cmd.Write) {
                            val writeMsg = executeWriteCommand(cmd.text)
                            if (cleanResponse.isBlank()) cleanResponse = writeMsg
                        } else {
                            IntentExecutor.execute(this, cmd)
                        }
                    } else {
                        // Any other unrecognized ACTION:<VERB>:<TARGET> tag -> run as PhoneAgent task
                        val fallbackGoal = "${actionType.lowercase(java.util.Locale.US)} $target".trim()
                        if (fallbackGoal.isNotBlank()) {
                            return PhoneAgent.run(this, fallbackGoal, null) { setChatVisible(it) }
                        }
                    }
                }
            }
        }
        return cleanResponse
    }

    private fun brainAvailable(): Boolean =
        (LlmRouter.hasAnyApiKey(this) && NetworkUtils.isOnline(this)) || OnDeviceAiClient.isAvailable(this)

    private suspend fun handleReply(reply: String, allowSee: Boolean = true): String {
        val see = Regex("\\[?ACTION[=:]\\s*SEE_SCREEN(?:[=:]([^]\\n]*))?\\]?", RegexOption.IGNORE_CASE).find(reply)
        if (see != null && allowSee) {
            val question = see.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: "Describe what is on screen and answer the user."
            setChatVisible(false)
            val shotBytes = try {
                CapriAccessibilityService.instance?.captureScreenBitmap()
            } finally {
                setChatVisible(true)
            }
            if (shotBytes != null) {
                return askBrain(question, shotBytes) ?: "i looked at the screen, choom, but couldn't get an answer."
            }
            return "i couldn't capture the screen right now, choom. make sure accessibility is enabled."
        }
        val cleaned = Regex("\\[?ACTION[=:]\\s*SEE_SCREEN(?:[=:][^]\\n]*)?\\]?", RegexOption.IGNORE_CASE).replace(reply, "").trim()
        val task = Regex("\\[?ACTION[=:]\\s*TASK[=:]\\s*(.+?)\\]?$", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(cleaned)
        if (task != null) {
            val goal = task.groupValues[1].trim().removeSuffix("]").trim()
            return PhoneAgent.run(this, goal, null) { flag -> setChatVisible(flag) }
        }
        return executeEmbeddedActionTag(cleaned).ifBlank { "on it, choom." }
    }

    suspend fun executeBrainReply(reply: String): String = handleReply(reply, allowSee = true)

    // 3-way brain: API -> local model -> null (caller falls back to commands / canned lines)
    private suspend fun askBrain(prompt: String, imageBytes: ByteArray? = null): String? {
        var apiError: String? = null
        val wantsCloud = LlmRouter.getActiveProviderName(this) != "Local" && LlmRouter.hasAnyApiKey(this) && NetworkUtils.isOnline(this)
        val allowSee = imageBytes == null

        // Tier 1: cloud API (needs a key, internet, and NOT explicitly set to Local)
        if (wantsCloud) {
            val result = withContext(Dispatchers.IO) {
                CloudAiClient.generateResponse(this@BubbleService, prompt, imageBytes)
            }
            val text = result.getOrNull()?.takeIf { it.isNotBlank() }
            if (text != null && !text.contains("No brain installed", ignoreCase = true) && !text.contains("brain chip in my slot", ignoreCase = true)) {
                return handleReply(text, allowSee)
            }
            apiError = result.exceptionOrNull()?.message ?: "empty reply"
            android.util.Log.w("CAPRI_BRAIN", "API tier failed ($apiError), trying local")
        }

        // Tier 2: on-device model (only if imported, enabled, and the phone has the RAM)
        if (OnDeviceAiClient.isAvailable(this)) {
            val local = OnDeviceAiClient.generateResponse(this, prompt, imageBytes)
            val text = local.getOrNull()?.takeIf { it.isNotBlank() }
            if (text != null) return handleReply(text, allowSee)
            android.util.Log.w("CAPRI_BRAIN", "Local tier failed (${local.exceptionOrNull()?.message})")
        }

        // Tier 3: nothing worked. A spent budget is the one case worth saying out loud.
        if (apiError?.contains("budget", ignoreCase = true) == true) return Personality.budgetExhausted()
        OnDeviceAiClient.lastError?.let { return "my brain glitched: $it" }
        return null
    }

    private fun noBrainReply(): String {
        val why = OnDeviceAiClient.lastError
        return if (why != null) {
            "my brain glitched: $why"
        } else {
            "i can't reach a brain right now, choom. no internet and no local model yet. " +
                    "download one from settings, or say 'open <app name>' for an app."
        }
    }

    internal suspend fun processCommand(text: String): String {
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

        com.dot.capri.intents.AppChooser.pending?.let { list ->
            val num = trimmedInput.toIntOrNull()
            if (num != null && num in 1..list.size) {
                com.dot.capri.intents.AppChooser.pending = null
                val choice = list[num - 1]
                com.dot.capri.intents.AppChooser.open(this, choice.pkg)
                return "opening ${choice.label}, choom."
            }
            com.dot.capri.intents.AppChooser.pending = null
        }

        val recordMatch = Regex("^(?:record\\s+macro|start\\s+recording|record)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .find(trimmedInput)
        if (recordMatch != null) {
            val name = recordMatch.groupValues[1].trim()
            com.dot.capri.macro.MacroRecorder.start(name, this)
            return "recording macro '$name'. do the steps in your apps, then say 'stop recording' when you're done, choom."
        }

        if (Regex("^(?:stop\\s+recording|finish\\s+macro|save\\s+macro)$", RegexOption.IGNORE_CASE).matches(trimmedInput)) {
            return com.dot.capri.macro.MacroRecorder.stop(this)
        }

        val macro = com.dot.capri.macro.MacroStore.find(this, trimmedInput)
        if (macro != null) {
            return com.dot.capri.macro.MacroRunner.run(this, macro) { setChatVisible(it) }
        }

        // "calibrate attack 0.9 0.55" stores a button as fractions of the screen (0..1).
        Regex("^calibrate\\s+([a-z0-9_]+)\\s+([01](?:\\.\\d+)?)\\s+([01](?:\\.\\d+)?)$", RegexOption.IGNORE_CASE)
            .find(trimmedInput)?.let { m ->
                ControlRegions.set(this, m.groupValues[1], m.groupValues[2].toFloat(), m.groupValues[3].toFloat())
                return "saved ${m.groupValues[1].lowercase()} at ${m.groupValues[2]}, ${m.groupValues[3]}."
            }
        if (Regex("^(?:add\\s+mb|new\\s+mb|spawn\\s+mb)$", RegexOption.IGNORE_CASE).matches(trimmedInput)) {
            val mb = ControlRegions.addNextMacroButton(this)
            minimizeToChat()
            return "spawned ${mb.name.uppercase()}. drag it over the button on screen (tap it to set role, or leave it on auto so i learn what it does)."
        }
        if (Regex("^(?:show\\s+mbs?|mbs\\s+on)$", RegexOption.IGNORE_CASE).matches(trimmedInput)) {
            ControlRegions.showOverlays(this)
            minimizeToChat()
            return "showing macro buttons on screen."
        }
        if (Regex("^(?:hide\\s+mbs?|mbs\\s+off)$", RegexOption.IGNORE_CASE).matches(trimmedInput)) {
            ControlRegions.hideOverlays(this)
            return "hidden macro buttons (their positions are still saved for me to use)."
        }
        if (Regex("^(?:clear\\s+mbs?|delete\\s+mbs?|reset\\s+mbs?)$", RegexOption.IGNORE_CASE).matches(trimmedInput)) {
            ControlRegions.clearAll(this)
            return "cleared all macro buttons, choom."
        }
        if (trimmedInput.equals("regions", ignoreCase = true) || trimmedInput.equals("mbs", ignoreCase = true)) {
            return ControlRegions.names(this).ifEmpty { "no macro buttons yet. tap [ +MB ] in the bubble header to drop one on screen!" }
        }

        // INSTANT MACRO BUTTON EXECUTION:
        // If the user mentions anything that resembles the name/label of ANY Macro Button (e.g. "tap peashooter and tap lane 1", "resume game"),
        // execute it immediately without LLM delay!
        ControlRegions.tryExecuteUserMbCommand(this, trimmedInput) { setChatVisible(it) }?.let { instantResult ->
            return instantResult
        }

        val taskMatch = Regex("^(?:task|agent|do\\s+this)\\s*[:\\-]\\s*(.+)$", RegexOption.IGNORE_CASE)
            .find(trimmedInput)
        if (taskMatch != null) {
            PhoneAgent.pending = null
            return PhoneAgent.run(this, taskMatch.groupValues[1], null) { setChatVisible(it) }
        }

        // Setting or querying default video player (reels app)
        val setDefaultVideoMatch = Regex(
            "^(?:set\\s+(?:default\\s+)?(?:reels|videos?|vids?|videoplayer|video\\s+player)(?:\\s+(?:app|player))?\\s+(?:to\\s+|is\\s+)?|default\\s+(?:videoplayer|video\\s+player|reels|videos?|vids?)\\s+|(?i)(?:reels|videos?|vids?)\\s+(?:play|plays|should\\s+play|open|opens)\\s+(?:on|in|using|via)\\s+)(.+?)(?:\\s+by\\s+default)?$",
            RegexOption.IGNORE_CASE
        ).find(trimmedInput)
        if (setDefaultVideoMatch != null) {
            val targetApp = setDefaultVideoMatch.groupValues[1].trim()
            val resolved = com.dot.capri.intents.AppChooser.findBestMatch(this, targetApp)
            val appName = resolved?.label ?: targetApp
            MainActivity.setDefaultReelsApp(this, appName)
            return "set default video player to $appName, choom."
        }
        if (Regex("^(?:what(?:'s|\\s+is)\\s+(?:the\\s+)?default\\s+(?:videoplayer|video\\s+player|reels\\s+app)|default\\s+(?:videoplayer|video\\s+player)\\??)$", RegexOption.IGNORE_CASE).containsMatchIn(trimmedInput)) {
            return "default video player is ${MainActivity.getDefaultReelsApp(this)}, choom."
        }

        // Setting or querying default media player (music app)
        val setDefaultMusicMatch = Regex(
            "^(?:set\\s+(?:default\\s+)?(?:music|songs?|mediaplayer|media\\s+player|music\\s+player)(?:\\s+(?:app|player))?\\s+(?:to\\s+|is\\s+)?|default\\s+(?:mediaplayer|media\\s+player|music\\s+player|music|songs?)\\s+|(?i)(?:songs?|music)\\s+(?:play|plays|should\\s+play|open|opens)\\s+(?:on|in|using|via)\\s+)(.+?)(?:\\s+by\\s+default)?$",
            RegexOption.IGNORE_CASE
        ).find(trimmedInput)
        if (setDefaultMusicMatch != null) {
            val targetApp = setDefaultMusicMatch.groupValues[1].trim()
            val resolved = com.dot.capri.intents.AppChooser.findBestMatch(this, targetApp)
            val appName = resolved?.label ?: targetApp
            MainActivity.setDefaultMusicPlayer(this, appName)
            return "set default media player to $appName, choom."
        }
        if (Regex("^(?:what(?:'s|\\s+is)\\s+(?:the\\s+)?default\\s+(?:mediaplayer|media\\s+player|music\\s+player)|default\\s+(?:mediaplayer|media\\s+player)\\??)$", RegexOption.IGNORE_CASE).containsMatchIn(trimmedInput)) {
            return "default media player is ${MainActivity.getDefaultMusicPlayer(this)}, choom."
        }

        // ANY Action Request (e.g. "open spotify", "open my liked songs", "walk to that tree",
        // "take over the screen and mine wood", "turn on bluetooth", "search for cat videos", "craft a bed"):
        // Activates screen control via PhoneAgent immediately.
        // Normal questions (e.g. "what is the capital of France?", "why is the sky blue?", "tell me a joke", "what is on my screen?")
        // are identified as informational questions and fall through to askBrain without touching the screen!
        val actionGoal = extractActionRequest(trimmedInput)
        if (actionGoal != null) {
            PhoneAgent.pending = null
            return PhoneAgent.run(this, actionGoal, null) { setChatVisible(it) }
        }

        // Dev overlay: "dev grid on" / "dev grid off".
        Regex("^dev grid (on|off)$", RegexOption.IGNORE_CASE).find(trimmedInput)?.let { m ->
            val on = m.groupValues[1].equals("on", ignoreCase = true)
            DevOverlay.setEnabled(this, on)
            return if (on) "dev grid on. red marks show every tap." else "dev grid off."
        }

        // Dev tap: "go C4", "go to C4", "go 0.42 0.31" (percent), "go 540 1200" (pixels).
        Regex("^go(?:\\s+to)?\\s+(?:([a-j][0-9]{1,2})|([01](?:\\.\\d+)?)\\s+([01](?:\\.\\d+)?)|(\\d+)\\s+(\\d+))$",
            RegexOption.IGNORE_CASE).find(trimmedInput)?.let { m ->
            val (w, h) = PhoneControl.screenSize() ?: return "i can't read the screen size right now."
            val target: Pair<Int, Int> = when {
                m.groupValues[1].isNotEmpty() ->
                    DevOverlay.cellCentre(m.groupValues[1], w, h) ?: return "there's no cell called ${m.groupValues[1].uppercase()}."
                m.groupValues[2].isNotEmpty() ->
                    Pair((m.groupValues[2].toFloat() * w).toInt(), (m.groupValues[3].toFloat() * h).toInt())
                else ->
                    Pair(m.groupValues[4].toInt(), m.groupValues[5].toInt())
            }
            val tx = target.first.coerceIn(0, w - 1)
            val ty = target.second.coerceIn(0, h - 1)
            val cell = DevOverlay.cellName(tx, ty, w, h)
            DevOverlay.mark(tx, ty, "go $cell")
            val ok = PhoneControl.tapAt(tx, ty)
            return if (ok) "tapped $cell ($tx, $ty)." else "the tap at $cell ($tx, $ty) didn't go through."
        }

        // Brain interprets all requests (no hardcoded keyword/Cmd parser fallback).
        if (brainAvailable()) {
            return askBrain(text) ?: "i didn't get an answer, try again."
        }
        return noBrainReply()
    }

    private fun extractActionRequest(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        // 1. Casual chat greetings are never device action requests
        val isCasualGreeting = Regex(
            "^(?:hello|hi|hey|howdy|sup|yo|good\\s+(?:morning|afternoon|evening|night))\\b[!?.]*$",
            RegexOption.IGNORE_CASE
        ).matches(trimmed)
        if (isCasualGreeting) return null

        val cleaned = trimmed
            .replace(Regex("^(?:(?:hey|yo|ok|hi)\\s+)?capri[,!.:]?\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:please\\s+|just\\s+)+", RegexOption.IGNORE_CASE), "")
            .trim()

        // 2. Questions seeking explanations, definitions, facts, or reasons are NORMAL QUESTIONS
        val isPureInformationQuestion = Regex(
            "^(?:what(?:'s|\\s+is|\\s+are|\\s+was|\\s+were|\\s+does|\\s+did|\\s+do|\\s+causes|\\s+makes|\\s+happened)\\b|" +
                    "who(?:'s|\\s+is|\\s+was|\\s+are|\\s+were)\\b|" +
                    "why(?:'s|\\s+is|\\s+are|\\s+do|\\s+does|\\s+did|\\s+was|\\s+were)\\b|" +
                    "how\\s+(?:do\\s+i|does|did|is|are|come|much|many|far|old|long)\\b|" +
                    "which\\b|when\\b|where\\b|" +
                    "tell\\s+me\\s+(?:about|a\\s+joke|a\\s+story|something)|" +
                    "explain\\b|describe\\b|define\\b|" +
                    "do\\s+you\\s+(?:know|think|like|have)|are\\s+you\\b)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(cleaned)

        val isPoliteExplanationQuestion = Regex(
            "^(?:can|could|would|will)\\s+you\\s+(?:tell|explain|describe|answer|help\\s+me\\s+understand)\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(cleaned)

        val isScreenQueryQuestion = Regex(
            "^(?:what(?:'s|\\s+is)|what\\s+do\\s+you\\s+see|describe|read|tell\\s+me\\s+what).*screen",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(cleaned)

        if (isPureInformationQuestion || isPoliteExplanationQuestion || isScreenQueryQuestion) {
            return null
        }

        // 3. Strip polite request wrappers: "Can you...", "Could you please...", "Take over the screen and..."
        val stripped = cleaned
            .replace(Regex("^(?:can|could|will|would)\\s+you\\s+(?:please\\s+)?(?:just\\s+)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:i\\s+want\\s+you\\s+to|i\\s+need\\s+you\\s+to)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:take\\s+over(?:\\s+the\\s+screen)?|take\\s+control)\\s*(?:and\\s+|then\\s+|,\\s*)?", RegexOption.IGNORE_CASE), "")
            .trim()

        // 4. Match Action Directive verbs and patterns
        val isActionDirective = Regex(
            "^(?:open|launch|start|close|exit|quit|" +
                    "play|stream|pause|resume|unpause|skip|" +
                    "go\\s+(?:to|on|in)|navigate\\s+to|switch\\s+to|" +
                    "turn\\s+(?:on|off)|switch\\s+(?:on|off)|enable|disable|mute|unmute|" +
                    "volume\\s+(?:up|down)|increase\\s+volume|decrease\\s+volume|" +
                    "tap|click|press|hold|long\\s+press|double\\s+tap|swipe|scroll|drag|pinch|" +
                    "walk|move|run|mine|chop|break|dig|attack|hit|kill|shoot|jump|craft|make|build|buy|collect|gather|place|put|farm|plant|harvest|" +
                    "look\\s+(?:up|down|left|right|around)|" +
                    "search(?:\\s+for)?|find|" +
                    "text|message|send\\s+a\\s+message|call|dial|" +
                    "type|write|clear\\s+field)\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(stripped) ||
                Regex("\\b(and\\s+mine|and\\s+walk|and\\s+craft|and\\s+attack|and\\s+turn\\s+on|and\\s+play|and\\s+open|and\\s+search)\\b", RegexOption.IGNORE_CASE).containsMatchIn(stripped) ||
                Regex("\\b(take\\s+over(?:\\s+the\\s+screen)?|take\\s+control)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)

        if (isActionDirective) {
            return stripped.ifEmpty { cleaned }
        }

        // Questions ending with '?' without action verbs are normal questions
        if (trimmed.endsWith("?")) return null

        return null
    }

    override fun onDestroy() {
        CapriCursor.hide()
        ControlRegions.hideOverlays(this)
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
