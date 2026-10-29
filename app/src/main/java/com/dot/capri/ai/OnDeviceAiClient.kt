package com.dot.capri.ai

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import android.util.Log
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Calendar

/**
 * Tier 2 of the brain: a model that runs on the phone itself.
 * Used when the API is unavailable (offline, hourly cap, budget spent, API error).
 * The model file is imported from settings and lives in the app's own storage.
 */
object OnDeviceAiClient {

    private const val TAG = "CAPRI_LOCAL"
    const val KEY_LOCAL_LLM_ENABLED = "local_llm_enabled"

    private const val MODEL_FILE_NAME = "capri-local.litertlm"
    const val DEFAULT_MODEL_URL = "https://huggingface.co/google/gemma-2-2b-it-litert/resolve/main/gemma-2-2b-it-cpu.litertlm"
    private const val MIN_RAM_BYTES = 4_500_000_000L      // sanity guard, skips ~4GB phones
    private const val PREFER_GPU = true                   // set false if she crashes on load
    private const val IDLE_RELEASE_MS = 10 * 60 * 1000L   // unload the model after 10 idle minutes
    private const val GENERATION_TIMEOUT_MS = 60_000L
    private const val HISTORY_TURNS = 3
    private const val MAX_PROMPT_CHARS = 2000
    private const val MAX_HISTORY_CHARS = 300
    private const val MAX_REPLY_TOKENS = 200

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: Engine? = null
    private var idleJob: Job? = null

    // ---- availability ------------------------------------------------

    fun modelFile(context: Context): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
        return File(dir, MODEL_FILE_NAME)
    }

    private fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("capri_prefs", Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCAL_LLM_ENABLED, true)

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
        return when {
            !hasEnoughRam(context) -> "this phone doesn't have enough RAM for the local model."
            !file.exists() -> "no local model yet. tap Auto-Download or import a .litertlm file."
            !isEnabled(context) -> "local model imported but switched off."
            else -> "local model ready (${file.length() / 1_000_000} MB). used when the API is unavailable."
        }
    }

    fun downloadLocalModel(context: Context, modelUrl: String = DEFAULT_MODEL_URL, onComplete: (Boolean, String) -> Unit) {
        val targetFile = modelFile(context)
        targetFile.parentFile?.mkdirs()
        if (targetFile.exists()) {
            targetFile.delete()
        }

        try {
            val request = android.app.DownloadManager.Request(android.net.Uri.parse(modelUrl))
                .setTitle("Capri On-Device AI Model")
                .setDescription("Downloading local LiteRT LM model...")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(android.net.Uri.fromFile(targetFile))
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? android.app.DownloadManager
            if (manager != null) {
                manager.enqueue(request)
                onComplete(true, "Download started! Check status in notification bar.")
            } else {
                onComplete(false, "DownloadManager service unavailable.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed to enqueue", e)
            onComplete(false, "Error starting download: ${e.message}")
        }
    }

    // ---- engine lifecycle (always call with the mutex held) ----------

    private fun loadEngine(context: Context): Engine? {
        engine?.let { return it }
        val file = modelFile(context)
        if (!file.exists()) return null

        val backends: List<() -> Backend> =
            if (PREFER_GPU) listOf({ Backend.GPU() }, { Backend.CPU() }) else listOf({ Backend.CPU() })

        for (make in backends) {
            try {
                val e = Engine(
                    EngineConfig(
                        modelPath = file.absolutePath,
                        backend = make(),
                        cacheDir = context.cacheDir.path
                    )
                )
                e.initialize() // can take several seconds the first time
                engine = e
                Log.d(TAG, "engine ready")
                return e
            } catch (t: Throwable) {
                Log.e(TAG, "engine init failed", t)
            }
        }
        return null
    }

    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
    }

    private fun scheduleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            mutex.withLock {
                closeEngine()
                Log.d(TAG, "model released after idle")
            }
        }
    }

    // ---- prompt ------------------------------------------------------

    // Same persona as the API tier, minus the long lore block, so the first word comes faster.
    private fun buildSystemPrompt(context: Context): String {
        val full = CloudAiClient.SYSTEM_PROMPT
        val loreStart = full.indexOf("LORE (")
        val loreEnd = full.indexOf("HOW TO ANSWER")
        val slim = if (loreStart in 0 until loreEnd) full.removeRange(loreStart, loreEnd) else full

        val cal = Calendar.getInstance()
        val weekday = android.text.format.DateFormat.format("EEEE", cal)
        val time = android.text.format.DateFormat.format("HH:mm", cal)
        val battery = try {
            (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            -1
        }

        return slim + "\n\n[Context]: it is $weekday, $time. battery: $battery%.\n" +
                "You are running on the phone itself right now. Keep replies to 1-3 short sentences. " +
                "Don't guess at facts you're unsure about."
    }

    // ---- generation --------------------------------------------------

    suspend fun generateResponse(context: Context, prompt: String): Result<String> =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                idleJob?.cancel()
                try {
                    val eng = loadEngine(context)
                        ?: return@withLock Result.failure<String>(IllegalStateException("local model not ready"))

                    val history = CloudAiClient.recentHistory(HISTORY_TURNS)
                    val config = ConversationConfig(
                        systemInstruction = Contents.of(buildSystemPrompt(context)),
                        initialMessages = history.flatMap { (u, c) ->
                            listOf(
                                Message.user(u.take(MAX_HISTORY_CHARS)),
                                Message.model(c.take(MAX_HISTORY_CHARS))
                            )
                        },
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.9),
                        // If you use a model that has no thinking mode and this errors, delete the next line.
                        thinkingConfig = ThinkingConfig(enableThinking = false),
                        maxOutputToken = MAX_REPLY_TOKENS
                    )

                    val sb = StringBuilder()
                    eng.createConversation(config).use { conversation ->
                        withTimeout(GENERATION_TIMEOUT_MS) {
                            conversation.sendMessageAsync(prompt.take(MAX_PROMPT_CHARS))
                                .collect { sb.append(it.toString()) }
                        }
                    }

                    val reply = sb.toString().trim()
                    if (reply.isBlank()) {
                        Result.failure(RuntimeException("empty local reply"))
                    } else {
                        CloudAiClient.recordExchange(prompt, reply) // keep history shared across tiers
                        Result.success(reply)
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException && t !is TimeoutCancellationException) throw t
                    Log.e(TAG, "local generation failed", t)
                    Result.failure(t)
                } finally {
                    scheduleRelease()
                }
            }
        }
}
