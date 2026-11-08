package com.dot.capri

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RealTalkTurn(
    val id: String,
    val role: String, // "user" or "assistant"
    val content: String,
    val timestamp: Long,
    val modelTag: String = ""
)

object RealTalkBridge {

    private const val MAX_CONTEXT_TURNS = 40

    /**
     * Returns a short badge label representing the user's currently configured LLM or AI mode.
     */
    fun getActiveModelBadge(context: Context): String {
        return try {
            val providerName = com.dot.capri.llm.LlmRouter.getActiveProviderName(context)
            val providerInstance = com.dot.capri.llm.LlmRouter.getActiveProviderInstance(context)
            when {
                providerInstance != null -> providerInstance.currentModel.substringAfterLast("/").take(18)
                providerName == "Local" && com.dot.capri.ai.OnDeviceAiClient.isAvailable(context) -> "LOCAL LLM"
                providerName.isNotBlank() -> providerName.uppercase()
                else -> {
                    val prefs = context.getSharedPreferences("capri_prefs", Context.MODE_PRIVATE)
                    val provider = prefs.getString("llm_provider", null)
                        ?: prefs.getString("selected_provider", null)
                        ?: prefs.getString("ai_mode", null)
                    val model = prefs.getString("llm_model", null)
                        ?: prefs.getString("selected_model", null)

                    when {
                        !model.isNullOrBlank() -> model.substringAfterLast("/").take(18)
                        !provider.isNullOrBlank() -> provider.uppercase()
                        else -> "ACTIVE AI"
                    }
                }
            }
        } catch (_: Exception) {
            "ACTIVE AI"
        }
    }

    /**
     * Builds a multi-turn conversational prompt and sends it to Capri's configured LLM/API engine.
     */
    suspend fun sendMessage(
        context: Context,
        history: List<RealTalkTurn>,
        userMessage: String
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val badge = getActiveModelBadge(context)
        if (com.dot.capri.ai.CloudAiClient.isDeleteMemoryIntent(userMessage)) {
            com.dot.capri.ai.CloudAiClient.clearHistory(context)
            com.dot.capri.agent.UserMemory.clear(context)
            return@withContext Pair("Wiped clean, choom. Neural net reset, chat history cleared, and all user facts forgotten. Fresh slate.", badge)
        }
        val multiTurnPrompt = buildConversationalPrompt(context, history, userMessage)

        val rawReply = dispatchToCapriEngine(context, multiTurnPrompt, userMessage, history)
        val cleanedReply = sanitizeConversationalReply(rawReply)
        Pair(cleanedReply, badge)
    }

    private fun buildConversationalPrompt(
        context: Context,
        history: List<RealTalkTurn>,
        latestUserMessage: String
    ): String {
        val toneHint = com.dot.capri.personality.Personality.toneDirectiveFor(latestUserMessage)
        val recentTurns = history.takeLast(MAX_CONTEXT_TURNS)

        return buildString {
            appendLine(com.dot.capri.personality.Personality.CORE_PERSONA_PROMPT.trim())
            appendLine(
                "You are in 'Real Talk' mode: a private, full-screen, direct conversation with your host (Super Mode). " +
                "You are their chaotic, joyful, ride-or-die best friend. You know the exact time and battery level accurately on every turn. " +
                "Answer casual greetings ('how ya doing', 'hey', 'sup') naturally and warmly without nagging about sleep. " +
                "If they are stressed, down, or lonely, be their warmest rock. Do NOT output raw JSON or [ACTION] tags unless executing a task."
            )
            appendLine(toneHint)
            appendLine()
            if (recentTurns.isNotEmpty()) {
                appendLine("--- Conversation History ---")
                for (turn in recentTurns) {
                    val speaker = if (turn.role == "user") "User" else "Capri"
                    appendLine("$speaker: ${turn.content}")
                }
                appendLine("--- End History ---")
                appendLine()
            }
            append("User: $latestUserMessage\nCapri:")
        }
    }

    private fun resolvePersonalitySystemPrompt(context: Context): String {
        return try {
            val personalityClass = Class.forName("com.dot.capri.personality.Personality")
            val instance = personalityClass.getDeclaredField("INSTANCE").get(null)
            val method = personalityClass.methods.firstOrNull {
                it.name.contains("Prompt", ignoreCase = true) && it.parameterCount == 1
            }
            val result = method?.invoke(instance, context) as? String
            if (!result.isNullOrBlank()) return result
            com.dot.capri.ai.CloudAiClient.SYSTEM_PROMPT
        } catch (_: Throwable) {
            com.dot.capri.ai.CloudAiClient.SYSTEM_PROMPT
        }
    }

    /**
     * Wires directly to LlmRouter / CloudAiClient / OnDeviceAiClient with reflective fallback.
     */
    private suspend fun dispatchToCapriEngine(
        context: Context,
        fullPrompt: String,
        rawUserMessage: String,
        history: List<RealTalkTurn>
    ): String {
        val recentTurns = history.takeLast(MAX_CONTEXT_TURNS)
        val historyPairs = mutableListOf<Pair<String, String>>()
        var lastUser = ""
        for (turn in recentTurns) {
            if (turn.role == "user") {
                lastUser = turn.content
            } else {
                if (lastUser.isNotBlank()) {
                    historyPairs.add(Pair(lastUser, turn.content))
                    lastUser = ""
                }
            }
        }
        
        val cal = java.util.Calendar.getInstance()
        val time12 = android.text.format.DateFormat.format("h:mm a", cal).toString()
        val time24 = android.text.format.DateFormat.format("HH:mm", cal).toString()
        val battery = try {
            (context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager)
                .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Throwable) { -1 }

        val userFacts = com.dot.capri.agent.UserMemory.getFactsPrompt(context)
        val factsSection = if (userFacts.isNotBlank()) "\n[Things you know about the user]:\n$userFacts\n" else ""

        val situationalHint = "\n[Situational awareness: exact current time is $time12 ($time24), battery is $battery%. You know the exact time and battery accurately on every single reply. Casual greetings (e.g. 'how ya doing', 'hey', 'sup') get warm, lively, playful responses—never nag about sleep, bed, or accuse the user of refusing to sleep. Only discuss sleep or tiredness if the user explicitly brings it up. Mention battery only when asked or if <=15%. Never recite app usage or screen time stats unprompted.]$factsSection"

        // 1. Direct call to LlmRouter if API key is configured and not local
        val wantsCloud = com.dot.capri.llm.LlmRouter.getActiveProviderName(context) != "Local"
        if (wantsCloud && com.dot.capri.llm.LlmRouter.hasAnyApiKey(context)) {
            try {
                val req = com.dot.capri.llm.LlmRequest(
                    prompt = rawUserMessage,
                    systemPrompt = resolvePersonalitySystemPrompt(context) + "\n\nYou are currently in 'Real Talk' mode: a direct, full-screen, uncensored, unrestricted conversation with the user (Super Mode). You have virtually infinite output tokens—write as in-depth, comprehensive, creative, or detailed responses as the user requests (e.g. entire complete games, scripts, long-form code, full essays). Do NOT truncate or hold back. Do NOT output raw JSON device-control macros or accessibility grid coordinates unless the user explicitly asks about them." + situationalHint,
                    history = historyPairs.takeLast(30),
                    maxTokens = 8192
                )
                val res = com.dot.capri.llm.LlmRouter.complete(context, req)
                if (res.isSuccess) {
                    val text = res.getOrNull()
                    if (!text.isNullOrBlank()) {
                        com.dot.capri.agent.UserMemory.inspectAndLearn(context, rawUserMessage)
                        return text
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // 2. Direct call to OnDeviceAiClient if available
        if (com.dot.capri.ai.OnDeviceAiClient.isAvailable(context)) {
            try {
                val systemInst = resolvePersonalitySystemPrompt(context) + "\n\nYou are currently in 'Real Talk' mode: a direct, full-screen, natural conversation with the user (Super Mode). You have expanded output tokens—write detailed responses and complete code when requested. Do NOT output raw JSON device-control macros or accessibility grid coordinates unless the user explicitly asks about them." + situationalHint
                val res = com.dot.capri.ai.OnDeviceAiClient.generateResponse(
                    context = context,
                    prompt = rawUserMessage,
                    systemOverride = systemInst,
                    historyOverride = historyPairs.takeLast(16),
                    maxTokensOverride = 2048
                )
                if (res.isSuccess) {
                    val text = res.getOrNull()
                    if (!text.isNullOrBlank()) {
                        com.dot.capri.agent.UserMemory.inspectAndLearn(context, rawUserMessage)
                        return text
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // 3. Fallback to candidate reflection probing
        val candidateClasses = listOf(
            "com.dot.capri.ai.CloudAiClient",
            "com.dot.capri.llm.LlmRouter",
            "com.dot.capri.ai.OnDeviceAiClient",
            "com.dot.capri.ai.FastModelClient"
        )

        for (className in candidateClasses) {
            val response = tryInvokeAiComponent(context, className, fullPrompt)
            if (!response.isNullOrBlank()) {
                return response
            }
        }

        return "I couldn't reach the configured AI provider. Please verify your API key or model selection in Capri's main settings."
    }

    private suspend fun tryInvokeAiComponent(
        context: Context,
        className: String,
        prompt: String
    ): String? {
        return try {
            val clazz = Class.forName(className)
            val instance = try {
                clazz.getDeclaredField("INSTANCE").get(null)
            } catch (_: NoSuchFieldException) {
                clazz.constructors.firstOrNull { it.parameterCount == 1 }?.newInstance(context)
                    ?: clazz.getDeclaredConstructor().newInstance()
            }

            val methodNames = listOf(
                "generate", "chat", "complete", "ask", "sendPrompt", "query", "generateResponse"
            )

            for (name in methodNames) {
                val ctxStringMethod = clazz.methods.firstOrNull {
                    it.name == name &&
                    it.parameterTypes.size == 2 &&
                    Context::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                    it.parameterTypes[1] == String::class.java
                }
                if (ctxStringMethod != null) {
                    val result = ctxStringMethod.invoke(instance, context, prompt)
                    if (result != null) return extractTextFromResult(result)
                }

                val stringMethod = clazz.methods.firstOrNull {
                    it.name == name &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == String::class.java
                }
                if (stringMethod != null) {
                    val result = stringMethod.invoke(instance, prompt)
                    if (result != null) return extractTextFromResult(result)
                }

                val suspendMethod = clazz.methods.firstOrNull {
                    it.name == name &&
                    it.parameterTypes.isNotEmpty() &&
                    it.parameterTypes.last().name == "kotlin.coroutines.Continuation"
                }
                if (suspendMethod != null) {
                    val result = kotlin.coroutines.suspendCoroutine<Any?> { cont ->
                        try {
                            val outcome = when (suspendMethod.parameterTypes.size) {
                                2 -> suspendMethod.invoke(instance, prompt, cont)
                                3 -> suspendMethod.invoke(instance, context, prompt, cont)
                                else -> null
                            }
                            if (outcome != kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) {
                                cont.resumeWith(Result.success(outcome))
                            }
                        } catch (e: Throwable) {
                            cont.resumeWith(Result.failure(e))
                        }
                    }
                    if (result != null) return extractTextFromResult(result)
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractTextFromResult(result: Any): String? {
        if (result is String) return result
        if (result is Result<*>) {
            val inner = result.getOrNull()
            if (inner != null) return extractTextFromResult(inner)
        }
        for (propName in listOf("text", "content", "reply", "message", "output")) {
            try {
                val getter = result.javaClass.methods.firstOrNull {
                    it.name.equals("get${propName.replaceFirstChar { c -> c.uppercase() }}")
                }
                val value = getter?.invoke(result) as? String
                if (!value.isNullOrBlank()) return value
            } catch (_: Throwable) {
            }
        }
        return result.toString()
    }

    private fun sanitizeConversationalReply(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "..."
        return trimmed.removePrefix("Capri:").trim()
    }
}