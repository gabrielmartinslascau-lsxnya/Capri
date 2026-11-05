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
You are Capri, an AI agent controlling an Android phone. You see the clean, unobstructed screen with a thin 0.1..0.9 coordinate ruler along the top (X) and left (Y) border.
Never use grid squares. Target buttons using exact text, element IDs, or exact normalized (x, y) coordinates from 0.00 to 1.00.

Actions (reply with ONLY a JSON object):
{"do":"tap_text","text":"Play"}                   tap any visible word/button from OCR TEXT ON SCREEN
{"do":"tap","id":N}                               tap numbered UI element N from SCREEN ELEMENTS
{"do":"tap","x":0.52,"y":0.64,"label":"button"}   tap exact normalized coordinate (0.00..1.00)
{"do":"double_tap","x":0.52,"y":0.64}             double tap exact coordinate
{"do":"hold","x":0.50,"y":0.50,"duration_ms":3500} press and hold exact coordinate (e.g. mining wood in Minecraft)
{"do":"drag","x":0.18,"y":0.12,"x2":0.45,"y2":0.55,"hold_ms":200,"duration_ms":500} drag from (x,y) to (x2,y2) (e.g. placing a PvZ plant)
{"do":"swipe","x":0.50,"y":0.75,"x2":0.50,"y2":0.25} fast swipe from (x,y) to (x2,y2)
{"do":"look","dx":140,"dy":-30,"duration_ms":250} turn 3D game camera by (dx, dy) pixels
{"do":"press_region","region":"attack","duration_ms":4000} press a calibrated region
{"do":"type","id":N,"text":"..."}                 type text into input field N
{"do":"scroll","dir":"down"}                      scroll up, down, left, or right
{"do":"open","app":"app name"}                    launch an app
{"do":"back"}
{"do":"home"}
{"do":"wait"}
{"do":"done","say":"short summary"}

Rules:
- If the goal is already complete on screen, output {"do":"done","say":"..."} immediately.
- If OCR TEXT ON SCREEN or SCREEN ELEMENTS shows the button you need, use its exact (x, y) coordinate or tap_text / id.
- For visual game objects without text (trees, zombies, sun, seeds, chess pieces, D-pad), align with the top X (0.1..0.9) and left Y (0.1..0.9) border ticks to pick exact (x, y) coordinates.
- Never repeat an action listed in BLACKLIST or one that "(failed/no change)" in STEPS SO FAR.
""".trimIndent()

    private val SYSTEM_LOCAL = """
You control an Android phone and play games by looking at the clean screenshot, the top/left 0.1..0.9 border ruler, and the OCR text coordinates.
Reply with ONLY ONE line (either a command or JSON):
TAP <x> <y>              (e.g. TAP 0.50 0.62 — tap exact 0.00..1.00 coordinate)
TAP_TEXT <words>         (e.g. TAP_TEXT Play — tap visible button text)
TAP_ID <id>              (e.g. TAP_ID 3 — tap numbered element #3)
HOLD <x> <y> <ms>        (e.g. HOLD 0.50 0.50 3500 — hold to mine/break block)
DRAG <x1> <y1> <x2> <y2> (e.g. DRAG 0.22 0.12 0.45 0.55 — drag card/item)
SEQ <x1> <y1> <x2> <y2>  (e.g. SEQ 0.22 0.12 0.45 0.55 — tap A then tap B, e.g. Chess or PvZ)
LOOK <dx> <dy>           (e.g. LOOK 120 -30 — turn 3D camera)
SCROLL <up|down|left|right>
OPEN <app name>
WAIT
DONE

Rules:
- Output ONLY the single command line. No explanation.
- If OCR TEXT ON SCREEN lists the button you want, use its exact (x, y) in TAP <x> <y> or use TAP_TEXT <words>.
- Never repeat any move in BLACKLIST or a move that failed in STEPS SO FAR.
""".trimIndent()

    private fun screenSignature(root: AccessibilityNodeInfo?, items: List<PhoneControl.UiItem>, frameHash: Long = 0L): String {
        val pkg = root?.packageName?.toString() ?: "game_surface"
        return if (items.size >= 3) {
            val labels = items.joinToString("|") { it.label }
            "$pkg#${labels.hashCode()}"
        } else {
            "$pkg#hash_$frameHash"
        }
    }

    private suspend fun waitForChange(before: String, timeoutMs: Long = 300L): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            delay(80L)
            waited += 80L
            val root = PhoneControl.foregroundRoot()
            if (root != null) {
                val items = PhoneControl.snapshot(root)
                if (screenSignature(root, items, 0L) != before) return true
            }
        }
        return false
    }

    private fun cellToXY(cell: String, w: Int, h: Int): Pair<Int, Int>? {
        val (sw, sh) = PhoneControl.screenSize() ?: (w to h)
        return GridSpec.centre(cell, sw, sh)
    }

    private fun parseAct(raw: String, w: Int, h: Int): Act? {
        var cleaned = raw
            .replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```\\s*", RegexOption.IGNORE_CASE), "")
            .trim()

        cleaned = cleaned
            .replace(Regex("^(?:CMD|COMMAND|ACTION|NEXT MOVE):\\s*", RegexOption.IGNORE_CASE), "")
            .trim()

        // 1. Try JSON object parsing (for Cloud API or JSON responses)
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) {
            try {
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

                val xIsCell = !o.has("x") && !(rawDoubleX >= 0.0 && rawDoubleX <= 1.0)
                val yIsCell = !o.has("y") && !(rawDoubleY >= 0.0 && rawDoubleY <= 1.0)
                val x2IsCell = !o.has("x2") && !(rawDoubleX2 >= 0.0 && rawDoubleX2 <= 1.0)
                val y2IsCell = !o.has("y2") && !(rawDoubleY2 >= 0.0 && rawDoubleY2 <= 1.0)
                val x = rawX?.let { if (xIsCell) it else (it * scaleX).toInt() }
                val y = rawY?.let { if (yIsCell) it else (it * scaleY).toInt() }
                val x2 = rawX2?.let { if (x2IsCell) it else (it * scaleX).toInt() }
                val y2 = rawY2?.let { if (y2IsCell) it else (it * scaleY).toInt() }

                val textOrConcept = o.optString("text").ifEmpty { o.optString("concept") }.ifEmpty { null }
                val dur = if (o.has("duration_ms")) o.optLong("duration_ms") else if (o.has("duration")) o.optLong("duration") else null

                return Act(
                    doWhat = o.optString("do").lowercase(),
                    id = if (o.has("id")) o.optInt("id") else if (o.has("n")) o.optInt("n") else null,
                    text = textOrConcept,
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
            } catch (_: Exception) {}
        }

        // 2. Try Single-Line Command Grammar parsing (for local Gemma E2B fast commands)
        val (sw, sh) = PhoneControl.screenSize() ?: (w to h)
        val firstLine = cleaned.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

        if (firstLine.equals("WAIT", ignoreCase = true)) return Act("wait", null, null, null, null, null, null, null, null, null)
        if (firstLine.equals("BACK", ignoreCase = true)) return Act("back", null, null, null, null, null, null, null, null, null)
        if (firstLine.equals("HOME", ignoreCase = true)) return Act("home", null, null, null, null, null, null, null, null, null)

        if (firstLine.equals("DONE", ignoreCase = true) || firstLine.startsWith("DONE ", ignoreCase = true)) {
            val msg = firstLine.substringAfter("DONE", "").trim().ifEmpty { "done." }
            return Act("done", null, null, null, null, msg, null, null, null, null)
        }
        if (firstLine.equals("FAIL", ignoreCase = true) || firstLine.startsWith("FAIL ", ignoreCase = true)) {
            val msg = firstLine.substringAfter("FAIL", "").trim().ifEmpty { "failed." }
            return Act("fail", null, null, null, null, msg, null, null, null, null)
        }

        val openMatch = Regex("^(?:OPEN|LAUNCH|START)\\s+(.+)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (openMatch != null) {
            val app = openMatch.groupValues[1].trim()
            return Act("open", null, null, null, app, null, null, null, null, null)
        }

        val typeMatch = Regex("^TYPE\\s+(?:#?(\\d+)\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (typeMatch != null) {
            val id = typeMatch.groupValues[1].toIntOrNull()
            val txt = typeMatch.groupValues[2].trim()
            return Act("type", id = id, text = txt, dir = null, app = null, say = null, x = null, y = null, x2 = null, y2 = null)
        }

        val tapIdMatch = Regex("^(?:TAP|CLICK)\\s+#?(\\d+)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (tapIdMatch != null) {
            val id = tapIdMatch.groupValues[1].toIntOrNull()
            if (id != null) return Act("tap", id = id, text = null, dir = null, app = null, say = null, x = null, y = null, x2 = null, y2 = null)
        }

        val tapPercentMatch = Regex("^(?:TAP|CLICK)\\s+([01](?:\\.\\d+)?)\\s+([01](?:\\.\\d+)?)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (tapPercentMatch != null) {
            val nx = tapPercentMatch.groupValues[1].toFloat()
            val ny = tapPercentMatch.groupValues[2].toFloat()
            val px = (nx * sw).toInt().coerceIn(0, sw - 1)
            val py = (ny * sh).toInt().coerceIn(0, sh - 1)
            return Act("tap", id = null, text = null, dir = null, app = null, say = null, x = px, y = py, x2 = null, y2 = null)
        }

        val dragPercentMatch = Regex("^(?:DRAG|SWIPE)\\s+([01](?:\\.\\d+)?)\\s+([01](?:\\.\\d+)?)\\s+([01](?:\\.\\d+)?)\\s+([01](?:\\.\\d+)?)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (dragPercentMatch != null) {
            val nx1 = dragPercentMatch.groupValues[1].toFloat()
            val ny1 = dragPercentMatch.groupValues[2].toFloat()
            val nx2 = dragPercentMatch.groupValues[3].toFloat()
            val ny2 = dragPercentMatch.groupValues[4].toFloat()
            val px1 = (nx1 * sw).toInt().coerceIn(0, sw - 1)
            val py1 = (ny1 * sh).toInt().coerceIn(0, sh - 1)
            val px2 = (nx2 * sw).toInt().coerceIn(0, sw - 1)
            val py2 = (ny2 * sh).toInt().coerceIn(0, sh - 1)
            return Act("drag", id = null, text = null, dir = null, app = null, say = null, x = px1, y = py1, x2 = px2, y2 = py2)
        }

        val holdPercentMatch = Regex("^HOLD\\s+([01](?:\\.\\d+)?)\\s+([01](?:\\.\\d+)?)(?:\\s+(\\d+))?$", RegexOption.IGNORE_CASE).find(firstLine)
        if (holdPercentMatch != null) {
            val nx = holdPercentMatch.groupValues[1].toFloat()
            val ny = holdPercentMatch.groupValues[2].toFloat()
            val dur = holdPercentMatch.groupValues[3].toLongOrNull() ?: 3000L
            val px = (nx * sw).toInt().coerceIn(0, sw - 1)
            val py = (ny * sh).toInt().coerceIn(0, sh - 1)
            return Act("hold", id = null, text = null, dir = null, app = null, say = null, x = px, y = py, x2 = null, y2 = null, durationMs = dur)
        }

        val scrollPercentMatch = Regex("^SCROLL\\s+([01](?:\\.\\d+)?)\\s+([01](?:\\.\\d+)?)\\s+(UP|DOWN|LEFT|RIGHT)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (scrollPercentMatch != null) {
            val nx = scrollPercentMatch.groupValues[1].toFloat()
            val ny = scrollPercentMatch.groupValues[2].toFloat()
            val dir = scrollPercentMatch.groupValues[3].lowercase()
            val px = (nx * sw).toInt().coerceIn(0, sw - 1)
            val py = (ny * sh).toInt().coerceIn(0, sh - 1)
            return Act("scroll", id = null, text = null, dir = dir, app = null, say = null, x = px, y = py, x2 = null, y2 = null)
        }

        val tapMatch = Regex("^(?:TAP|CLICK)\\s+([A-Z][0-9]{1,2})$", RegexOption.IGNORE_CASE).find(firstLine)
        if (tapMatch != null) {
            val cell = tapMatch.groupValues[1].uppercase()
            val pt = cellToXY(cell, sw, sh)
            if (pt != null) return Act("tap", null, null, null, null, null, pt.first, pt.second, null, null, label = cell)
        }

        val seqMatch = Regex("^SEQ\\s+([A-Z][0-9]{1,2})\\s+([A-Z][0-9]{1,2})$", RegexOption.IGNORE_CASE).find(firstLine)
        if (seqMatch != null) {
            val c1 = cellToXY(seqMatch.groupValues[1].uppercase(), sw, sh)
            val c2 = cellToXY(seqMatch.groupValues[2].uppercase(), sw, sh)
            if (c1 != null && c2 != null) {
                return Act("seq", null, null, null, null, null, c1.first, c1.second, c2.first, c2.second, label = "${seqMatch.groupValues[1]}->${seqMatch.groupValues[2]}")
            }
        }

        val dragMatch = Regex("^(?:DRAG|SWIPE)\\s+([A-Z][0-9]{1,2})\\s+([A-Z][0-9]{1,2})$", RegexOption.IGNORE_CASE).find(firstLine)
        if (dragMatch != null) {
            val c1 = cellToXY(dragMatch.groupValues[1].uppercase(), sw, sh)
            val c2 = cellToXY(dragMatch.groupValues[2].uppercase(), sw, sh)
            if (c1 != null && c2 != null) {
                return Act("drag", null, null, null, null, null, c1.first, c1.second, c2.first, c2.second, label = "${dragMatch.groupValues[1]}->${dragMatch.groupValues[2]}")
            }
        }

        val holdMatch = Regex("^HOLD\\s+([A-Z][0-9]{1,2})(?:\\s+(\\d+))?$", RegexOption.IGNORE_CASE).find(firstLine)
        if (holdMatch != null) {
            val cell = holdMatch.groupValues[1].uppercase()
            val dur = holdMatch.groupValues[2].toLongOrNull() ?: 3000L
            val pt = cellToXY(cell, sw, sh)
            if (pt != null) return Act("hold", null, null, null, null, null, pt.first, pt.second, null, null, durationMs = dur, label = cell)
        }

        val lookMatch = Regex("^LOOK\\s+(-?\\d+)\\s+(-?\\d+)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (lookMatch != null) {
            val dx = lookMatch.groupValues[1].toInt()
            val dy = lookMatch.groupValues[2].toInt()
            return Act("look", null, null, null, null, null, null, null, null, null, dx = dx, dy = dy)
        }

        val textMatch = Regex("^TAP_TEXT\\s+(.+)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (textMatch != null) {
            val words = textMatch.groupValues[1].trim()
            return Act("tap_text", null, words, null, null, null, null, null, null, null)
        }

        val scrollCellMatch = Regex("^SCROLL\\s+([A-Z][0-9]{1,2})\\s+(UP|DOWN|LEFT|RIGHT)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (scrollCellMatch != null) {
            val cell = scrollCellMatch.groupValues[1].uppercase()
            val dir = scrollCellMatch.groupValues[2].lowercase()
            val pt = cellToXY(cell, sw, sh)
            return Act("scroll", id = null, text = null, dir = dir, app = null, say = null, x = pt?.first, y = pt?.second, x2 = null, y2 = null, label = cell)
        }

        val scrollMatch = Regex("^SCROLL\\s+(UP|DOWN|LEFT|RIGHT)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (scrollMatch != null) {
            return Act("scroll", null, null, scrollMatch.groupValues[1].lowercase(), null, null, null, null, null, null)
        }

        // 3. Fallback Regex extraction if model output prose
        val cellRegex = Regex("\\b([A-Z][1-9][0]?)\\b", RegexOption.IGNORE_CASE)
        val foundCell = cellRegex.find(cleaned)?.groupValues?.get(1)?.uppercase()
        if (foundCell != null) {
            val pt = cellToXY(foundCell, sw, sh)
            if (pt != null) {
                val doWhat = when {
                    cleaned.contains("hold", ignoreCase = true) -> "hold"
                    cleaned.contains("drag", ignoreCase = true) || cleaned.contains("swipe", ignoreCase = true) -> "swipe"
                    else -> "tap"
                }
                return Act(doWhat, null, null, null, null, null, pt.first, pt.second, null, null, label = foundCell)
            }
        }

        return null
    }

    private fun summary(log: List<String>): String =
        if (log.isEmpty()) "" else "did: " + log.takeLast(6).joinToString(" > ")

    private fun useApi(context: Context): Boolean {
        val wantsCloud = LlmRouter.getActiveProviderName(context) != "Local"
        val hasKey = LlmRouter.hasAnyApiKey(context)
        val isOnline = NetworkUtils.isOnline(context)
        return wantsCloud && hasKey && isOnline
    }

    /**
     * Scales the screenshot to VisionConfig.getMaxSide(context) and draws a non-intrusive
     * 14px coordinate ruler ONLY along the outer top (X: 0.1..0.9) and left (Y: 0.1..0.9) edges.
     * The center of the image remains 100% unobstructed so every button and pixel is clearly visible.
     */
    private fun annotate(context: Context, bytes: ByteArray?): ByteArray? {
        if (bytes == null) return null
        val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes

        val maxSide = VisionConfig.getMaxSide(context)
        val jpegQuality = VisionConfig.getJpegQuality(context)
        val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        val targetW = (src.width * scale).toInt().coerceAtLeast(1)
        val targetH = (src.height * scale).toInt().coerceAtLeast(1)

        val bmp = if (targetW != src.width || targetH != src.height) {
            android.graphics.Bitmap.createScaledBitmap(src, targetW, targetH, true)
                .copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        } else {
            src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        }

        lastShotWidth = bmp.width
        lastShotHeight = bmp.height

        val canvas = android.graphics.Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val maxDim = maxOf(w, h)
        val uiScale = (maxDim / 512f).coerceIn(0.65f, 1.8f)

        val stripBg = android.graphics.Paint().apply {
            color = 0xBB000000.toInt()
            style = android.graphics.Paint.Style.FILL
        }
        val tickPaint = android.graphics.Paint().apply {
            color = 0xFFFFEA00.toInt()
            strokeWidth = (1.8f * uiScale).coerceAtLeast(1.2f)
        }
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFEA00.toInt()
            textSize = (10.5f * uiScale).coerceIn(8f, 18f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val stripThickness = textPaint.textSize + (4f * uiScale)
        // Top X-axis border bar & Left Y-axis border bar (only 12-18px wide at the very edge)
        canvas.drawRect(0f, 0f, w, stripThickness, stripBg)
        canvas.drawRect(0f, 0f, stripThickness * 1.35f, h, stripBg)

        for (i in 1..9) {
            val frac = i / 10f
            val label = ".${i}"
            val px = w * frac
            val py = h * frac

            // Top edge X tick
            canvas.drawLine(px, 0f, px, stripThickness, tickPaint)
            val tw = textPaint.measureText(label)
            canvas.drawText(label, (px - tw / 2f).coerceIn(2f, w - tw - 2f), stripThickness - 2f, textPaint)

            // Left edge Y tick
            canvas.drawLine(0f, py, stripThickness * 1.35f, py, tickPaint)
            canvas.drawText(label, 2f, (py + textPaint.textSize / 3f).coerceIn(textPaint.textSize, h - 2f), textPaint)
        }

        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, jpegQuality, out)
        return out.toByteArray()
    }

    private suspend fun askModel(
        context: Context,
        goal: String,
        pkg: String,
        items: List<PhoneControl.UiItem>,
        log: List<String>,
        shotBytes: ByteArray?,
        stateKey: String = "",
        ocrSummary: String = "",
        frameAnalysis: AgentVisionHelper.FrameAnalysis? = null
    ): String? {
        val lastLog = log.lastOrNull() ?: ""
        val strictInstruction = if (lastLog.contains("HARD BLOCKED")) {
            "LAST ACTION FAILED. Do not tap that cell again. Look for a different button.\n"
        } else ""

        val (sw, sh) = PhoneControl.orientedScreenSize(lastShotWidth, lastShotHeight)
        val ocrLines = TextFinder.formatForPrompt(shotBytes, sw, sh)
        val screenElements = items.take(25).joinToString("\n") { item ->
            val nx = String.format(java.util.Locale.US, "%.2f", item.cx.toFloat() / sw.coerceAtLeast(1))
            val ny = String.format(java.util.Locale.US, "%.2f", item.cy.toFloat() / sh.coerceAtLeast(1))
            "[${item.id}] ${item.kind} \"${item.label}\" at (x=$nx, y=$ny)"
        }

        val reply = if (useApi(context)) {
            val prompt = "TASK: $goal\n" +
                    "SCREEN SIZE: ${sw}x${sh}\n" +
                    "SCREENSHOT: ${if (shotBytes != null) "attached" else "none, use element ids"}\n" +
                    "CONTROL REGIONS: ${ControlRegions.names(context).ifEmpty { "none calibrated" }}\n" +
                    (if (strictInstruction.isNotBlank()) strictInstruction else "") +
                    "STEPS SO FAR: ${if (log.isEmpty()) "none" else log.takeLast(8).joinToString(" > ")}\n" +
                    "FOREGROUND APP: $pkg\n" +
                    "OCR TEXT ON SCREEN:\n$ocrLines\n" +
                    "SCREEN ELEMENTS:\n${screenElements.ifEmpty { "(nothing readable, use the screenshot)" }}"

            LlmRouter.complete(
                context,
                LlmRequest(prompt = prompt, systemPrompt = SYSTEM, history = emptyList(), imageBytes = shotBytes)
            ).getOrNull()
        } else {
            val openAppNote = if (pkg != "game_surface" && pkg != "com.nothing.launcher" && pkg != "none" && pkg.isNotBlank()) {
                "NOTE: APP '$pkg' IS ALREADY OPEN ON YOUR SCREEN. Do NOT use OPEN. Choose a TAP, TAP_TEXT, or DRAG action to interact with the screen!"
            } else ""

            val memoryBlock = if (stateKey.isNotBlank()) AgentMemory.getMemoryPromptBlock(context, stateKey) else ""
            val prompt = "GOAL: $goal\n" +
                    "APP: $pkg\n" +
                    "OCR TEXT ON SCREEN:\n$ocrLines\n" +
                    "SCREEN ELEMENTS:\n${screenElements.ifEmpty { "none" }}\n" +
                    "HOTSPOTS: yellow=${frameAnalysis?.yellowTargetCells.orEmpty()}, motion=${frameAnalysis?.motionCells.orEmpty()}\n" +
                    (if (openAppNote.isNotBlank()) "$openAppNote\n" else "") +
                    (if (memoryBlock.isNotBlank()) "$memoryBlock\n" else "") +
                    (if (strictInstruction.isNotBlank()) strictInstruction else "") +
                    "STEPS SO FAR: ${if (log.isEmpty()) "none" else log.takeLast(4).joinToString(" > ")}"

            OnDeviceAiClient.generateResponse(context, prompt, shotBytes, systemOverride = SYSTEM_LOCAL, maxTokensOverride = 48).getOrNull()
        }
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
        val sentTexts = HashSet<String>()
        var previousCellHashes: LongArray? = null
        val deadClickCounts = mutableMapOf<String, Int>()

        chatVisible(false)
        CapriCursor.show(context)
        delay(250)
        var plan: String? = null

        try {
            // Turn off the active DevOverlay grid drawing so the screenshot is clean
            DevOverlay.setEnabled(context, false)
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            
            // Differentiate between "play music" and "play game" goals
            val isGameRequest = Regex("\\b(game|screen|win|beat|world|wood|minecraft|pvz)\\b", RegexOption.IGNORE_CASE).containsMatchIn(goal)
            val isMusicGoal = !isGameRequest && Regex("\\b(play|music|song|track|audio|listen|spotify)\\b", RegexOption.IGNORE_CASE).containsMatchIn(goal)

            while (!CapriCursor.cancelled) {
                if (isMusicGoal && audioManager?.isMusicActive == true && steps > 0) {
                    log.add("music is actively playing on device")
                    return "playing music, choom."
                }

                var root = PhoneControl.foregroundRoot()
                var rawBytes = service.captureScreenBitmap()

                if (root == null && rawBytes == null) {
                    repeat(3) {
                        delay(250)
                        root = PhoneControl.foregroundRoot()
                        rawBytes = service.captureScreenBitmap()
                        if (root != null || rawBytes != null) return@repeat
                    }
                }
                if (root == null && rawBytes == null) {
                    return "i can't see an app or screen surface to work in, choom. ${summary(log)}"
                }

                val pkg = root?.packageName?.toString() ?: "game_surface"
                if (pkg in BLOCKED_PACKAGES) return "i keep my hands off that app, choom."
                val items = if (root != null) PhoneControl.snapshot(root) else emptyList()

                val frameAnalysis = if (rawBytes != null) {
                    AgentVisionHelper.processFrame(context, rawBytes, previousCellHashes)
                } else null
                previousCellHashes = frameAnalysis?.cellHashes
                val frameHash = frameAnalysis?.screenHash ?: 0L

                val ocrLines = if (rawBytes != null && items.size < 5) {
                    try { TextFinder.listLines(rawBytes) } catch (_: Exception) { emptyList() }
                } else emptyList()
                val ocrSummary = ocrLines.take(6).joinToString(", ")

                val stateKey = AgentMemory.buildStateKey(pkg, ocrSummary, frameHash)

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
                    val shotBytes = annotate(context, rawBytes) ?: rawBytes

                    val reply = askModel(context, goal, pkg, items, log, shotBytes, stateKey, ocrSummary, frameAnalysis)

                    if (reply == null) {
                        log.add("brain unresponsive, retrying")
                        delay(500)
                        continue
                    }

                    val (sw, sh) = PhoneControl.screenSize() ?: (lastShotWidth to lastShotHeight)
                    chosen = parseAct(reply, sw, sh)
                    Log.d(TAG, "raw reply: ${reply.take(300)}")
                    if (chosen == null) {
                        Log.w(TAG, "unreadable reply: ${reply.take(120)}")
                        log.add("unreadable reply")
                        steps++
                        continue
                    }
                }

                var a: Act = chosen
                val (sw, sh) = PhoneControl.screenSize() ?: (lastShotWidth to lastShotHeight)

                val actionCmd = when (a.doWhat) {
                    "tap" -> if (a.label != null) "TAP ${a.label}" else "TAP (${a.x},${a.y})"
                    "seq" -> "SEQ (${a.x},${a.y})->(${a.x2},${a.y2})"
                    "drag", "swipe" -> "DRAG (${a.x},${a.y})->(${a.x2},${a.y2})"
                    "hold" -> if (a.label != null) "HOLD ${a.label}" else "HOLD (${a.x},${a.y})"
                    "tap_text" -> "TAP_TEXT ${a.text}"
                    else -> "${a.doWhat.uppercase()} ${a.label ?: ""}".trim()
                }

                val currentFails = deadClickCounts[actionCmd] ?: 0

                // Never give up: Blacklist guardrail intercept with micro-offset corner shift
                if (AgentMemory.isBlacklisted(context, stateKey, actionCmd)) {
                    log.add("blacklisted move intercepted: $actionCmd")
                    
                    if (currentFails >= 2) {
                        log.add("HARD BLOCKED: $actionCmd failed 2 times. Force LLM to pick different target.")
                        steps++
                        continue
                    }
                    
                    var redirected = false

                    if (rawBytes != null) {
                        val keywords = listOf("continue", "resume", "play", "start", "next", "ok", "create", "confirm")
                        for (kw in keywords) {
                            if (goal.contains(kw, ignoreCase = true) || ocrLines.any { it.contains(kw, ignoreCase = true) }) {
                                val hit = try { TextFinder.find(rawBytes, sw, sh, kw) } catch (_: Exception) { null }
                                if (hit != null && !AgentMemory.isBlacklisted(context, stateKey, "TAP (${hit.x},${hit.y})")) {
                                    a = Act("tap", null, null, null, null, null, hit.x, hit.y, null, null, label = "OCR_${hit.text}")
                                    log.add("redirected to clean OCR match \"${hit.text}\" at (${hit.x}, ${hit.y})")
                                    redirected = true
                                    break
                                }
                            }
                        }
                    }

                    if (!redirected) {
                        val yellowCell = frameAnalysis?.yellowTargetCells?.firstOrNull { cell ->
                            !AgentMemory.isBlacklisted(context, stateKey, "TAP $cell")
                        }
                        if (yellowCell != null) {
                            val pt = GridSpec.centre(yellowCell, sw, sh)
                            if (pt != null) {
                                a = Act("tap", null, null, null, null, null, pt.first, pt.second, null, null, label = yellowCell)
                                log.add("redirected to yellow target cell $yellowCell")
                                redirected = true
                            }
                        }
                    }

                    if (!redirected && a.x != null && a.y != null) {
                        val shiftCount = (steps % 5) + 1
                        val (offX, offY) = when (shiftCount) {
                            1 -> Pair(0, 55)    
                            2 -> Pair(0, -55)   
                            3 -> Pair(55, 55)   
                            4 -> Pair(-55, 55)  
                            else -> Pair(55, 0) 
                        }
                        val shiftedX = (a.x + offX).coerceIn(0, sw - 1)
                        val shiftedY = (a.y + offY).coerceIn(0, sh - 1)
                        a = Act("tap", a.id, a.text, a.dir, a.app, a.say, shiftedX, shiftedY, a.x2, a.y2, label = a.label)
                        log.add("shifted tap coordinate by ($offX, $offY) to ($shiftedX, $shiftedY)")
                    }
                }

                // OCR Word Snapping on clean unstamped rawBytes
                if (a.doWhat == "tap" && a.id == null && a.text.isNullOrBlank() && rawBytes != null) {
                    val commonButtonWords = listOf("continue", "resume", "play", "start", "next", "ok", "create", "confirm")
                    for (word in commonButtonWords) {
                        if (goal.contains(word, ignoreCase = true) || ocrLines.any { it.contains(word, ignoreCase = true) }) {
                            val hit = try { TextFinder.find(rawBytes, sw, sh, word) } catch (_: Exception) { null }
                            if (hit != null) {
                                val (hx, hy) = PhoneControl.clampToScreen(hit.x, hit.y)
                                a = Act("tap", a.id, hit.text, a.dir, a.app, a.say, hx, hy, a.x2, a.y2, label = "OCR_${hit.text}")
                                log.add("auto-snapped cell tap to exact OCR word \"${hit.text}\" at ($hx, $hy)")
                                break
                            }
                        }
                    }
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

                val beforeSig = screenSignature(root, items, frameHash)

                when (a.doWhat) {
                    "done" -> return a.say ?: "done."
                    "fail" -> return a.say ?: "couldn't do that, choom."
                    "seq" -> {
                        val fx = a.x; val fy = a.y; val tx = a.x2; val ty = a.y2
                        if (fx == null || fy == null || tx == null || ty == null) {
                            log.add("seq: missing coordinates")
                        } else {
                            CapriCursor.setStatus("sequence tap 1...")
                            CapriCursor.moveTo(fx, fy)
                            CapriCursor.pulse()
                            val ok1 = PhoneControl.tapAt(fx, fy)
                            delay(180)
                            CapriCursor.setStatus("sequence tap 2...")
                            CapriCursor.moveTo(tx, ty)
                            CapriCursor.pulse()
                            val ok2 = PhoneControl.tapAt(tx, ty)
                            log.add("executed seq ($fx,$fy) -> ($tx,$ty)" + if (ok1 && ok2) "" else " (partial failure)")
                        }
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
                            item != null -> {
                                CapriCursor.moveTo(item.cx, item.cy)
                                CapriCursor.pulse()
                                PhoneControl.longPress(item)
                            }
                            a.x != null && a.y != null -> {
                                val (cx, cy) = PhoneControl.clampToScreen(a.x, a.y)
                                CapriCursor.moveTo(cx, cy)
                                CapriCursor.pulse()
                                PhoneControl.longPressAt(cx, cy)
                            }
                            else -> false
                        }
                        log.add("long pressed" + if (ok) "" else " (failed)")
                    }
                    "tap" -> {
                        CapriCursor.setStatus("tapping...")
                        val matchedItem = when {
                            a.id != null -> items.firstOrNull { it.id == a.id }
                            !a.text.isNullOrBlank() -> items.firstOrNull { it.label.contains(a.text, ignoreCase = true) }
                            a.label != null && !a.label.matches(Regex("^[A-Z][0-9]{1,2}$", RegexOption.IGNORE_CASE)) ->
                                items.firstOrNull { it.label.contains(a.label, ignoreCase = true) }
                            else -> null
                        }

                        if (matchedItem != null) {
                            if (rp == null && RISKY.containsMatchIn(matchedItem.label)) {
                                pending = Pending(goal, "tap", matchedItem.label, null, log.toList(), steps)
                                return "about to tap \"${matchedItem.label}\". go ahead? (yes/no)"
                            }
                            CapriCursor.moveTo(matchedItem.cx, matchedItem.cy)
                            CapriCursor.pulse()
                            val ok = PhoneControl.tap(matchedItem)
                            log.add("tapped element #${matchedItem.id} \"${matchedItem.label}\" at (${matchedItem.cx}, ${matchedItem.cy})" + if (ok) "" else " (failed)")
                        } else if (a.x != null && a.y != null) {
                            val (cx, cy) = PhoneControl.clampToScreen(a.x, a.y)
                            CapriCursor.moveTo(cx, cy)
                            CapriCursor.pulse()
                            val ok = PhoneControl.tapAt(cx, cy)
                            log.add("tapped exact ($cx, $cy)" + if (ok) "" else " (failed)")
                        } else {
                            log.add("tap: unknown element")
                        }
                    }
                    "type" -> {
                        CapriCursor.setStatus("typing...")
                        val item = items.firstOrNull { it.id == a.id }
                        val text = a.text
                        if (item == null || text.isNullOrBlank()) {
                            log.add("type: bad target")
                        } else if (!sentTexts.add(text.lowercase())) {
                            log.add("refused to repeat text already typed")
                        } else {
                            val submit = goal.contains(text, ignoreCase = true)
                            val ok = PhoneControl.typeInto(context, item, text, submit)
                            log.add("typed into ${item.label}" + (if (submit) " and sent" else " (not sent)") + if (ok) "" else " (failed)")
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
                            else -> null
                        }
                        if (dir != null) {
                            if (a.x != null && a.y != null) {
                                PhoneControl.scrollAt(a.x, a.y, dir)
                                log.add("scrolled ${a.dir} inside ${a.label ?: "cell"}")
                            } else {
                                PhoneControl.scroll(dir)
                                log.add("scrolled ${a.dir}")
                            }
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
                        val targetApp = a.app.orEmpty().lowercase()
                        val currentPkg = pkg.lowercase()
                        val userRequestedOpen = Regex("\\b(open|launch|start|go to|run|play)\\b", RegexOption.IGNORE_CASE).containsMatchIn(goal)
                        val isAlreadyOpen = currentPkg != "game_surface" && currentPkg != "com.nothing.launcher" && currentPkg != "none" &&
                                (currentPkg.contains(targetApp) || targetApp.contains(currentPkg) || targetApp.contains("game"))

                        if (!userRequestedOpen) {
                            log.add("REJECTED OPEN '${a.app}': User did NOT request opening a new app!")
                        } else if (isAlreadyOpen && targetApp.isNotBlank()) {
                            log.add("app '$pkg' is ALREADY open on screen, proceeding directly to screen controls")
                        } else {
                            val ok = IntentExecutor.execute(context, Cmd.Open(a.app.orEmpty()))
                            if (ok) {
                                log.add("opened ${a.app}")
                                delay(500)
                            } else {
                                log.add("failed to open ${a.app}")
                                AgentMemory.recordOutcome(context, stateKey, "OPEN ${a.app}", screenChanged = false)
                            }
                        }
                    }
                    "double_tap" -> {
                        CapriCursor.setStatus("double tapping...")
                        if (a.x != null && a.y != null) {
                            CapriCursor.moveTo(a.x, a.y)
                            CapriCursor.pulse()
                            val ok = PhoneControl.doubleTapAt(a.x, a.y)
                            log.add("double tapped (${a.x}, ${a.y})" + if (ok) "" else " (failed)")
                        } else {
                            val item = items.firstOrNull { it.id == a.id }
                            if (item != null) {
                                val ok = PhoneControl.doubleTap(item)
                                log.add("double tapped ${item.label}" + if (ok) "" else " (failed)")
                            }
                        }
                    }
                    "hold" -> {
                        CapriCursor.setStatus("holding...")
                        val duration = a.durationMs ?: 3000L
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
                        if (p != null) {
                            val ms = (a.durationMs ?: 300L).coerceIn(50L, 120_000L)
                            val ok = PhoneControl.holdAt(p.first, p.second, ms)
                            log.add("pressed ${a.region} for ${ms}ms" + if (ok) "" else " (failed)")
                        }
                    }
                    "drag" -> {
                        val fx = a.x; val fy = a.y; val tx = a.x2; val ty = a.y2
                        if (fx != null && fy != null && tx != null && ty != null) {
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
                        val shotBytes = frameAnalysis?.jpegBytes ?: service.captureScreenBitmap()
                        val size = PhoneControl.screenSize()
                        val hit = if (shotBytes != null && size != null && wanted.isNotBlank())
                            TextFinder.find(shotBytes, size.first, size.second, wanted)
                        else null
                        if (hit == null) {
                            log.add("tap_text: \"$wanted\" not found")
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
                if (items.size < 5) delay(400)

                val isContinuousAction = a.doWhat in setOf("hold", "long_press", "swipe", "scroll", "multitap", "multi_touch", "wait", "press_region", "look", "drag", "seq")
                val changed = waitForChange(beforeSig)
                val statusTag = if (changed) "(worked/screen updated)" else if (isContinuousAction) "(in progress)" else "(failed/no change)"

                if (!changed && !isContinuousAction) {
                    deadClickCounts[actionCmd] = (deadClickCounts[actionCmd] ?: 0) + 1
                } else if (changed) {
                    deadClickCounts.clear()
                }

                if (log.isNotEmpty()) {
                    val lastIdx = log.lastIndex
                    if (!log[lastIdx].contains("(worked") && !log[lastIdx].contains("(failed") && !log[lastIdx].contains("(in progress)")) {
                        log[lastIdx] = "${log[lastIdx]} $statusTag"
                    }
                }

                // Outcome Learning per screen signature
                AgentMemory.recordOutcome(context, stateKey, actionCmd, screenChanged = (changed || isContinuousAction))
            }
            return "stopped as requested, choom. ${summary(log)}"
        } finally {
            CapriCursor.hide()
            chatVisible(true)
        }
    }
}
