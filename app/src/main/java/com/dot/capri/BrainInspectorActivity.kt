package com.dot.capri

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dot.capri.agent.AgentMemory
import com.dot.capri.agent.BrainTraceStore
import com.dot.capri.agent.VisionConfig
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BrainInspectorActivity : AppCompatActivity() {

    private lateinit var contentContainer: LinearLayout
    private lateinit var tvSubtitle: TextView
    private lateinit var tabThoughtsBtn: Button
    private lateinit var tabMemoryBtn: Button
    private lateinit var tabLogcatBtn: Button
    private var currentTab = 0 // 0 = Thoughts & Screenshots, 1 = Memory & Maps, 2 = Logcat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRootLayout())
        switchTab(0)
    }

    override fun onResume() {
        super.onResume()
        refreshCurrentTab()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedBg(fillHex: Int, strokeHex: Int? = null, radiusDp: Int = 12): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fillHex)
            if (strokeHex != null) {
                setStroke(dp(1), strokeHex)
            }
        }
    }

    private fun buildRootLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0F19.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Header Bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF111827.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val btnBack = Button(this).apply {
            text = "←"
            setTextColor(Color.WHITE)
            textSize = 18f
            background = roundedBg(0xFF1E293B.toInt(), 0xFF334155.toInt(), 8)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(38))
            setOnClickListener { finish() }
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            }
        }

        val tvTitle = TextView(this).apply {
            text = "Capri Brain Inspector"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        }

        tvSubtitle = TextView(this).apply {
            text = "Vision: ${VisionConfig.getMaxSide(this@BrainInspectorActivity)}px · Live Thought & Memory Telemetry"
            setTextColor(0xFF94A3B8.toInt())
            textSize = 11f
        }

        titleCol.addView(tvTitle)
        titleCol.addView(tvSubtitle)

        val btnCopy = Button(this).apply {
            text = "Copy All"
            isAllCaps = false
            setTextColor(0xFF00E5FF.toInt())
            textSize = 12f
            background = roundedBg(0xFF0F172A.toInt(), 0xFF00E5FF.toInt(), 8)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                marginEnd = dp(6)
            }
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener {
                val export = BrainTraceStore.exportAllAsText(this@BrainInspectorActivity)
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("Capri Brain Trace", export))
                Toast.makeText(this@BrainInspectorActivity, "Copied full brain trace & memory to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        val btnClear = Button(this).apply {
            text = "Clear"
            isAllCaps = false
            setTextColor(0xFFFF2D6B.toInt())
            textSize = 12f
            background = roundedBg(0xFF0F172A.toInt(), 0xFFFF2D6B.toInt(), 8)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36))
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener {
                AlertDialog.Builder(this@BrainInspectorActivity)
                    .setTitle("Clear Inspector Logs?")
                    .setMessage("Clear recorded step screenshots/traces or reset learned memory too?")
                    .setPositiveButton("Clear Traces Only") { _, _ ->
                        BrainTraceStore.clearAll(this@BrainInspectorActivity)
                        refreshCurrentTab()
                    }
                    .setNeutralButton("Wipe Traces + Memory") { _, _ ->
                        BrainTraceStore.clearAll(this@BrainInspectorActivity)
                        AgentMemory.clearAllMemory(this@BrainInspectorActivity)
                        refreshCurrentTab()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        header.addView(btnBack)
        header.addView(titleCol)
        header.addView(btnCopy)
        header.addView(btnClear)
        root.addView(header)

        // Tab Row
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF111827.toInt())
            setPadding(dp(12), dp(4), dp(12), dp(10))
        }

        tabThoughtsBtn = createTabButton("Thoughts & Shots", 0)
        tabMemoryBtn = createTabButton("Memory & Maps", 1)
        tabLogcatBtn = createTabButton("Live Logcat", 2)

        tabRow.addView(tabThoughtsBtn)
        tabRow.addView(tabMemoryBtn)
        tabRow.addView(tabLogcatBtn)
        root.addView(tabRow)

        // Scrollable Content Area
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }

        scroll.addView(contentContainer)
        root.addView(scroll)
        return root
    }

    private fun createTabButton(title: String, index: Int): Button {
        return Button(this).apply {
            text = title
            isAllCaps = false
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                if (index < 2) marginEnd = dp(6)
            }
            setOnClickListener { switchTab(index) }
        }
    }

    private fun switchTab(index: Int) {
        currentTab = index
        val buttons = listOf(tabThoughtsBtn, tabMemoryBtn, tabLogcatBtn)
        for (i in buttons.indices) {
            if (i == index) {
                buttons[i].background = roundedBg(0xFFFF2D6B.toInt(), null, 10)
                buttons[i].setTextColor(Color.WHITE)
            } else {
                buttons[i].background = roundedBg(0xFF1E293B.toInt(), 0xFF334155.toInt(), 10)
                buttons[i].setTextColor(0xFFCBD5E1.toInt())
            }
        }
        refreshCurrentTab()
    }

    private fun refreshCurrentTab() {
        contentContainer.removeAllViews()
        val traces = BrainTraceStore.getAllTraces()
        tvSubtitle.text = "Vision Slider: ${VisionConfig.getMaxSide(this)}px · ${traces.size} recorded turns"

        when (currentTab) {
            0 -> renderThoughtsTab(traces)
            1 -> renderMemoryTab()
            2 -> renderLogcatTab()
        }
    }

    private fun renderThoughtsTab(traces: List<BrainTraceStore.TurnTrace>) {
        if (traces.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No brain turns recorded yet in this session.\n\nGive Capri a task (e.g. \"go on Spotify and pick one of my liked songs\" or \"play the game on screen\"), then open this inspector to see every screenshot she saw, her active plan, OCR hits, and exact commands!"
                setTextColor(0xFF94A3B8.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(48), dp(24), dp(48))
            }
            contentContainer.addView(emptyTv)
            return
        }

        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

        for (trace in traces) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBg(0xFF151C2C.toInt(), 0xFF2A364F.toInt(), 14)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            }

            // Top Badge Row: Step #, Mode, Latency, Outcome
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val modeColor = if (trace.mode == "GAME_MODE") 0xFFFF2D6B.toInt() else 0xFF00E5FF.toInt()
            val tvStepBadge = TextView(this).apply {
                text = "#${trace.id} · ${trace.mode} · ${trace.latencyMs}ms"
                setTextColor(modeColor)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvTime = TextView(this).apply {
                text = "${timeFmt.format(Date(trace.timestamp))} · ${trace.provider}"
                setTextColor(0xFF94A3B8.toInt())
                textSize = 11f
            }

            topRow.addView(tvStepBadge)
            topRow.addView(tvTime)
            card.addView(topRow)

            // App & Resolution Subline
            val tvMeta = TextView(this).apply {
                text = "App: ${trace.pkg}  |  Saw: ${trace.visionResolution}"
                setTextColor(0xFFCBD5E1.toInt())
                textSize = 11f
                setPadding(0, dp(4), 0, dp(8))
            }
            card.addView(tvMeta)

            // Main Body Row: Screenshot Thumbnail on Left + Plan & Action on Right
            val bodyRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }

            if (trace.screenshotFile != null && File(trace.screenshotFile).exists()) {
                val imgView = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(96), dp(148)).apply {
                        marginEnd = dp(10)
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = roundedBg(0xFF090D16.toInt(), 0xFF334155.toInt(), 8)
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    val bmp = BitmapFactory.decodeFile(trace.screenshotFile)
                    setImageBitmap(bmp)
                    setOnClickListener {
                        showFullScreenImageDialog(trace)
                    }
                }
                bodyRow.addView(imgView)
            }

            val rightCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            // Active Plan Box
            val tvPlan = TextView(this).apply {
                text = "PLAN: ${trace.activePlan}"
                setTextColor(0xFFFBBF24.toInt())
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                background = roundedBg(0xFF1F2937.toInt(), 0xFF92400E.toInt(), 8)
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            rightCol.addView(tvPlan)

            // Raw LLM Output
            val tvRawReply = TextView(this).apply {
                text = "Brain Output:\n${trace.rawModelReply}"
                setTextColor(0xFFE2E8F0.toInt())
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(6), 0, dp(4))
            }
            rightCol.addView(tvRawReply)

            // Executed Action & Outcome Status
            val isSuccess = trace.outcomeStatus.contains("worked", ignoreCase = true) ||
                trace.outcomeStatus.contains("progress", ignoreCase = true)
            val outcomeColor = if (isSuccess) 0xFF10B981.toInt() else 0xFFF87171.toInt()

            val tvAction = TextView(this).apply {
                text = "Did: ${trace.executedAction}\nStatus: ${trace.outcomeStatus}"
                setTextColor(outcomeColor)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            }
            rightCol.addView(tvAction)

            bodyRow.addView(rightCol)
            card.addView(bodyRow)

            // Expandable Details Section (OCR, UI Elements, Memory Injected, Full Prompt)
            val detailsBox = TextView(this).apply {
                visibility = View.GONE
                text = buildString {
                    appendLine("--- MEMORY & HINTS INJECTED ---")
                    appendLine(trace.memoryBlock)
                    appendLine("\n--- OCR TEXT SEEN ON SCREEN ---")
                    appendLine(trace.ocrText)
                    appendLine("\n--- ACCESSIBILITY UI ELEMENTS ---")
                    appendLine(trace.uiElementsText.ifBlank { "(none)" })
                }
                setTextColor(0xFF94A3B8.toInt())
                textSize = 11f
                typeface = Typeface.MONOSPACE
                background = roundedBg(0xFF090D16.toInt(), 0xFF1E293B.toInt(), 8)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setTextIsSelectable(true)
            }

            val btnToggleDetails = TextView(this).apply {
                text = "▼ Show What She Saw (OCR, UI Tree & Memory Hints)"
                setTextColor(0xFF38BDF8.toInt())
                textSize = 12f
                setPadding(0, dp(8), 0, dp(4))
                setOnClickListener {
                    if (detailsBox.visibility == View.GONE) {
                        detailsBox.visibility = View.VISIBLE
                        text = "▲ Hide OCR, UI Tree & Memory Hints"
                    } else {
                        detailsBox.visibility = View.GONE
                        text = "▼ Show What She Saw (OCR, UI Tree & Memory Hints)"
                    }
                }
            }

            card.addView(btnToggleDetails)
            card.addView(detailsBox)
            contentContainer.addView(card)
        }
    }

    private fun showFullScreenImageDialog(trace: BrainTraceStore.TurnTrace) {
        val path = trace.screenshotFile ?: return
        val bmp = BitmapFactory.decodeFile(path) ?: return
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFA050811.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val topInfo = TextView(this).apply {
            text = "Step #${trace.id} Exact Vision Frame (${trace.visionResolution})\nPlan: ${trace.activePlan}\nTap image to close"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(8), dp(8), dp(8), dp(12))
        }

        val iv = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bmp)
            setOnClickListener { dialog.dismiss() }
        }

        wrapper.addView(topInfo)
        wrapper.addView(iv)
        dialog.setContentView(wrapper)
        dialog.show()
    }

    private fun renderMemoryTab() {
        val summaryCard = TextView(this).apply {
            text = AgentMemory.dumpMemorySummary(this@BrainInspectorActivity)
            setTextColor(0xFFE2E8F0.toInt())
            textSize = 12f
            typeface = Typeface.MONOSPACE
            background = roundedBg(0xFF151C2C.toInt(), 0xFF2A364F.toInt(), 12)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setTextIsSelectable(true)
        }
        contentContainer.addView(summaryCard)
    }

    private fun renderLogcatTab() {
        val logText = readCapriLogcat()
        val logCard = TextView(this).apply {
            text = logText.ifBlank { "No CAPRI logcat lines found yet." }
            setTextColor(0xFF34D399.toInt())
            textSize = 11f
            typeface = Typeface.MONOSPACE
            background = roundedBg(0xFF090D16.toInt(), 0xFF1E293B.toInt(), 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        contentContainer.addView(logCard)
    }

    private fun readCapriLogcat(): String {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", "180", "-v", "time")
            )
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val filtered = reader.readLines().filter { line ->
                line.contains("CAPRI_AGENT") ||
                line.contains("CAPRI_LOCAL") ||
                line.contains("CAPRI_BRAIN") ||
                line.contains("CAPRI_CURSOR") ||
                line.contains("CAPRI_PARSE")
            }
            filtered.takeLast(100).reversed().joinToString("\n")
        } catch (e: Exception) {
            "Could not read logcat: ${e.message}"
        }
    }
}
