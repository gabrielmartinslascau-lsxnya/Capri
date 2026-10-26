package com.dot.capri.llm

import android.content.Context
import android.util.Log
import com.dot.capri.MainActivity
import com.dot.capri.personality.Personality
import java.util.concurrent.ConcurrentHashMap

object LlmRouter {

    private const val TAG = "CAPRI_LLM_ROUTER"

    const val KEY_ACTIVE_PROVIDER = "active_provider"
    const val KEY_ANTHROPIC_API_KEY = "user_anthropic_api_key"
    const val KEY_OPENROUTER_API_KEY = "user_openrouter_api_key"
    const val KEY_GEMINI_MODEL_OVERRIDE = "gemini_model_override"
    const val KEY_ANTHROPIC_MODEL_OVERRIDE = "anthropic_model_override"
    const val KEY_OPENROUTER_MODEL_OVERRIDE = "openrouter_model_override"

    private val responseCache = ConcurrentHashMap<String, String>()
    private var hourlyCallCount = 0
    private var currentHourStart = System.currentTimeMillis()

    @Synchronized
    private fun checkHourlyCap() {
        val now = System.currentTimeMillis()
        if (now - currentHourStart >= 3600_000L) {
            hourlyCallCount = 0
            currentHourStart = now
        }
    }

    fun clearCache() {
        responseCache.clear()
    }

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
            else -> {
                if (geminiKey.isNotBlank()) "Gemini"
                else if (anthropicKey.isNotBlank()) "Anthropic"
                else if (openRouterKey.isNotBlank()) "OpenRouter"
                else "Local"
            }
        }

        Log.d(TAG, "Key Status check -> Selected preference: '$selected', Gemini key present: ${geminiKey.isNotBlank()}, Anthropic key present: ${anthropicKey.isNotBlank()}, OpenRouter key present: ${openRouterKey.isNotBlank()} => Resolved Provider: $resolved")
        return resolved
    }

    fun getActiveProviderInstance(context: Context): LlmProvider? {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val providerName = getActiveProviderName(context)

        val geminiKey = MainActivity.getApiKey(context)
        val anthropicKey = prefs.getString(KEY_ANTHROPIC_API_KEY, "")?.trim() ?: ""
        val openRouterKey = prefs.getString(KEY_OPENROUTER_API_KEY, "")?.trim() ?: ""

        val geminiModel = prefs.getString(KEY_GEMINI_MODEL_OVERRIDE, "")?.trim()
        val anthropicModel = prefs.getString(KEY_ANTHROPIC_MODEL_OVERRIDE, "")?.trim()
        val openRouterModel = prefs.getString(KEY_OPENROUTER_MODEL_OVERRIDE, "")?.trim()

        return when (providerName) {
            "Gemini" -> GeminiProvider(context, geminiKey, geminiModel)
            "Anthropic" -> AnthropicProvider(anthropicKey, anthropicModel)
            "OpenRouter" -> OpenRouterProvider(openRouterKey, openRouterModel)
            else -> null
        }
    }

    suspend fun complete(context: Context, req: LlmRequest): Result<String> {
        if (UsageTracker.isBudgetExhausted(context)) {
            Log.w(TAG, "Daily token budget exhausted")
            return Result.success(Personality.budgetExhausted())
        }

        val cacheKey = if (req.imageBytes == null) req.prompt.lowercase().trim() else req.prompt.lowercase().trim() + "_img"
        if (req.imageBytes == null) {
            responseCache[cacheKey]?.let { cached ->
                Log.d(TAG, "Cache HIT for '$cacheKey'")
                return Result.success(cached)
            }
        }

        checkHourlyCap()
        if (hourlyCallCount >= 10) {
            Log.w(TAG, "Hourly limit reached ($hourlyCallCount/10). Skipping API call.")
            return Result.failure(IllegalStateException("Hourly limit reached"))
        }

        val provider = getActiveProviderInstance(context)
        if (provider == null) {
            Log.d(TAG, "No LLM provider key configured -> local mode")
            return Result.success(Personality.noBrainInstalled())
        }

        hourlyCallCount++
        Log.d(TAG, "Routing request to ${provider.name} (model: ${provider.currentModel})")

        val result = provider.complete(req)
        return if (result.isSuccess) {
            val response = result.getOrThrow()
            UsageTracker.recordCall(
                context = context,
                provider = response.provider,
                model = response.model,
                tokensIn = response.tokensIn,
                tokensOut = response.tokensOut,
                latencyMs = response.latencyMs
            )
            if (req.imageBytes == null) {
                responseCache[cacheKey] = response.text
            }
            Result.success(response.text)
        } else {
            val err = result.exceptionOrNull()
            Log.e(TAG, "Provider ${provider.name} failed", err)
            Result.failure(err ?: RuntimeException("Provider call failed"))
        }
    }
}
