package com.dot.capri.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.dot.capri.BubbleService
import com.dot.capri.R
import com.dot.capri.RealTalkActivity
import com.dot.capri.agent.ControlRegions
import com.dot.capri.personality.Personality

class CapriVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private var rootContainer: FrameLayout? = null
    private var cardView: View? = null

    override fun onCreateContentView(): View {
        val themedContext = ContextThemeWrapper(context, R.style.Theme_Capri)

        rootContainer = FrameLayout(themedContext).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x66000000.toInt()) // Soft dim scrim
            setOnClickListener {
                hide()
                finish()
            }
        }

        val inflater = LayoutInflater.from(themedContext)
        cardView = inflater.inflate(R.layout.chat_expanded, rootContainer, false).apply {
            setBackgroundResource(R.drawable.bg_chat_window)
            setOnClickListener {
                // Consume clicks on the card so tapping it does not dismiss
            }
        }

        val cardParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            leftMargin = dpToPx(16)
            rightMargin = dpToPx(16)
            bottomMargin = dpToPx(8)
        }
        rootContainer?.addView(cardView, cardParams)

        cardView?.let { setupChatUi(it) }

        // WindowInsets handling for status bar (battery/notifs) and IME (keyboard)
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer!!) { _, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            // Top: ensure corner buttons never get covered by battery, clock, or notification icons
            val topSafe = statusBarInsets.top + dpToPx(16)

            // Bottom: ensure chat card is lifted cleanly above the keyboard
            val bottomSafe = if (imeInsets.bottom > 0) {
                imeInsets.bottom + dpToPx(8)
            } else {
                navBarInsets.bottom + dpToPx(16)
            }

            rootContainer?.setPadding(0, topSafe, 0, bottomSafe)

            val chatScroll = cardView?.findViewById<ScrollView>(R.id.chat_scroll)
            if (imeInsets.bottom > 0) {
                chatScroll?.post {
                    chatScroll.fullScroll(ScrollView.FOCUS_DOWN)
                }
            }

            insets
        }

        return rootContainer!!
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d("CAPRI_ASSIST", "CapriVoiceInteractionSession onShow with showFlags=$showFlags")

        BubbleService.onRequestCloseAssistant = {
            hide()
            finish()
        }
        BubbleService.onAssistantVisibilityChanged = { visible ->
            if (!visible) {
                hide()
                finish()
            }
        }

        val service = BubbleService.instance
        if (service == null) {
            val intent = Intent(context, BubbleService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        window?.window?.apply {
            WindowCompat.setDecorFitsSystemWindows(this, false)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        val historyContainer = cardView?.findViewById<LinearLayout>(R.id.chat_history_container)
        historyContainer?.removeAllViews()
        val history = BubbleService.getPersistentChatHistory()
        if (history.isEmpty()) {
            val greeting = "h-hey... what are we doing today, choom~?"
            history.add(Pair("Capri", greeting))
            addMessage(historyContainer, "Capri", greeting)
        } else {
            for ((speaker, msg) in history) {
                addMessage(historyContainer, speaker, msg)
            }
        }
        val chatScroll = cardView?.findViewById<ScrollView>(R.id.chat_scroll)
        chatScroll?.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }

        val inputText = cardView?.findViewById<EditText>(R.id.input_text)
        inputText?.requestFocus()
        inputText?.postDelayed({
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(inputText, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun setupChatUi(view: View) {
        val inputText = view.findViewById<EditText>(R.id.input_text)
        val sendBtn = view.findViewById<ImageButton>(R.id.send)
        val closeBtn = view.findViewById<ImageButton>(R.id.close)
        val expandBtn = view.findViewById<TextView>(R.id.btn_expand)
        val realTalkBtn = view.findViewById<TextView>(R.id.btn_real_talk)
        val addMbBtn = view.findViewById<TextView>(R.id.btn_add_mb)
        val toggleMbBtn = view.findViewById<TextView>(R.id.btn_toggle_mb)
        val chatScroll = view.findViewById<ScrollView>(R.id.chat_scroll)

        var isExpanded = false
        chatScroll?.layoutParams?.height = dpToPx(110)
        chatScroll?.requestLayout()

        expandBtn?.setOnClickListener {
            isExpanded = !isExpanded
            chatScroll?.layoutParams?.height = dpToPx(if (isExpanded) 250 else 110)
            chatScroll?.requestLayout()
            expandBtn.text = if (isExpanded) "[ - ]" else "[ + ]"
        }

        realTalkBtn?.setOnClickListener {
            hide()
            finish()
            val intent = Intent(context, RealTalkActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        addMbBtn?.setOnClickListener {
            val mb = ControlRegions.addNextMacroButton(context)
            Toast.makeText(context, "Spawned ${mb.name.uppercase()}!", Toast.LENGTH_SHORT).show()
            hide()
            finish()
        }

        toggleMbBtn?.setOnClickListener {
            val shown = ControlRegions.toggleOverlays(context)
            Toast.makeText(context, if (shown) "Showing MBs" else "Hidden MBs", Toast.LENGTH_SHORT).show()
            if (shown) {
                hide()
                finish()
            }
        }

        sendBtn?.setOnClickListener {
            handleSend(view)
        }

        closeBtn?.setOnClickListener {
            hide()
            finish()
        }

        inputText?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                handleSend(view)
                true
            } else {
                false
            }
        }
    }

    override fun onHide() {
        super.onHide()
        BubbleService.onRequestCloseAssistant = null
        BubbleService.onAssistantVisibilityChanged = null
    }

    override fun onDestroy() {
        super.onDestroy()
        BubbleService.onRequestCloseAssistant = null
        BubbleService.onAssistantVisibilityChanged = null
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    private fun handleSend(view: View) {
        val inputText = view.findViewById<EditText>(R.id.input_text) ?: return
        val text = inputText.text.toString().trim()
        if (text.isEmpty()) return

        val historyContainer = view.findViewById<LinearLayout>(R.id.chat_history_container)
        val chatScroll = view.findViewById<ScrollView>(R.id.chat_scroll)
        val thinkingContainer = view.findViewById<View>(R.id.thinking_container)
        val thinkingText = view.findViewById<TextView>(R.id.thinking_text)

        BubbleService.getPersistentChatHistory().add(Pair("You", text))
        addMessage(historyContainer, "You", text)
        inputText.setText("")

        thinkingText?.text = Personality.thinking()
        thinkingContainer?.visibility = View.VISIBLE

        val service = BubbleService.instance
        if (service != null) {
            service.processCommandFromAssistant(text) { response ->
                if (window?.isShowing == true) {
                    thinkingContainer?.visibility = View.GONE
                    addMessage(historyContainer, "Capri", response)
                    chatScroll?.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        } else {
            val intent = Intent(context, BubbleService::class.java)
            ContextCompat.startForegroundService(context, intent)
            thinkingContainer?.visibility = View.GONE
            val fallback = "my bubble service is waking up, choom. try again in a second."
            BubbleService.getPersistentChatHistory().add(Pair("Capri", fallback))
            addMessage(historyContainer, "Capri", fallback)
            chatScroll?.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun addMessage(container: LinearLayout?, speaker: String, text: String) {
        if (container == null) return
        val tv = TextView(context).apply {
            this.text = (if (speaker == "You") "You: " else "Capri: ") + text
            setTextColor(if (speaker == "You") 0xFFFFFFFF.toInt() else 0xFFFF2A55.toInt())
            textSize = 14f
            setPadding(0, 4, 0, 8)
        }
        container.addView(tv)
    }
}
