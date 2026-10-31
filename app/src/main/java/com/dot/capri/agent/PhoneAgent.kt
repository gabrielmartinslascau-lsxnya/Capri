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


    private val RISKY = Regex(
        "\\b(send|pay|buy|purchase|order|checkout|delete|remove|erase|reset|confirm|submit|post|publish|transfer|uninstall|sign out|log out|logout)\\b",
        RegexOption.IGNORE_CASE
    )

    private val BLOCKED_PACKAGES = setOf(
        "com.google.android.apps.walletnfcrel",
        "com.paypal.android.p2pmobile"
    )

    private var lastShotWidth = 1080
    private var lastShotHeight = 2400

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
        val plan: String? = null,
        val durationMs: Long? = null
    )

    private val SYSTEM = """
You are Capri, an AI agent controlling an Android phone. You perceive the screen through the annotated grid screenshot.
Columns are A to F (left to right), rows 1 to 10 (top to bottom). Cell labels are shown in dark badges in each cell's top-left corner.

Actions (reply with ONLY a JSON object):
{"do":"tap","cell":"C4"}                     tap grid cell C4 (or "id":N if element is in list)
{"do":"swipe","cell":"A1","cell2":"C4"}      drag/swipe from cell A1 to cell C4 (e.g. planting cards in PvZ)
{"do":"hold","cell":"C4","duration_ms":5000} hold down cell C4 for exact duration (default 5000ms)
{"do":"double_tap","cell":"C4"}              double tap cell C4
{"do":"type","id":N,"text":"..."}            type into field N
{"do":"scroll","dir":"down"}                 scroll (down, up, left, right)
{"do":"back"}
{"do":"home"}
{"do":"open","app":"app name"}
{"do":"wait"}
{"do":"done","say":"short summary"}
{"do":"fail","say":"why you can't do it"}

Direct Instructions:
- Be decisive. Act immediately based on what is visible in the screenshot grid.
- In games (like Plants vs Zombies or action games), use grid cells directly (e.g. swipe/drag plant card cell A1 to lawn cell C4, or tap card cell then tap lawn cell).
- If the user's goal is accomplished, output {"do":"done","say":"..."} immediately. If a screen is loading or transitioning, output {"do":"wait"}.
- Never repeat the exact same action if the result is already visible on screen.
- Keep "say" to one short lowercase sentence.
""".trimIndent()

    /** A cheap fingerprint of what is on screen right now. */
    private fun screenSignature(): String {
        val root = PhoneControl.foregroundRoot() ?: return "none"
        val labels = PhoneControl.snapshot(root, max = 60).joinToString("|") { it.label }
        return "${root.packageName}#${labels.hashCode()}"
    }

    /** Waits until the screen differs from `before`, or the timeout passes. Returns true if it changed. */
    private suspend fun waitForChange(before: String, timeoutMs: Long = 300L): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            delay(80L)
            waited += 80L
            if (screenSignature() != before) return true
        }
        return false
    }

    private fun cellToXY(cell: String, w: Int, h: Int): Pair<Int, Int>? {
        val c = cell.trim().uppercase()
        val col = c.firstOrNull()?.let { it - 'A' } ?: return null
        val row = c.drop(1).toIntOrNull()?.minus(1) ?: return null
        if (col !in 0..5 || row !in 0..9) return null

        val (screenW, screenH) = PhoneControl.screenSize() ?: (w to h)
        val realX = ((col + 0.5f) * screenW / 6f).toInt()
        val realY = ((row + 0.5f) * screenH / 10f).toInt()
        return Pair(realX, realY)
    }

    private fun annotate(bytes: ByteArray?): ByteArray? {
        if (bytes == null) return null
        val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        lastShotWidth = src.width
        lastShotHeight = src.height
        val bmp = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(bmp)

        val shadowLine = android.graphics.Paint().apply { color = 0xAA000000.toInt(); strokeWidth = 3f }
        val cyanLine = android.graphics.Paint().apply { color = 0xCC00E5FF.toInt(); strokeWidth = 2f }
        val bgBadge = android.graphics.Paint().apply { color = 0xCC111111.toInt(); style = android.graphics.Paint.Style.FILL }
        val borderBadge = android.graphics.Paint().apply { color = 0xFF00E5FF.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.5f }
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = (bmp.height / 42f).coerceAtLeast(18f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()

        for (col in 0..6) {
            val x = w * col / 6f
            canvas.drawLine(x + 1f, 0f, x + 1f, h, shadowLine)
            canvas.drawLine(x, 0f, x, h, cyanLine)
        }
        for (row in 0..10) {
            val y = h * row / 10f
            canvas.drawLine(0f, y + 1f, w, y + 1f, shadowLine)
            canvas.drawLine(0f, y, w, y, cyanLine)
        }

        val badgePadding = 6f
        for (col in 0..5) {
            for (row in 0..9) {
                val tag = "${'A' + col}${row + 1}"
                val x = w * col / 6f + 8f
                val y = h * row / 10f + textPaint.textSize + 6f
                val tw = textPaint.measureText(tag)
                val rect = android.graphics.RectF(x - 4f, y - textPaint.textSize - 2f, x + tw + badgePadding, y + badgePadding)
                canvas.drawRoundRect(rect, 6f, 6f, bgBadge)
                canvas.drawRoundRect(rect, 6f, 6f, borderBadge)
                canvas.drawText(tag, x, y, textPaint)
            }
        }
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
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

            val (screenW, screenH) = PhoneControl.screenSize() ?: (w to h)
            val scaleX = screenW.toFloat() / w.toFloat()
            val scaleY = screenH.toFloat() / h.toFloat()

            val rawX = if (o.has("x")) o.optInt("x") else cell1?.first
            val rawY = if (o.has("y")) o.optInt("y") else cell1?.second
            val rawX2 = if (o.has("x2")) o.optInt("x2") else cell2?.first
            val rawY2 = if (o.has("y2")) o.optInt("y2") else cell2?.second

            val x = rawX?.let { (it * scaleX).toInt() }
            val y = rawY?.let { (it * scaleY).toInt() }
            val x2 = rawX2?.let { (it * scaleX).toInt() }
            val y2 = rawY2?.let { (it * scaleY).toInt() }

            val dur = if (o.has("duration_ms")) o.optLong("duration_ms") else if (o.has("duration")) o.optLong("duration") else null
            Act(
                doWhat = o.optString("do").lowercase(),
                id = if (o.has("id")) o.optInt("id") else if (o.has("n")) o.optInt("n") else null,
                text = o.optString("text").ifEmpty { null },
                dir = o.optString("dir").ifEmpty { null },
                app = o.optString("app").ifEmpty { null },
                say = o.optString("say").ifEmpty { null },
                x = x?.coerceIn(0, screenW - 1),
                y = y?.coerceIn(0, screenH - 1),
                x2 = x2?.coerceIn(0, screenW - 1),
                y2 = y2?.coerceIn(0, screenH - 1),
                label = o.optString("label").ifEmpty { null },
                plan = o.optString("plan").ifEmpty { null },
                durationMs = dur
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
        var noChangeStreak = 0
        val sentTexts = HashSet<String>()

        chatVisible(false)
        CapriCursor.show(context)
        delay(250)
        var plan: String? = null

        try {
            while (!CapriCursor.cancelled) {
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
                    val shot = if (items.size < 5) annotate(service.captureScreenBitmap()) else null
                    var reply: String? = null
                    repeat(3) {
                        reply = askModel(context, goal, pkg, items, log, shot)
                        if (reply != null) return@repeat
                        delay(500)
                    }
                    if (reply == null) {
                        log.add("brain unresponsive, retrying")
                        delay(1000)
                        continue
                    }
                    chosen = parseAct(reply, lastShotWidth, lastShotHeight)
                    if (chosen == null) {
                        Log.w(TAG, "unreadable reply: ${reply.take(120)}")
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

                val targetItem = a.id?.let { id -> items.firstOrNull { it.id == id } }

                Log.d(TAG, "step=$steps act=${a.doWhat} id=${a.id} x=${a.x} y=${a.y} pkg=$pkg items=${items.size}")

                val before = screenSignature()

                when (a.doWhat) {
                    "done" -> return a.say ?: "done."
                    "fail" -> return a.say ?: "couldn't do that, choom."
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
                            CapriCursor.moveTo(a.x, a.y)
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
                        if (item == null || text.isNullOrBlank()) {
                            log.add("type: bad target")
                        } else if (!sentTexts.add(text.lowercase())) {
                            // Already typed this exact text in this task. Don't send it again.
                            log.add("refused to repeat text already typed")
                        } else {
                            // Only press Enter (send) when the user's own goal contains this text.
                            // Otherwise the text is typed and left for you to send.
                            val submit = goal.contains(text, ignoreCase = true)
                            val ok = PhoneControl.typeInto(context, item, text, submit)
                            log.add("typed into ${item.label}" +
                                    (if (submit) " and sent" else " (not sent)") +
                                    if (ok) "" else " (failed)")
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
                    "double_tap" -> {
                        CapriCursor.setStatus("double tapping...")
                        if (a.x != null && a.y != null) {
                            CapriCursor.moveTo(a.x, a.y)
                            CapriCursor.pulse()
                            val ok = PhoneControl.doubleTapAt(a.x, a.y)
                            log.add("double tapped pixel (${a.x}, ${a.y})" + if (ok) "" else " (failed)")
                        } else {
                            val item = items.firstOrNull { it.id == a.id }
                            if (item == null) {
                                log.add("double_tap: unknown element")
                            } else {
                                val ok = PhoneControl.doubleTap(item)
                                log.add("double tapped ${item.label}" + if (ok) "" else " (failed)")
                            }
                        }
                    }
                    "hold" -> {
                        CapriCursor.setStatus("holding continuously...")
                        val duration = a.durationMs ?: 5000L
                        val item = items.firstOrNull { it.id == a.id }
                        val ok = when {
                            item != null -> PhoneControl.hold(item, duration)
                            a.x != null && a.y != null -> PhoneControl.holdAt(a.x, a.y, duration)
                            else -> false
                        }
                        log.add("held continuously for ${duration}ms" + if (ok) "" else " (failed)")
                    }
                    "wait" -> log.add("waited")
                    else -> log.add("unknown action")
                }
                steps++
                // Let the screen settle, then check that the action did something.
                if (items.size < 5) {
                    delay(400) // Allow game frame animations/renders to settle before taking fresh screenshot
                }
                val isContinuousAction = a.doWhat in setOf("hold", "long_press", "swipe", "scroll", "multitap", "multi_touch", "wait")
                val changed = waitForChange(before)
                if (changed || isContinuousAction) {
                    noChangeStreak = 0
                } else {
                    noChangeStreak++
                    log.add("no change on screen")
                    delay(500)
                    if (noChangeStreak >= 4) {
                        noChangeStreak = 0 // Never give up on static screens
                    }
                }
            }
            return "that took too many steps, choom. ${summary(log)}"
        } finally {
            CapriCursor.hide()
            chatVisible(true)
        }
    }
}
