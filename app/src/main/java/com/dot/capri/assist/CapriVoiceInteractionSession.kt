package com.dot.capri.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dot.capri.BubbleService
import com.dot.capri.R
import com.dot.capri.ai.CloudAiClient
import com.dot.capri.intents.Cmd
import com.dot.capri.intents.IntentExecutor
import com.dot.capri.intents.IntentParser
import com.dot.capri.personality.Personality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CapriVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private var sessionView: View? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreateContentView(): View {
        val themedContext = ContextThemeWrapper(context, R.style.Theme_Capri)
        val inflater = LayoutInflater.from(themedContext)
        sessionView = inflater.inflate(R.layout.chat_expanded, null)
        sessionView?.setBackgroundResource(R.drawable.bg_chat_window)
        sessionView?.let { setupChatUi(it) }
        return sessionView!!
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d("CAPRI_ASSIST", "CapriVoiceInteractionSession onShow with showFlags=$showFlags")

        val service = BubbleService.instance
        if (service == null) {
            val intent = Intent(context, BubbleService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        window?.window?.apply {
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawableResource(android.R.color.transparent)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        val historyContainer = sessionView?.findViewById<LinearLayout>(R.id.chat_history_container)
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
        val chatScroll = sessionView?.findViewById<ScrollView>(R.id.chat_scroll)
        chatScroll?.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }

        val inputText = sessionView?.findViewById<EditText>(R.id.input_text)
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

        scope.launch {
            val response = withContext(Dispatchers.IO) {
                val service = BubbleService.instance
                if (service != null) {
                    service.processCommand(text)
                } else {
                    val cmd = IntentParser.parse(text)
                    if (cmd !is Cmd.Unknown) {
                        IntentExecutor.execute(context, cmd)
                        Personality.respond(cmd, true)
                    } else {
                        "i can't reach a brain right now, choom."
                    }
                }
            }
            thinkingContainer?.visibility = View.GONE
            BubbleService.getPersistentChatHistory().add(Pair("Capri", response))
            addMessage(historyContainer, "Capri", response)
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
