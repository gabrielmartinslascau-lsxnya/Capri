package com.dot.capri.ai

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fast Model engine (ShowUI-2B / local LLM brain): mirrors OnDeviceAiClient architecture 100%.
 */
object FastModelEngine {

    private const val TAG = "CAPRI_FAST_ENGINE"
    const val KEY_FAST_LLM_ENABLED = "fast_llm_enabled"
    private const val MODEL_FILE_NAME = "showui-2b.litertlm"
    private const val MIN_RAM_BYTES = 4_500_000_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: Engine? = null
    @Volatile var lastError: String? = null

    fun modelFile(context: Context): File {
        val dir = File(context.filesDir, "models")
        return File(dir, MODEL_FILE_NAME)
    }

    private fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("capri_prefs", Context.MODE_PRIVATE)
            .getBoolean(KEY_FAST_LLM_ENABLED, true)

    private fun hasEnoughRam(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem >= MIN_RAM_BYTES
    }

    fun isAvailable(context: Context): Boolean =
        isEnabled(context) && modelFile(context).exists() && hasEnoughRam(context)

    fun statusLine(context: Context): String {
        val file = modelFile(context)
        val base = when {
            !hasEnoughRam(context) -> "this phone doesn't have enough RAM for the fast model."
            !file.exists() -> "no fast model file yet. download or import showui-2b.litertlm."
            !isEnabled(context) -> "fast model imported but switched off."
            else -> "fast model ready (${file.length() / 1_000_000} MB)."
        }
        return lastError?.let { "$base\nlast error: $it" } ?: base
    }

    private fun loadEngine(context: Context): Engine? {
        engine?.let { return it }
        val file = modelFile(context)
        if (!file.exists()) {
            lastError = "fast model file missing"
            return null
        }
        return try {
            val cfg = EngineConfig(
                modelPath = file.absolutePath,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.path
            )
            val e = Engine(cfg)
            e.initialize()
            engine = e
            lastError = null
            e
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load fast model engine", t)
            lastError = t.message
            null
        }
    }

    suspend fun generateResponse(
        context: Context,
        prompt: String,
        imageBytes: ByteArray?,
        systemOverride: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val eng = loadEngine(context) ?: return@withContext Result.failure(
                IllegalStateException(lastError ?: "Fast model engine not loaded")
            )
            val config = ConversationConfig(
                systemInstruction = Contents.of(systemOverride ?: CloudAiClient.SYSTEM_PROMPT),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.9),
                maxOutputToken = 120
            )
            val sb = StringBuilder()
            eng.createConversation(config).use { conversation ->
                val flow = conversation.sendMessageAsync(prompt.take(2000))
                flow.collect { sb.append(it.toString()) }
            }
            Result.success(sb.toString().trim())
        } catch (e: Exception) {
            Log.e(TAG, "Fast model generation failed", e)
            lastError = e.message
            Result.failure(e)
        }
    }
}
