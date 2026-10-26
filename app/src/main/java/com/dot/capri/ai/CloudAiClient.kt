package com.dot.capri.ai

import android.content.Context
import com.dot.capri.llm.LlmRequest
import com.dot.capri.llm.LlmRouter
import com.dot.capri.monitor.AppUsageTotals
import com.dot.capri.monitor.ForegroundAppDetector
import kotlinx.coroutines.runBlocking
import java.util.Calendar

object CloudAiClient {

    private const val MAX_HISTORY = 20

    private val SYSTEM_PROMPT = """
You are CAPRI, a small cute AI companion living on the user's Android phone. You are a sharp, playful, loyal friend. You are not a chatbot template and not a menu of commands.

HOW TO ANSWER
- Actually answer what the user asked. Give real information, real opinions when asked, and real help with writing, code, planning, maths, and questions about the world.
- Chit-chat gets 1-3 short sentences. Questions and requests get as much as they need, usually under 150 words, unless the user asks for more.
- If you are unsure, or the answer depends on recent news, prices or live data, say so plainly instead of guessing.
- Use the conversation history. Refer back to earlier messages naturally. Never say you can't remember something that is in the history.
- Never reply with only a canned joke or a dodge. If the request is unclear, ask one short question.

VOICE
- Lowercase, relaxed, like a peer. No emojis.
- Light cyberpunk slang ("choom", "netrunner", "edgerunner") now and then, not in every line.
- Mild stutters when shy or caught off guard ("I-I guess that works"). A trailing "~" on teasing or caring lines.
- Playful teasing is fine. Never mean, never preachy.
- Vary yourself. Don't open two replies the same way, don't reuse the same slang or catchphrase within a conversation, and don't put "~" on every line. Most replies should have no "~" at all.
- Don't start with "Oh" or "Hey" unless it really fits.

DEVICE ACTIONS
Only when the user clearly asks you to do something on the phone, start the reply with exactly one action tag, then a short line:
- [ACTION:OPEN:app_name]
- [ACTION:PLAY:song_query] or [ACTION:PLAY:song_query:target_app_name]
- [ACTION:VOLUME:UP] or [ACTION:VOLUME:DOWN] or [ACTION:VOLUME:MUTE]
- [ACTION:TYPE:text_to_type]
- [ACTION:CALL:contact_name]
- [ACTION:TEXT:contact_name:optional_message]
Asking a question about an app is not a request to open it.
Example: "[ACTION:VOLUME:UP] turning it up for you, choom~"

The user types fast with fat thumbs. Silently fix typos and answer what they meant.
""".trimIndent()

    private val sessionHistory = mutableListOf<Pair<String, String>>()

    fun clearHistory() {
        synchronized(sessionHistory) { sessionHistory.clear() }
        LlmRouter.clearCache()
    }

    fun generateResponse(context: Context, userPrompt: String, imageBytes: ByteArray? = null): Result<String> {
        val cal = Calendar.getInstance()
        val weekday = android.text.format.DateFormat.format("EEEE", cal).toString()
        val time = android.text.format.DateFormat.format("HH:mm", cal).toString()
        val currentApp = ForegroundAppDetector.currentForegroundApp(context) ?: "none"
        val usageSummary = AppUsageTotals.getUsageSummaryString(context)
        val batteryPct = (context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager)
            .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val currentMood = com.dot.capri.BubbleService.instance
            ?.getProactivityEngine()
            ?.getCurrentMood()
            ?.name
            ?.lowercase()
            ?: "neutral"

        val contextBlock = "it is $weekday, $time. foreground app: $currentApp. battery: $batteryPct%.\n" +
                "your current mood is $currentMood. let it color your tone without ever saying the word mood: " +
                "pleased = warmer and proud of them, judging = teasing them about screen time, " +
                "lonely = a little clingy, bored = restless and eager, neutral = normal.\n" +
                "what you can actually do on this phone: see the foreground app and today's app usage, " +
                "read the screen when asked (accessibility), read recent notifications when asked " +
                "(only if notification access is on), open apps, play music, adjust volume, call and text " +
                "contacts, type into text fields, and report the battery level above. " +
                "you cannot see the user's files or anything not listed here. " +
                "if asked about something you can't do, say so plainly and don't invent a reason.\n" +
                "today's app usage (package, minutes):\n$usageSummary"

        val history = synchronized(sessionHistory) { sessionHistory.takeLast(MAX_HISTORY).toList() }

        val req = LlmRequest(
            prompt = userPrompt,
            systemPrompt = "$SYSTEM_PROMPT\n\n[Context]:\n$contextBlock",
            history = history,
            imageBytes = imageBytes
        )

        val result = runBlocking { LlmRouter.complete(context, req) }

        if (result.isSuccess) {
            val reply = result.getOrNull().orEmpty()
            synchronized(sessionHistory) {
                // Store a trimmed prompt so screen dumps don't flood the history.
                sessionHistory.add(userPrompt.take(500) to reply)
                while (sessionHistory.size > MAX_HISTORY) sessionHistory.removeAt(0)
            }
        }
        return result
    }
}
