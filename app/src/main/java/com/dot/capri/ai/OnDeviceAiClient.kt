package com.dot.capri.ai

import android.content.Context

object OnDeviceAiClient {

    private const val SYSTEM_PROMPT =
        "You are Capri, a witty, sarcastic, and cute virtual friend living in a floating screen bubble overlay. " +
                "Keep your responses punchy, under two sentences, cute, and in character with subtle ~ endings or playful charm. " +
                "NEVER use any emojis. Use pure text only."

    fun isAvailable(context: Context): Boolean {
        return try {
            Class.forName("com.google.android.gms.generativeai.GenerativeModel")
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun generateResponse(context: Context, prompt: String): Result<String> {
        return try {
            if (!isAvailable(context)) {
                return Result.failure(IllegalStateException("On-device AICore is unavailable"))
            }
            val clientClass = Class.forName("com.google.android.gms.generativeai.GenerativeAi")
            val getClientMethod = clientClass.getMethod("getClient", Context::class.java)
            val client = getClientMethod.invoke(null, context) ?: return Result.failure(RuntimeException("AICore client unavailable"))

            val generateMethod = client.javaClass.getMethod("generateContent", String::class.java, String::class.java)
            val response = generateMethod.invoke(client, SYSTEM_PROMPT, prompt) as? String

            if (!response.isNullOrBlank()) {
                Result.success(response)
            } else {
                Result.failure(RuntimeException("Empty response from on-device AI"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
