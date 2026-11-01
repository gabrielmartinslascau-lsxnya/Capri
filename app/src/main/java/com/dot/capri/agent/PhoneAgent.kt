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
        val durationMs: Long? = null,
        val region: String? = null,
        val holdMs: Long? = null,
        val dx: Int? = null,
        val dy: Int? = null
    )

    private val SYSTEM = """
You are Capri, an AI agent controlling an Android phone. You perceive the screen through the annotated grid screenshot.
The GRID line in each prompt gives the exact columns and rows for this screen. Use only those. The screenshot also features a 0.0 to 1.0 percentage coordinate overlay (yellow reference lines and axis labels at 0.2, 0.4, 0.6, 0.8). You can use grid cells (cell/cell2) or specify exact pixel/percentage coordinates (x, y, x2, y2) by estimating from the overlay axes for precise grounding.

Actions (reply with ONLY a JSON object):
{"do":"tap","cell":"C4"}                     tap grid cell C4 (or "id":N if element is in list)
{"do":"tap_text","text":"Resume Game"}   tap the centre of the words on screen that match. Use this first for any button or label that has words (menus, Play, Continue, Resume, Next, OK). Only fall back to a grid cell when no text fits.
{"do":"swipe","cell":"A1","cell2":"C4"}      drag/swipe from cell A1 to cell C4 (e.g. planting cards in PvZ)
{"do":"hold","cell":"C4","duration_ms":5000} hold down cell C4 for exact duration (default 5000ms)
{"do":"double_tap","cell":"C4"}              double tap cell C4
{"do":"press_region","region":"attack","duration_ms":60000} press a calibrated CONTROL REGION for duration_ms. Use it for game buttons: mining, attacking, jumping, sprinting. A long hold is fine.
{"do":"drag","cell":"C4","cell2":"F4","hold_ms":300,"duration_ms":600} press cell C4, hold hold_ms, move to cell F4 over duration_ms, release. Use for dragging a plant card onto the lawn or moving items.
{"do":"look","dx":120,"dy":-40,"duration_ms":250} turn the camera by dx,dy pixels from the look zone. Positive dx turns right, negative left, positive dy turns down. Use it to aim at a block or target.
{"do":"type","id":N,"text":"..."}            type into field N
{"do":"scroll","dir":"down"}                 scroll (down, up, left, right)
{"do":"back"}
{"do":"home"}
{"do":"open","app":"app name"}
{"do":"wait"}
{"do":"done","say":"short summary"}
{"do":"fail","say":"why you can't do it"}

Direct Instructions:
- CRITICAL GOAL CHECK: Before taking an action, check if the TASK is ALREADY SATISFIED on the current screen or foreground app. If TASK is complete (e.g., requested app is open, menu item was clicked, or goal was achieved), reply {"do":"done","say":"..."} immediately. Never repeat an action for a goal that is already finished.
- Review STEPS SO FAR: Each previous step shows whether it (worked/screen updated) or (failed/no change). Use this feedback to decide your next move—never repeat a step that failed or re-do a step that already worked.
- For buttons with words, use tap_text with the exact words you see. Never guess a cell for a word you can read.
- If tap_text reports "not found", try the shortest distinctive word from the button, or use a cell.
- If SCREEN ELEMENTS contains the target, always tap it by id. Use a grid cell only when no element matches.
- Only use a grid cell when the target is clearly visible inside that cell. Never guess a cell.
- Columns A and F and rows 1 and 10 are the screen edges. Use them only when the target is there.
- Be decisive, but look first: name the target in "label" before you act.
- In games (like Plants vs Zombies or action games), use grid cells directly (e.g. swipe/drag plant card cell A1 to lawn cell C4, or tap card cell then tap lawn cell).
- If the user's goal is accomplished, output {"do":"done","say":"..."} immediately. If a screen is loading or transitioning, output {"do":"wait"}.
- Never repeat the exact same action if the result is already visible on screen.
- Keep "say" to one short lowercase sentence.
""".trimIndent()

    // Smaller action set for the on-device model: element ids and labels only.
    private val SYSTEM_LOCAL = """
You control an Android phone by reading the numbered element list. Reply with ONLY one JSON object.
Actions:
{"do":"tap","id":N,"label":"exact label of element N"}
{"do":"tap_nth","n":N}
{"do":"press_region","region":"attack","duration_ms":3000}
{"do":"look","dx":120,"dy":-40}
{"do":"drag","cell":"C4","cell2":"F4"}
{"do":"tap","cell":"C4"}
{"do":"type","id":N,"label":"exact label","text":"..."}
{"do":"scroll","dir":"up|down|left|right"}
{"do":"open","app":"app name"}
{"do":"back"}
{"do":"home"}
{"do":"wait"}
{"do":"done","say":"short summary"}
{"do":"fail","say":"why"}
Rules:
- CRITICAL GOAL CHECK: If the task is already completed or the requested app/screen is open, output {"do":"done","say":"..."} immediately. Never repeat an action that already succeeded.
- In first-person games, aim first: use look until the target is at the centre of the screen, then press_region attack. Never press attack while the target is off-centre.
- Mining or breaking a block: press_region attack with a duration long enough to finish, then check the screen. Do not repeat attack in short bursts.
- Placing cards in PvZ: use drag from the card cell to the lawn cell.
- Use CONTROL REGIONS names exactly as listed.
- Use only ids and labels that appear in SCREEN ELEMENTS. Copy the label exactly.
- One action per reply. Never guess.
- Pick the element that matches the goal. If nothing matches, scroll down or answer fail.
- Do not repeat an action that did not change the screen. Use wait once if the screen is loading.
- Only type text the user gave you.
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
        val (sw, sh) = PhoneControl.screenSize() ?: (w to h)
        return GridSpec.centre(cell, sw, sh)
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
        val g = GridSpec.of(w.toInt(), h.toInt())

        for (col in 0..g.cols) {
            val x = w * col / g.cols.toFloat()
            canvas.drawLine(x + 1f, 0f, x + 1f, h, shadowLine)
            canvas.drawLine(x, 0f, x, h, cyanLine)
        }
        for (row in 0..g.rows) {
            val y = h * row / g.rows.toFloat()
            canvas.drawLine(0f, y + 1f, w, y + 1f, shadowLine)
            canvas.drawLine(0f, y, w, y, cyanLine)
        }

        // Percentage coordinate overlay (0.0 to 1.0 at 0.2 intervals with high-contrast dual-layer lines & background badges)
        val percentTextPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = (bmp.height / 38f).coerceAtLeast(18f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val percentBgPaint = android.graphics.Paint().apply { color = 0xEE1565C0.toInt(); style = android.graphics.Paint.Style.FILL }
        val percentLineShadow = android.graphics.Paint().apply { color = 0xAA000000.toInt(); strokeWidth = 4f }
        val percentLineFg = android.graphics.Paint().apply { color = 0xFFFFEA00.toInt(); strokeWidth = 2.5f }

        for (p in listOf(0.2f, 0.4f, 0.6f, 0.8f)) {
            val px = w * p
            val py = h * p
            // Vertical line
            canvas.drawLine(px + 1f, 0f, px + 1f, h, percentLineShadow)
            canvas.drawLine(px, 0f, px, h, percentLineFg)

            // Horizontal line
            canvas.drawLine(0f, py + 1f, w, py + 1f, percentLineShadow)
            canvas.drawLine(0f, py, w, py, percentLineFg)

            // Text labels with background badges
            val label = String.format(java.util.Locale.US, "%.1f", p)
            val tw = percentTextPaint.measureText(label)
            val th = percentTextPaint.textSize

            val rectV = android.graphics.RectF(px - tw / 2f - 6f, 10f, px + tw / 2f + 6f, 10f + th + 8f)
            canvas.drawRoundRect(rectV, 6f, 6f, percentBgPaint)
            canvas.drawText(label, px - tw / 2f, 10f + th, percentTextPaint)

            val rectH = android.graphics.RectF(10f, py - th / 2f - 4f, 10f + tw + 12f, py + th / 2f + 4f)
            canvas.drawRoundRect(rectH, 6f, 6f, percentBgPaint)
            canvas.drawText(label, 14f, py + th / 2f - 2f, percentTextPaint)
        }

        val badgePadding = 6f
        for (col in 0 until g.cols) {
            for (row in 0 until g.rows) {
                val tag = "${'A' + col}${row + 1}"
                val x = w * col / g.cols.toFloat() + 8f
                val y = h * row / g.rows.toFloat() + textPaint.textSize + 6f
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

            val rawDoubleX = if (o.has("x")) o.optDouble("x", -1.0) else -1.0
            val rawDoubleY = if (o.has("y")) o.optDouble("y", -1.0) else -1.0
            val rawDoubleX2 = if (o.has("x2")) o.optDouble("x2", -1.0) else -1.0
            val rawDoubleY2 = if (o.has("y2")) o.optDouble("y2", -1.0) else -1.0

            val rawX = when {
                rawDoubleX >= 0.0 && rawDoubleX <= 1.0 -> (rawDoubleX * w).toInt()
                o.has("x") -> o.optInt("x")
                else -> cell1?.first
            }
            val rawY = when {
                rawDoubleY >= 0.0 && rawDoubleY <= 1.0 -> (rawDoubleY * h).toInt()
                o.has("y") -> o.optInt("y")
                else -> cell1?.second
            }
            val rawX2 = when {
                rawDoubleX2 >= 0.0 && rawDoubleX2 <= 1.0 -> (rawDoubleX2 * w).toInt()
                o.has("x2") -> o.optInt("x2")
                else -> cell2?.first
            }
            val rawY2 = when {
                rawDoubleY2 >= 0.0 && rawDoubleY2 <= 1.0 -> (rawDoubleY2 * h).toInt()
                o.has("y2") -> o.optInt("y2")
                else -> cell2?.second
            }

            // Cell values are already in real screen pixels, so they must not be scaled again.
            // Only raw x/y and 0..1 percentages come from the screenshot and need scaling.
            val xIsCell = !o.has("x") && !(rawDoubleX >= 0.0 && rawDoubleX <= 1.0)
            val yIsCell = !o.has("y") && !(rawDoubleY >= 0.0 && rawDoubleY <= 1.0)
            val x2IsCell = !o.has("x2") && !(rawDoubleX2 >= 0.0 && rawDoubleX2 <= 1.0)
            val y2IsCell = !o.has("y2") && !(rawDoubleY2 >= 0.0 && rawDoubleY2 <= 1.0)
            val x = rawX?.let { if (xIsCell) it else (it * scaleX).toInt() }
            val y = rawY?.let { if (yIsCell) it else (it * scaleY).toInt() }
            val x2 = rawX2?.let { if (x2IsCell) it else (it * scaleX).toInt() }
            val y2 = rawY2?.let { if (y2IsCell) it else (it * scaleY).toInt() }

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
                durationMs = dur,
                region = o.optString("region").ifEmpty { null },
                holdMs = if (o.has("hold_ms")) o.optLong("hold_ms") else null,
                dx = if (o.has("dx")) o.optInt("dx") else null,
                dy = if (o.has("dy")) o.optInt("dy") else null
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
                "GRID: ${PhoneControl.screenSize()?.let { GridSpec.describe(it.first, it.second) } ?: "unknown"}\n" +
                "SCREENSHOT: ${if (shot != null) "attached, grid shown" else "none, use element ids"}\n" +
                "CONTROL REGIONS: ${ControlRegions.names(context).ifEmpty { "none calibrated" }}\n" +
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
            OnDeviceAiClient.generateResponse(context, prompt, shot, systemOverride = SYSTEM_LOCAL).getOrNull()
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
        var lastSpot = ""
        var spotRepeats = 0

        chatVisible(false)
        CapriCursor.show(context)
        delay(250)
        var plan: String? = null

        try {
            DevOverlay.start(context)
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
                    val shot = annotate(service.captureScreenBitmap()) // val shot = if (items.size < 5)
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
                    Log.d(TAG, "raw reply: ${reply.take(300)}")
                    if (chosen == null) {
                        Log.w(TAG, "unreadable reply: ${reply.take(120)}")
                        log.add("unreadable reply")
                        steps++
                        continue
                    }
                }
                val a: Act = chosen

                // The same action on the same spot again and again is a loop, not progress.
                val spot = "${a.doWhat}|${a.id}|${a.x}|${a.y}|${a.x2}|${a.y2}|${a.region}|${a.dx}|${a.dy}|${a.durationMs}|${a.text}"
                val repeatable = a.doWhat in setOf("wait", "press_region", "hold", "look", "drag", "double_tap")
                if (!repeatable && spot == lastSpot) spotRepeats++ else { spotRepeats = 0; lastSpot = spot }
                if (spotRepeats >= 2) {
                    return "i kept doing the same thing in the same place, so I stopped. ${summary(log)}"
                }

                a.plan?.let {
                    if (plan == null) {
                        plan = it
                        log.add("plan: $it")
                    }
                }

                val targetItem = a.id?.let { id -> items.firstOrNull { it.id == id } }

                Log.d(TAG, "step=$steps act=${a.doWhat} id=${a.id} x=${a.x} y=${a.y} pkg=$pkg items=${items.size}")
                if (DevOverlay.isEnabled(context)) {
                    val rawMx = a.x ?: targetItem?.cx
                    val rawMy = a.y ?: targetItem?.cy
                    val marked = if (rawMx != null && rawMy != null) PhoneControl.clampToScreen(rawMx, rawMy) else null
                    val mx = marked?.first
                    val my = marked?.second
                    if (mx != null && my != null) {
                        val size = PhoneControl.screenSize()
                        val cell = size?.let { (w, h) -> DevOverlay.cellName(mx, my, w, h) } ?: "?"
                        val idPart = if (a.id != null) " #${a.id}" else ""
                        DevOverlay.mark(mx, my, "${a.doWhat} $cell$idPart")
                    }
                }

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
                            val (cx, cy) = PhoneControl.clampToScreen(a.x, a.y)
                            CapriCursor.moveTo(cx, cy)
                            CapriCursor.pulse()
                            val ok = PhoneControl.tapAt(cx, cy)
                            log.add("tapped ($cx, $cy)" + if (a.x != cx || a.y != cy) " (asked for ${a.x}, ${a.y})" else "" + if (ok) "" else " (failed)")
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
                    "press_region" -> {
                        CapriCursor.setStatus("pressing ${a.region}...")
                        val p = a.region?.let { ControlRegions.resolve(context, it) }
                        if (p == null) {
                            log.add("press_region: unknown region '${a.region}'")
                        } else {
                            val ms = (a.durationMs ?: 300L).coerceIn(50L, 120_000L)
                            val ok = PhoneControl.holdAt(p.first, p.second, ms)
                            log.add("pressed ${a.region} for ${ms}ms" + if (ok) "" else " (failed)")
                        }
                    }
                    "drag" -> {
                        val fx = a.x; val fy = a.y; val tx = a.x2; val ty = a.y2
                        if (fx == null || fy == null || tx == null || ty == null) {
                            log.add("drag: missing coordinates (use cell, cell2 or x,y,x2,y2)")
                        } else {
                            CapriCursor.setStatus("dragging...")
                            val ok = PhoneControl.dragWithHold(
                                fx, fy, tx, ty,
                                holdMs = a.holdMs ?: 150L,
                                moveMs = a.durationMs ?: 300L
                            )
                            log.add("dragged ($fx,$fy) to ($tx,$ty)" + if (ok) "" else " (failed)")
                        }
                    }
                    "look" -> {
                        CapriCursor.setStatus("looking...")
                        val dx = a.dx ?: 0
                        val dy = a.dy ?: 0
                        val origin: Pair<Int, Int> = ControlRegions.resolve(context, "look")
                            ?: PhoneControl.screenSize()?.let { (w, h) -> Pair(w * 3 / 4, h / 2) }
                            ?: Pair(900, 600)
                        val ok = PhoneControl.look(origin.first, origin.second, dx, dy, a.durationMs ?: 250L)
                        log.add("looked ($dx,$dy)" + if (ok) "" else " (failed)")
                    }
                    "tap_text" -> {
                        CapriCursor.setStatus("looking for \"${a.text}\"...")
                        val wanted = a.text ?: ""
                        val shotBytes = service.captureScreenBitmap()
                        val size = PhoneControl.screenSize()
                        val hit = if (shotBytes != null && size != null && wanted.isNotBlank())
                            TextFinder.find(shotBytes, size.first, size.second, wanted)
                        else null
                        if (hit == null) {
                            log.add("tap_text: \"$wanted\" not found on screen")
                        } else {
                            val (hx, hy) = PhoneControl.clampToScreen(hit.x, hit.y)
                            CapriCursor.moveTo(hx, hy)
                            CapriCursor.pulse()
                            val ok = PhoneControl.tapAt(hx, hy)
                            log.add("tapped text \"${hit.text}\" at ($hx, $hy)" + if (ok) "" else " (failed)")
                        }
                    }
                    "wait" -> log.add("waited")
                    else -> log.add("unknown action")
                }
                steps++
                // Let the screen settle, then check that the action did something.
                if (items.size < 5) {
                    delay(400) // Allow game frame animations/renders to settle before taking fresh screenshot
                }
                val isContinuousAction = a.doWhat in setOf("hold", "long_press", "swipe", "scroll", "multitap", "multi_touch", "wait", "press_region", "look", "drag")
                val changed = waitForChange(before)
                val statusTag = if (changed) "(worked/screen updated)" else if (isContinuousAction) "(in progress)" else "(failed/no change)"
                if (log.isNotEmpty()) {
                    val lastIdx = log.lastIndex
                    if (!log[lastIdx].contains("(worked") && !log[lastIdx].contains("(failed") && !log[lastIdx].contains("(in progress)")) {
                        log[lastIdx] = "${log[lastIdx]} $statusTag"
                    }
                }
                if (changed || isContinuousAction) {
                    noChangeStreak = 0
                } else {
                    noChangeStreak++
                    delay(500)
                    if (noChangeStreak >= 3) {
                        return "nothing changed on screen after three tries, so I stopped. ${summary(log)}"
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
