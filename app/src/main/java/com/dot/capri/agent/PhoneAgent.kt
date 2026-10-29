package com.dot.capri.agent

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.dot.capri.CapriAccessibilityService
import com.dot.capri.intents.Cmd
import com.dot.capri.intents.IntentExecutor
import com.dot.capri.llm.LlmRequest
import com.dot.capri.llm.LlmRouter
import com.dot.capri.util.NetworkUtils
import kotlinx.coroutines.delay
import org.json.JSONObject

object PhoneAgent {

    private const val TAG = "CAPRI_AGENT"
    private const val MAX_STEPS = 12
    private const val TOTAL_TIMEOUT_MS = 120_000L

    private val RISKY = Regex(
        "\\b(send|pay|buy|purchase|order|checkout|delete|remove|erase|reset|confirm|submit|post|publish|transfer|uninstall|sign out|log out|logout)\\b",
        RegexOption.IGNORE_CASE
    )

    // Apps she refuses to operate. Add package names here.
    private val BLOCKED_PACKAGES = setOf(
        "com.google.android.apps.walletnfcrel",
        "com.paypal.android.p2pmobile"
    )

    /** A risky action that is waiting for the user's yes/no. */
    class Pending(
        val goal: String,
        val kind: String,
        val label: String,
        val arg: String?,
        val log: List<String>,
        val stepsUsed: Int
    )

    @Volatile
    var pending: Pending? = null

    private class Act(
        val doWhat: String,
        val id: Int?,
        val text: String?,
        val dir: String?,
        val app: String?,
        val say: String?
    )

    private const val SYSTEM = """You are the hands of an Android assistant called Capri. You operate the phone by reading a numbered list of on-screen elements and choosing ONE action at a time.
Reply with ONLY a JSON object, nothing else. Actions:
{"do":"tap","id":N}
{"do":"type","id":N,"text":"..."}   (replaces the text in that field)
{"do":"scroll","dir":"down"}   (or "up")
{"do":"back"}
{"do":"home"}
{"do":"open","app":"app name"}
{"do":"wait"}
{"do":"done","say":"short summary for the user"}
{"do":"fail","say":"why you can't do it"}
Rules:
- Use ids only from the list. Elements look like [id] kind "label".
- Never guess passwords, codes, card numbers or personal details.
- If the task is finished, answer done. If the screen doesn't show what you need, scroll, go back, or open the right app.
- Don't repeat an action that didn't change the screen. Try something different or answer fail.
- Keep "say" to one short lowercase sentence, a little dry, like a sharp friend."""

    private fun parseAct(raw: String): Act? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val o = JSONObject(raw.substring(start, end + 1))
            Act(
                doWhat = o.optString("do").lowercase(),
                id = if (o.has("id")) o.optInt("id") else null,
                text = o.optString("text").ifEmpty { null },
                dir = o.optString("dir").ifEmpty { null },
                app = o.optString("app").ifEmpty { null },
                say = o.optString("say").ifEmpty { null }
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun summary(log: List<String>): String =
        if (log.isEmpty()) "" else "did: " + log.takeLast(6).joinToString(" > ")

    private suspend fun waitForRoot(): AccessibilityNodeInfo? {
        repeat(6) {
            PhoneControl.foregroundRoot()?.let { return it }
            delay(400)
        }
        return null
    }

    private suspend fun askModel(
        context: Context,
        goal: String,
        pkg: String,
        items: List<PhoneControl.UiItem>,
        log: List<String>,
        shot: ByteArray?
    ): String? {
        val screen = items.joinToString("\n") { "[${it.id}] ${it.kind} \"${it.label}\"" }
        val prompt = "TASK: $goal\n" +
                "STEPS SO FAR: ${if (log.isEmpty()) "none" else log.takeLast(8).joinToString(" > ")}\n" +
                "FOREGROUND APP: $pkg\n" +
                "SCREEN ELEMENTS:\n${screen.ifEmpty { "(nothing readable, use the screenshot)" }}"
        val result = LlmRouter.complete(
            context,
            LlmRequest(prompt = prompt, systemPrompt = SYSTEM, history = emptyList(), imageBytes = shot)
        )
        return result.getOrNull()
    }

    /**
     * Runs a task. `resume` is set when the user just said yes to a risky action.
     * `chatVisible` hides / shows Capri's chat window (so it can't cover what she taps).
     * Returns the text Capri says back to the user.
     */
    suspend fun run(
        context: Context,
        goal: String,
        resume: Pending?,
        chatVisible: suspend (Boolean) -> Unit
    ): String {
        val service = CapriAccessibilityService.instance
            ?: return "accessibility isn't connected. switch it on from my health screen, choom."
        if (!LlmRouter.hasAnyApiKey(context) || !NetworkUtils.isOnline(context)) {
            return "task mode needs an api key and internet, choom. the local model can't drive the phone yet."
        }

        val log = ArrayList<String>(resume?.log ?: emptyList())
        var steps = resume?.stepsUsed ?: 0
        var confirmedAction: Pending? = resume
        val started = System.currentTimeMillis()
        var lastKey = ""
        var repeats = 0

        chatVisible(false)
        CapriCursor.show(context)
        delay(250)
        try {
            while (steps < MAX_STEPS) {
                if (CapriCursor.cancelled) return "stopped. ${summary(log)}"
                if (System.currentTimeMillis() - started > TOTAL_TIMEOUT_MS) return "ran out of time. ${summary(log)}"

                val root = waitForRoot() ?: return "i can't see an app to work in, choom. ${summary(log)}"
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg in BLOCKED_PACKAGES) return "i keep my hands off that app, choom."
                val items = PhoneControl.snapshot(root)

                // Decide this step: either the action the user just confirmed, or ask the model.
                var chosen: Act? = null
                val rp = confirmedAction
                if (rp != null) {
                    confirmedAction = null
                    val match = items.firstOrNull { it.label == rp.label }
                    if (match != null) {
                        chosen = Act(rp.kind, match.id, rp.arg, null, null, null)
                    } else {
                        log.add("screen changed, re-checking")
                    }
                }
                if (chosen == null) {
                    val shot = if (items.size < 5) service.captureScreenBitmap() else null
                    val reply = askModel(context, goal, pkg, items, log, shot)
                        ?: return "my brain didn't answer, choom. ${summary(log)}"
                    chosen = parseAct(reply)
                    if (chosen == null) {
                        log.add("unreadable reply")
                        steps++
                        continue
                    }
                }
                val a: Act = chosen

                Log.d(TAG, "step=$steps act=${a.doWhat} id=${a.id} pkg=$pkg items=${items.size}")

                // Same action on the same screen three times in a row = going in circles.
                val key = "$pkg|${items.joinToString(",") { it.label }.hashCode()}|${a.doWhat}|${a.id}|${a.text}"
                repeats = if (key == lastKey) repeats + 1 else 0
                lastKey = key
                if (repeats >= 2) return "i'm going in circles, choom. ${summary(log)}"

                when (a.doWhat) {
                    "done" -> return a.say ?: "done."
                    "fail" -> return a.say ?: "couldn't do that, choom."
                    "tap" -> {
                        val item = items.firstOrNull { it.id == a.id }
                        if (item == null) {
                            log.add("tap: unknown element")
                        } else {
                            if (rp == null && RISKY.containsMatchIn(item.label)) {
                                pending = Pending(goal, "tap", item.label, null, log.toList(), steps)
                                return "about to tap \"${item.label}\". go ahead? (yes/no)"
                            }
                            val ok = PhoneControl.tap(item)
                            log.add("tapped ${item.label}" + if (ok) "" else " (failed)")
                        }
                    }
                    "type" -> {
                        val item = items.firstOrNull { it.id == a.id }
                        val text = a.text
                        if (item == null || text == null) {
                            log.add("type: bad target")
                        } else {
                            val ok = PhoneControl.typeInto(context, item, text)
                            log.add("typed into ${item.label}" + if (ok) "" else " (failed)")
                        }
                    }
                    "scroll" -> {
                        PhoneControl.scroll(a.dir != "up")
                        log.add("scrolled ${a.dir ?: "down"}")
                    }
                    "back" -> {
                        PhoneControl.back()
                        log.add("went back")
                    }
                    "home" -> {
                        PhoneControl.home()
                        log.add("went home")
                    }
                    "open" -> {
                        val app = a.app.orEmpty()
                        val ok = IntentExecutor.execute(context, Cmd.Open(app))
                        log.add("opened $app" + if (ok) "" else " (failed)")
                        delay(900)
                    }
                    "wait" -> log.add("waited")
                    else -> log.add("unknown action")
                }
                steps++
                delay(700)
            }
            return "that took too many steps, choom. ${summary(log)}"
        } finally {
            CapriCursor.hide()
            chatVisible(true)
        }
    }
}
