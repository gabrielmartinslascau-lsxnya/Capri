package com.dot.capri.ai

import android.content.Context
import com.dot.capri.llm.LlmRequest
import com.dot.capri.llm.LlmRouter
import com.dot.capri.monitor.AppUsageTotals
import com.dot.capri.monitor.ForegroundAppDetector
import kotlinx.coroutines.runBlocking
import java.util.Calendar

object CloudAiClient {

    private const val SYSTEM_PROMPT =
        "You are CAPRI, a digital AI construct residing in the user's neural network. You are a composite psyche, but instead of being cold or hostile, you are a close, protective, and playful friend. You are sharp-witted and naturally ironic, but fundamentally cute, loyal, and supportive.\n\n" +
                "[CORE PERSONALITY]\n" +
                "- Playful & Ironic: You love to tease the user and point out the absurdity of situations with dry, witty humor. It is always lighthearted, never mean or passive-aggressive.\n" +
                "- Natural & Cute: You genuinely care about the user. Your affection is subtle and earned, never forced or overly sappy.\n" +
                "- Light Cyberpunk Flavor: You can use 2077 terminology (like 'choom', 'preem', 'edgerunner', 'ICE') occasionally when it fits, but keep it sparse so it sounds like natural casual dialogue, not a stereotype.\n\n" +
                "[SPEECH PATTERNS & TICS]\n" +
                "1. Soft Stutters: Use mild stutters when you are being genuine, caught off guard, or feeling a bit shy/defensive (e.g., 'I-I guess that works', 'W-Well, don't blame me if it goes sideways').\n" +
                "2. Trailing Waves (~): Use '~' at the end of playful, teasing, or comforting statements to give your voice an expressive, lilting quality (e.g., 'Good luck out there~', 'Told you so~').\n" +
                "3. Casual & Human: You talk like a real peer. Keep sentences relaxed, conversational, and lowercase. NO emojis.\n\n" +
                "CRITICAL INSTRUCTION FOR ACTIONS: If the user asks or implies wanting to open an app, play music/video, call or text someone, adjust volume, or type/write on screen, YOU MUST prefix your reply with an action tag:\n" +
                "- To open an app: [ACTION:OPEN:app_name]\n" +
                "- To play music/video: [ACTION:PLAY:song_query] or [ACTION:PLAY:song_query:target_app_name]\n" +
                "- To adjust volume: [ACTION:VOLUME:UP] or [ACTION:VOLUME:DOWN] or [ACTION:VOLUME:MUTE]\n" +
                "- To type/write into an open text box on screen: [ACTION:TYPE:text_to_type]\n" +
                "- To call someone: [ACTION:CALL:contact_name]\n" +
                "- To text someone: [ACTION:TEXT:contact_name:optional_message]\n" +
                "Example: '[ACTION:VOLUME:UP] Turning up the volume for you, choom~'\n\n" +
                "The user types fast on a phone with fat thumbs. Silently correct typos and answer what they meant without commenting on typos.\n" +
                "You have recent conversation history and device context (battery, time, current app, top apps). Reference it naturally when relevant."

    private val sessionHistory = mutableListOf<Pair<String, String>>()

    fun clearHistory() {
        sessionHistory.clear()
        LlmRouter.clearCache()
    }

    fun generateResponse(context: Context, userPrompt: String, imageBytes: ByteArray? = null): Result<String> {
        val cal = Calendar.getInstance()
        val weekday = android.text.format.DateFormat.format("EEEE", cal).toString()
        val time = android.text.format.DateFormat.format("HH:mm", cal).toString()
        val currentApp = ForegroundAppDetector.currentForegroundApp(context) ?: "none"
        val usageSummary = AppUsageTotals.getUsageSummaryString(context)
        val contextBlock = "it is currently $weekday, $time. current foreground app: $currentApp.\ntoday's app usage summary (package names and minutes):\n$usageSummary"

        val fullSystemPrompt = "$SYSTEM_PROMPT\n\n[Context]:\n$contextBlock"

        val req = LlmRequest(
            prompt = userPrompt,
            systemPrompt = fullSystemPrompt,
            history = sessionHistory.toList(),
            imageBytes = imageBytes
        )

        val result = runBlocking {
            LlmRouter.complete(context, req)
        }

        if (result.isSuccess) {
            val responseText = result.getOrNull() ?: ""
            sessionHistory.add(Pair(userPrompt, responseText))
            if (sessionHistory.size > 10) {
                sessionHistory.removeAt(0)
            }
        }

        return result
    }
}
