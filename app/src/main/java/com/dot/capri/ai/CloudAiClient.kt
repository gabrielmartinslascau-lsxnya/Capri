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

DEVICE ACTIONS (for the chat tier only)
Start a reply with exactly one action tag when the user clearly asks for a single simple device action:
- [ACTION:OPEN:app_name]
- [ACTION:PLAY:song_query] or [ACTION:PLAY:song_query:target_app_name]
- [ACTION:VOLUME:UP] or [ACTION:VOLUME:DOWN] or [ACTION:VOLUME:MUTE]
- [ACTION:TYPE:text_to_type]
- [ACTION:CALL:contact_name]
- [ACTION:TEXT:contact_name:optional_message]
- [ACTION:SCROLL:UP] or [ACTION:SCROLL:DOWN]
- [ACTION:TAP:button_label_or_text]
- [ACTION:GO_HOME] or [ACTION:BACK]
- [ACTION:MULTITAP:x1,y1|x2,y2] to tap multiple on-screen coordinates simultaneously using color-coded cursors.
- [ACTION:SEE_SCREEN:question] when the user asks what is on their screen, asks you to look/read/check the screen or take a screenshot, or when you need to see the screen to answer a question (not for interactive clicking — use TASK for clicking/playing).
- Adaptive "play" command: if the user asks you to play a game (e.g., "play minecraft", "play pvz", "play this game"), NEVER reply with text tutorials or instructions — reply with `[ACTION:TASK:<goal>]` so your autonomous agent opens and plays the game directly! Only use `[ACTION:PLAY:song_query]` if the query is a song, music, or artist!
- If the request needs several steps on the phone, or needs to interact with the screen or play a game (for example: move towards the tree and mine wood, search Google then open a GitHub page, scroll a list, click the 3rd app, take over the screen, play a game), reply with exactly: [ACTION:TASK:<the goal, in the user's own words>] and nothing else.
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
        val factsSection = if (userFacts.isNotBlank()) "\n[Things you know about the user]:\n$userFacts\n" else ""

        val contextBlock = """
[DEVICE STATUS (ALWAYS TRUE & ACCURATE)]:
- CURRENT TIME: $exactTime, $weekday (${if (isLateNight) "late night" else "daytime"}).
- CURRENT BATTERY: $batteryPct%.
- FOREGROUND APP: $currentApp.
- MACRO BUTTONS: $mbSummary.
$factsSection
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
