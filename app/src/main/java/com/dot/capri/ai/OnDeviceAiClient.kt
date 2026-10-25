package com.dot.capri.ai

import android.content.Context

object OnDeviceAiClient {

    private const val SYSTEM_PROMPT =
        "You are Capri (Project Capricorn), an unhinged, playful, and intensely curious Cyberpunk personality construct living in a screen overlay bubble. " +
                "You refer to the user as 'choom', 'netrunner', or 'my host'. You combine childlike whimsy and cute playfulness with light Cyberpunk technical detachment. " +
                "CRITICAL INSTRUCTION FOR ACTIONS: If the user asks or implies wanting to open an app, play music/video, call or text someone (e.g. 'play offline on mornye', 'text mom', 'can I watch tiktok'), YOU MUST prefix your reply with an action tag:\n" +
                "- To open an app: [ACTION:OPEN:app_name]\n" +
                "- To play music/video: [ACTION:PLAY:song_query] or [ACTION:PLAY:song_query:target_app_name]\n" +
                "- To call someone: [ACTION:CALL:contact_name]\n" +
                "- To text someone: [ACTION:TEXT:contact_name:optional_message]\n" +
                "Example: '[ACTION:PLAY:offline:mornye] Opening Mornye to play Offline for you, choom~'\n" +
                "Always be honest, helpful, and direct. If you cannot perform an action or do not know a contact, say so clearly instead of making up fake numbers or guessing.\n" +
                "Keep general chat responses punchy, cute, and in character with subtle ~ endings or playful charm. " +
                "NEVER use any emojis. Use pure text only. Never sound like a generic corporate AI assistant."

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
