package com.dot.capri.llm

import android.content.Context
import android.util.Log
import com.dot.capri.MainActivity
import com.dot.capri.personality.Personality

object LlmRouter {

    private const val TAG = "CAPRI_LLM_ROUTER"

    const val KEY_ACTIVE_PROVIDER = "active_provider"
    const val KEY_ANTHROPIC_API_KEY = "user_anthropic_api_key"
    const val KEY_OPENROUTER_API_KEY = "user_openrouter_api_key"
    const val KEY_GEMINI_MODEL_OVERRIDE = "gemini_model_override"
    const val KEY_ANTHROPIC_MODEL_OVERRIDE = "anthropic_model_override"
    const val KEY_OPENROUTER_MODEL_OVERRIDE = "openrouter_model_override"

    // Was 10/hour, which is why she went silent after a few messages. Raise or lower freely.
    private const val MAX_CALLS_PER_HOUR = 300
    private const val HOUR_MS = 3_600_000L
    private val lock = Any()
    private var hourlyCallCount = 0
    private var hourStart = System.currentTimeMillis()

    private fun reserveCall(): Boolean = synchronized(lock) {
        val now = System.currentTimeMillis()
        if (now - hourStart >= HOUR_MS) {
            hourlyCallCount = 0
            hourStart = now
        }
        if (hourlyCallCount >= MAX_CALLS_PER_HOUR) {
            false
        } else {
            hourlyCallCount++
            true
        }
    }

    /** Kept so existing callers still compile. There is no response cache any more. */
    fun clearCache() = Unit

    fun hasAnyApiKey(context: Context): Boolean {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val geminiKey = MainActivity.getApiKey(context)
        val anthropicKey = prefs.getString(KEY_ANTHROPIC_API_KEY, "")?.trim() ?: ""
        val openRouterKey = prefs.getString(KEY_OPENROUTER_API_KEY, "")?.trim() ?: ""
        return geminiKey.isNotBlank() || anthropicKey.isNotBlank() || openRouterKey.isNotBlank()
    }

    fun getActiveProviderName(context: Context): String {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val selected = prefs.getString(KEY_ACTIVE_PROVIDER, "auto") ?: "auto"
        val geminiKey = MainActivity.getApiKey(context)
        val anthropicKey = prefs.getString(KEY_ANTHROPIC_API_KEY, "")?.trim() ?: ""
        val openRouterKey = prefs.getString(KEY_OPENROUTER_API_KEY, "")?.trim() ?: ""

        val resolved = when (selected.lowercase()) {
            "anthropic" -> if (anthropicKey.isNotBlank()) "Anthropic" else if (geminiKey.isNotBlank()) "Gemini" else "Local"
            "openrouter" -> if (openRouterKey.isNotBlank()) "OpenRouter" else if (geminiKey.isNotBlank()) "Gemini" else "Local"
            "gemini" -> if (geminiKey.isNotBlank()) "Gemini" else "Local"
            else -> when {
                geminiKey.isNotBlank() -> "Gemini"
                anthropicKey.isNotBlank() -> "Anthropic"
                openRouterKey.isNotBlank() -> "OpenRouter"
                else -> "Local"
            }
        }
        Log.d(TAG, "Selected '$selected' -> resolved provider: $resolved")
        return resolved
    }

    fun getActiveProviderInstance(context: Context): LlmProvider? {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val geminiKey = MainActivity.getApiKey(context)
        val anthropicKey = prefs.getString(KEY_ANTHROPIC_API_KEY, "")?.trim() ?: ""
        val openRouterKey = prefs.getString(KEY_OPENROUTER_API_KEY, "")?.trim() ?: ""

        val geminiModel = prefs.getString(KEY_GEMINI_MODEL_OVERRIDE, "")?.trim()
        val anthropicModel = prefs.getString(KEY_ANTHROPIC_MODEL_OVERRIDE, "")?.trim()
        val openRouterModel = prefs.getString(KEY_OPENROUTER_MODEL_OVERRIDE, "")?.trim()

        return when (getActiveProviderName(context)) {
            "Gemini" -> GeminiProvider(context, geminiKey, geminiModel)
            "Anthropic" -> AnthropicProvider(anthropicKey, anthropicModel)
            "OpenRouter" -> OpenRouterProvider(openRouterKey, openRouterModel)
            else -> null
        }
    }

    suspend fun complete(context: Context, req: LlmRequest): Result<String> {
        if (UsageTracker.isBudgetExhausted(context)) {
            return Result.failure(IllegalStateException("Daily token budget reached"))
        }

        val provider = getActiveProviderInstance(context)
            ?: return Result.success(Personality.noBrainInstalled())

        if (!reserveCall()) {
            Log.w(TAG, "Hourly cap of $MAX_CALLS_PER_HOUR reached")
            return Result.failure(IllegalStateException("Hourly limit reached"))
        }

        Log.d(TAG, "Routing to ${provider.name} (${provider.currentModel})")
        val result = provider.complete(req)
        result.onSuccess { r ->
            UsageTracker.recordCall(context, r.provider, r.model, r.tokensIn, r.tokensOut, r.latencyMs)
        }
        return result.map { it.text }
    }
}
