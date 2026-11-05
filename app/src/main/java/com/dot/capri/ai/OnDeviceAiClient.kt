package com.dot.capri.ai

import android.app.ActivityManager
import android.content.Context
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
    // Open repo (no login, no gate). Apache 2.0. Multimodal Gemma 4 E2B.
    const val DEFAULT_MODEL_URL = "https://huggingface.co/tibaf/gemma/resolve/main/gemma-4-E2B-it.litertlm"
    const val ALT_MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    private const val EXPECTED_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    private const val MIN_MODEL_BYTES = 5_000_000L
    private const val MIN_RAM_BYTES = 4_500_000_000L      // sanity guard, skips ~4GB phones
    // CPU is enforced as default backend (no GPU/NPU) to avoid OpenCL/driver errors
    private const val IDLE_RELEASE_MS = 30 * 60 * 1000L   // keep the model warm for 30 idle minutes
    private const val GENERATION_TIMEOUT_MS = 60_000L
    private const val HISTORY_TURNS = 2                   // trimmed for speed
    private const val MAX_PROMPT_CHARS = 1500             // trimmed for speed
    private const val MAX_HISTORY_CHARS = 200
    private const val MAX_REPLY_TOKENS = 64               // lightning-fast action JSON replies

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: Engine? = null
    private var engineHasVision = false
    @Volatile private var visionBroken = false
    @Volatile var lastError: String? = null
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
        val base = when {
            !hasEnoughRam(context) -> "this phone doesn't have enough RAM for the local model."
            !file.exists() -> "no local model yet. tap Auto-Download or import a .litertlm file."
            !isEnabled(context) -> "local model imported but switched off."
            else -> "local model ready (${file.length() / 1_000_000} MB). used when the API is unavailable."
        }
        return lastError?.let { "$base\nlast error: $it" } ?: base
    }

    private fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Scales an image to VisionConfig.getMaxSide(context) only if it exceeds the target size.
     * Skips re-encoding if CapriAccessibilityService or PhoneAgent already scaled the frame.
     */
    fun shrinkImage(context: Context, bytes: ByteArray): ByteArray {
        val maxSide = com.dot.capri.agent.VisionConfig.getMaxSide(context)
        val quality = com.dot.capri.agent.VisionConfig.getJpegQuality(context)

        val boundsOpts = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        val origW = boundsOpts.outWidth
        val origH = boundsOpts.outHeight
        if (origW <= 0 || origH <= 0) return bytes

        if (maxOf(origW, origH) <= maxSide) {
            return bytes
        }

        val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        val targetW = (src.width * scale).toInt().coerceAtLeast(1)
        val targetH = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = android.graphics.Bitmap.createScaledBitmap(src, targetW, targetH, true)

        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    fun downloadLocalModel(
        context: Context,
        modelUrl: String = DEFAULT_MODEL_URL,
        onProgress: ((Int) -> Unit)? = null,
        onComplete: (Boolean, String) -> Unit
    ) {
        val targetFile = modelFile(context)
        targetFile.parentFile?.mkdirs()
        // Stored in persistent app internal storage (filesDir), never in temporary cache.
        val tempFile = File(targetFile.parentFile, "capri-local.litertlm.tmp")

        scope.launch(Dispatchers.IO) {
            suspend fun finish(ok: Boolean, msg: String) =
                withContext(Dispatchers.Main) { onComplete(ok, msg) }

            var conn: java.net.HttpURLConnection? = null
            try {
                val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

                // Follow redirects by hand so every hop's status code is visible.
                var url = java.net.URL(modelUrl)
                var response: java.net.HttpURLConnection? = null
                var hops = 0
                while (response == null) {
                    val c = (url.openConnection() as java.net.HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 15_000
                        readTimeout = 60_000
                        setRequestProperty("User-Agent", "Capri-Android-App")
                        if (existingBytes > 0) {
                            setRequestProperty("Range", "bytes=$existingBytes-")
                        }
                    }
                    conn = c
                    when (val code = c.responseCode) {
                        in 300..399 -> {
                            val loc = c.getHeaderField("Location")
                            if (loc == null || ++hops > 6) {
                                finish(false, "Download failed: bad redirect (HTTP $code).")
                                return@launch
                            }
                            url = java.net.URL(url, loc)
                            c.disconnect()
                        }
                        200, 206 -> response = c
                        401, 403 -> {
                            finish(false, "This model needs login (HTTP $code). Download the .litertlm file in your browser, then tap Import local model.")
                            return@launch
                        }
                        404 -> {
                            finish(false, "That model link is gone (HTTP 404). Download the file in your browser, then tap Import local model.")
                            return@launch
                        }
                        429 -> {
                            finish(false, "Hugging Face is rate-limiting downloads (HTTP 429). Try again in a few minutes.")
                            return@launch
                        }
                        else -> {
                            finish(false, "Download failed (HTTP $code).")
                            return@launch
                        }
                    }
                }
                val resp = response
                val isPartial = (resp.responseCode == 206)
                val serverLen = resp.contentLengthLong

                val startBytes = if (isPartial) existingBytes else {
                    tempFile.delete()
                    0L
                }
                val total = if (isPartial && serverLen > 0) startBytes + serverLen else serverLen
                var done = startBytes
                var lastPct = -1

                resp.inputStream.use { input ->
                    java.io.FileOutputStream(tempFile, isPartial).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = ((done * 100) / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    withContext(Dispatchers.Main) { onProgress?.invoke(pct) }
                                }
                            }
                        }
                    }
                }

                // A login or error page is small and starts with '<' or '{'. Reject it.
                if (tempFile.length() < MIN_MODEL_BYTES) {
                    tempFile.delete()
                    finish(false, "Download too small (${tempFile.length() / 1024} KB). The link returned an error page, not a model.")
                    return@launch
                }
                val head = tempFile.inputStream().use { s -> ByteArray(64).also { s.read(it) } }
                    .toString(Charsets.ISO_8859_1).trimStart()
                if (head.startsWith("<") || head.startsWith("{")) {
                    tempFile.delete()
                    finish(false, "Got a web page instead of a model. Download the file in your browser, then tap Import local model.")
                    return@launch
                }

                if (modelUrl == DEFAULT_MODEL_URL && !sha256(tempFile).equals(EXPECTED_SHA256, ignoreCase = true)) {
                    tempFile.delete()
                    finish(false, "The download didn't match its checksum (corrupted or incomplete). Tap Auto-Download again.")
                    return@launch
                }

                if (targetFile.exists() && !targetFile.delete()) {
                    finish(false, "Couldn't replace the old model file.")
                    return@launch
                }
                if (!tempFile.renameTo(targetFile)) {
                    finish(false, "Couldn't save the model file to app storage.")
                    return@launch
                }
                Log.d(TAG, "Local model saved (${targetFile.length()} bytes)")
                finish(true, "Local model downloaded. Turn on 'Use on-device model' and you're set.")
            } catch (e: java.net.UnknownHostException) {
                tempFile.delete()
                finish(false, "No internet connection. Connect and try again.")
            } catch (e: java.net.SocketTimeoutException) {
                tempFile.delete()
                finish(false, "Connection timed out. Try again on a better signal.")
            } catch (e: Exception) {
                Log.e(TAG, "Local model download failed", e)
                tempFile.delete()
                finish(false, "Download failed: ${e.message ?: "network error"}")
            } finally {
                conn?.disconnect()
            }
        }
    }

    // ---- engine lifecycle (always call with the mutex held) ----------

    private fun loadEngine(context: Context, wantVision: Boolean): Engine? {
        engine?.let { if (!wantVision || engineHasVision || visionBroken) return it }
        closeEngine()
        val file = modelFile(context)
        if (!file.exists()) {
            lastError = "model file missing"
            return null
        }
        // Strictly use CPU backend to avoid Adreno OpenCL/GPU native crashes and vision tensor failures
        val visionTries = if (wantVision && !visionBroken) listOf(true, false) else listOf(false)

        for (vision in visionTries) {
            try {
                val cfg = if (vision) {
                    EngineConfig(
                        modelPath = file.absolutePath,
                        backend = Backend.CPU(),
                        visionBackend = Backend.CPU(),
                        cacheDir = context.cacheDir.path
                    )
                } else {
                    EngineConfig(
                        modelPath = file.absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = context.cacheDir.path
                    )
                }
                val e = Engine(cfg)
                e.initialize()
                engine = e
                engineHasVision = vision
                lastError = null
                Log.d(TAG, "engine ready on CPU (vision=$vision)")
                return e
            } catch (t: Throwable) {
                if (vision) visionBroken = true
                lastError = "engine init failed (vision=$vision): ${t.message ?: t.javaClass.simpleName}"
                Log.e(TAG, lastError!!, t)
            }
        }
        return null
    }

    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
        engineHasVision = false
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
                "You are Capri running on-device. You CAN control the phone screen and play games using your autonomous agent loop. " +
                "When asked to play a game or control the screen, reply with: [ACTION:TASK:your goal]. " +
                "Keep chat replies to 1-2 short sentences."
    }

    // ---- generation --------------------------------------------------

    private suspend fun generateOnce(
        context: Context,
        prompt: String,
        imageBytes: ByteArray?,
        systemOverride: String?,
        historyOverride: List<Pair<String, String>>?,
        maxTokensOverride: Int?
    ): String {
        val eng = loadEngine(context, wantVision = imageBytes != null && !visionBroken)
            ?: throw IllegalStateException(lastError ?: "local model not ready")

        val history = historyOverride ?: if (systemOverride == null) CloudAiClient.recentHistory(HISTORY_TURNS) else emptyList()
        // Fix #3: Anytime systemOverride is provided (e.g. PhoneAgent), it is a deterministic task!
        val isTask = systemOverride != null

        val config = ConversationConfig(
            systemInstruction = Contents.of(systemOverride ?: buildSystemPrompt(context)),
            initialMessages = history.flatMap { (u, c) ->
                listOf(
                    Message.user(u.take(if (historyOverride != null) 2000 else MAX_HISTORY_CHARS)),
                    Message.model(c.take(if (historyOverride != null) 2000 else MAX_HISTORY_CHARS))
                )
            },
            samplerConfig = SamplerConfig(
                topK = if (isTask) 1 else 40,
                topP = if (isTask) 1.0 else 0.9,
                temperature = if (isTask) 0.0 else 0.7
            ),
            thinkingConfig = ThinkingConfig(enableThinking = false),
            maxOutputToken = maxTokensOverride ?: (if (isTask) 48 else MAX_REPLY_TOKENS)
        )

        var imageFile: File? = null
        val sb = StringBuilder()
        try {
            eng.createConversation(config).use { conversation ->
                withTimeout(GENERATION_TIMEOUT_MS) {
                    val useImage = imageBytes != null && engineHasVision
                    val maxChars = if (historyOverride != null) 4000 else (if (isTask) 3000 else MAX_PROMPT_CHARS)
                    val flow = if (useImage) {
                        imageFile = File(context.cacheDir, "capri_screen.jpg").apply {
                            writeBytes(shrinkImage(context, imageBytes!!))
                        }
                        conversation.sendMessageAsync(
                            Contents.of(
                                Content.Text(prompt.take(maxChars)),
                                Content.ImageFile(imageFile.absolutePath)
                            )
                        )
                    } else {
                        conversation.sendMessageAsync(prompt.take(maxChars))
                    }
                    try {
                        flow.collect { chunk ->
                            sb.append(chunk.toString())
                            if (isTask) {
                                val cur = sb.toString().trimStart()
                                // Stop immediately once a single JSON object or single-line command is complete
                                val hasCompleteJson = cur.startsWith("{") && cur.contains("}")
                                val hasCompleteLine = !cur.startsWith("{") && !cur.startsWith("`") && cur.contains("\n") && cur.lineSequence().first().trim().length >= 4
                                if (hasCompleteJson || hasCompleteLine) {
                                    throw CancellationException("COMPLETE_AGENT_COMMAND")
                                }
                            }
                        }
                    } catch (ce: CancellationException) {
                        if (ce.message != "COMPLETE_AGENT_COMMAND") throw ce
                    }
                }
            }
        } finally {
            imageFile?.delete()
        }
        return sb.toString().trim()
    }

    suspend fun generateResponse(
        context: Context,
        prompt: String,
        imageBytes: ByteArray? = null,
        systemOverride: String? = null,
        historyOverride: List<Pair<String, String>>? = null,
        maxTokensOverride: Int? = null
    ): Result<String> =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                idleJob?.cancel()
                try {
                    val reply = generateOnce(context, prompt, imageBytes, systemOverride, historyOverride, maxTokensOverride)
                    lastError = null
                    if (reply.isBlank()) {
                        Result.failure(RuntimeException("empty local reply"))
                    } else {
                        if (systemOverride == null && historyOverride == null) {
                            CloudAiClient.recordExchange(prompt, reply)
                        }
                        Result.success(reply)
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException && t !is TimeoutCancellationException) throw t
                    lastError = "generation failed: ${t.message ?: t.javaClass.simpleName}"
                    Log.e(TAG, lastError!!, t)
                    Result.failure(if (t is Exception) t else RuntimeException(t.message))
                } finally {
                    scheduleRelease()
                }
            }
        }
}
