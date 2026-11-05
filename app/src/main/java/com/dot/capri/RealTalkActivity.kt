package com.dot.capri

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class RealTalkActivity : AppCompatActivity() {

    private lateinit var listMessages: ListView
    private lateinit var emptyStateContainer: LinearLayout
    private lateinit var typingIndicatorContainer: LinearLayout
    private lateinit var tvTypingStatus: TextView
    private lateinit var tvActiveModelBadge: TextView
    private lateinit var etInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnClear: ImageButton

    private val messages = mutableListOf<RealTalkTurn>()
    private lateinit var adapter: RealTalkMessageAdapter
    private var isGenerating = false

    companion object {
        private const val PREFS_NAME = "capri_real_talk_prefs"
        private const val KEY_HISTORY_JSON = "real_talk_history_v1"
        private const val MAX_SAVED_MESSAGES = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_real_talk)

        bindViews()
        setupAdapter()
        loadConversationHistory()
        refreshHeaderModelBadge()
        setupListeners()

        // Optional: handle pre-filled prompt if launched with an intent extra
        val initialPrompt = intent?.getStringExtra("EXTRA_INITIAL_PROMPT")
        if (!initialPrompt.isNullOrBlank()) {
            intent.removeExtra("EXTRA_INITIAL_PROMPT")
            submitUserMessage(initialPrompt)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHeaderModelBadge()
    }

    private fun bindViews() {
        listMessages = findViewById(R.id.listRealTalkMessages)
        emptyStateContainer = findViewById(R.id.emptyStateContainer)
        typingIndicatorContainer = findViewById(R.id.typingIndicatorContainer)
        tvTypingStatus = findViewById(R.id.tvTypingStatus)
        tvActiveModelBadge = findViewById(R.id.tvActiveModelBadge)
        etInput = findViewById(R.id.etRealTalkInput)
        btnSend = findViewById(R.id.btnSendRealTalk)
        btnBack = findViewById(R.id.btnBackRealTalk)
        btnClear = findViewById(R.id.btnClearRealTalk)
    }

    private fun setupAdapter() {
        adapter = RealTalkMessageAdapter(this, messages)
        listMessages.adapter = adapter
    }

    private fun refreshHeaderModelBadge() {
        val badge = RealTalkBridge.getActiveModelBadge(this)
        tvActiveModelBadge.text = badge
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
        }

        btnClear.setOnClickListener {
            if (messages.isEmpty()) {
                Toast.makeText(this, "Conversation is already empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Clear Real Talk History?")
                .setMessage("This will remove all messages in the current Real Talk conversation.")
                .setPositiveButton("Clear") { _, _ ->
                    messages.clear()
                    saveConversationHistory()
                    updateEmptyState()
                    adapter.notifyDataSetChanged()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnSend.setOnClickListener {
            val text = etInput.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && !isGenerating) {
                etInput.setText("")
                submitUserMessage(text)
            }
        }

        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val text = etInput.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty() && !isGenerating) {
                    etInput.setText("")
                    submitUserMessage(text)
                }
                true
            } else {
                false
            }
        }
    }

    private fun submitUserMessage(userText: String) {
        val userTurn = RealTalkTurn(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = userText,
            timestamp = System.currentTimeMillis()
        )
        messages.add(userTurn)
        saveConversationHistory()
        updateEmptyState()
        adapter.notifyDataSetChanged()
        scrollToBottom()

        isGenerating = true
        btnSend.isEnabled = false
        btnSend.alpha = 0.5f
        typingIndicatorContainer.visibility = View.VISIBLE

        val historySnapshot = messages.dropLast(1)

        val gameAgentRequest = Regex("^(?:can\\s+you\\s+|please\\s+|could\\s+you\\s+|i\\s+want\\s+you\\s+to\\s+)?(?:play\\s+(?:this|the)?\\s*game|play\\s+for\\s+me|take\\s+over|control\\s+the\\s+screen|play\\s+on\\s+screen|help\\s+me\\s+(?:win|play|beat)|win\\s+(?:this|the)\\s+game|beat\\s+this\\s+level|make\\s+a\\s+new\\s+world|mine\\s+wood)(?:\\s+.*)?$", RegexOption.IGNORE_CASE).matches(userText)
        val isDeviceWork = com.dot.capri.agent.DeviceTasks.mentionsDeviceWork(userText) || gameAgentRequest
        val parsedCmd = com.dot.capri.intents.IntentParser.parse(userText)
        val isSeeScreen = parsedCmd is com.dot.capri.intents.Cmd.SeeScreen || Regex("what(?:'s|\\s+is)\\s+on\\s+(?:my\\s+|the\\s+)?screen|what\\s+do\\s+you\\s+see", RegexOption.IGNORE_CASE).containsMatchIn(userText)

        lifecycleScope.launch {
            try {
                val (replyText, modelUsed) = if (isDeviceWork || isSeeScreen) {
                    moveTaskToBack(true)
                    delay(350)
                    val result = if (isDeviceWork) {
                        BubbleService.instance?.processCommand(userText)
                            ?: com.dot.capri.agent.PhoneAgent.run(applicationContext, userText, null) {}
                    } else {
                        val shot = CapriAccessibilityService.instance?.captureScreenBitmap()
                        if (shot != null && com.dot.capri.ai.OnDeviceAiClient.isAvailable(applicationContext)) {
                            com.dot.capri.ai.OnDeviceAiClient.generateResponse(applicationContext, "Describe what is on screen: $userText", shot).getOrNull()
                                ?: "i couldn't read the screen, choom."
                        } else {
                            BubbleService.instance?.processCommand(userText) ?: "i couldn't capture the screen, choom."
                        }
                    }
                    Pair(result, RealTalkBridge.getActiveModelBadge(this@RealTalkActivity))
                } else {
                    RealTalkBridge.sendMessage(
                        context = this@RealTalkActivity,
                        history = historySnapshot,
                        userMessage = userText
                    )
                }

                typingIndicatorContainer.visibility = View.GONE

                val assistantTurn = RealTalkTurn(
                    id = UUID.randomUUID().toString(),
                    role = "assistant",
                    content = "",
                    timestamp = System.currentTimeMillis(),
                    modelTag = modelUsed
                )
                messages.add(assistantTurn)
                updateEmptyState()
                adapter.notifyDataSetChanged()
                scrollToBottom()

                val targetId = assistantTurn.id
                val sb = StringBuilder()
                val stepDelay = if (replyText.length > 300) 8L else 12L

                for (char in replyText) {
                    sb.append(char)
                    val idx = messages.indexOfFirst { it.id == targetId }
                    if (idx != -1) {
                        messages[idx] = messages[idx].copy(content = sb.toString())
                        adapter.notifyDataSetChanged()
                        scrollToBottom()
                    } else {
                        break
                    }
                    delay(stepDelay)
                }

                saveConversationHistory()
            } catch (e: Exception) {
                typingIndicatorContainer.visibility = View.GONE
                val errorTurn = RealTalkTurn(
                    id = UUID.randomUUID().toString(),
                    role = "assistant",
                    content = "Error communicating with LLM: ${e.localizedMessage ?: "Unknown error"}",
                    timestamp = System.currentTimeMillis(),
                    modelTag = "ERROR"
                )
                messages.add(errorTurn)
                adapter.notifyDataSetChanged()
                scrollToBottom()
            } finally {
                isGenerating = false
                btnSend.isEnabled = true
                btnSend.alpha = 1.0f
                typingIndicatorContainer.visibility = View.GONE
            }
        }
    }

    private fun updateEmptyState() {
        if (messages.isEmpty()) {
            emptyStateContainer.visibility = View.VISIBLE
            listMessages.visibility = View.GONE
        } else {
            emptyStateContainer.visibility = View.GONE
            listMessages.visibility = View.VISIBLE
        }
    }

    private fun scrollToBottom() {
        if (messages.isNotEmpty()) {
            listMessages.post {
                listMessages.setSelection(adapter.count - 1)
            }
        }
    }

    private fun loadConversationHistory() {
        messages.clear()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawJson = prefs.getString(KEY_HISTORY_JSON, null)
        if (!rawJson.isNullOrBlank()) {
            try {
                val array = JSONArray(rawJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    messages.add(
                        RealTalkTurn(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            role = obj.optString("role", "assistant"),
                            content = obj.optString("content", ""),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            modelTag = obj.optString("modelTag", "")
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }
        updateEmptyState()
        adapter.notifyDataSetChanged()
        scrollToBottom()
    }

    private fun saveConversationHistory() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val trimmed = messages.takeLast(MAX_SAVED_MESSAGES)
        val array = JSONArray()
        for (turn in trimmed) {
            val obj = JSONObject().apply {
                put("id", turn.id)
                put("role", turn.role)
                put("content", turn.content)
                put("timestamp", turn.timestamp)
                put("modelTag", turn.modelTag)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY_JSON, array.toString()).apply()
    }

    private class RealTalkMessageAdapter(
        private val context: Context,
        private val items: List<RealTalkTurn>
    ) : BaseAdapter() {

        private val inflater = LayoutInflater.from(context)
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): RealTalkTurn = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder: ViewHolder
            val view: View

            if (convertView == null) {
                view = inflater.inflate(R.layout.item_real_talk_message, parent, false)
                holder = ViewHolder(
                    userContainer = view.findViewById(R.id.userMessageContainer),
                    tvUserText = view.findViewById(R.id.tvUserMessageText),
                    tvUserTime = view.findViewById(R.id.tvUserTimestamp),
                    capriContainer = view.findViewById(R.id.capriMessageContainer),
                    tvCapriLabel = view.findViewById(R.id.tvCapriSenderLabel),
                    tvCapriText = view.findViewById(R.id.tvCapriMessageText),
                    tvCapriTime = view.findViewById(R.id.tvCapriTimestamp)
                )
                view.tag = holder
            } else {
                view = convertView
                holder = view.tag as ViewHolder
            }

            val turn = getItem(position)
            val formattedTime = timeFormat.format(Date(turn.timestamp))

            if (turn.role == "user") {
                holder.userContainer.visibility = View.VISIBLE
                holder.capriContainer.visibility = View.GONE
                holder.tvUserText.text = turn.content
                holder.tvUserTime.text = formattedTime

                holder.userContainer.setOnLongClickListener {
                    copyToClipboard(turn.content)
                    true
                }
            } else {
                holder.userContainer.visibility = View.GONE
                holder.capriContainer.visibility = View.VISIBLE
                holder.tvCapriText.text = turn.content
                holder.tvCapriTime.text = formattedTime
                holder.tvCapriLabel.text = if (turn.modelTag.isNotBlank()) {
                    "Capri · ${turn.modelTag}"
                } else {
                    "Capri"
                }

                holder.capriContainer.setOnLongClickListener {
                    copyToClipboard(turn.content)
                    true
                }
            }

            return view
        }

        private fun copyToClipboard(text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Capri Real Talk", text))
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        private class ViewHolder(
            val userContainer: LinearLayout,
            val tvUserText: TextView,
            val tvUserTime: TextView,
            val capriContainer: LinearLayout,
            val tvCapriLabel: TextView,
            val tvCapriText: TextView,
            val tvCapriTime: TextView
        )
    }
}