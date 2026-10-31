package com.dot.capri.agent

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.dot.capri.CapriAccessibilityService
import com.dot.capri.ai.OnDeviceAiClient
import com.dot.capri.intents.Cmd
import com.dot.capri.intents.IntentExecutor
import com.dot.capri.llm.LlmRequest
import com.dot.capri.llm.LlmRouter
import com.dot.capri.util.NetworkUtils
import kotlinx.coroutines.delay
import org.json.JSONObject

object PhoneAgent {

    private const val TAG = "CAPRI_AGENT"
    private const val MAX_STEPS = 15
    private const val TOTAL_TIMEOUT_MS = 180_000L

    private val RISKY = Regex(
        "\\b(send|pay|buy|purchase|order|checkout|delete|remove|erase|reset|confirm|submit|post|publish|transfer|uninstall|sign out|log out|logout)\\b",
        RegexOption.IGNORE_CASE
    )

    private val BLOCKED_PACKAGES = setOf(
        "com.google.android.apps.walletnfcrel",
        "com.paypal.android.p2pmobile"
    )

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
        val say: String?,
        val x: Int?,
        val y: Int?,
        val x2: Int?,
        val y2: Int?,
        val label: String? = null,
        val plan: String? = null
    )

    private val SYSTEM = """
You are the hands of an Android assistant called Capri. You control the phone by reading the numbered element list AND the screenshot.
The screenshot has a light grid. Columns are A to F, left to right. Rows are 1 to 10, top to bottom. Each cell label is in the cell's top-left corner.
Reply with ONLY a JSON object. Actions:
{"do":"tap","id":N}                          tap element N from the list (preferred)
{"do":"tap","cell":"C4"}                     tap the centre of a grid cell
{"do":"tap","x":X,"y":Y}                     tap an exact pixel (rarely needed)
{"do":"long_press","id":N}                   press and hold element N
{"do":"long_press","cell":"C4"}              press and hold a grid cell
{"do":"swipe","cell":"B5","cell2":"B2"}      drag from cell B5 to cell B2 (pixels also work: x,y,x2,y2)
{"do":"type","id":N,"text":"..."}            type into field N and press Enter
{"do":"submit","id":N}                       press Enter on field N
{"do":"scroll","dir":"down"}                 show MORE content below. dir is down, up, left or right. "right" shows content further right.
{"do":"tap_nth","n":3}    tap the Nth labelled item on screen, counted top to bottom, left to right. Use this for "the 3rd app", "the first icon", etc. Never count items yourself.
{"do":"back"}
{"do":"home"}
{"do":"open","app":"app name"}
{"do":"wait"}
{"do":"done","say":"short summary"}
{"do":"fail","say":"why you can't do it"}
Rules:
- Every "tap" and "type" action must include "label": the exact label of the element you are acting on, copied from SCREEN ELEMENTS.
- On your first action of a task, include "plan": one short sentence describing how you will do it.
- Choose only an element that matches the goal. Never press things to explore the screen. If nothing matches, answer fail.
- An id is only valid with its matching label. Do not guess ids.
- Pick an element from the list by its label or id when one matches. Use a grid cell only when nothing in the list matches.
- Never choose by colour. Choose by the label, the id, or what the element does.
- If the screen doesn't show what you need, scroll down, go back, or open the right app.
- Don't repeat an action when the screen did not change.
- Never guess passwords, codes, card numbers or personal details.
- Keep "say" to one short lowercase sentence.
""".trimIndent()

    private fun cellToXY(cell: String, w: Int, h: Int): Pair<Int, Int>? {
        val c = cell.trim().uppercase()
        val col = c.firstOrNull()?.let { it - 'A' } ?: return null
        val row = c.drop(1).toIntOrNull()?.minus(1) ?: return null
        if (col !in 0..5 || row !in 0..9) return null
        return Pair(((col + 0.5f) * w / 6f).toInt(), ((row + 0.5f) * h / 10f).toInt())
    }

    private fun annotate(bytes: ByteArray?): ByteArray? {
        if (bytes == null) return null
        val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val bmp = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(bmp)
        val line = android.graphics.Paint().apply { color = 0x88FF2D6B.toInt(); strokeWidth = 2f }
        val label = android.graphics.Paint().apply {
            color = 0xFFFFFFFF.toInt()
            textSize = bmp.height / 45f
            isAntiAlias = true
            setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
        }
        for (col in 0..6) {
            val x = bmp.width * col / 6f
            canvas.drawLine(x, 0f, x, bmp.height.toFloat(), line)
        }
        for (row in 0..10) {
            val y = bmp.height * row / 10f
            canvas.drawLine(0f, y, bmp.width.toFloat(), y, line)
        }
        for (col in 0..5) {
            for (row in 0..9) {
                val x = bmp.width * col / 6f + 6f
                val y = bmp.height * row / 10f + label.textSize + 4f
                canvas.drawText("${'A' + col}${row + 1}", x, y, label)
            }
        }
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    private fun parseAct(raw: String, w: Int, h: Int): Act? {
        val cleaned = raw
            .replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val o = JSONObject(cleaned.substring(start, end + 1))
            val cell1 = o.optString("cell").ifEmpty { null }?.let { cellToXY(it, w, h) }
            val cell2 = o.optString("cell2").ifEmpty { null }?.let { cellToXY(it, w, h) }
            val x = if (o.has("x")) o.optInt("x") else cell1?.first
            val y = if (o.has("y")) o.optInt("y") else cell1?.second
            val x2 = if (o.has("x2")) o.optInt("x2") else cell2?.first
            val y2 = if (o.has("y2")) o.optInt("y2") else cell2?.second
            Act(
                doWhat = o.optString("do").lowercase(),
                id = if (o.has("id")) o.optInt("id") else if (o.has("n")) o.optInt("n") else null,
                text = o.optString("text").ifEmpty { null },
                dir = o.optString("dir").ifEmpty { null },
                app = o.optString("app").ifEmpty { null },
                say = o.optString("say").ifEmpty { null },
                x = x?.coerceIn(0, w - 1),
                y = y?.coerceIn(0, h - 1),
                x2 = x2?.coerceIn(0, w - 1),
                y2 = y2?.coerceIn(0, h - 1),
                label = o.optString("label").ifEmpty { null },
                plan = o.optString("plan").ifEmpty { null }
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Loose match: either label contains the other, ignoring case. */
    private fun labelMatches(actual: String, claimed: String): Boolean {
        val x = actual.lowercase().trim()
        val c = claimed.lowercase().trim()
        if (x.isEmpty() || c.isEmpty()) return false
        return x.contains(c) || c.contains(x)
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

    private fun useApi(context: Context): Boolean =
        LlmRouter.hasAnyApiKey(context) && NetworkUtils.isOnline(context)

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
                "SCREEN SIZE: ${PhoneControl.screenSize()?.let { "${it.first}x${it.second}" } ?: "unknown"}\n" +
                "SCREENSHOT: ${if (shot != null) "attached, grid shown" else "none, use element ids"}\n" +
                "STEPS SO FAR: ${if (log.isEmpty()) "none" else log.takeLast(8).joinToString(" > ")}\n" +
                "FOREGROUND APP: $pkg\n" +
                "SCREEN ELEMENTS:\n${screen.ifEmpty { "(nothing readable, use the screenshot)" }}"

        val t0 = System.currentTimeMillis()
        val reply = if (useApi(context)) {
            LlmRouter.complete(
                context,
                LlmRequest(prompt = prompt, systemPrompt = SYSTEM, history = emptyList(), imageBytes = shot)
            ).getOrNull()
        } else {
            OnDeviceAiClient.generateResponse(context, prompt, shot, systemOverride = SYSTEM).getOrNull()
        }
        Log.d(TAG, "model call ${System.currentTimeMillis() - t0} ms, image=${shot != null}, elements=${items.size}")
        return reply
    }

    suspend fun run(
        context: Context,
        goal: String,
        resume: Pending?,
        chatVisible: suspend (Boolean) -> Unit
    ): String {
        val service = CapriAccessibilityService.instance
            ?: return "accessibility isn't connected. switch it on from my health screen, choom."
        if (!useApi(context) && !OnDeviceAiClient.isAvailable(context)) {
            return "task mode needs an api key + internet, or the on-device model downloaded, choom."
        }

        val log = ArrayList<String>(resume?.log ?: emptyList())
        var steps = resume?.stepsUsed ?: 0
        var confirmedAction: Pending? = resume
        val started = System.currentTimeMillis()
        var lastKey = ""
        var repeats = 0
        val (screenW, screenH) = PhoneControl.screenSize() ?: (1080 to 2400)

        chatVisible(false)
        CapriCursor.show(context)
        delay(250)
        var failStreak = 0
        var plan: String? = null
        val maxSteps = if (useApi(context)) 12 else 6   // the on-device model gets fewer steps
        try {
            while (steps < maxSteps) {
                if (CapriCursor.cancelled) return "stopped. ${summary(log)}"
                if (System.currentTimeMillis() - started > TOTAL_TIMEOUT_MS) return "ran out of time. ${summary(log)}"

                val root = waitForRoot() ?: return "i can't see an app to work in, choom. ${summary(log)}"
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg in BLOCKED_PACKAGES) return "i keep my hands off that app, choom."
                val items = PhoneControl.snapshot(root)

                var chosen: Act? = null
                val rp = confirmedAction
                if (rp != null) {
                    confirmedAction = null
                    val match = items.firstOrNull { it.label == rp.label }
                    if (match != null) {
                        chosen = Act(rp.kind, match.id, rp.arg, null, null, null, null, null, null, null)
                    } else {
                        log.add("screen changed, re-checking")
                    }
                }
                if (chosen == null) {
                    CapriCursor.setStatus("observing...")
                    // Send the screenshot only when the element list is too thin to act on.
                    // Images are the slowest part of a step on a phone.
                    val shot = if (items.size < 5) annotate(service.captureScreenBitmap()) else null
                    var reply = askModel(context, goal, pkg, items, log, shot)
                        ?: return "my brain didn't answer, choom. ${summary(log)}"
                    chosen = parseAct(reply, screenW, screenH)
                    // Retry only on the API tier. A second on-device call doubles the wait.
                    if (chosen == null && useApi(context)) {
                        Log.w(TAG, "unreadable reply, retrying: ${reply.take(120)}")
                        reply = askModel(context, goal, pkg, items, log, shot)
                            ?: return "my brain didn't answer, choom. ${summary(log)}"
                        chosen = parseAct(reply, screenW, screenH)
                    }
                    if (chosen == null) {
                        log.add("unreadable reply")
                        steps++
                        continue
                    }
                }
                val a: Act = chosen

                a.plan?.let {
                    if (plan == null) {
                        plan = it
                        log.add("plan: $it")
                    }
                }

                // The model must name the element it's acting on. A tap whose label doesn't
                // match the id it picked is a guess, so it's refused.
                val targetItem = a.id?.let { id -> items.firstOrNull { it.id == id } }
                val claimed = a.label
                if (a.doWhat == "tap" && targetItem != null && claimed != null &&
                    !labelMatches(targetItem.label, claimed)
                ) {
                    failStreak++
                    log.add("label mismatch: wanted '$claimed', id ${a.id} is '${targetItem.label}'")
                    if (failStreak >= 2) return "i kept grabbing the wrong thing, choom. ${summary(log)}"
                    steps++
                    continue
                }

                // A tap on an element that isn't on screen is a failed step, not a retry.
                val missing = a.doWhat == "tap" && a.id != null && a.x == null && items.none { it.id == a.id }
                if (missing) {
                    failStreak++
                    log.add("no element ${a.id} on screen")
                    if (failStreak >= 2) return "couldn't find that on screen, choom. ${summary(log)}"
                    steps++
                    continue
                }
                failStreak = 0

                Log.d(TAG, "step=$steps act=${a.doWhat} id=${a.id} x=${a.x} y=${a.y} pkg=$pkg items=${items.size}")

                val key = "$pkg|${items.joinToString(",") { it.label }.hashCode()}|${a.doWhat}|${a.id}|${a.x}|${a.y}|${a.text}"
                repeats = if (key == lastKey) repeats + 1 else 0
                lastKey = key
                if (repeats >= 4) return "i'm going in circles, choom. ${summary(log)}"

                when (a.doWhat) {
                    "done" -> return a.say ?: "done."
                    "fail" -> return a.say ?: "couldn't do that, choom."
                    "spawn_cursor" -> {
                        val cid = a.id ?: 1
                        val cx = a.x ?: (items.firstOrNull()?.cx ?: 500)
                        val cy = a.y ?: (items.firstOrNull()?.cy ?: 500)
                        CapriCursor.spawnCursor(context, cid, cx, cy)
                        log.add("spawned cursor $cid at ($cx, $cy)")
                    }
                    "move_cursor" -> {
                        val cid = a.id ?: 1
                        val cx = a.x ?: 500
                        val cy = a.y ?: 500
                        CapriCursor.moveTo(cx, cy, id = cid)
                        log.add("moved cursor $cid to ($cx, $cy)")
                    }
                    "remove_cursor" -> {
                        val cid = a.id ?: 1
                        CapriCursor.removeCursor(cid)
                        log.add("removed cursor $cid")
                    }
                    "clear_cursors" -> {
                        CapriCursor.hide()
                        log.add("cleared all cursors")
                    }
                    "swipe" -> {
                        val fx = a.x; val fy = a.y; val tx = a.x2; val ty = a.y2
                        if (fx == null || fy == null || tx == null || ty == null) {
                            log.add("swipe: missing coordinates")
                        } else {
                            CapriCursor.setStatus("swiping...")
                            PhoneControl.swipe(fx.toFloat(), fy.toFloat(), tx.toFloat(), ty.toFloat())
                            log.add("swiped ($fx,$fy) to ($tx,$ty)")
                        }
                    }
                    "long_press" -> {
                        CapriCursor.setStatus("holding...")
                        val item = items.firstOrNull { it.id == a.id }
                        val ok = when {
                            item != null -> PhoneControl.longPress(item)
                            a.x != null && a.y != null -> PhoneControl.longPressAt(a.x, a.y)
                            else -> false
                        }
                        log.add("long pressed" + if (ok) "" else " (failed)")
                    }
                    "tap" -> {
                        CapriCursor.setStatus("tapping...")
                        if (a.x != null && a.y != null) {
                            CapriCursor.spawnCursor(context, 1, a.x, a.y)
                            CapriCursor.pulse()
                            val ok = PhoneControl.tapAt(a.x, a.y)
                            log.add("glided cursor to (${a.x}, ${a.y}) & tapped pixel" + if (ok) "" else " (failed)")
                        } else {
                            val item = items.firstOrNull { it.id == a.id }
                            if (item == null) {
                                log.add("tap: unknown element")
                            } else {
                                if (rp == null && RISKY.containsMatchIn(item.label)) {
                                    pending = Pending(goal, "tap", item.label, null, log.toList(), steps)
                                    return "about to tap \"${item.label}\". go ahead? (yes/no)"
                                }
                                val ok = PhoneControl.tap(item)
                                log.add("glided cursor to (${item.cx}, ${item.cy}) & tapped ${item.label}" + if (ok) "" else " (failed)")
                            }
                        }
                    }
                    "type" -> {
                        CapriCursor.setStatus("typing...")
                        val item = items.firstOrNull { it.id == a.id }
                        val text = a.text
                        if (item == null || text == null) {
                            log.add("type: bad target")
                        } else {
                            val ok = PhoneControl.typeInto(context, item, text)
                            log.add("glided cursor to (${item.cx}, ${item.cy}) & typed into ${item.label}" + if (ok) "" else " (failed)")
                        }
                    }
                    "submit" -> {
                        CapriCursor.setStatus("submitting...")
                        val item = items.firstOrNull { it.id == a.id }
                        if (item == null) {
                            log.add("submit: bad target")
                        } else {
                            val ok = PhoneControl.submitTypedText(context, item)
                            log.add("submitted ${item.label}" + if (ok) "" else " (failed)")
                        }
                    }
                    "scroll" -> {
                        CapriCursor.setStatus("scrolling...")
                        val dir = when (a.dir?.lowercase()) {
                            "up" -> com.dot.capri.intents.ScrollDirection.UP
                            "down" -> com.dot.capri.intents.ScrollDirection.DOWN
                            "left" -> com.dot.capri.intents.ScrollDirection.LEFT
                            "right" -> com.dot.capri.intents.ScrollDirection.RIGHT
                            else -> {
                                log.add("scroll: unknown direction '${a.dir}'")
                                null
                            }
                        }
                        if (dir != null) {
                            PhoneControl.scroll(dir)
                            log.add("scrolled ${a.dir}")
                        }
                    }
                    "tap_nth" -> {
                        CapriCursor.setStatus("finding item ${a.id}...")
                        val n = a.id ?: 0
                        val root = PhoneControl.foregroundRoot()
                        val size = PhoneControl.screenSize()
                        val targets = if (root != null && size != null)
                            PhoneControl.orderedTargets(root, size.second / 20) else emptyList()
                        val item = targets.getOrNull(n - 1)
                        if (item == null) {
                            log.add("tap_nth: only ${targets.size} labelled items, wanted $n")
                        } else {
                            val ok = PhoneControl.tap(item)
                            log.add("tapped item $n: ${item.label}" + if (ok) "" else " (failed)")
                        }
                    }
                    "back" -> {
                        CapriCursor.setStatus("going back...")
                        PhoneControl.back()
                        log.add("went back")
                    }
                    "home" -> {
                        CapriCursor.setStatus("going home...")
                        PhoneControl.home()
                        log.add("went home")
                    }
                    "open" -> {
                        CapriCursor.setStatus("opening app...")
                        val app = a.app.orEmpty()
                        val ok = IntentExecutor.execute(context, Cmd.Open(app))
                        log.add("opened $app" + if (ok) "" else " (failed)")
                        delay(500)
                    }
                    "wait" -> log.add("waited")
                    else -> log.add("unknown action")
                }
                steps++
                delay(300)
            }
            return "that took too many steps, choom. ${summary(log)}"
        } finally {
            CapriCursor.hide()
            chatVisible(true)
        }
    }
}
