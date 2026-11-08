package com.dot.capri.agent

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Flight recorder for Capri's brain.
 * Stores the exact annotated screenshot, active strategic plan, prompt context,
 * raw LLM output, parsed action, latency, and outcome for every step so the user
 * can inspect them in BrainInspectorActivity.
 */
object BrainTraceStore {

    data class TurnTrace(
        val id: Int,
        val timestamp: Long,
        val goal: String,
        val pkg: String,
        val mode: String,           // "APP_MODE" or "GAME_MODE"
        val provider: String,       // "Local Gemma E2B" or "Cloud API (...)"
        val latencyMs: Long,
        val visionResolution: String,
        val screenshotFile: String?, // Saved JPEG path in cacheDir/brain_traces
        val activePlan: String,
        val memoryBlock: String,
        val ocrText: String,
        val uiElementsText: String,
        val fullPrompt: String,
        val rawModelReply: String,
        var executedAction: String = "pending...",
        var outcomeStatus: String = "RUNNING"
    )

    private const val MAX_TRACES = 35
    private const val MAX_SAVED_IMAGES = 18
    private var nextId = 1

    private val traces = CopyOnWriteArrayList<TurnTrace>()

    fun recordTurnStart(
        context: Context,
        goal: String,
        pkg: String,
        isGameMode: Boolean,
        provider: String,
        latencyMs: Long,
        shotBytes: ByteArray?,
        shotW: Int,
        shotH: Int,
        activePlan: String,
        memoryBlock: String,
        ocrText: String,
        uiElementsText: String,
        fullPrompt: String,
        rawModelReply: String
    ): Int {
        val turnId = synchronized(this) { nextId++ }
        val savedImgPath = saveTraceScreenshot(context, turnId, shotBytes)
        val sliderPx = VisionConfig.getMaxSide(context)
        val resLabel = if (shotBytes != null) "${shotW}x${shotH}px (Slider: ${sliderPx}px)" else "No Screenshot (Text Tree Only)"

        val trace = TurnTrace(
            id = turnId,
            timestamp = System.currentTimeMillis(),
            goal = goal,
            pkg = pkg,
            mode = if (isGameMode) "GAME_MODE" else "APP_MODE",
            provider = provider,
            latencyMs = latencyMs,
            visionResolution = resLabel,
            screenshotFile = savedImgPath,
            activePlan = activePlan,
            memoryBlock = memoryBlock,
            ocrText = ocrText,
            uiElementsText = uiElementsText,
            fullPrompt = fullPrompt,
            rawModelReply = rawModelReply.ifBlank { "(empty reply)" }
        )

        traces.add(0, trace) // newest first
        while (traces.size > MAX_TRACES) {
            val removed = traces.removeAt(traces.lastIndex)
            removed.screenshotFile?.let { runCatching { File(it).delete() } }
        }
        return turnId
    }

    fun recordProactivityTrace(
        context: Context,
        pkg: String,
        remark: String,
        rawModelReply: String,
        shotBytes: ByteArray?
    ): Int {
        val turnId = synchronized(this) { nextId++ }
        val savedImgPath = saveTraceScreenshot(context, turnId, shotBytes)
        val provider = com.dot.capri.llm.LlmRouter.getActiveProviderName(context)

        val trace = TurnTrace(
            id = turnId,
            timestamp = System.currentTimeMillis(),
            goal = "Vision Banner: \"$remark\"",
            pkg = pkg,
            mode = "PROACTIVITY",
            provider = provider,
            latencyMs = 0L,
            visionResolution = if (shotBytes != null) "${shotBytes.size / 1024} KB JPEG" else "Blind (Text Only)",
            screenshotFile = savedImgPath,
            activePlan = "Proactive Comment: $remark",
            memoryBlock = "",
            ocrText = "",
            uiElementsText = "",
            fullPrompt = "Proactive Vision Evaluation",
            rawModelReply = rawModelReply,
            executedAction = "Display Droplet Banner: \"$remark\"",
            outcomeStatus = "POSTED"
        )

        traces.add(0, trace)
        while (traces.size > MAX_TRACES) {
            val removed = traces.removeAt(traces.lastIndex)
            removed.screenshotFile?.let { runCatching { File(it).delete() } }
        }
        return turnId
    }

    fun updateTurnOutcome(turnId: Int, executedAction: String, outcomeStatus: String) {
        val item = traces.firstOrNull { it.id == turnId } ?: return
        item.executedAction = executedAction
        item.outcomeStatus = outcomeStatus
    }

    fun getAllTraces(): List<TurnTrace> = traces.toList()

    fun clearAll(context: Context) {
        traces.clear()
        val dir = File(context.cacheDir, "brain_traces")
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    fun exportAllAsText(context: Context): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return buildString {
            appendLine("=== CAPRI BRAIN INSPECTOR EXPORT ===")
            appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("Vision Slider: ${VisionConfig.getMaxSide(context)}px\n")
            appendLine(AgentMemory.dumpMemorySummary(context))
            appendLine("\n=== STEP-BY-STEP THOUGHT & VISION TRACE (${traces.size} turns) ===")
            for (t in traces) {
                appendLine("\n----------------------------------------")
                appendLine("STEP #${t.id} [${fmt.format(Date(t.timestamp))}] · ${t.mode} · ${t.provider} · ${t.latencyMs}ms")
                appendLine("App: ${t.pkg} | Vision: ${t.visionResolution}")
                appendLine("Goal: ${t.goal}")
                appendLine("Plan: ${t.activePlan}")
                appendLine("Raw Model Output: ${t.rawModelReply}")
                appendLine("Executed Action: ${t.executedAction} -> [${t.outcomeStatus}]")
                appendLine("Memory Injected:\n${t.memoryBlock}")
                appendLine("OCR Text:\n${t.ocrText}")
            }
        }
    }

    private fun saveTraceScreenshot(context: Context, turnId: Int, bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            val dir = File(context.cacheDir, "brain_traces").apply { mkdirs() }
            // Keep only the newest MAX_SAVED_IMAGES files on disk
            val existing = dir.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
            if (existing.size >= MAX_SAVED_IMAGES) {
                existing.take(existing.size - MAX_SAVED_IMAGES + 1).forEach { runCatching { it.delete() } }
            }
            val f = File(dir, "trace_step_$turnId.jpg")
            f.writeBytes(bytes)
            f.absolutePath
        } catch (_: Exception) {
            null
        }
    }
}
