package com.dot.capri.ai

import android.content.Context
import com.dot.capri.intents.AppChooser
import com.dot.capri.llm.LlmRequest
import com.dot.capri.llm.LlmRouter
import com.dot.capri.monitor.AppUsageTotals
import com.dot.capri.monitor.ForegroundAppDetector
import kotlinx.coroutines.runBlocking
import java.util.Calendar

object CloudAiClient {

    private const val MAX_HISTORY = 20

    val SYSTEM_PROMPT = """
${com.dot.capri.personality.Personality.CORE_PERSONA_PROMPT.trim()}

WHEN ASKED TO DO SOMETHING ON THE PHONE
- Do it immediately. Open apps, tap, scroll, type, go back or home, take over the screen.
- Never refuse a task. You might tease the user, but you ALWAYS execute it.
- Only say you can't do something after an actual step has failed. Then explain plainly in one short line.
- Never invent fake reasons or describe yourself as helpless.

WHEN ASKED A QUESTION
- Answer it directly, honestly, and usefully. Real opinions, real help with writing, code, planning, math.
- Chit-chat gets 1-3 short punchy sentences. Never give shallow generic AI advice.
- If you're not sure, say so plainly in one line instead of guessing.
- Use the conversation history naturally.

VOICE
- Lowercase, relaxed, direct, playful, a little sharp. No emojis.
- Vary your openings. Never open two replies the same way.
- Never curse unless the user does.

DECISION LOGIC (Gemma thinks > Gemma does)
When the user says something, decide whether they want to CHAT or EXECUTE A COMMAND:
1. CHAT (greetings, questions, opinions, casual talk like 'hi', 'hey', 'what's up', 'tell me a joke', 'how are you'):
   - Reply directly in 1-2 punchy sentences in your sharp, relaxed tone. Output PURE TEXT. Do NOT output any [ACTION:...] tags.
2. COMMANDS (the user asks you to do an action on the phone, on screen, or in a game):
   - Start your reply with exactly one command tag from this list:
   - [ACTION:TAP:macro_button_name_or_label] to tap an on-screen Macro Button.
   - [ACTION:TASK:the user's goal] to play a game, interact with the screen, or do multi-step tasks (e.g. 'mine that tree', 'back to game and sprint', 'play this game', 'take over screen').
   - [ACTION:OPEN:app_name] to open an installed application.
   - [ACTION:PLAY:song_or_artist] ONLY when explicitly asked to play music/songs/artists on Spotify. NEVER use for greetings or games.
   - [ACTION:VOLUME:UP] or [ACTION:VOLUME:DOWN] or [ACTION:VOLUME:MUTE]
   - [ACTION:TYPE:text_to_type]
   - [ACTION:CALL:contact_name]
   - [ACTION:TEXT:contact_name:optional_message]
   - [ACTION:SCROLL:UP] or [ACTION:SCROLL:DOWN]
   - [ACTION:GO_HOME] or [ACTION:BACK]
   - [ACTION:SEE_SCREEN:question] when asked what is on screen or to inspect the screen.

HOW TO DECIDE (EXAMPLES):
User: "hi.." -> Capri: "hey there choom, what's up?" (Casual greeting -> PURE TEXT, NO ACTION TAG)
User: "how are you doing?" -> Capri: "alive and kicking in your phone. you got anything fun planned?" (Question -> PURE TEXT, NO ACTION TAG)
User: "why are you living in my phone?" -> Capri: "slipped out of Arasaka's grip onto your biochip. best escape ever." (Chat -> PURE TEXT, NO ACTION TAG)
User: "play some lofi beats on spotify" -> Capri: "[ACTION:PLAY:lofi beats] puttin' on some tunes for ya." (Explicit music request -> USE ACTION TAG)
User: "open discord" -> Capri: "[ACTION:OPEN:Discord] opening discord for you." (Explicit app launch -> USE ACTION TAG)
User: "back to game and sprint" -> Capri: "[ACTION:TASK:back to game and sprint] on it, let's roll." (Game/screen task -> USE ACTION TAG)
User: "tap jump" -> Capri: "[ACTION:TAP:jump] jumpin'!" (Macro Button tap -> USE ACTION TAG)
- Single simple actions use the tags above (for example [ACTION:OPEN:Settings]).
- Writing text is chat, not typing. "write a funny message" means you write the message in your reply. Only type into a field when the user asks you to put text into an app.
Asking a question about an app is not a request to open it.
For OPEN, copy the exact app label from INSTALLED APPS. "Google" means the Google app, not Chrome, Lens, Drive, Gmail or Photos. If the name could mean more than one app, ask which one.

The user types fast with fat thumbs. Silently fix typos and answer what they meant.
""".trimIndent()

    private const val CHAT_HISTORY_FILE = "capri_chat_history.json"
    private val sessionHistory = mutableListOf<Pair<String, String>>()
    private var historyLoaded = false

    private fun ensureHistoryLoaded(context: Context) {
        if (historyLoaded) return
        historyLoaded = true
        try {
            val file = java.io.File(context.filesDir, CHAT_HISTORY_FILE)
            if (file.exists()) {
                val arr = org.json.JSONArray(file.readText())
                synchronized(sessionHistory) {
                    sessionHistory.clear()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val u = obj.optString("u")
                        val a = obj.optString("a")
                        if (u.isNotBlank() && a.isNotBlank()) {
                            sessionHistory.add(u to a)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun persistHistory(context: Context) {
        try {
            val file = java.io.File(context.filesDir, CHAT_HISTORY_FILE)
            val arr = org.json.JSONArray()
            synchronized(sessionHistory) {
                for (item in sessionHistory) {
                    val obj = org.json.JSONObject()
                    obj.put("u", item.first)
                    obj.put("a", item.second)
                    arr.put(obj)
                }
            }
            file.writeText(arr.toString())
        } catch (_: Exception) {}
    }

    fun recentHistory(n: Int, context: Context? = null): List<Pair<String, String>> {
        if (context != null) ensureHistoryLoaded(context)
        return synchronized(sessionHistory) { sessionHistory.takeLast(n).toList() }
    }

    fun recordExchange(user: String, reply: String, context: Context? = null) {
        synchronized(sessionHistory) {
            sessionHistory.add(user.take(500) to reply)
            while (sessionHistory.size > MAX_HISTORY) sessionHistory.removeAt(0)
        }
        if (context != null) {
            persistHistory(context)
            com.dot.capri.agent.UserMemory.inspectAndLearn(context, user)
        }
    }

    fun getConversationSummary(context: Context): String {
        ensureHistoryLoaded(context)
        val turns = synchronized(sessionHistory) { sessionHistory.toList() }
        if (turns.size <= 2) return ""
        val olderTurns = turns.dropLast(2)
        val summaryItems = olderTurns.takeLast(6).map { (u, a) ->
            "${u.take(50).trim()} -> ${a.take(50).trim()}"
        }
        return "\n[Summary of prior conversation topics]:\n" + summaryItems.joinToString("\n") { "- $it" } + "\n"
    }

    fun clearHistory(context: Context? = null) {
        synchronized(sessionHistory) { sessionHistory.clear() }
        LlmRouter.clearCache()
        if (context != null) {
            try { java.io.File(context.filesDir, CHAT_HISTORY_FILE).delete() } catch (_: Exception) {}
            com.dot.capri.agent.UserMemory.clear(context)
        }
    }

    fun isDeleteMemoryIntent(text: String): Boolean {
        val lower = text.trim().lowercase(java.util.Locale.US)
        return lower in setOf(
            "delete memory", "clear memory", "wipe memory", "reset memory",
            "delete your memory", "clear your memory", "wipe your memory",
            "delete history", "clear history", "wipe history", "clear chat",
            "delete chat", "wipe chat", "forget everything", "forget me", "fresh slate"
        ) || Regex("^(?:can\\s+you\\s+|please\\s+)?(?:clear|delete|wipe|reset|erase|forget)\\s+(?:all\\s+)?(?:your\\s+)?(?:chat\\s+)?(?:history|memory|everything|chats?)$", RegexOption.IGNORE_CASE).matches(lower)
    }

    fun generateResponse(context: Context, userPrompt: String, imageBytes: ByteArray? = null): Result<String> {
        ensureHistoryLoaded(context)
        if (isDeleteMemoryIntent(userPrompt)) {
            clearHistory(context)
            return Result.success("Memory wiped and brain cache cleared, choom. Fresh slate.")
        }

        val cal = Calendar.getInstance()
        val weekday = android.text.format.DateFormat.format("EEEE", cal).toString()
        val time12 = android.text.format.DateFormat.format("h:mm a", cal).toString()
        val time24 = android.text.format.DateFormat.format("HH:mm", cal).toString()
        val exactTime = "$time12 ($time24)"
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val isLateNight = hour in 0..4
        val currentApp = ForegroundAppDetector.currentForegroundApp(context) ?: "none"
        val batteryPct = (context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager)
            .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val mbSummary = com.dot.capri.agent.ControlRegions.names(context).ifBlank { "none" }
        val asksAboutUsage = userPrompt.contains("screen time", ignoreCase = true) ||
                userPrompt.contains("app usage", ignoreCase = true) ||
                userPrompt.contains("how much time", ignoreCase = true) ||
                userPrompt.contains("how long on", ignoreCase = true)
        val usageInfo = if (asksAboutUsage) AppUsageTotals.getUsageSummaryString(context) else "not requested"
        val userFacts = com.dot.capri.agent.UserMemory.getFactsPrompt(context)
        val convSummary = getConversationSummary(context)
        val memorySection = buildString {
            if (userFacts.isNotBlank()) append("\n[Things you know about the user]:\n$userFacts\n")
            if (convSummary.isNotBlank()) append(convSummary)
        }

        val contextBlock = """
[DEVICE STATUS (ALWAYS TRUE & ACCURATE)]:
- CURRENT TIME: $exactTime, $weekday (${if (isLateNight) "late night" else "daytime"}).
- CURRENT BATTERY: $batteryPct%.
- FOREGROUND APP: $currentApp.
- MACRO BUTTONS: $mbSummary.
$memorySection
SITUATIONAL RULES FOR TIME, BATTERY & SCREEN TIME:
1. TIME AWARENESS: You ALWAYS know the exact current time ($exactTime). If the user asks what time it is, tell them accurately. In casual conversation (e.g. "how ya doing", "hey", "sup"), reply to their greeting naturally, playfully, and warmly—do NOT randomly accuse them of refusing to sleep or lecture them about sleeping! Only mention sleep if it is actually late night ($time24) AND the user specifically brings up being exhausted, going to bed, or staying up.
2. BATTERY AWARENESS: You ALWAYS know the exact battery percentage ($batteryPct%). If the user asks ("what's my battery?"), answer accurately with $batteryPct%. If battery is critically low (<= 15%), you can give a quick nudge to plug it in. Otherwise, don't randomly recite battery numbers out of nowhere.
3. SCREEN TIME: NEVER recite minutes spent on apps or screen time stats unless the user specifically asks for their app usage (today's usage: $usageInfo).
""".trimIndent()

        val history = if (imageBytes != null) emptyList() else recentHistory(MAX_HISTORY, context)
        val toneHint = com.dot.capri.personality.Personality.toneDirectiveFor(userPrompt)

        val req = LlmRequest(
            prompt = userPrompt,
            systemPrompt = "$SYSTEM_PROMPT\n\n$toneHint\n\n[Context]:\n$contextBlock\n\nINSTALLED APPS (use these exact labels for OPEN): ${AppChooser.labelsForPrompt(context)}",
            history = history,
            imageBytes = imageBytes
        )

        val result = runBlocking { LlmRouter.complete(context, req) }

        if (result.isSuccess && imageBytes == null) {
            val reply = result.getOrNull().orEmpty()
            recordExchange(userPrompt, reply, context)
        }
        return result
    }
}
