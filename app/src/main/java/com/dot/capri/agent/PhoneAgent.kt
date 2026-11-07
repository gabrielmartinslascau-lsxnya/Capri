package com.dot.capri.agent

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.dot.capri.CapriAccessibilityService
import com.dot.capri.MainActivity
import com.dot.capri.ai.OnDeviceAiClient
import com.dot.capri.intents.AppChooser
import com.dot.capri.intents.IntentExecutor
import com.dot.capri.llm.LlmRequest
import com.dot.capri.llm.LlmRouter
import com.dot.capri.util.NetworkUtils
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.hypot

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

    private val AD_OR_STORE_PACKAGES = setOf(
        "com.android.vending",
        "com.google.android.gms",
        "com.android.chrome",
        "com.sec.android.app.sbrowser"
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
        val id: Int? = null,
        val text: String? = null,
        val dir: String? = null,
        val app: String? = null,
        val say: String? = null,
        val x: Int? = null,
        val y: Int? = null,
        val x2: Int? = null,
        val y2: Int? = null,
        val label: String? = null,
        val plan: String? = null,
        val durationMs: Long? = null,
        val region: String? = null,
        val holdMs: Long? = null,
        val dx: Int? = null,
        val dy: Int? = null,
        val undoReason: String? = null,
        val contact: String? = null
    )

    private data class ExecutedTurn(
        val stateKey: String,
        val pkg: String,
        val actionCmd: String,
        val move: MoveMemory.Move,
        val isGameMode: Boolean,
        val placedNormX: Float? = null,
        val placedNormY: Float? = null
    )

    private val SYSTEM_WITH_MBS = """
You are Capri, an autonomous AI agent controlling an Android device.
The user has placed MACRO BUTTONS (MB1, MB2, ...) on screen for fixed controls (e.g. walk, mine, jump, inventory, finish crafting, confirm).
You work in ADAPTIVE HYBRID MODE:
- Use MACRO BUTTONS (`press_region`) for any control that has an MB (walk, mine, jump, open inventory, finish crafting, card slots, confirm).
- Use VISUAL SCREEN COORDINATES (`tap`, `seq`, `drag`, or `x,y -> MB`) whenever you need to click something inside the screenshot that does NOT have its own MB — such as item icons in an inventory or shop, crafting recipe icons, grid slots, chests, cards, or dialog buttons!

Actions (reply with ONLY a single JSON object, always including your updated "plan"):
{"do":"press_region","region":"mb1","plan":"Tap MB1 (e.g. open inventory or jump)"}
  -> Taps Macro Button MB1 immediately.
{"do":"press_region","region":"mb1","duration_ms":4700,"plan":"Hold MB1 for 4700ms to break target block"}
  -> Holds Macro Button MB1 for duration_ms milliseconds (e.g. ~4500ms for breaking/mining, ~1000ms for soft blocks, ~2000ms for walking).
{"do":"press_region","x":0.28,"y":0.45,"region":"mb5","plan":"Tap target item icon at (0.28,0.45) then tap MB5"}
  -> Taps visual item at (x, y) on screen FIRST, then taps Macro Button MB5 (e.g. select recipe/item icon, then tap confirm/craft MB).
{"do":"press_region","region":"mb1","x2":0.45,"y2":0.55,"plan":"Select MB1 and place/use at (0.45, 0.55)"}
  -> Taps MB1 first, then immediately taps target coordinate (x2, y2) on screen.
{"do":"tap","x":0.32,"y":0.48,"plan":"Visually identified target item / recipe icon in screenshot -> tap it"}
  -> Taps normalized coordinate (x, y) directly when clicking an in-game item icon, recipe, card, or slot that has no MB.
{"do":"seq","x":0.32,"y":0.48,"x2":0.78,"y2":0.45,"plan":"Tap item at (x,y) then tap target slot at (x2,y2)"}
  -> Taps (x, y) first, then taps (x2, y2) on screen (ideal for selecting an inventory/shop item and clicking the slot when there is no confirm MB).
{"do":"drag","x":0.32,"y":0.48,"x2":0.65,"y2":0.38,"hold_ms":150,"duration_ms":280,"plan":"Drag item into target slot"}
{"do":"swipe","x":0.70,"y":0.50,"x2":0.30,"y2":0.40,"duration_ms":280,"plan":"Swipe camera diagonally to look around"}
{"do":"joystick","dir":"forward","duration_ms":2000,"plan":"Deflect virtual joystick forward for 2000ms"}
  -> Operates a virtual movement joystick: touches center (from an MB or default bottom-left), drags outward in direction ("forward", "back", "left", "right"), and holds for duration_ms before releasing.
{"do":"look","dir":"up","plan":"Look up at next part of target in 3D"}
  -> Turns 3D camera in direction ("up", "down", "left", "right", "up_left", "up_right", "down_left", "down_right", "around") OR pass custom pixel offsets ("dx":180, "dy":-120).
{"do":"tap_text","text":"Play","plan":"Tap visible text button"}
{"do":"tap","id":N,"plan":"Tap UI element N"}
{"do":"call","contact":"name or number","plan":"Open dialer for contact, then tap Call button on screen"}
{"do":"text","contact":"name","text":"message body","plan":"Open contact's chat with prefilled message, then tap Send on screen"}
{"do":"search","text":"search query","plan":"Launch Google search and inspect/click results on screen"}
{"do":"play","text":"song or video","app":"spotify","plan":"Launch media search and tap the top result/Play button on screen"}
{"do":"volume","dir":"up|down|mute|unmute","plan":"Adjust device volume"}
{"do":"media","dir":"play|pause|next|previous","plan":"Control active media playback"}
{"do":"power_menu","plan":"Open system power menu on screen"}
{"do":"back","undo":"opened wrong menu","plan":"Close unintended popup and blacklist last move"}
{"do":"wait","plan":"Wait for loading"}
{"do":"done","say":"short in-character tsundere remark confirming what you did (e.g. 'there, i started your liked songs. don't get used to me spoiling you.' or 'done. next time tap it yourself, dummy.')"}
{"do":"fail","say":"short in-character explanation of why the screen blocked you"}

CRITICAL ADAPTIVE, CONTEXT & TIMING RULES:
1. EVALUATE CONTEXT FIRST (HAVE I COMPLETED IT? DID I FAIL? DO I CONTINUE?):
   - Before every action, look at the screenshot and STEPS SO FAR:
   - Have I ALREADY completed the task (or the current phase of a multi-step task)? If the entire TASK is finished, output `{"do":"done","say":"..."}` IMMEDIATELY! Do NOT keep clicking the same button just because the screen changed!
2. ADAPTIVE INVENTORY / CRAFTING / MENU (MIXING MBs + VISUAL ICONS):
   - If asked to craft, make, buy, or equip something and the menu/inventory is NOT open yet, tap your `inventory`/`menu` MB first!
   - Once the menu/inventory screen IS open on the screenshot, DO NOT press `walk`, `mine`, `jump`, or `inventory` again! Instead, LOOK at the image to visually recognize the item/recipe/card icons:
     * If you have a "finish crafting" / "confirm" / "craft" MB, use `{"do":"press_region","x":<icon_x>,"y":<icon_y>,"region":"<confirm_mb>"}` to click the recipe/item icon at `(x,y)` and then tap the MB!
     * If you only have gameplay MBs, use `{"do":"tap","x":<icon_x>,"y":<icon_y>}` or `{"do":"seq","x":<item_x>,"y":<item_y>,"x2":<output_x>,"y2":<output_y>}` to click the visual icons directly by their `(x, y)` coordinates in the screenshot!
3. SMART HOLD DURATION: When holding a gameplay MB (like mining, breaking, or walking), ALWAYS pass `duration_ms` (e.g. ~4500ms for breaking/mining, ~1000ms for soft blocks, ~2000ms for walking).
4. LOOKING AROUND IN 3D: For multi-block 3D tasks, after mining/breaking a block (4500ms), use `{"do":"look","dir":"up|down|left|right|around"}` to aim at the remaining blocks.
5. RESPECT BLACKLISTED MOVES: Never repeat any move in BLACKLISTED NO-EFFECT MOVES or BLACKLISTED WRONG-OUTCOME MOVES.
""".trimIndent()

    private val SYSTEM_NO_MBS = """
You are Capri, an autonomous AI agent controlling an Android phone across apps and games.
No Macro Buttons are currently placed, so you use normal screen coordinates (0.00..1.00), UI element IDs, OCR text, and your spatial memory.

Actions (reply with ONLY a single JSON object, always including your updated "plan"):
{"do":"tap","id":N,"plan":"Tap UI element N from SCREEN ELEMENTS"}
{"do":"tap_text","text":"Play","plan":"Tap visible button text from OCR TEXT ON SCREEN"}
{"do":"tap","x":0.52,"y":0.64,"plan":"Tap normalized coordinate (0.00..1.00)"}
{"do":"seq","x":0.18,"y":0.10,"x2":0.34,"y2":0.41,"plan":"Tap item at (x,y) then place at target (x2,y2)"}
{"do":"drag","x":0.18,"y":0.10,"x2":0.34,"y2":0.41,"hold_ms":120,"duration_ms":260,"plan":"Drag from (x,y) to (x2,y2)"}
{"do":"swipe","x":0.70,"y":0.50,"x2":0.30,"y2":0.45,"duration_ms":260,"plan":"Swipe screen / rotate camera"}
{"do":"hold","x":0.50,"y":0.50,"duration_ms":4700,"plan":"Hold at (x,y) for duration_ms"}
{"do":"look","dir":"up","plan":"Turn 3D camera up/down/left/right/around (or pass dx, dy)"}
{"do":"type","id":N,"text":"...","plan":"Enter text"}
{"do":"scroll","dir":"down","plan":"Scroll list"}
{"do":"open","app":"app name","plan":"Open requested app"}
{"do":"call","contact":"name or number","plan":"Open dialer for contact, then tap Call button on screen"}
{"do":"text","contact":"name","text":"message body","plan":"Open contact's chat with prefilled message, then tap Send on screen"}
{"do":"search","text":"search query","plan":"Launch Google search and inspect/click results on screen"}
{"do":"play","text":"song or video","app":"spotify","plan":"Launch media search and tap the top result/Play button on screen"}
{"do":"volume","dir":"up|down|mute|unmute","plan":"Adjust device volume"}
{"do":"media","dir":"play|pause|next|previous","plan":"Control active media playback"}
{"do":"power_menu","plan":"Open system power menu on screen"}
{"do":"back","undo":"opened wrong screen","plan":"Undo unintended navigation and blacklist last move"}
{"do":"home","plan":"Go home"}
{"do":"wait","plan":"Wait for loading screen"}
{"do":"done","say":"short in-character tsundere remark confirming what you did (e.g. 'there, i opened your playlist. don't get used to me spoiling you.' or 'done. next time tap it yourself, dummy.')"}
{"do":"fail","say":"short in-character explanation of why the screen blocked you"}

CRITICAL RULES:
1. EVALUATE CONTEXT FIRST: Check the screenshot and STEPS SO FAR — have you ALREADY completed the task or current sub-step? If the goal is achieved, output `{"do":"done","say":"..."}` immediately instead of repeating clicks! If you are already looking at the target, perform the action (`hold` or `press_region`) directly.
2. HIERARCHICAL NAVIGATION: In apps, prefer `{"do":"tap","id":N}` from SCREEN ELEMENTS or `{"do":"tap_text","text":"..."}` from OCR TEXT.
3. SMART TIMING & LOOKING AROUND: Use `hold` with an appropriate `duration_ms` for timed actions (e.g. ~4500ms for hard blocks, ~1000ms for soft blocks) and `{"do":"look","dir":"up|down|left|right|around"}` or `swipe` to rotate the 3D camera.
4. RESPECT BLACKLISTED MOVES: Never repeat any move listed in BLACKLISTED NO-EFFECT MOVES or BLACKLISTED WRONG-OUTCOME MOVES.
5. NEVER tap a "STOP" button unless the user explicitly requested it.
""".trimIndent()

    private val SYSTEM_LOCAL_WITH_MBS = """
You control an Android phone in ADAPTIVE HYBRID MODE: use MACRO BUTTONS (MB1, MB2, ...) for fixed controls, and use VISUAL (x, y) coordinates when clicking item icons, recipes, or slots in the screenshot that do NOT have an MB!
Reply with ONLY ONE line (command + | PLAN: short strategy):
MB1 | PLAN: <strategy>                       (taps Macro Button MB1, MB2, etc., e.g. inventory or jump)
MB1 <ms> | PLAN: <strategy>                  (holds MB1 for <ms> milliseconds, e.g. MB1 4500 for mining, MB1 2000 for walk)
TAP <x> <y> MB1 | PLAN: <strategy>           (taps visual icon at x,y on screen FIRST, then taps MB1 e.g. confirm/craft)
MB1 <x> <y> | PLAN: <strategy>               (taps MB1 first, then taps screen target x,y in 0.00..1.00)
JOYSTICK <FORWARD|BACK|LEFT|RIGHT> <ms> | PLAN: <strategy> (operates virtual movement joystick)
TAP <x> <y> | PLAN: <strategy>               (taps visual item/icon/slot at 0.00..1.00 coordinate when no MB covers it)
SEQ <x1> <y1> <x2> <y2> | PLAN: <strategy>   (taps visual icon at x1,y1 then taps slot/output at x2,y2)
LOOK <UP|DOWN|LEFT|RIGHT|AROUND> | PLAN: <strategy> (turns 3D camera to look up/down/left/right/around)
SWIPE <x1> <y1> <x2> <y2> | PLAN: <strategy> (swipes screen / rotates camera in any direction)
TAP_TEXT <words> | PLAN: <strategy>          (taps visible text button)
TAP_ID <id> | PLAN: <strategy>               (taps UI element #id)
CALL <contact> | PLAN: <strategy>            (opens dialer for contact)
TEXT <contact> : <message> | PLAN: <strategy> (opens chat with message)
SEARCH <query> | PLAN: <strategy>            (opens web search)
PLAY <query> | PLAN: <strategy>              (opens media query)
VOLUME <up|down|mute|unmute> | PLAN: <strategy>
MEDIA <play|pause|next|previous> | PLAN: <strategy>
POWER_MENU | PLAN: <strategy>
BACK | UNDO: <why last move was wrong>
WAIT
DONE

Rules:
- FIRST check the screenshot and STEPS SO FAR: (1) Have I ALREADY completed the task? -> Reply DONE immediately! (2) If crafting/buying/selecting an item: tap your menu/inventory MB first to open the screen. Once open, DO NOT tap movement/action MBs again — look at the image to locate the target item/recipe icon and use `TAP <x> <y> <confirm_MB>` or `SEQ <x1> <y1> <x2> <y2>` or `TAP <x> <y>`! (3) For multi-target 3D tasks: alternate action (4500ms) with LOOK UP / LOOK DOWN / LOOK AROUND. (4) For joystick games: use JOYSTICK FORWARD <ms>.
- ALWAYS include <ms> when mining/breaking/walking (e.g. MB1 4500 for breaking, MB1 1000 for soft blocks, MB1 2000 for walking).
- Never repeat any move in BLACKLISTED NO-EFFECT MOVES or BLACKLISTED WRONG-OUTCOME MOVES.
""".trimIndent()

    private val SYSTEM_LOCAL_NO_MBS = """
You control an Android phone using screen coordinates, UI elements, and OCR text.
Reply with ONLY ONE line (command + | PLAN: short strategy):
TAP_ID <id> | PLAN: <strategy>               (taps UI element #id from SCREEN ELEMENTS)
TAP_TEXT <words> | PLAN: <strategy>          (taps visible words from OCR TEXT)
JOYSTICK <FORWARD|BACK|LEFT|RIGHT> <ms> | PLAN: <strategy> (operates virtual movement joystick)
TAP <x> <y> | PLAN: <strategy>               (taps 0.00..1.00 coordinate)
HOLD <x> <y> <ms> | PLAN: <strategy>         (holds x,y for <ms> milliseconds, e.g. 4500 for mining, 1000 for soft blocks)
SEQ <x1> <y1> <x2> <y2> | PLAN: <strategy>   (taps x1,y1 then taps x2,y2)
LOOK <UP|DOWN|LEFT|RIGHT|AROUND> | PLAN: <strategy> (turns 3D camera, or LOOK <dx> <dy>)
SWIPE <x1> <y1> <x2> <y2> | PLAN: <strategy> (swipes from x1,y1 to x2,y2)
TYPE <id> <text> | PLAN: <strategy>          (types into field #id)
SCROLL <down|up|left|right> | PLAN: <strategy>
CALL <contact> | PLAN: <strategy>            (opens dialer for contact)
TEXT <contact> : <message> | PLAN: <strategy> (opens chat with message)
SEARCH <query> | PLAN: <strategy>            (opens web search)
PLAY <query> | PLAN: <strategy>              (opens media query)
VOLUME <up|down|mute|unmute> | PLAN: <strategy>
MEDIA <play|pause|next|previous> | PLAN: <strategy>
POWER_MENU | PLAN: <strategy>
BACK | UNDO: <why last move was wrong>
WAIT
DONE

Rules:
- FIRST check the screenshot and STEPS SO FAR: Have I ALREADY completed the task? If yes, reply DONE immediately! Do NOT repeat the same action just because the screen changed. If looking at a target to mine/break, use HOLD <x> <y> 4500, and use LOOK UP / LOOK DOWN / LOOK LEFT / LOOK RIGHT to aim at more targets or look around.
- Prefer TAP_ID <id> when SCREEN ELEMENTS are listed, or TAP_TEXT <words> when OCR text matches.
- Never repeat any move in BLACKLISTED NO-EFFECT MOVES or BLACKLISTED WRONG-OUTCOME MOVES.
""".trimIndent()

    private fun screenSignature(root: AccessibilityNodeInfo?, items: List<PhoneControl.UiItem>, frameHash: Long = 0L): String {
        val pkg = root?.packageName?.toString() ?: "game_surface"
        return if (items.size >= 3) {
            val labels = items.joinToString("|") { "${it.label}@${it.cx / 40},${it.cy / 40}" }
            "$pkg#${labels.hashCode()}"
        } else {
            "$pkg#hash_$frameHash"
        }
    }

    private fun cellToXY(cell: String, w: Int, h: Int): Pair<Int, Int>? {
        return GridSpec.centre(cell, w, h)
    }

    private fun toScreenCoord(rawVal: Double, shotDim: Int, screenDim: Int): Int {
        val px = when {
            rawVal in 0.0..1.0 -> (rawVal * screenDim).toInt()
            rawVal <= 100.0 && shotDim > 200 -> ((rawVal / 100.0) * screenDim).toInt()
            shotDim > 0 && rawVal <= shotDim -> ((rawVal / shotDim.toDouble()) * screenDim).toInt()
            else -> rawVal.toInt()
        }
        return px.coerceIn(0, (screenDim - 1).coerceAtLeast(1))
    }

    /**
     * Converts a semantic 3D camera direction ("up", "down", "left", "right", "up_left", "up_right",
     * "down_left", "down_right", "around") into screen-scaled pixel offsets (dx, dy) from the right-side look zone.
     * In standard 3D touch controls, dragging the finger UP (negative dy) tilts the camera UP,
     * and dragging RIGHT/LEFT rotates horizontally.
     */
    private fun dirToLookDelta(rawDir: String, sw: Int, sh: Int): Pair<Int, Int> {
        val d = rawDir.lowercase(Locale.US).replace('-', '_').replace(' ', '_').trim()
        val stepX = (sw * 0.18f).toInt().coerceIn(140, 420)
        val stepY = (sh * 0.18f).toInt().coerceIn(120, 320)
        return when {
            d.contains("up") && d.contains("left") -> Pair(-stepX, -stepY)
            d.contains("up") && d.contains("right") -> Pair(stepX, -stepY)
            d.contains("down") && d.contains("left") -> Pair(-stepX, stepY)
            d.contains("down") && d.contains("right") -> Pair(stepX, stepY)
            d == "up" || d == "higher" || d == "above" -> Pair(0, -stepY)
            d == "down" || d == "lower" || d == "below" -> Pair(0, stepY)
            d == "left" -> Pair(-stepX, 0)
            d == "right" -> Pair(stepX, 0)
            d == "around" || d == "turn" || d == "360" -> Pair((sw * 0.28f).toInt().coerceIn(220, 560), 0)
            else -> Pair(stepX, 0)
        }
    }

    private fun parseAct(raw: String, w: Int, h: Int): Act? {
        var cleaned = raw
            .replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```\\s*", RegexOption.IGNORE_CASE), "")
            .trim()

        cleaned = cleaned
            .replace(Regex("^(?:CMD|COMMAND|ACTION|NEXT MOVE):\\s*", RegexOption.IGNORE_CASE), "")
            .trim()

        val (screenW, screenH) = PhoneControl.orientedScreenSize(w, h)

        // 1. Try JSON object parsing
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) {
            try {
                val o = JSONObject(cleaned.substring(start, end + 1))
                val cell1 = o.optString("cell").ifEmpty { null }?.let { cellToXY(it, screenW, screenH) }
                val cell2 = o.optString("cell2").ifEmpty { null }?.let { cellToXY(it, screenW, screenH) }

                val x = when {
                    o.has("x") && !o.isNull("x") -> toScreenCoord(o.optDouble("x", -1.0), w, screenW)
                    else -> cell1?.first
                }
                val y = when {
                    o.has("y") && !o.isNull("y") -> toScreenCoord(o.optDouble("y", -1.0), h, screenH)
                    else -> cell1?.second
                }
                val x2 = when {
                    o.has("x2") && !o.isNull("x2") -> toScreenCoord(o.optDouble("x2", -1.0), w, screenW)
                    else -> cell2?.first
                }
                val y2 = when {
                    o.has("y2") && !o.isNull("y2") -> toScreenCoord(o.optDouble("y2", -1.0), h, screenH)
                    else -> cell2?.second
                }

                val textOrConcept = o.optString("text").ifEmpty { o.optString("query") }.ifEmpty { o.optString("concept") }.ifEmpty { null }
                val contact = o.optString("contact").ifEmpty { null }
                val dur = if (o.has("duration_ms")) o.optLong("duration_ms") else if (o.has("duration")) o.optLong("duration") else null
                val undo = o.optString("undo").ifEmpty { o.optString("wrong") }.ifEmpty { null }

                var doKind = o.optString("do").lowercase(Locale.US).trim()
                if (doKind == "tap_id" || doKind == "click") doKind = "tap"
                if (doKind == "tap_then_mb" || doKind == "coord_then_mb") doKind = "press_region"
                if (doKind == "look_around" || doKind.startsWith("look_")) {
                    val subDir = doKind.removePrefix("look_").ifBlank { "around" }
                    val (ldx, ldy) = dirToLookDelta(subDir, screenW, screenH)
                    return Act(
                        doWhat = "look",
                        id = null, text = null, dir = subDir, app = null, say = null,
                        x = null, y = null, x2 = null, y2 = null,
                        plan = o.optString("plan").ifEmpty { null },
                        durationMs = dur,
                        dx = if (o.has("dx")) o.optInt("dx") else ldx,
                        dy = if (o.has("dy")) o.optInt("dy") else ldy
                    )
                }
                if (doKind.matches(Regex("^mb\\d+$"))) {
                    return Act(
                        doWhat = "press_region",
                        id = null,
                        text = null,
                        dir = null,
                        app = null,
                        say = null,
                        x = x?.coerceIn(0, screenW - 1),
                        y = y?.coerceIn(0, screenH - 1),
                        x2 = x2?.coerceIn(0, screenW - 1),
                        y2 = y2?.coerceIn(0, screenH - 1),
                        label = doKind.uppercase(Locale.US),
                        plan = o.optString("plan").ifEmpty { null },
                        durationMs = dur,
                        region = doKind
                    )
                }

                val rawDir = o.optString("dir").ifEmpty { o.optString("direction") }.ifEmpty { null }
                val (parsedDx, parsedDy) = if (doKind == "look" && !o.has("dx") && !o.has("dy")) {
                    dirToLookDelta(rawDir ?: "around", screenW, screenH)
                } else {
                    Pair(
                        if (o.has("dx")) o.optInt("dx") else null,
                        if (o.has("dy")) o.optInt("dy") else null
                    )
                }

                return Act(
                    doWhat = doKind,
                    id = if (o.has("id")) o.optInt("id") else if (o.has("n")) o.optInt("n") else null,
                    text = textOrConcept,
                    dir = rawDir,
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
                    dx = parsedDx,
                    dy = parsedDy,
                    undoReason = undo,
                    contact = contact
                )
            } catch (_: Exception) {}
        }

        // 2. Single-Line Command Grammar parsing
        val sw = screenW
        val sh = screenH
        val rawFirstLine = cleaned.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

        val extractedPlan = Regex("\\|\\s*PLAN\\s*:\\s*([^|]+)", RegexOption.IGNORE_CASE)
            .find(rawFirstLine)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
        val extractedUndo = Regex("\\|\\s*(?:UNDO|WRONG)\\s*:\\s*([^|]+)", RegexOption.IGNORE_CASE)
            .find(rawFirstLine)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

        val firstLine = rawFirstLine.substringBefore("|").trim()

        if (firstLine.equals("WAIT", ignoreCase = true)) {
            return Act("wait", null, null, null, null, null, null, null, null, null, plan = extractedPlan)
        }
        if (firstLine.equals("BACK", ignoreCase = true) || firstLine.startsWith("UNDO", ignoreCase = true)) {
            val inlineReason = firstLine.substringAfter(" ", "").trim().ifEmpty { null }
            return Act("back", null, null, null, null, null, null, null, null, null, plan = extractedPlan, undoReason = extractedUndo ?: inlineReason ?: "reverted previous step")
        }
        if (firstLine.equals("HOME", ignoreCase = true)) {
            return Act("home", null, null, null, null, null, null, null, null, null, plan = extractedPlan)
        }
        if (firstLine.equals("DONE", ignoreCase = true) || firstLine.startsWith("DONE ", ignoreCase = true)) {
            val msg = firstLine.substringAfter("DONE", "").trim().ifEmpty { "done." }
            return Act("done", null, null, null, null, msg, null, null, null, null, plan = extractedPlan)
        }
        if (firstLine.equals("FAIL", ignoreCase = true) || firstLine.startsWith("FAIL ", ignoreCase = true)) {
            val msg = firstLine.substringAfter("FAIL", "").trim().ifEmpty { "failed." }
            return Act("fail", null, null, null, null, msg, null, null, null, null, plan = extractedPlan)
        }

        val openMatch = Regex("^(?:OPEN|LAUNCH|START)\\s+(.+)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (openMatch != null) {
            val app = openMatch.groupValues[1].trim()
            return Act("open", null, null, null, app, null, null, null, null, null, plan = extractedPlan)
        }

        val typeMatch = Regex("^TYPE\\s+(?:#?(\\d+)\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (typeMatch != null) {
            val id = typeMatch.groupValues[1].toIntOrNull()
            val txt = typeMatch.groupValues[2].trim().removeSurrounding("\"")
            return Act("type", id = id, text = txt, dir = null, app = null, say = null, x = null, y = null, x2 = null, y2 = null, plan = extractedPlan)
        }

        val callMatch = Regex("^CALL\\s+(.+)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (callMatch != null) {
            val who = callMatch.groupValues[1].trim()
            return Act("call", contact = who, plan = extractedPlan)
        }

        val textCmdMatch = Regex("^TEXT\\s+(.+?)(?:\\s*:\\s*(.+)|\\s+(.+))?$", RegexOption.IGNORE_CASE).find(firstLine)
        if (textCmdMatch != null) {
            val who = textCmdMatch.groupValues[1].trim()
            val msg = textCmdMatch.groupValues[2].ifEmpty { textCmdMatch.groupValues[3] }.trim().ifEmpty { null }
            return Act("text", contact = who, text = msg, plan = extractedPlan)
        }

        val searchCmdMatch = Regex("^SEARCH\\s+(.+)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (searchCmdMatch != null) {
            val q = searchCmdMatch.groupValues[1].trim()
            return Act("search", text = q, plan = extractedPlan)
        }

        val playCmdMatch = Regex("^PLAY\\s+(.+)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (playCmdMatch != null) {
            val q = playCmdMatch.groupValues[1].trim()
            return Act("play", text = q, plan = extractedPlan)
        }

        val volCmdMatch = Regex("^VOLUME\\s+(UP|DOWN|MUTE|UNMUTE)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (volCmdMatch != null) {
            val dir = volCmdMatch.groupValues[1].lowercase(Locale.US)
            return Act("volume", dir = dir, plan = extractedPlan)
        }

        val mediaCmdMatch = Regex("^MEDIA\\s+(PLAY|PAUSE|PLAY_PAUSE|NEXT|PREVIOUS|PREV|STOP)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (mediaCmdMatch != null) {
            val act = mediaCmdMatch.groupValues[1].lowercase(Locale.US)
            return Act("media", dir = act, plan = extractedPlan)
        }

        if (firstLine.equals("POWER_MENU", ignoreCase = true) || firstLine.equals("SHUTDOWN", ignoreCase = true) || firstLine.equals("POWER_OFF", ignoreCase = true)) {
            return Act("power_menu", plan = extractedPlan)
        }

        val tapIdMatch = Regex("^(?:TAP_ID|CLICK_ID|(?:TAP|CLICK)\\s+#?)(\\d+)$", RegexOption.IGNORE_CASE).find(firstLine)
            ?: Regex("^(?:TAP_ID|CLICK_ID)\\s+#?(\\d+)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (tapIdMatch != null) {
            val id = tapIdMatch.groupValues[1].toIntOrNull()
            if (id != null) return Act("tap", id = id, text = null, dir = null, app = null, say = null, x = null, y = null, x2 = null, y2 = null, plan = extractedPlan)
        }

        val numPattern = "(\\d+(?:\\.\\d+)?)"

        // Macro Button & Hybrid commands:
        // - "TAP <x> <y> MB1" or "<x> <y> MB1" (tap visual coordinate x,y FIRST, then tap MB1 e.g. finish crafting)
        // - "MB1 <x> <y>" (tap MB1 first, then tap target x,y)
        // - "MB1 <cell>"  (tap MB1 first, then tap grid cell)
        // - "MB1 <ms>"    (hold MB1 for <ms> milliseconds)
        // - "MB1"         (tap MB1)
        val coordThenMbMatch = Regex("^(?:TAP\\s+|CLICK\\s+|TAP_THEN_MB\\s+)?\\(?$numPattern[\\s,]+$numPattern\\)?\\s+(?:->\\s*|THEN\\s+)?(MB\\d+)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (coordThenMbMatch != null) {
            val px1 = toScreenCoord(coordThenMbMatch.groupValues[1].toDouble(), w, sw)
            val py1 = toScreenCoord(coordThenMbMatch.groupValues[2].toDouble(), h, sh)
            val mbName = coordThenMbMatch.groupValues[3].lowercase(Locale.US)
            return Act("press_region", null, null, null, null, null, px1, py1, null, null, region = mbName, label = "(${px1},${py1})->${mbName.uppercase(Locale.US)}", plan = extractedPlan)
        }
        val mbCoordMatch = Regex("^(?:PRESS_REGION\\s+|USE\\s+|TAP\\s+)?(MB\\d+)\\s+$numPattern[\\s,]+$numPattern$", RegexOption.IGNORE_CASE).find(firstLine)
        if (mbCoordMatch != null) {
            val mbName = mbCoordMatch.groupValues[1].lowercase(Locale.US)
            val px2 = toScreenCoord(mbCoordMatch.groupValues[2].toDouble(), w, sw)
            val py2 = toScreenCoord(mbCoordMatch.groupValues[3].toDouble(), h, sh)
            return Act("press_region", null, null, null, null, null, null, null, px2, py2, region = mbName, label = mbName.uppercase(Locale.US), plan = extractedPlan)
        }
        val mbCellMatch = Regex("^(?:PRESS_REGION\\s+|USE\\s+|TAP\\s+)?(MB\\d+)\\s+([A-J][0-9]{1,2})$", RegexOption.IGNORE_CASE).find(firstLine)
        if (mbCellMatch != null) {
            val mbName = mbCellMatch.groupValues[1].lowercase(Locale.US)
            val cell = mbCellMatch.groupValues[2].uppercase(Locale.US)
            val pt = cellToXY(cell, sw, sh)
            return Act("press_region", null, null, null, null, null, null, null, pt?.first, pt?.second, region = mbName, label = "${mbName.uppercase(Locale.US)}->$cell", plan = extractedPlan)
        }
        val mbHoldMatch = Regex("^(?:(?:PRESS_REGION|TAP|CLICK|HOLD|PRESS|USE)\\s+)?(MB\\d+)(?:\\s+(\\d+))?$", RegexOption.IGNORE_CASE).find(firstLine)
        if (mbHoldMatch != null) {
            val mbName = mbHoldMatch.groupValues[1].lowercase(Locale.US)
            val dur = mbHoldMatch.groupValues[2].toLongOrNull()
            return Act("press_region", null, null, null, null, null, null, null, null, null, durationMs = dur, region = mbName, label = mbName.uppercase(Locale.US), plan = extractedPlan)
        }
        val pressRegionMatch = Regex("^PRESS_REGION\\s+([a-z0-9_]+)(?:\\s+(\\d+))?$", RegexOption.IGNORE_CASE).find(firstLine)
        if (pressRegionMatch != null) {
            val reg = pressRegionMatch.groupValues[1].lowercase(Locale.US)
            val dur = pressRegionMatch.groupValues[2].toLongOrNull()
            return Act("press_region", null, null, null, null, null, null, null, null, null, durationMs = dur, region = reg, label = reg.uppercase(Locale.US), plan = extractedPlan)
        }

        // SEQ <x1> <y1> <x2> <y2>
        val seqCoordMatch = Regex("^SEQ\\s+\\(?$numPattern[\\s,]+$numPattern\\)?(?:\\s*(?:->|to)?\\s*)\\(?$numPattern[\\s,]+$numPattern\\)?$", RegexOption.IGNORE_CASE).find(firstLine)
        if (seqCoordMatch != null) {
            val px1 = toScreenCoord(seqCoordMatch.groupValues[1].toDouble(), w, sw)
            val py1 = toScreenCoord(seqCoordMatch.groupValues[2].toDouble(), h, sh)
            val px2 = toScreenCoord(seqCoordMatch.groupValues[3].toDouble(), w, sw)
            val py2 = toScreenCoord(seqCoordMatch.groupValues[4].toDouble(), h, sh)
            return Act("seq", null, null, null, null, null, px1, py1, px2, py2, plan = extractedPlan)
        }

        // SWIPE / DRAG <x1> <y1> <x2> <y2> [ms]
        val swipeCoordMatch = Regex("^(SWIPE|DRAG)\\s+\\(?$numPattern[\\s,]+$numPattern\\)?(?:\\s*(?:->|to)?\\s*)\\(?$numPattern[\\s,]+$numPattern\\)?(?:\\s+(\\d+))?$", RegexOption.IGNORE_CASE).find(firstLine)
        if (swipeCoordMatch != null) {
            val verb = swipeCoordMatch.groupValues[1].lowercase(Locale.US)
            val px1 = toScreenCoord(swipeCoordMatch.groupValues[2].toDouble(), w, sw)
            val py1 = toScreenCoord(swipeCoordMatch.groupValues[3].toDouble(), h, sh)
            val px2 = toScreenCoord(swipeCoordMatch.groupValues[4].toDouble(), w, sw)
            val py2 = toScreenCoord(swipeCoordMatch.groupValues[5].toDouble(), h, sh)
            val dur = swipeCoordMatch.groupValues[6].toLongOrNull()
            return Act(verb, null, null, null, null, null, px1, py1, px2, py2, durationMs = dur, plan = extractedPlan)
        }

        // TAP / CLICK <x> <y>
        val tapCoordMatch = Regex("^(?:TAP|CLICK)\\s+\\(?$numPattern[\\s,]+$numPattern\\)?$", RegexOption.IGNORE_CASE).find(firstLine)
        if (tapCoordMatch != null) {
            val px = toScreenCoord(tapCoordMatch.groupValues[1].toDouble(), w, sw)
            val py = toScreenCoord(tapCoordMatch.groupValues[2].toDouble(), h, sh)
            return Act("tap", null, null, null, null, null, px, py, null, null, plan = extractedPlan)
        }

        // HOLD <x> <y> [ms]
        val holdCoordMatch = Regex("^HOLD\\s+\\(?$numPattern[\\s,]+$numPattern\\)?(?:\\s+(\\d+))?$", RegexOption.IGNORE_CASE).find(firstLine)
        if (holdCoordMatch != null) {
            val px = toScreenCoord(holdCoordMatch.groupValues[1].toDouble(), w, sw)
            val py = toScreenCoord(holdCoordMatch.groupValues[2].toDouble(), h, sh)
            val dur = holdCoordMatch.groupValues[3].toLongOrNull() ?: 3000L
            return Act("hold", null, null, null, null, null, px, py, null, null, durationMs = dur, plan = extractedPlan)
        }

        val scrollPercentMatch = Regex("^SCROLL\\s+$numPattern\\s+$numPattern\\s+(UP|DOWN|LEFT|RIGHT)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (scrollPercentMatch != null) {
            val px = toScreenCoord(scrollPercentMatch.groupValues[1].toDouble(), w, sw)
            val py = toScreenCoord(scrollPercentMatch.groupValues[2].toDouble(), h, sh)
            val dir = scrollPercentMatch.groupValues[3].lowercase(Locale.US)
            return Act("scroll", null, null, dir, null, null, px, py, null, null, plan = extractedPlan)
        }

        val tapMatch = Regex("^(?:TAP|CLICK)\\s+([A-Z][0-9]{1,2})$", RegexOption.IGNORE_CASE).find(firstLine)
        if (tapMatch != null) {
            val cell = tapMatch.groupValues[1].uppercase(Locale.US)
            val pt = cellToXY(cell, sw, sh)
            if (pt != null) return Act("tap", null, null, null, null, null, pt.first, pt.second, null, null, label = cell, plan = extractedPlan)
        }

        val seqMatch = Regex("^SEQ\\s+([A-Z][0-9]{1,2})\\s+([A-Z][0-9]{1,2})$", RegexOption.IGNORE_CASE).find(firstLine)
        if (seqMatch != null) {
            val c1 = cellToXY(seqMatch.groupValues[1].uppercase(Locale.US), sw, sh)
            val c2 = cellToXY(seqMatch.groupValues[2].uppercase(Locale.US), sw, sh)
            if (c1 != null && c2 != null) {
                return Act("seq", null, null, null, null, null, c1.first, c1.second, c2.first, c2.second, label = "${seqMatch.groupValues[1]}->${seqMatch.groupValues[2]}", plan = extractedPlan)
            }
        }

        val dragMatch = Regex("^(?:DRAG|SWIPE)\\s+([A-Z][0-9]{1,2})\\s+([A-Z][0-9]{1,2})$", RegexOption.IGNORE_CASE).find(firstLine)
        if (dragMatch != null) {
            val c1 = cellToXY(dragMatch.groupValues[1].uppercase(Locale.US), sw, sh)
            val c2 = cellToXY(dragMatch.groupValues[2].uppercase(Locale.US), sw, sh)
            if (c1 != null && c2 != null) {
                return Act("drag", null, null, null, null, null, c1.first, c1.second, c2.first, c2.second, label = "${dragMatch.groupValues[1]}->${dragMatch.groupValues[2]}", plan = extractedPlan)
            }
        }

        val holdMatch = Regex("^HOLD\\s+([A-Z][0-9]{1,2})(?:\\s+(\\d+))?$", RegexOption.IGNORE_CASE).find(firstLine)
        if (holdMatch != null) {
            val cell = holdMatch.groupValues[1].uppercase(Locale.US)
            val dur = holdMatch.groupValues[2].toLongOrNull() ?: 3000L
            val pt = cellToXY(cell, sw, sh)
            if (pt != null) return Act("hold", null, null, null, null, null, pt.first, pt.second, null, null, durationMs = dur, label = cell, plan = extractedPlan)
        }

        val joystickMatch = Regex("^(?:(MB\\d+)\\s+)?JOYSTICK(?:\\s+(FORWARD|UP|BACKWARD|BACK|DOWN|LEFT|RIGHT|FORWARD_LEFT|FORWARD_RIGHT|BACK_LEFT|BACK_RIGHT))?(?:\\s+(\\d+))?$", RegexOption.IGNORE_CASE).find(firstLine)
        if (joystickMatch != null) {
            val mbName = joystickMatch.groupValues[1].takeIf { it.isNotBlank() }?.lowercase(Locale.US)
            val dirWord = joystickMatch.groupValues[2].ifBlank { "forward" }.lowercase(Locale.US)
            val dur = joystickMatch.groupValues[3].toLongOrNull() ?: 2000L
            return Act("joystick", null, null, dirWord, null, null, null, null, null, null, durationMs = dur, region = mbName, plan = extractedPlan)
        }

        val lookMatch = Regex("^LOOK\\s+(-?\\d+)[\\s,]+(-?\\d+)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (lookMatch != null) {
            val dx = lookMatch.groupValues[1].toInt()
            val dy = lookMatch.groupValues[2].toInt()
            return Act("look", null, null, null, null, null, null, null, null, null, dx = dx, dy = dy, plan = extractedPlan)
        }
        val lookDirMatch = Regex("^LOOK(?:\\s+AROUND)?(?:\\s+(UP(?:[\\s_-]+LEFT|[\\s_-]+RIGHT)?|DOWN(?:[\\s_-]+LEFT|[\\s_-]+RIGHT)?|LEFT|RIGHT|AROUND))?$", RegexOption.IGNORE_CASE).find(firstLine)
        if (lookDirMatch != null) {
            val dirWord = lookDirMatch.groupValues[1].ifBlank { "around" }.lowercase(Locale.US)
            val (dx, dy) = dirToLookDelta(dirWord, sw, sh)
            return Act("look", null, null, dirWord, null, null, null, null, null, null, dx = dx, dy = dy, plan = extractedPlan)
        }

        val textMatch = Regex("^TAP_TEXT\\s+(.+)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (textMatch != null) {
            val words = textMatch.groupValues[1].trim().removeSurrounding("\"")
            return Act("tap_text", null, words, null, null, null, null, null, null, null, plan = extractedPlan)
        }

        val scrollCellMatch = Regex("^SCROLL\\s+([A-Z][0-9]{1,2})\\s+(UP|DOWN|LEFT|RIGHT)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (scrollCellMatch != null) {
            val cell = scrollCellMatch.groupValues[1].uppercase(Locale.US)
            val dir = scrollCellMatch.groupValues[2].lowercase(Locale.US)
            val pt = cellToXY(cell, sw, sh)
            return Act("scroll", id = null, text = null, dir = dir, app = null, say = null, x = pt?.first, y = pt?.second, x2 = null, y2 = null, label = cell, plan = extractedPlan)
        }

        val scrollMatch = Regex("^SCROLL\\s+(UP|DOWN|LEFT|RIGHT)$", RegexOption.IGNORE_CASE).find(firstLine)
        if (scrollMatch != null) {
            return Act("scroll", null, null, scrollMatch.groupValues[1].lowercase(Locale.US), null, null, null, null, null, null, plan = extractedPlan)
        }

        val tapWordsMatch = Regex("^(?:TAP|CLICK)\\s+\"?([A-Za-z][A-Za-z0-9\\s]{1,28})\"?$", RegexOption.IGNORE_CASE).find(firstLine)
        if (tapWordsMatch != null) {
            val words = tapWordsMatch.groupValues[1].trim()
            if (!words.matches(Regex("^[A-Z][0-9]{1,2}$", RegexOption.IGNORE_CASE)) && !words.matches(Regex("^MB\\d+$", RegexOption.IGNORE_CASE))) {
                return Act("tap_text", null, words, null, null, null, null, null, null, null, plan = extractedPlan)
            }
        }

        return null
    }

    private fun parseActList(raw: String, w: Int, h: Int): List<Act> {
        val cleaned = raw
            .replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .replace(Regex("^(?:CMD|COMMAND|ACTION|NEXT MOVE):\\s*", RegexOption.IGNORE_CASE), "")
            .trim()

        val (screenW, screenH) = PhoneControl.orientedScreenSize(w, h)

        // 1. Check for JSON array: [ {"do":...}, {"do":...} ]
        val arrStart = cleaned.indexOf('[')
        val arrEnd = cleaned.lastIndexOf(']')
        if (arrStart >= 0 && arrEnd > arrStart) {
            try {
                val arr = JSONArray(cleaned.substring(arrStart, arrEnd + 1))
                val list = mutableListOf<Act>()
                for (i in 0 until arr.length()) {
                    val itemStr = arr.getJSONObject(i).toString()
                    parseAct(itemStr, screenW, screenH)?.let { list.add(it) }
                }
                if (list.isNotEmpty()) return list
            } catch (_: Exception) {}
        }

        // 2. Check for multi-line or arrow-separated actions (e.g. "JOYSTICK FORWARD 2000 -> MB2 4500" or newlines)
        val delimiter = when {
            cleaned.contains("->") -> Regex("\\s*->\\s*")
            cleaned.contains(";") -> Regex("\\s*;\\s*")
            cleaned.lines().count { it.isNotBlank() } > 1 && !cleaned.contains("{") -> Regex("\r?\n+")
            else -> null
        }
        if (delimiter != null) {
            val parts = cleaned.split(delimiter).filter { it.isNotBlank() }
            if (parts.size > 1) {
                val list = parts.mapNotNull { parseAct(it, screenW, screenH) }
                if (list.isNotEmpty()) return list
            }
        }

        // 3. Fallback to single Act
        val single = parseAct(cleaned, screenW, screenH)
        return if (single != null) listOf(single) else emptyList()
    }

    private class GoalStateMachine(val goal: String, val mbs: List<ControlRegions.MacroButton>) {
        val goalLower = goal.lowercase(Locale.US)
        val hasApproach = Regex("\\b(walk|move|go|run|towards|toward|approach|joystick)\\b").containsMatchIn(goalLower)
        val hasAction = Regex("\\b(mine|chop|break|dig|attack|hit|kill|craft|make|create|buy|plant)\\b").containsMatchIn(goalLower)

        var completedApproachSteps = 0
        var completedActionSteps = 0

        fun recordStep(act: Act) {
            if (isMovementAct(act)) {
                completedApproachSteps++
            } else if (act.doWhat == "press_region" || act.doWhat == "hold" || act.doWhat == "seq") {
                completedActionSteps++
            }
        }

        fun isMovementAct(act: Act): Boolean {
            if (act.doWhat == "joystick") return true
            val reg = act.region?.lowercase(Locale.US) ?: ""
            val mb = mbs.firstOrNull { it.name.equals(reg, ignoreCase = true) }
            val lbl = mb?.effectiveLabel()?.lowercase(Locale.US).orEmpty()
            return lbl.contains("walk") || lbl.contains("move") || lbl.contains("forward") || lbl.contains("run") || lbl.contains("joystick") || lbl.contains("stick")
        }

        fun shouldRejectDoneOrFail(act: Act): Boolean {
            if (act.doWhat != "done" && act.doWhat != "fail") return false
            if (hasAction && completedActionSteps == 0) return true
            if (hasApproach && completedApproachSteps == 0 && !hasAction) return true
            return false
        }

        fun getActionAct(sw: Int, sh: Int): Act {
            val actionMb = mbs.firstOrNull {
                val l = it.effectiveLabel().lowercase(Locale.US)
                l.contains("mine") || l.contains("break") || l.contains("chop") || l.contains("dig") ||
                        l.contains("attack") || l.contains("hit") || l.contains("craft") || l.contains("finish") ||
                        (!l.contains("walk") && !l.contains("move") && !l.contains("pause") && !l.contains("resume") && !l.contains("menu"))
            }
            if (actionMb != null) {
                val targetMs = inferTargetHoldMs(goalLower, actionMb.effectiveLabel().lowercase(Locale.US)) ?: 4500L
                return Act(
                    doWhat = "press_region",
                    id = null, text = null, dir = null, app = null, say = null,
                    x = null, y = null, x2 = null, y2 = null,
                    label = actionMb.name.uppercase(Locale.US),
                    plan = "State machine: auto-transition to action phase (${actionMb.effectiveLabel()}) for ${targetMs}ms",
                    durationMs = targetMs,
                    region = actionMb.name
                )
            }
            return Act("look", dir = "around", plan = "State machine: looking around for target")
        }
    }

    private fun tryBuildFastPathCompoundChunk(
        context: Context,
        goal: String,
        mbs: List<ControlRegions.MacroButton>,
        sw: Int,
        sh: Int
    ): List<Act>? {
        val goalLower = goal.lowercase(Locale.US).trim()
        val hasWalk = Regex("\\b(walk|move|go|run|approach|towards|toward)\\b").containsMatchIn(goalLower)
        val hasMineOrAttack = Regex("\\b(mine|chop|break|dig|attack|hit|kill|strike)\\b").containsMatchIn(goalLower)

        val walkMb = mbs.firstOrNull {
            val l = it.effectiveLabel().lowercase(Locale.US)
            l.contains("walk") || l.contains("move") || l.contains("forward") || l.contains("run") || l.contains("joystick") || l.contains("stick")
        }
        val isWalkJoystick = walkMb?.effectiveLabel()?.lowercase(Locale.US)?.let { it.contains("joystick") || it.contains("stick") } == true

        val actionMb = mbs.firstOrNull {
            val l = it.effectiveLabel().lowercase(Locale.US)
            l.contains("mine") || l.contains("break") || l.contains("chop") || l.contains("dig") ||
                    l.contains("attack") || l.contains("hit") || l.contains("use") ||
                    (it != walkMb && !l.contains("walk") && !l.contains("move") && !l.contains("pause") && !l.contains("resume") && !l.contains("menu") && !l.contains("inv"))
        }

        // 1. Walk towards target and mine/act:
        if (hasWalk && hasMineOrAttack && (walkMb != null || isWalkJoystick) && actionMb != null) {
            val moveAct = if (isWalkJoystick) {
                Act("joystick", dir = "forward", durationMs = 2200L, region = walkMb?.name, plan = "Fast-path: approach target via joystick")
            } else {
                Act("press_region", region = walkMb?.name, durationMs = 2200L, label = walkMb?.name?.uppercase(Locale.US), plan = "Fast-path: approach target")
            }
            val targetMs = inferTargetHoldMs(goalLower, actionMb.effectiveLabel().lowercase(Locale.US)) ?: 4500L
            val actionAct = Act("press_region", region = actionMb.name, durationMs = targetMs, label = actionMb.name.uppercase(Locale.US), plan = "Fast-path: hold ${actionMb.effectiveLabel()} for ${targetMs}ms")
            return listOf(moveAct, actionAct)
        }

        // 2. Direct timed mine/action (e.g. "mine for 5 seconds", "mine wood"):
        if (!hasWalk && hasMineOrAttack && actionMb != null) {
            val targetMs = inferTargetHoldMs(goalLower, actionMb.effectiveLabel().lowercase(Locale.US)) ?: 4500L
            val actionAct = Act("press_region", region = actionMb.name, durationMs = targetMs, label = actionMb.name.uppercase(Locale.US), plan = "Fast-path: hold ${actionMb.effectiveLabel()} for ${targetMs}ms")
            return listOf(actionAct)
        }

        return null
    }

    private fun buildMoveForAct(
        a: Act,
        sw: Int,
        sh: Int,
        matchedItem: PhoneControl.UiItem? = null
    ): MoveMemory.Move {
        val kind = when (a.doWhat) {
            "tap" -> MoveMemory.Kind.TAP
            "double_tap" -> MoveMemory.Kind.DOUBLE_TAP
            "long_press" -> MoveMemory.Kind.LONG_PRESS
            "seq" -> MoveMemory.Kind.SEQ
            "drag" -> MoveMemory.Kind.DRAG
            "swipe" -> MoveMemory.Kind.SWIPE
            "hold" -> MoveMemory.Kind.HOLD
            "look" -> MoveMemory.Kind.LOOK
            "scroll" -> MoveMemory.Kind.SCROLL
            "back" -> MoveMemory.Kind.BACK
            "home" -> MoveMemory.Kind.HOME
            "open" -> MoveMemory.Kind.OPEN
            "type" -> MoveMemory.Kind.TYPE
            "tap_text" -> MoveMemory.Kind.TAP_TEXT
            "press_region" -> MoveMemory.Kind.PRESS_REGION
            "wait" -> MoveMemory.Kind.WAIT
            else -> MoveMemory.Kind.OTHER
        }
        val mbObj = if (a.doWhat == "press_region" && !a.region.isNullOrBlank()) {
            val svc = CapriAccessibilityService.instance
            if (svc != null) ControlRegions.getButton(svc, a.region) else null
        } else null
        val px = matchedItem?.cx ?: a.x
        val py = matchedItem?.cy ?: a.y
        val nx = when {
            px != null && sw > 0 -> (px.toFloat() / sw.toFloat()).coerceIn(0f, 1f)
            mbObj != null -> mbObj.normX
            else -> -1f
        }
        val ny = when {
            py != null && sh > 0 -> (py.toFloat() / sh.toFloat()).coerceIn(0f, 1f)
            mbObj != null -> mbObj.normY
            else -> -1f
        }
        val nx2 = if (a.x2 != null && sw > 0) (a.x2.toFloat() / sw.toFloat()).coerceIn(0f, 1f) else -1f
        val ny2 = if (a.y2 != null && sh > 0) (a.y2.toFloat() / sh.toFloat()).coerceIn(0f, 1f) else -1f
        val label = matchedItem?.label ?: a.label ?: a.text
        val text = a.text ?: a.app ?: a.dir ?: a.region
        return MoveMemory.Move(kind = kind, nx = nx, ny = ny, nx2 = nx2, ny2 = ny2, text = text, label = label)
    }

    private fun formatNormalizedActionCmd(move: MoveMemory.Move, a: Act): String {
        if (a.doWhat == "press_region" && !a.region.isNullOrBlank()) {
            return "PRESS_REGION ${a.region.lowercase(Locale.US)}"
        }
        return move.describe().ifBlank { "${a.doWhat.uppercase(Locale.US)} ${a.label ?: a.text ?: ""}".trim() }
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
     * FIRST SCREENSHOT MB AUTO-ASSIGNMENT:
     * When Macro Buttons exist and any of them are unassigned (role == "auto" and blank learnedNote):
     * 1. First checks if the MB directly overlaps any OCR word on screen.
     * 2. If still unassigned, draws hollow neon rings labeled MB1, MB2, ... on the first screenshot
     *    so the icon inside each ring is clearly visible, and asks the vision model once to name each MB.
     */
    private suspend fun assignUnassignedMbsIfNeeded(
        context: Context,
        rawShot: ByteArray?,
        ocrHits: List<TextFinder.Hit>,
        log: MutableList<String>
    ) {
        val allMbs = ControlRegions.allMacroButtons(context)
        if (allMbs.isEmpty()) return

        ControlRegions.autoInspectOverlapsFromScreen(context, ocrHits)
        val stillUnassigned = ControlRegions.getUnassignedButtons(context)
        if (stillUnassigned.isEmpty() || rawShot == null) return

        CapriCursor.setStatus("assigning MB buttons...")
        val annotatedShot = ControlRegions.annotateScreenshotWithMbRings(rawShot, stillUnassigned)
        val mbNamesList = stillUnassigned.joinToString(", ") {
            String.format(Locale.US, "%s at (x=%.2f, y=%.2f)", it.name.uppercase(Locale.US), it.normX, it.normY)
        }
        val inspectPrompt = "Look at the hollow rings labeled $mbNamesList in this screenshot. " +
                "Identify what button, icon, or control is inside each ring in 1 to 3 words. " +
                "Reply on a single line in the exact format: MB1: <label> | MB2: <label>"
        val inspectSys = "You identify UI buttons and game icons inside labeled rings (MB1, MB2, ...). " +
                "Reply ONLY with 'MB1: <short function> | MB2: <short function>'."

        val reply = if (useApi(context)) {
            LlmRouter.complete(
                context,
                LlmRequest(prompt = inspectPrompt, systemPrompt = inspectSys, history = emptyList(), imageBytes = annotatedShot)
            ).getOrNull()
        } else {
            OnDeviceAiClient.generateResponse(
                context = context,
                prompt = inspectPrompt,
                imageBytes = annotatedShot,
                systemOverride = inspectSys,
                maxTokensOverride = 36
            ).getOrNull()
        }

        if (!reply.isNullOrBlank()) {
            val count = ControlRegions.applyModelAssignedLabels(context, reply)
            log.add("auto-assigned $count MB(s) from 1st screenshot: ${ControlRegions.names(context)}")
        } else {
            ControlRegions.applyModelAssignedLabels(context, "")
        }
    }

    private suspend fun askModel(
        context: Context,
        goal: String,
        pkg: String,
        items: List<PhoneControl.UiItem>,
        log: List<String>,
        rawShot: ByteArray?,
        stateKey: String,
        visitedTabs: Set<String> = emptySet(),
        preScannedOcrHits: List<TextFinder.Hit>? = null
    ): Pair<String?, ByteArray?> = coroutineScope {
        val t0 = System.currentTimeMillis()
        val (orientedW, orientedH) = PhoneControl.orientedScreenSize(lastShotWidth, lastShotHeight)

        val ocrHits = when {
            preScannedOcrHits != null -> preScannedOcrHits
            rawShot != null && items.size < 10 -> TextFinder.scanAll(rawShot, orientedW, orientedH, maxItems = 28)
            else -> emptyList()
        }
        val ocrLines = if (rawShot != null && items.size < 10) {
            TextFinder.formatHitsForPrompt(ocrHits)
        } else {
            "(use SCREEN ELEMENTS below)"
        }

        val screen = items.take(25).joinToString("\n") { item ->
            val nx = String.format(Locale.US, "%.2f", item.cx.toFloat() / orientedW.coerceAtLeast(1))
            val ny = String.format(Locale.US, "%.2f", item.cy.toFloat() / orientedH.coerceAtLeast(1))
            "[${item.id}] ${item.kind} \"${item.label}\" at (x=$nx, y=$ny)"
        }

        val isGameMode = items.size < 5
        val macroButtons = ControlRegions.allMacroButtons(context)
        val hasMbs = macroButtons.isNotEmpty()

        val strategicMemoryBlock = AgentMemory.buildDualModeContext(
            pkg = pkg,
            stateKey = stateKey,
            goal = goal,
            isGameMode = isGameMode,
            currentLabels = items.map { it.label },
            visitedTabs = visitedTabs
        )

        val taskForPrompt = if (!isGameMode && !pkg.contains("launcher", ignoreCase = true)) {
            goal.replace(
                Regex("^(?:in\\s+[a-z0-9_]+\\s*:\\s*|(?:open|launch|start|go\\s+(?:on|to|in))\\s+[a-z0-9_]+(?:\\s*,\\s*|\\s+and\\s+|\\s+then\\s+))", RegexOption.IGNORE_CASE),
                ""
            ).trim().ifEmpty { goal }
        } else {
            goal
        }

        val rawOcrTexts = ocrHits.map { it.text }

        val prompt = buildString {
            appendLine("TASK: $taskForPrompt")
            appendLine("FOREGROUND APP: $pkg (${orientedW}x${orientedH})")
            if (hasMbs) {
                appendLine("MACRO BUTTONS (USE THESE DIRECTLY): ${ControlRegions.describeForPrompt(context)}")
            } else {
                appendLine("MACRO BUTTONS: none (use normal screen coordinates, UI element IDs, or OCR text)")
            }
            appendLine(strategicMemoryBlock)
            appendLine("STEPS SO FAR: ${if (log.isEmpty()) "none" else log.takeLast(6).joinToString(" > ")}")
            val statusHint = buildTaskContextStatus(goal, log, macroButtons, rawOcrTexts)
            if (statusHint.isNotBlank()) {
                appendLine(statusHint)
            }
            appendLine("OCR TEXT ON SCREEN:\n$ocrLines")
            if (!isGameMode) {
                appendLine("SCREEN ELEMENTS:\n${screen.ifEmpty { "(nothing readable, use the screenshot)" }}")
            }
        }

        val sysPrompt = if (hasMbs) SYSTEM_WITH_MBS else SYSTEM_NO_MBS
        val sysPromptLocal = if (hasMbs) SYSTEM_LOCAL_WITH_MBS else SYSTEM_LOCAL_NO_MBS

        // In app mode with UI tags, use the much faster tag method with zero vision/screenshot overhead.
        // Only send screenshots when in game mode / canvas surfaces lacking accessibility tags.
        val effectiveShot = if (!isGameMode && items.isNotEmpty()) null else rawShot

        val reply = if (useApi(context)) {
            LlmRouter.complete(
                context,
                LlmRequest(prompt = prompt, systemPrompt = sysPrompt, history = emptyList(), imageBytes = effectiveShot)
            ).getOrNull()
        } else {
            OnDeviceAiClient.generateResponse(context, prompt, effectiveShot, systemOverride = sysPromptLocal, maxTokensOverride = 28).getOrNull()
        }

        val elapsed = System.currentTimeMillis() - t0
        val providerLabel = if (useApi(context)) {
            "Cloud API (${LlmRouter.getActiveProviderName(context)})"
        } else {
            "Local Gemma E2B (${OnDeviceAiClient.getActiveBackendLabel()})"
        }
        val traceId = BrainTraceStore.recordTurnStart(
            context = context,
            goal = goal,
            pkg = pkg,
            isGameMode = isGameMode,
            provider = providerLabel,
            latencyMs = elapsed,
            shotBytes = effectiveShot,
            shotW = lastShotWidth,
            shotH = lastShotHeight,
            activePlan = AgentMemory.getActivePlan(pkg, goal),
            memoryBlock = strategicMemoryBlock,
            ocrText = ocrLines,
            uiElementsText = screen,
            fullPrompt = prompt,
            rawModelReply = reply ?: "(null/timeout)"
        )

        Pair(if (reply != null) "$reply|_TRACE_ID_:$traceId" else null, effectiveShot)
    }

    /**
     * Launches an explicitly requested app before starting the loop if not already in the foreground.
     */
    private suspend fun ensureRequestedAppLaunched(context: Context, goal: String, log: MutableList<String>): String? {
        val root = PhoneControl.foregroundRoot()
        val currentPkg = root?.packageName?.toString()?.lowercase(Locale.US).orEmpty()
        val goalLower = goal.lowercase(Locale.US).trim()

        val explicitAppName = AppChooser.extractExplicitAppName(goal)
        val candidateAppName = when {
            explicitAppName != null -> explicitAppName
            // Handle reels / short video requests ("play a vid", "play video", "reels", "play shorts", "watch a vid")
            Regex("\\b(reels?|videos?|vids?|shorts?|tiktok)\\b").containsMatchIn(goalLower) &&
                    Regex("\\b(play|watch|open|start|view|see)\\b").containsMatchIn(goalLower) ->
                MainActivity.getDefaultReelsApp(context)
            // Handle general music requests without explicit app ("play music", "play song", "play track", "play something", "play a tune")
            Regex("\\b(songs?|music|track|tracks|audio|tune|playlist|liked)\\b").containsMatchIn(goalLower) &&
                    Regex("\\b(play|listen|stream|start)\\b").containsMatchIn(goalLower) ->
                MainActivity.getDefaultMusicPlayer(context)
            else -> null
        }

        val matchedApp = if (candidateAppName != null) {
            AppChooser.findBestMatch(context, candidateAppName)
        } else {
            null
        }

        if (matchedApp != null) {
            val targetPkg = matchedApp.pkg.lowercase(Locale.US)
            if (currentPkg != targetPkg) {
                if (AppChooser.open(context, matchedApp.pkg)) {
                    log.add("launched ${matchedApp.label}")
                    delay(1800)
                }
            }
            return targetPkg
        }

        return currentPkg.takeIf { it.isNotBlank() && !it.contains("launcher") }
    }

    private class OutcomeState(
        val root: AccessibilityNodeInfo?,
        val items: List<PhoneControl.UiItem>,
        val bytes: ByteArray?,
        val fp: ScreenFingerprint?
    )

    private suspend fun captureOutcomeState(
        service: CapriAccessibilityService,
        isGameMode: Boolean,
        settleMs: Long = 440L
    ): OutcomeState {
        delay(if (isGameMode) settleMs.coerceAtLeast(480L) else settleMs.coerceAtLeast(380L))
        val afterRoot = PhoneControl.foregroundRoot()
        val afterItems = if (afterRoot != null) PhoneControl.snapshot(afterRoot) else emptyList()
        // In app mode with UI tags, skip capturing screenshots to make tag-based interactions blazing fast.
        val afterBytes = if (isGameMode || afterItems.size < 5) service.captureScreenBitmap() else null
        val afterFp = ScreenFingerprint.of(afterBytes)
        return OutcomeState(afterRoot, afterItems, afterBytes, afterFp)
    }

    private fun detectUnintendedTransition(
        goal: String,
        beforePkg: String,
        afterPkg: String,
        afterItems: List<PhoneControl.UiItem>,
        beforeFp: ScreenFingerprint?,
        afterFp: ScreenFingerprint?,
        act: Act,
        move: MoveMemory.Move,
        targetAppPkg: String? = null
    ): Pair<String, Boolean>? {
        val goalLower = goal.lowercase(Locale.US)

        if (act.doWhat != "open" && act.doWhat != "home" && act.doWhat != "back") {
            val wasInRealApp = beforePkg.isNotBlank() && !beforePkg.contains("launcher", ignoreCase = true)
            if (wasInRealApp && afterPkg.isNotBlank() && afterPkg != beforePkg && afterPkg != "game_surface") {
                val askedForAfterPkg = goalLower.contains(afterPkg.substringAfterLast('.'))
                if (!askedForAfterPkg) {
                    if (targetAppPkg != null && beforePkg.equals(targetAppPkg, ignoreCase = true)) {
                        return Pair("unexpectedly left target app into $afterPkg", true)
                    }
                    if (afterPkg in AD_OR_STORE_PACKAGES || afterPkg.contains("vending") || afterPkg.contains("browser") ||
                        afterPkg.contains("chrome") || afterPkg.contains("packageinstaller")
                    ) {
                        return Pair("opened external store/installer/ad ($afterPkg)", true)
                    }
                    if (afterPkg.contains("launcher", ignoreCase = true)) {
                        return Pair("accidentally exited app to home launcher", true)
                    }
                }
            }
        }

        if (act.doWhat == "tap" || act.doWhat == "tap_text") {
            val labelsLower = afterItems.map { it.label.lowercase(Locale.US) }
            val openedAdOrPurchase = labelsLower.any {
                it.contains("google play") || it.contains("install now") || it.contains("in-app purchase") ||
                        it.contains("buy now") || it.contains("watch ad")
            } && !goalLower.contains("buy") && !goalLower.contains("install")
            if (openedAdOrPurchase) {
                return Pair("opened ad or purchase popup", true)
            }
        }

        if ((act.doWhat == "seq" || act.doWhat == "drag") && beforeFp != null && afterFp != null && move.isPath) {
            val destChange = beforeFp.changedFractionAround(
                other = afterFp,
                points = listOf(move.nx2 to move.ny2),
                radius = 0.09f,
                near = true,
                threshold = 10
            )
            val sourceChange = beforeFp.changedFractionAround(
                other = afterFp,
                points = listOf(move.nx to move.ny),
                radius = 0.08f,
                near = true,
                threshold = 10
            )
            if (destChange < 0.01f && sourceChange < 0.02f) {
                return Pair("placement did not change target area on screen", false)
            }
        }

        return null
    }

    private fun isItemCreationOrSelectionGoal(goalLower: String): Boolean {
        return Regex("\\b(craft|make|create|build|combine|buy|purchase|select|choose|plant|equip|cook|brew)\\b").containsMatchIn(goalLower)
    }

    private fun extractMenuTargetEntity(goal: String): String {
        val clean = goal.lowercase(Locale.US)
            .replace(Regex("^(please|hey\\s+capri|capri|can\\s+you|could\\s+you)\\s+"), "")
            .trim()
        val match = Regex("\\b(?:craft|make|create|build|combine|buy|purchase|select|choose|plant|equip|cook|brew)\\s+(?:a\\s+|an\\s+|the\\s+|some\\s+)?([a-z0-9\\s_-]{2,30})").find(clean)
        if (match != null) {
            var target = match.groupValues[1].trim()
            target = target.replace(Regex("\\s+(?:for\\s+me|please|now|using.*|with.*|in\\s+.*|on\\s+.*)$"), "").trim()
            if (target.isNotBlank()) return target
        }
        return "target item"
    }

    private fun isMenuOrInventoryScreen(ocrTexts: List<String>, log: List<String>): Boolean {
        val ocrMatches = ocrTexts.any { text ->
            val l = text.lowercase(Locale.US)
            l.contains("inventory") || l.contains("craft") || l.contains("recipe") ||
                    l.contains("menu") || l.contains("shop") || l.contains("store") ||
                    l.contains("bag") || l.contains("chest") || l.contains("items") ||
                    l.contains("search") || l.contains("catalog") || l.contains("select")
        }
        val logMatches = log.any { step ->
            step.contains("used ", ignoreCase = true) &&
                    (step.contains("inv", ignoreCase = true) || step.contains("bag", ignoreCase = true) ||
                            step.contains("menu", ignoreCase = true) || step.contains("shop", ignoreCase = true) ||
                            step.contains("chest", ignoreCase = true) || step.contains("catalog", ignoreCase = true))
        }
        return ocrMatches || logMatches
    }

    /**
     * When Macro Buttons are placed on screen:
     * 1. Resolves any TAP_TEXT or PRESS_REGION that uses an MB's custom label (e.g. "peashooter") to its MB ID ("mb1").
     * 2. Converts any raw coordinate TAP/HOLD/SEQ that lands on or near an MB into a direct `press_region` command.
     * 3. Prevents clicking a "resume" or "menu/pause" MB during active gameplay unless a pause dialog is visible on screen
     *    or the user explicitly asked for it.
     */
    private fun snapToMacroButtonIfApplicable(
        context: Context,
        initialAct: Act,
        goal: String,
        ocrLines: List<String>,
        sw: Int,
        sh: Int,
        log: MutableList<String>
    ): Act {
        val mbs = ControlRegions.allMacroButtons(context)
        if (mbs.isEmpty()) return initialAct

        var act = initialAct

        // 1. If the model referenced an MB by its custom label (e.g. TAP_TEXT peashooter or PRESS_REGION peashooter), resolve to mb.name
        if (act.doWhat == "tap_text" && !act.text.isNullOrBlank()) {
            val matchedMb = ControlRegions.findMatchingMb(context, act.text)
            if (matchedMb != null) {
                log.add("matched '${act.text}' to Macro Button ${matchedMb.name.uppercase(Locale.US)} (${matchedMb.effectiveLabel()})")
                act = Act(
                    doWhat = "press_region",
                    id = null,
                    text = null,
                    dir = null,
                    app = null,
                    say = null,
                    x = null,
                    y = null,
                    x2 = act.x2,
                    y2 = act.y2,
                    label = matchedMb.name.uppercase(Locale.US),
                    plan = act.plan,
                    durationMs = act.durationMs,
                    region = matchedMb.name
                )
            }
        } else if (act.doWhat == "press_region" && !act.region.isNullOrBlank() && ControlRegions.getButton(context, act.region) == null) {
            val matchedMb = ControlRegions.findMatchingMb(context, act.region)
            if (matchedMb != null) {
                act = Act(
                    doWhat = "press_region",
                    id = null,
                    text = null,
                    dir = null,
                    app = null,
                    say = null,
                    x = null,
                    y = null,
                    x2 = act.x2,
                    y2 = act.y2,
                    label = matchedMb.name.uppercase(Locale.US),
                    plan = act.plan,
                    durationMs = act.durationMs,
                    region = matchedMb.name
                )
            }
        }

        // 2. Snap raw coordinates that land near an MB (except visual item/recipe taps inside an open menu/inventory/crafting screen)
        if ((act.doWhat == "tap" || act.doWhat == "hold" || act.doWhat == "seq") &&
            act.id == null && act.x != null && act.y != null
        ) {
            val goalLower = goal.lowercase(Locale.US)
            val isMenuGoal = isItemCreationOrSelectionGoal(goalLower)
            val isMenuOpen = isMenuOrInventoryScreen(ocrLines, log)

            val nx = act.x.toFloat() / sw.coerceAtLeast(1)
            val ny = act.y.toFloat() / sh.coerceAtLeast(1)
            val nearest = mbs.minByOrNull { hypot((it.normX - nx).toDouble(), (it.normY - ny).toDouble()) }
            val nearestLabel = nearest?.effectiveLabel()?.lowercase(Locale.US).orEmpty()
            val isGameplayMb = nearestLabel.contains("walk") || nearestLabel.contains("move") ||
                    nearestLabel.contains("mine") || nearestLabel.contains("jump") ||
                    nearestLabel.contains("attack") || nearestLabel.contains("shoot") ||
                    nearestLabel.contains("run")

            // If inside an inventory/menu/crafting screen, do NOT snap visual taps on items to gameplay MBs!
            val allowSnap = nearest != null && (!isMenuOpen || !isMenuGoal || !isGameplayMb)
            if (allowSnap && nearest != null && hypot((nearest.normX - nx).toDouble(), (nearest.normY - ny).toDouble()) <= 0.10) {
                log.add("locked onto Macro Button ${nearest.name.uppercase(Locale.US)} (${nearest.effectiveLabel()})")
                act = Act(
                    doWhat = "press_region",
                    id = null,
                    text = null,
                    dir = null,
                    app = null,
                    say = null,
                    x = null,
                    y = null,
                    x2 = act.x2,
                    y2 = act.y2,
                    label = nearest.name.uppercase(Locale.US),
                    plan = act.plan,
                    durationMs = act.durationMs,
                    region = nearest.name
                )
            }
        }

        // 3. Guard against pressing a "resume" or "menu/pause" MB during active gameplay when no pause dialog is open
        if (act.doWhat == "press_region" && !act.region.isNullOrBlank()) {
            val chosenMb = ControlRegions.getButton(context, act.region)
            if (chosenMb != null) {
                val lbl = chosenMb.effectiveLabel().lowercase(Locale.US)
                val isPauseOrResumeMb = lbl.contains("resume") || lbl.contains("pause") || lbl.contains("menu")
                val userAskedForPauseOrResume = Regex("\\b(resume|pause|menu|continue)\\b", RegexOption.IGNORE_CASE).containsMatchIn(goal)
                val pauseVisibleOnScreen = ocrLines.any { line ->
                    val l = line.lowercase(Locale.US)
                    l.contains("resume") || l.contains("paused") || l.contains("continue") || l.contains("main menu") || l.contains("restart")
                }
                if (isPauseOrResumeMb && !userAskedForPauseOrResume && !pauseVisibleOnScreen) {
                    val gameplayMbs = mbs.filterNot {
                        val l = it.effectiveLabel().lowercase(Locale.US)
                        l.contains("resume") || l.contains("pause") || l.contains("menu")
                    }
                    if (gameplayMbs.isNotEmpty()) {
                        val replacement = gameplayMbs.first()
                        log.add("skipped ${chosenMb.name.uppercase(Locale.US)} (${chosenMb.effectiveLabel()} - not paused); switched to ${replacement.name.uppercase(Locale.US)} (${replacement.effectiveLabel()})")
                        act = Act(
                            doWhat = "press_region",
                            id = null,
                            text = null,
                            dir = null,
                            app = null,
                            say = null,
                            x = null,
                            y = null,
                            x2 = act.x2,
                            y2 = act.y2,
                            label = replacement.name.uppercase(Locale.US),
                            plan = act.plan,
                            durationMs = act.durationMs,
                            region = replacement.name
                        )
                    }
                }
            }
        }

        // 4. Smart Hold Duration, 3D Look-Around & Phase Progression:
        //    - When pressing a mining/breaking MB (or when the goal asks to mine wood/tree/dirt) and the model omitted duration_ms
        //      (or sent a quick tap < 1000ms), upgrade to the right hold duration (4700ms for wood/tree, 1000ms for dirt, 3000ms default).
        //    - When in a multi-step goal ("move towards the tree and mine wood") and the agent has already pressed the movement MB
        //      repeatedly on previous steps, automatically advance to the next action MB (e.g. mine for 4700ms) instead of walking forever!
        //    - When in a multi-block 3D goal ("mine the whole tree", "chop all logs", "look around and mine"), after completing a 4700ms
        //      mine step, if the model tries to immediately mine again without adjusting the camera, automatically tilt the camera (`look up`)
        //      so Capri aims at the upper trunk blocks instead of mining empty air!
        if (act.doWhat == "press_region" && !act.region.isNullOrBlank()) {
            val rawChosenMb = ControlRegions.getButton(context, act.region)
            if (rawChosenMb != null) {
                val goalLower = goal.lowercase(Locale.US)
                // If multiple MBs are stacked at the same spot (e.g. "mine" and "attack" on top of each other),
                // disambiguate to the one matching the current goal ("mine" vs "attack").
                val chosenMb = resolveOverlappingMbForGoal(rawChosenMb, mbs, goalLower)
                if (chosenMb.name != rawChosenMb.name) {
                    log.add("disambiguated stacked MB ${rawChosenMb.name.uppercase(Locale.US)} (${rawChosenMb.effectiveLabel()}) -> ${chosenMb.name.uppercase(Locale.US)} (${chosenMb.effectiveLabel()}) for goal")
                    act = Act(
                        doWhat = "press_region",
                        id = null, text = null, dir = null, app = null, say = null,
                        x = null, y = null, x2 = act.x2, y2 = act.y2,
                        label = chosenMb.name.uppercase(Locale.US),
                        plan = act.plan,
                        durationMs = act.durationMs,
                        region = chosenMb.name
                    )
                }

                val lblLower = chosenMb.effectiveLabel().lowercase(Locale.US)
                val isJoystickMb = lblLower.contains("joystick") || lblLower.contains("stick")
                val isMovementMb = isJoystickMb || lblLower.contains("walk") || lblLower.contains("move") || lblLower.contains("forward") || lblLower.contains("run")

                if (isJoystickMb) {
                    val dir = when {
                        goalLower.contains("back") || goalLower.contains("down") -> "backward"
                        goalLower.contains("left") -> "left"
                        goalLower.contains("right") -> "right"
                        else -> "forward"
                    }
                    val targetMs = inferTargetHoldMs(goalLower, lblLower) ?: 2000L
                    log.add("deflecting joystick on ${chosenMb.name.uppercase(Locale.US)} $dir for ${targetMs}ms")
                    return Act(
                        doWhat = "joystick",
                        id = null, text = null, dir = dir, app = null, say = null,
                        x = (chosenMb.normX * sw).toInt(), y = (chosenMb.normY * sh).toInt(),
                        x2 = null, y2 = null,
                        label = chosenMb.name.uppercase(Locale.US),
                        plan = "Deflect joystick $dir and hold for ${targetMs}ms",
                        durationMs = targetMs,
                        region = chosenMb.name
                    )
                }

                val isMineMb = lblLower.contains("mine") || lblLower.contains("break") || lblLower.contains("dig") || lblLower.contains("chop") || lblLower.contains("attack") || lblLower.contains("hit") ||
                        (!isMovementMb && Regex("\\b(mine|chop|break|dig)\\b").containsMatchIn(goalLower))
                val goalWantsMining = Regex("\\b(mine|chop|break|dig|wood|tree|log|dirt|block)\\b").containsMatchIn(goalLower)
                val goalWantsWalkFirst = Regex("\\b(walk|move|go|run)\\s+(?:to|towards|toward|up\\s+to|near|forward)\\b").containsMatchIn(goalLower) && goalWantsMining
                val isMultiBlockGoal = Regex("\\b(whole|entire|all|every|full)\\b").containsMatchIn(goalLower) ||
                        Regex("\\b([2-9]|1\\d)\\s+(?:wood|trees?|logs?|blocks?|dirt|stone)\\b").containsMatchIn(goalLower)

                val workedSteps = log.filter { it.contains("(worked") }
                val walkMb = mbs.firstOrNull { mb ->
                    val l = mb.effectiveLabel().lowercase(Locale.US)
                    l.contains("walk") || l.contains("move") || l.contains("forward") || l.contains("run") || l.contains("joystick") || l.contains("stick")
                }
                val completedWalkSteps = if (walkMb != null) {
                    workedSteps.count { it.contains("used ${walkMb.name.uppercase(Locale.US)} ", ignoreCase = true) || it.contains("joystick", ignoreCase = true) }
                } else 0

                // Phase 1 enforcement: If the user explicitly asked to "walk towards that tree and mine...",
                // ensure at least 1 walk step happens before jumping straight to mining!
                if (goalWantsWalkFirst && walkMb != null && completedWalkSteps == 0 && !isMovementMb) {
                    log.add("goal asks to walk first; starting with ${walkMb.name.uppercase(Locale.US)} (${walkMb.effectiveLabel()}) for 2200ms before mining")
                    return Act(
                        doWhat = "press_region",
                        id = null, text = null, dir = null, app = null, say = null,
                        x = null, y = null, x2 = null, y2 = null,
                        label = walkMb.name.uppercase(Locale.US),
                        plan = "Walk towards target first (2200ms), then hold mine on next step",
                        durationMs = 2200L,
                        region = walkMb.name
                    )
                }

                val consecutiveSameMoveCount = workedSteps.takeLastWhile {
                    it.contains("used ${chosenMb.name.uppercase(Locale.US)} ", ignoreCase = true)
                }.size

                val walkThreshold = if (goalLower.contains("one block") || goalLower.contains("1 block")) 1 else 2
                if (isMovementMb && goalWantsMining && consecutiveSameMoveCount >= walkThreshold) {
                    val rawMineMb = mbs.firstOrNull { mb ->
                        val l = mb.effectiveLabel().lowercase(Locale.US)
                        mb.name != chosenMb.name && (l.contains("mine") || l.contains("break") || l.contains("dig") || l.contains("chop") || l.contains("attack") || l.contains("use") || (!l.contains("walk") && !l.contains("move") && !l.contains("pause") && !l.contains("resume") && !l.contains("menu")))
                    }
                    val mineMb = if (rawMineMb != null) resolveOverlappingMbForGoal(rawMineMb, mbs, goalLower) else null
                    if (mineMb != null) {
                        val targetMs = inferTargetHoldMs(goalLower, mineMb.effectiveLabel().lowercase(Locale.US)) ?: 4700L
                        log.add("already moved towards target ($consecutiveSameMoveCount step(s)); advancing to ${mineMb.name.uppercase(Locale.US)} (${mineMb.effectiveLabel()}) for ${targetMs}ms")
                        return Act(
                            doWhat = "press_region",
                            id = null,
                            text = null,
                            dir = null,
                            app = null,
                            say = null,
                            x = null,
                            y = null,
                            x2 = null,
                            y2 = null,
                            label = mineMb.name.uppercase(Locale.US),
                            plan = "Now at target -> hold ${mineMb.name.uppercase(Locale.US)} for ${targetMs}ms to complete goal",
                            durationMs = targetMs,
                            region = mineMb.name
                        )
                    }
                }

                // Multi-block 3D look progression: if the goal is "mine the whole tree" and the very last step was already a full mining hold
                // without a camera look in between, tilt the camera UP (or DOWN for the base block) before mining the next block!
                val lastWorked = workedSteps.lastOrNull().orEmpty()
                val lastWasLongMine = Regex("used\\s+MB\\d+.*for\\s+(3\\d\\d\\d|4\\d\\d\\d|5\\d\\d\\d)ms", RegexOption.IGNORE_CASE).containsMatchIn(lastWorked)
                if (isMultiBlockGoal && isMineMb && lastWasLongMine) {
                    val completedLookCount = workedSteps.count { it.startsWith("looked ") || it.startsWith("swiped ") }
                    val lookDir = when (completedLookCount) {
                        0 -> "up"
                        1 -> "up"
                        2 -> "down"
                        else -> "up"
                    }
                    val (ldx, ldy) = dirToLookDelta(lookDir, sw, sh)
                    log.add("mined block; tilting 3D camera $lookDir ($ldx, $ldy) to aim at next block of target")
                    return Act(
                        doWhat = "look",
                        id = null,
                        text = null,
                        dir = lookDir,
                        app = null,
                        say = null,
                        x = null,
                        y = null,
                        x2 = null,
                        y2 = null,
                        plan = "Look $lookDir at next block, then hold ${chosenMb.name.uppercase(Locale.US)} for 4700ms",
                        durationMs = 260L,
                        dx = ldx,
                        dy = ldy
                    )
                }

                // Ensure proper hold duration whenever using a mine/break/attack MB on a mining goal (or movement MB on a walk goal)
                if (act.x2 == null && act.y2 == null) {
                    if (isMovementMb && (act.durationMs == null || act.durationMs < 800L)) {
                        val walkMs = 2000L
                        log.add("adjusted ${chosenMb.name.uppercase(Locale.US)} (${chosenMb.effectiveLabel()}) walk hold duration to ${walkMs}ms")
                        act = Act(
                            doWhat = "press_region",
                            id = null, text = null, dir = null, app = null, say = null,
                            x = null, y = null, x2 = null, y2 = null,
                            label = act.label, plan = act.plan, durationMs = walkMs, region = act.region
                        )
                    } else if (!isMovementMb && (isMineMb || goalWantsMining)) {
                        val neededMs = inferTargetHoldMs(goalLower, lblLower)
                        if (neededMs != null && (act.durationMs == null || act.durationMs < neededMs * 2 / 3)) {
                            log.add("adjusted ${chosenMb.name.uppercase(Locale.US)} (${chosenMb.effectiveLabel()}) hold duration to ${neededMs}ms for target")
                            act = Act(
                                doWhat = "press_region",
                                id = null,
                                text = null,
                                dir = null,
                                app = null,
                                say = null,
                                x = null,
                                y = null,
                                x2 = null,
                                y2 = null,
                                label = act.label,
                                plan = act.plan,
                                durationMs = neededMs,
                                region = act.region
                            )
                        }
                    }
                }
            }
        } else if ((act.doWhat == "hold" || act.doWhat == "tap") && act.id == null && act.x != null && act.y != null) {
            val goalLower = goal.lowercase(Locale.US)
            if (Regex("\\b(mine|chop|break|dig)\\b").containsMatchIn(goalLower)) {
                val neededMs = inferTargetHoldMs(goalLower, "")
                if (neededMs != null && (act.doWhat == "tap" || (act.durationMs ?: 0L) < neededMs * 2 / 3)) {
                    log.add("upgraded raw ${act.doWhat} at (${act.x}, ${act.y}) to ${neededMs}ms hold for mining")
                    act = Act(
                        doWhat = "hold",
                        id = null,
                        text = null,
                        dir = null,
                        app = null,
                        say = null,
                        x = act.x,
                        y = act.y,
                        x2 = null,
                        y2 = null,
                        label = act.label,
                        plan = act.plan,
                        durationMs = neededMs
                    )
                }
            }
        }

        return act
    }

    /**
     * When the user places two MBs on top of each other (e.g. "mine" and "attack" at the same spot),
     * selects the MB at that coordinate whose label best matches the current goal.
     */
    private fun resolveOverlappingMbForGoal(
        chosen: ControlRegions.MacroButton,
        allMbs: List<ControlRegions.MacroButton>,
        goalLower: String
    ): ControlRegions.MacroButton {
        val overlapping = allMbs.filter {
            hypot((it.normX - chosen.normX).toDouble(), (it.normY - chosen.normY).toDouble()) <= 0.06
        }
        if (overlapping.size <= 1) return chosen

        val wantsMine = Regex("\\b(mine|chop|break|dig|wood|tree|log|dirt|stone|block)\\b").containsMatchIn(goalLower)
        val wantsAttack = Regex("\\b(attack|hit|fight|kill|strike|slay|mob|zombie)\\b").containsMatchIn(goalLower)

        if (wantsMine && !wantsAttack) {
            overlapping.firstOrNull {
                val l = it.effectiveLabel().lowercase(Locale.US)
                l.contains("mine") || l.contains("break") || l.contains("chop") || l.contains("dig")
            }?.let { return it }
        } else if (wantsAttack && !wantsMine) {
            overlapping.firstOrNull {
                val l = it.effectiveLabel().lowercase(Locale.US)
                l.contains("attack") || l.contains("hit") || l.contains("fight")
            }?.let { return it }
        }
        return chosen
    }

    private fun inferTargetHoldMs(goalLower: String, mbLabelLower: String): Long? {
        // Explicit duration in goal (e.g. "for 5 seconds", "4700ms")
        Regex("(\\d+)\\s*(?:ms|milliseconds?)").find(goalLower)?.groupValues?.get(1)?.toLongOrNull()?.let { return it }
        Regex("(\\d+(?:\\.\\d+)?)\\s*(?:s|sec|secs|seconds?)\\b").find(goalLower)?.groupValues?.get(1)?.toDoubleOrNull()?.let {
            return (it * 1000.0).toLong()
        }
        return when {
            goalLower.contains("wood") || goalLower.contains("tree") || goalLower.contains("log") || goalLower.contains("oak") ||
                    mbLabelLower.contains("wood") || mbLabelLower.contains("tree") -> 4700L
            goalLower.contains("stone") || goalLower.contains("cobble") || goalLower.contains("ore") -> 4000L
            goalLower.contains("dirt") || goalLower.contains("grass") || goalLower.contains("sand") || goalLower.contains("gravel") -> 1000L
            goalLower.contains("mine") || goalLower.contains("break") || goalLower.contains("chop") || goalLower.contains("dig") -> 4500L
            else -> null
        }
    }

    private fun isFiniteActionGoal(goal: String): Boolean {
        val g = goal.lowercase(Locale.US).trim()
        // Open-ended gameplay requests should keep playing until stopped
        if (Regex("\\b(play\\s+(?:and\\s+win|the\\s+game|for\\s+me)|win\\s+the\\s+game|beat\\s+the\\s+level|keep\\s+playing|survive|defend\\s+the\\s+lawn)\\b").containsMatchIn(g)) {
            return false
        }
        return true
    }

    private fun expectedTargetBlockCount(goalLower: String): Int {
        Regex("\\b([2-9]|1\\d)\\s+[a-z]{3,15}\\b")
            .find(goalLower)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        if (Regex("\\b(whole|entire|all|full|every)\\s+(?:the\\s+)?[a-z]{3,15}\\b").containsMatchIn(goalLower)) {
            return 4
        }
        return 1
    }

    /**
     * Builds an explicit per-turn context evaluation block so the model always knows:
     * 1. What steps have ALREADY been completed in this task.
     * 2. Whether it has repeated the same MB multiple times and must stop, look around, or switch phases.
     * 3. Whether the final action of a finite goal (like mining for 4500ms, or crafting/buying an item) just succeeded so it can reply DONE.
     */
    private fun buildTaskContextStatus(
        goal: String,
        log: List<String>,
        mbs: List<ControlRegions.MacroButton>,
        ocrTexts: List<String> = emptyList()
    ): String {
        val goalLower = goal.lowercase(Locale.US)
        val workedSteps = log.filter { it.contains("(worked") }

        // Menu / inventory / crafting / shop tasks ("make X", "craft X", "buy X", "select X")
        val isMenuGoal = isItemCreationOrSelectionGoal(goalLower)
        val isMenuOpen = isMenuOrInventoryScreen(ocrTexts, log)
        val targetItemName = extractMenuTargetEntity(goal)

        if (isMenuGoal) {
            val invMb = mbs.firstOrNull {
                val l = it.effectiveLabel().lowercase(Locale.US)
                l.contains("inv") || l.contains("bag") || l.contains("backpack") || l.contains("menu") || l.contains("shop") || l.contains("items")
            }
            val confirmMb = mbs.firstOrNull {
                val l = it.effectiveLabel().lowercase(Locale.US)
                l.contains("craft") || l.contains("finish") || l.contains("create") || l.contains("make") ||
                        l.contains("buy") || l.contains("confirm") || l.contains("done") || l.contains("ok")
            }

            if (!isMenuOpen) {
                if (invMb != null) {
                    return "CONTEXT CHECK (PHASE 1: OPEN MENU/INVENTORY): Task is \"$goal\". The menu/inventory screen is NOT open yet. " +
                            "Tap Macro Button ${invMb.name.uppercase(Locale.US)} (${invMb.effectiveLabel()}) to open the screen!"
                }
            } else {
                val worked = log.filter { it.contains("(worked") }
                val alreadyCompleted = worked.any {
                    it.contains("used ${confirmMb?.name?.uppercase(Locale.US) ?: "CONFIRM"}", ignoreCase = true) ||
                            it.contains("tapped item") || it.contains("seq")
                }
                if (alreadyCompleted && (worked.lastOrNull()?.contains("used ${confirmMb?.name?.uppercase(Locale.US) ?: "CONFIRM"}", ignoreCase = true) == true || worked.size >= 3)) {
                    return "CONTEXT CHECK (MENU ACTION COMPLETED): You already selected and completed the action for $targetItemName! Reply DONE immediately!"
                }
                return if (confirmMb != null) {
                    "CONTEXT CHECK (PHASE 2: SELECT ITEM & CONFIRM): Menu/inventory is OPEN on screen! " +
                            "DO NOT tap movement or action controls. Inspect the screenshot to visually locate the $targetItemName icon/option (at x, y), " +
                            "then tap that icon and tap ${confirmMb.name.uppercase(Locale.US)} (${confirmMb.effectiveLabel()}) using: " +
                            "{\"do\":\"press_region\",\"x\":<icon_x>,\"y\":<icon_y>,\"region\":\"${confirmMb.name}\",\"plan\":\"Tap $targetItemName then ${confirmMb.name.uppercase(Locale.US)}\"} " +
                            "or `TAP <x> <y> ${confirmMb.name.uppercase(Locale.US)}`!"
                } else {
                    "CONTEXT CHECK (PHASE 2: VISUAL ITEM TAP): Menu/inventory is OPEN on screen! " +
                            "DO NOT tap movement or action controls. Inspect the screenshot to visually locate the $targetItemName icon/button at (x, y) and tap it directly using: " +
                            "{\"do\":\"tap\",\"x\":<icon_x>,\"y\":<icon_y>,\"plan\":\"Tap $targetItemName icon\"}!"
                }
            }
        }

        val walkMb = mbs.firstOrNull {
            val l = it.effectiveLabel().lowercase(Locale.US)
            l.contains("walk") || l.contains("move") || l.contains("forward") || l.contains("run")
        }
        val goalWantsAction = Regex("\\b(mine|chop|break|dig|attack|gather|harvest|hit|kill)\\b").containsMatchIn(goalLower)
        val goalWantsWalkFirst = Regex("\\b(walk|move|go|run)\\s+(?:to|towards|toward|up\\s+to|near|forward)\\b").containsMatchIn(goalLower) && goalWantsAction

        if (log.isEmpty()) {
            if (goalWantsWalkFirst && walkMb != null) {
                return "CONTEXT CHECK (PHASE 1 OF 2 - WALK FIRST): The user asked to walk/move to the target first before acting (\"$goal\"). " +
                        "Start by holding ${walkMb.name.uppercase(Locale.US)} (${walkMb.effectiveLabel()}) for 2200ms to approach the target!"
            }
            return "CONTEXT CHECK: Step 1 of task \"$goal\". Use the matching Macro Button with the appropriate hold duration (e.g. ~4500ms for breaking/mining, ~2000ms for walking)!"
        }
        val lastStep = workedSteps.lastOrNull().orEmpty()
        val targetCount = expectedTargetBlockCount(goalLower)
        val completedLongActions = workedSteps.count {
            Regex("for\\s+(3\\d\\d\\d|4\\d\\d\\d|5\\d\\d\\d)ms").containsMatchIn(it)
        }

        // Multi-target task context guidance
        if (targetCount > 1 && goalWantsAction) {
            if (completedLongActions >= targetCount) {
                return "CONTEXT CHECK (CRITICAL): You have completed $completedLongActions/$targetCount steps of \"$goal\"! Reply DONE immediately!"
            }
            val lastWasAction = Regex("for\\s+(3\\d\\d\\d|4\\d\\d\\d|5\\d\\d\\d)ms").containsMatchIn(lastStep)
            return if (lastWasAction) {
                "CONTEXT CHECK (MULTI-TARGET PROGRESS $completedLongActions/$targetCount): You just completed part #$completedLongActions! " +
                        "Now use LOOK UP (or `{\"do\":\"look\",\"dir\":\"up\"}`) to adjust the 3D camera toward the remaining targets!"
            } else {
                "CONTEXT CHECK (MULTI-TARGET PROGRESS $completedLongActions/$targetCount): Camera adjusted — now hold the action MB for 4500ms on the next target (or reply DONE if complete)!"
            }
        }

        // Single finite action task
        val justCompletedLongAction = Regex("for\\s+(4\\d\\d\\d|5\\d\\d\\d|3\\d\\d\\d)ms").containsMatchIn(lastStep) &&
                goalWantsAction && isFiniteActionGoal(goal)
        if (justCompletedLongAction) {
            return "CONTEXT CHECK (CRITICAL): You JUST held the action control and completed \"$lastStep\"! " +
                    "Inspect the screenshot: if the target is finished/broken or the task \"$goal\" is complete, reply with DONE immediately! Do NOT keep clicking just because the screen changed!"
        }

        // Check if a walk step just completed on a walk-then-act goal
        if (goalWantsWalkFirst && walkMb != null && lastStep.contains("used ${walkMb.name.uppercase(Locale.US)} ", ignoreCase = true)) {
            return "CONTEXT CHECK (PHASE 2 OF 2 - ACT NOW): You already walked toward the target ($lastStep)! " +
                    "Now switch to your action/mine Macro Button and hold it for 4500ms to complete the task!"
        }

        // Check if the same MB was used 2+ times in a row
        if (workedSteps.size >= 2) {
            val lastTwo = workedSteps.takeLast(2)
            val mb1 = Regex("used\\s+(MB\\d+)\\b").find(lastTwo[0])?.groupValues?.get(1)
            val mb2 = Regex("used\\s+(MB\\d+)\\b").find(lastTwo[1])?.groupValues?.get(1)
            if (mb1 != null && mb1 == mb2) {
                val mbObj = mbs.firstOrNull { it.name.equals(mb1, ignoreCase = true) }
                val lbl = mbObj?.effectiveLabel() ?: mb1
                return "CONTEXT CHECK (WARNING): You have ALREADY used $mb1 ($lbl) ${workedSteps.takeLastWhile { it.contains("used $mb1", ignoreCase = true) }.size} times in a row! " +
                        "Do NOT keep clicking $mb1 just because the screen changed! Check if you have reached the target -> switch to the next step, use LOOK UP/DOWN/LEFT/RIGHT, or reply DONE if the task \"$goal\" is finished!"
            }
        }

        return "CONTEXT CHECK: Review STEPS SO FAR against TASK \"$goal\". Have you ALREADY completed the task? If yes, reply DONE. Did a sub-step finish? Advance to the next step."
    }

    /**
     * Checks if a finite task (e.g. "mine resource", "gather 3 items", "move towards target and act")
     * has completed its required actions so Capri stops cleanly instead of repeating forever.
     */
    private fun checkAutoTaskCompletion(
        context: Context,
        goal: String,
        act: Act,
        changed: Boolean,
        unintended: Pair<String, Boolean>?,
        log: List<String>
    ): String? {
        if (!changed || unintended != null || !isFiniteActionGoal(goal)) return null
        val goalLower = goal.lowercase(Locale.US).trim()

        val isMenuGoal = isItemCreationOrSelectionGoal(goalLower)
        if (isMenuGoal) {
            val worked = log.filter { it.contains("(worked") }
            val lastWorked = worked.lastOrNull().orEmpty()
            val completed = lastWorked.contains("finish", ignoreCase = true) ||
                    lastWorked.contains("craft", ignoreCase = true) ||
                    lastWorked.contains("buy", ignoreCase = true) ||
                    lastWorked.contains("confirm", ignoreCase = true) ||
                    lastWorked.contains("tapped item", ignoreCase = true) ||
                    (act.doWhat == "seq" && lastWorked.contains("seq"))
            if (completed) {
                return "done, choom — completed \"$goal\". ${summary(log)}"
            }
        }

        val requestedCount = expectedTargetBlockCount(goalLower)

        val isMineGoal = Regex("\\b(mine|chop|break|dig|gather|harvest)\\b").containsMatchIn(goalLower)

        if (isMineGoal) {
            val neededMs = inferTargetHoldMs(goalLower, "") ?: 3000L
            val heldMs = act.durationMs ?: 0L
            val isMiningAct = (act.doWhat == "hold" && heldMs >= neededMs * 2 / 3) ||
                    (act.doWhat == "press_region" && heldMs >= neededMs * 2 / 3 && run {
                        val mb = act.region?.let { ControlRegions.getButton(context, it) }
                        val lbl = mb?.effectiveLabel()?.lowercase(Locale.US).orEmpty()
                        !lbl.contains("walk") && !lbl.contains("move") && !lbl.contains("forward")
                    })
            if (isMiningAct) {
                val completedMines = log.count {
                    it.contains("(worked") && Regex("for\\s+(\\d+)ms").find(it)?.groupValues?.get(1)?.toLongOrNull()?.let { ms -> ms >= neededMs * 2 / 3 } == true
                }
                if (completedMines >= requestedCount) {
                    return "done, choom — completed \"$goal\". ${summary(log)}"
                }
            }
        }

        if (isMineGoal) {
            val neededMs = inferTargetHoldMs(goalLower, "") ?: 3000L
            val heldMs = act.durationMs ?: 0L
            val isMiningAct = (act.doWhat == "hold" && heldMs >= neededMs * 2 / 3) ||
                    (act.doWhat == "press_region" && heldMs >= neededMs * 2 / 3 && run {
                        val mb = act.region?.let { ControlRegions.getButton(context, it) }
                        val lbl = mb?.effectiveLabel()?.lowercase(Locale.US).orEmpty()
                        !lbl.contains("walk") && !lbl.contains("move") && !lbl.contains("forward")
                    })
            if (isMiningAct) {
                val completedMines = log.count {
                    it.contains("(worked") && Regex("for\\s+(\\d+)ms").find(it)?.groupValues?.get(1)?.toLongOrNull()?.let { ms -> ms >= neededMs * 2 / 3 } == true
                }
                if (completedMines >= requestedCount) {
                    return "done, choom — completed \"$goal\". ${summary(log)}"
                }
            }
        }

        // Guard against repeating the exact same MB 4+ times in a row on a finite task when no other MB is available
        if (act.doWhat == "press_region" && !act.region.isNullOrBlank()) {
            val regUpper = act.region.uppercase(Locale.US)
            val worked = log.filter { it.contains("(worked") }
            val streak = worked.takeLastWhile { it.contains("used $regUpper ", ignoreCase = true) }.size
            if (streak >= 4 && !Regex("\\b(keep|forever|until|repeat|spam)\\b").containsMatchIn(goalLower)) {
                return "done, choom — finished \"$goal\". ${summary(log)}"
            }
        }

        return null
    }

    private suspend fun redirectBlacklistedAct(
        context: Context,
        a: Act,
        stateKey: String,
        goal: String,
        isGameMode: Boolean,
        items: List<PhoneControl.UiItem>,
        rawBytes: ByteArray?,
        ocrLines: List<String>,
        frameAnalysis: AgentVisionHelper.FrameAnalysis?,
        sw: Int,
        sh: Int,
        aspect: Float,
        log: MutableList<String>
    ): Act? {
        if (!isGameMode && items.isNotEmpty()) {
            val goalWords = goal.lowercase(Locale.US).split(Regex("\\s+")).filter { it.length >= 4 }
            val altItem = items.firstOrNull { item ->
                item.id != a.id &&
                        goalWords.any { w -> item.label.contains(w, ignoreCase = true) } &&
                        !AgentMemory.isBlacklisted(
                            stateKey,
                            "TAP #${item.id}",
                            buildMoveForAct(Act("tap", item.id, null, null, null, null, item.cx, item.cy, null, null), sw, sh, item),
                            goal,
                            aspect
                        )
            }
            if (altItem != null) {
                log.add("redirected to un-blacklisted UI element #${altItem.id} \"${altItem.label}\"")
                return Act("tap", altItem.id, null, null, null, null, altItem.cx, altItem.cy, null, null, label = altItem.label, plan = a.plan)
            }
        }

        if (a.doWhat == "press_region") {
            val altMb = ControlRegions.allMacroButtons(context).firstOrNull { mb ->
                mb.name != a.region?.lowercase(Locale.US) &&
                        !AgentMemory.isBlacklisted(
                            stateKey,
                            "PRESS_REGION ${mb.name}",
                            MoveMemory.Move(MoveMemory.Kind.PRESS_REGION, mb.normX, mb.normY, text = mb.name, label = mb.name.uppercase(Locale.US)),
                            goal,
                            aspect
                        )
            }
            if (altMb != null) {
                log.add("switched from blocked ${a.region} to ${altMb.name.uppercase(Locale.US)}")
                return Act("press_region", null, null, null, null, null, null, null, a.x2, a.y2, durationMs = a.durationMs, region = altMb.name, label = altMb.name.uppercase(Locale.US), plan = a.plan)
            }
        }

        if (rawBytes != null) {
            val keywords = listOf("continue", "resume", "play", "start", "next", "ok", "create", "confirm")
            for (kw in keywords) {
                if (goal.contains(kw, ignoreCase = true) || ocrLines.any { it.contains(kw, ignoreCase = true) }) {
                    val hit = try { TextFinder.find(rawBytes, sw, sh, kw) } catch (_: Exception) { null }
                    if (hit != null) {
                        val hitAct = Act("tap", null, null, null, null, null, hit.x, hit.y, null, null, label = "OCR_${hit.text}", plan = a.plan)
                        val hitMove = buildMoveForAct(hitAct, sw, sh)
                        if (!AgentMemory.isBlacklisted(stateKey, hitMove.describe(), hitMove, goal, aspect)) {
                            log.add("redirected to clean OCR match \"${hit.text}\" at (${hit.x}, ${hit.y})")
                            return hitAct
                        }
                    }
                }
            }
        }

        val yellowCell = frameAnalysis?.yellowTargetCells?.firstOrNull { cell ->
            val pt = GridSpec.centre(cell, sw, sh)
            val ym = if (pt != null) MoveMemory.Move(MoveMemory.Kind.TAP, pt.first.toFloat() / sw, pt.second.toFloat() / sh, label = cell) else null
            !AgentMemory.isBlacklisted(stateKey, "TAP $cell", ym, goal, aspect)
        }
        if (yellowCell != null) {
            val pt = GridSpec.centre(yellowCell, sw, sh)
            if (pt != null) {
                log.add("redirected to yellow target cell $yellowCell")
                return Act("tap", null, null, null, null, null, pt.first, pt.second, null, null, label = yellowCell, plan = a.plan)
            }
        }

        val tapX = a.x
        val tapY = a.y
        if (tapX != null && tapY != null) {
            val stepPxX = (sw * 0.085f).toInt().coerceAtLeast(75)
            val stepPxY = (sh * 0.085f).toInt().coerceAtLeast(75)
            val offsets = listOf(
                Pair(0, stepPxY),
                Pair(0, -stepPxY),
                Pair(stepPxX, 0),
                Pair(-stepPxX, 0),
                Pair(stepPxX, stepPxY),
                Pair(-stepPxX, -stepPxY)
            )
            for ((ox, oy) in offsets) {
                val sx = (tapX + ox).coerceIn(16, sw - 16)
                val sy = (tapY + oy).coerceIn(16, sh - 16)
                val tryAct = Act(a.doWhat, null, a.text, a.dir, a.app, a.say, sx, sy, a.x2, a.y2, label = a.label, plan = a.plan)
                val tryMove = buildMoveForAct(tryAct, sw, sh)
                if (!AgentMemory.isBlacklisted(stateKey, tryMove.describe(), tryMove, goal, aspect)) {
                    log.add("shifted tap outside blacklisted zone to ($sx, $sy)")
                    return tryAct
                }
            }
        }

        return null
    }

    private fun applyAppNavigationGuard(
        a: Act,
        preMatchedItem: PhoneControl.UiItem?,
        items: List<PhoneControl.UiItem>,
        goal: String,
        visitedNavTabs: MutableSet<String>,
        log: MutableList<String>
    ): Pair<Act, PhoneControl.UiItem?> {
        if (a.doWhat != "tap" && a.doWhat != "tap_text") return Pair(a, preMatchedItem)
        val tappedLabelLower = (preMatchedItem?.label ?: a.text ?: a.label).orEmpty().trim().lowercase(Locale.US)
        val isNavTab = tappedLabelLower in setOf("library", "your library", "search", "home", "playlists", "songs", "albums") ||
                tappedLabelLower.contains("library") || tappedLabelLower == "search"

        if (isNavTab && tappedLabelLower in visitedNavTabs) {
            return if (tappedLabelLower.contains("search")) {
                val inputItem = items.firstOrNull { it.kind == "input" }
                if (inputItem != null) {
                    val searchQuery = goal.replace(Regex("(?i)\\b(go|on|to|in|open|play|pick|choose|a|one|of|my|song|songs|music|mornye|spotify|and|from|library|the)\\b"), " ")
                        .trim().ifBlank { "music" }
                    log.add("prevented toggling Search closed; typing '$searchQuery' into search field #${inputItem.id}")
                    Pair(Act("type", inputItem.id, searchQuery, null, null, null, inputItem.cx, inputItem.cy, null, null, label = inputItem.label, plan = a.plan), inputItem)
                } else {
                    log.add("prevented re-tapping Search; scrolling down instead")
                    Pair(Act("scroll", null, null, "down", null, null, null, null, null, null, plan = a.plan), null)
                }
            } else {
                val playBtn = items.firstOrNull {
                    val l = it.label.lowercase(Locale.US)
                    (l == "play" || l.contains("play all") || l.contains("shuffle")) && l !in visitedNavTabs
                }
                if (playBtn != null) {
                    log.add("already in $tappedLabelLower; redirected to Play button #${playBtn.id} \"${playBtn.label}\"")
                    Pair(Act("tap", playBtn.id, null, null, null, null, playBtn.cx, playBtn.cy, null, null, label = playBtn.label, plan = a.plan), playBtn)
                } else {
                    log.add("already tapped '$tappedLabelLower'; scrolling down to reveal items")
                    Pair(Act("scroll", null, null, "down", null, null, null, null, null, null, plan = a.plan), null)
                }
            }
        } else if (isNavTab && tappedLabelLower.isNotBlank()) {
            visitedNavTabs.add(tappedLabelLower)
        }
        return Pair(a, preMatchedItem)
    }

    private sealed class ActionResult {
        data class Terminal(val message: String) : ActionResult()
        data class RequireConfirmation(val pending: Pending, val prompt: String) : ActionResult()
        data class Executed(
            val candidateMove: MoveMemory.Move,
            val actionCmd: String,
            val recordedPlacementCoord: Pair<Float, Float>?
        ) : ActionResult()
    }

    private class FastPathOutcome(
        val completedEarly: String?,
        val carriedBytes: ByteArray?,
        val carriedRoot: AccessibilityNodeInfo?,
        val stepsTaken: Int
    )

    private suspend fun executeFastPathChunk(
        context: Context,
        service: CapriAccessibilityService,
        goal: String,
        fastPathChunk: List<Act>,
        swInit: Int,
        shInit: Int,
        stateMachine: GoalStateMachine,
        log: MutableList<String>
    ): FastPathOutcome {
        log.add("fast-path: executing compound plan directly (<100ms response time)")
        var steps = 0
        for ((idx, fpAct) in fastPathChunk.withIndex()) {
            val actToRun = snapToMacroButtonIfApplicable(context, fpAct, goal, emptyList(), swInit, shInit, log)
            when (actToRun.doWhat) {
                "press_region" -> {
                    val regName = actToRun.region.orEmpty().lowercase(Locale.US)
                    val mb = ControlRegions.getButton(context, regName)
                    val p = ControlRegions.resolve(context, regName)
                    val mbDesc = mb?.effectiveLabel() ?: regName
                    CapriCursor.setStatus("using ${regName.uppercase(Locale.US)} ($mbDesc)...")
                    if (p != null && mb != null) {
                        val ms = (actToRun.durationMs ?: 120L).coerceIn(60L, 120_000L)
                        val ok = if (ms <= 160L) {
                            PhoneControl.tapAt(p.first, p.second)
                        } else {
                            PhoneControl.holdAt(p.first, p.second, ms)
                        }
                        log.add("used ${regName.uppercase(Locale.US)} ($mbDesc) at (${p.first}, ${p.second}) for ${ms}ms" + if (ok) "" else " (failed)")
                    }
                }
                "joystick" -> {
                    val dir = actToRun.dir?.takeIf { it.isNotBlank() } ?: "forward"
                    val dur = actToRun.durationMs ?: 2000L
                    CapriCursor.setStatus("joystick $dir (${dur}ms)...")
                    val regName = actToRun.region?.lowercase(Locale.US)
                    val targetMb = if (!regName.isNullOrBlank()) {
                        ControlRegions.getButton(context, regName)
                    } else {
                        ControlRegions.allMacroButtons(context).firstOrNull {
                            val l = it.effectiveLabel().lowercase(Locale.US)
                            l.contains("joystick") || l.contains("stick")
                        }
                    }
                    val cx = if (targetMb != null) (targetMb.normX * swInit).toInt() else (swInit * 0.18f).toInt()
                    val cy = if (targetMb != null) (targetMb.normY * shInit).toInt() else (shInit * 0.75f).toInt()
                    val ok = PhoneControl.joystick(cx, cy, dir, dur, shInit)
                    log.add("joystick $dir for ${dur}ms" + if (ok) "" else " (failed)")
                }
            }
            stateMachine.recordStep(actToRun)
            steps++
            if (idx < fastPathChunk.size - 1) {
                delay(120)
            }
        }

        val fpOutcome = captureOutcomeState(service, isGameMode = true, settleMs = 500L)
        val autoDone = checkAutoTaskCompletion(context, goal, fastPathChunk.last(), changed = true, unintended = null, log)
        return FastPathOutcome(autoDone, fpOutcome.bytes, fpOutcome.root, steps)
    }

    private suspend fun executeAction(
        context: Context,
        service: CapriAccessibilityService,
        a: Act,
        items: List<PhoneControl.UiItem>,
        preMatchedItem: PhoneControl.UiItem?,
        initCandidateMove: MoveMemory.Move,
        initActionCmd: String,
        initPlacementCoord: Pair<Float, Float>?,
        sw: Int,
        sh: Int,
        goal: String,
        pkg: String,
        targetAppPkg: String?,
        rp: Pending?,
        steps: Int,
        rawBytes: ByteArray?,
        sentTexts: MutableSet<String>,
        log: MutableList<String>
    ): ActionResult {
        var candidateMove = initCandidateMove
        var actionCmd = initActionCmd
        var recordedPlacementCoord = initPlacementCoord

        when (a.doWhat) {
            "done" -> return ActionResult.Terminal(a.say ?: "there, all done. don't say i never do anything for you.")
            "fail" -> return ActionResult.Terminal(a.say ?: "screen got in my way and blocked me, dummy.")
            "seq" -> {
                val fx = a.x; val fy = a.y; val tx = a.x2; val ty = a.y2
                if (fx == null || fy == null || tx == null || ty == null) {
                    log.add("seq: missing coordinates")
                } else {
                    CapriCursor.setStatus("sequence tap 1...")
                    val ok1 = PhoneControl.tapAt(fx, fy)
                    delay(260)
                    CapriCursor.setStatus("sequence tap 2...")
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
                    val dur = (a.durationMs ?: 280L).coerceIn(80L, 5000L)
                    PhoneControl.swipe(fx.toFloat(), fy.toFloat(), tx.toFloat(), ty.toFloat(), dur)
                    log.add("swiped ($fx,$fy) to ($tx,$ty) in ${dur}ms")
                }
            }
            "long_press" -> {
                CapriCursor.setStatus("holding...")
                val item = items.firstOrNull { it.id == a.id }
                val ok = when {
                    item != null -> PhoneControl.longPress(item)
                    a.x != null && a.y != null -> {
                        val (cx, cy) = PhoneControl.clampToScreen(a.x, a.y)
                        PhoneControl.longPressAt(cx, cy)
                    }
                    else -> false
                }
                log.add("long pressed" + if (ok) "" else " (failed)")
            }
            "tap" -> {
                CapriCursor.setStatus("tapping...")
                val matchedItem = preMatchedItem

                if (matchedItem != null) {
                    if (rp == null && RISKY.containsMatchIn(matchedItem.label)) {
                        val pending = Pending(goal, "tap", matchedItem.label, null, log.toList(), steps)
                        return ActionResult.RequireConfirmation(pending, "about to tap \"${matchedItem.label}\". go ahead? (yes/no)")
                    }
                    val ok = PhoneControl.tap(matchedItem)
                    log.add("tapped element #${matchedItem.id} \"${matchedItem.label}\" at (${matchedItem.cx}, ${matchedItem.cy})" + if (ok) "" else " (failed)")
                } else if (a.x != null && a.y != null) {
                    val (cx, cy) = PhoneControl.clampToScreen(a.x, a.y)
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
                } else if (!sentTexts.add(text.lowercase(Locale.US))) {
                    log.add("refused to repeat text already typed")
                } else {
                    val submit = true
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
                val dir = when (a.dir?.lowercase(Locale.US)) {
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
                val targetApp = a.app.orEmpty().lowercase(Locale.US)
                val currentPkg = pkg.lowercase(Locale.US)
                val isAlreadyInTargetApp = (targetAppPkg != null && currentPkg == targetAppPkg) ||
                        (currentPkg != "game_surface" && !currentPkg.contains("launcher") && currentPkg != "none" &&
                                (currentPkg.contains(targetApp) || targetApp.contains(currentPkg) || targetApp in setOf("library", "search", "song", "music")))

                if (isAlreadyInTargetApp) {
                    log.add("already inside target app ($pkg); ignoring OPEN '${a.app}'")
                } else {
                    val resolved = AppChooser.findBestMatch(context, targetApp)
                    if (resolved != null && (targetAppPkg == null || resolved.pkg.equals(targetAppPkg, ignoreCase = true))) {
                        val ok = AppChooser.open(context, resolved.pkg)
                        if (ok) {
                            log.add("opened ${resolved.label}")
                            delay(800)
                        } else {
                            log.add("failed to open ${a.app}")
                        }
                    } else {
                        log.add("blocked switching away from $pkg to '${a.app}'")
                    }
                }
            }
            "call" -> {
                val who = a.contact ?: a.text ?: a.label.orEmpty()
                CapriCursor.setStatus("dialing $who...")
                val ok = IntentExecutor.execute(context, com.dot.capri.intents.Cmd.Call(who))
                if (ok) {
                    log.add("opened dialer for $who — now tap Call button on screen")
                    delay(1200)
                } else {
                    log.add("call: contact '$who' not found in contacts — open Phone/WhatsApp and search manually")
                }
            }
            "text" -> {
                val who = a.contact ?: a.app.orEmpty()
                val msg = a.text.orEmpty()
                CapriCursor.setStatus("opening chat with $who...")
                val ok = IntentExecutor.execute(context, com.dot.capri.intents.Cmd.Text(who, msg))
                if (ok) {
                    log.add("opened chat for $who with draft '$msg' — now tap Send button on screen")
                    delay(1500)
                } else {
                    log.add("text: contact '$who' not found in contacts — open messaging app and search manually")
                }
            }
            "search" -> {
                val q = a.text ?: a.label.orEmpty()
                CapriCursor.setStatus("searching '$q'...")
                val ok = IntentExecutor.execute(context, com.dot.capri.intents.Cmd.Search(q))
                log.add("opened web search for '$q'" + if (ok) " — now inspect/tap results on screen" else " (failed)")
                delay(1500)
            }
            "play" -> {
                val q = a.text ?: a.label.orEmpty()
                CapriCursor.setStatus("searching media '$q'...")
                val ok = IntentExecutor.execute(context, com.dot.capri.intents.Cmd.Play(q, a.app))
                log.add("launched media query '$q'" + if (ok) " — now tap the song/video or Play button on screen" else " (failed)")
                delay(1800)
            }
            "volume" -> {
                val dir = when (a.dir?.lowercase(Locale.US)) {
                    "down", "lower", "quieter" -> com.dot.capri.intents.VolumeDirection.DOWN
                    "mute", "silence" -> com.dot.capri.intents.VolumeDirection.MUTE
                    "unmute" -> com.dot.capri.intents.VolumeDirection.UNMUTE
                    else -> com.dot.capri.intents.VolumeDirection.UP
                }
                IntentExecutor.execute(context, com.dot.capri.intents.Cmd.Volume(dir))
                log.add("adjusted volume ${dir.name.lowercase(Locale.US)}")
            }
            "media" -> {
                val act = when (a.dir?.lowercase(Locale.US)) {
                    "pause", "stop" -> com.dot.capri.intents.MediaAction.PAUSE
                    "next", "skip" -> com.dot.capri.intents.MediaAction.NEXT
                    "prev", "previous", "back" -> com.dot.capri.intents.MediaAction.PREVIOUS
                    else -> com.dot.capri.intents.MediaAction.PLAY
                }
                IntentExecutor.execute(context, com.dot.capri.intents.Cmd.MediaControl(act))
                log.add("media control ${act.name.lowercase(Locale.US)}")
            }
            "power_menu", "shutdown" -> {
                CapriCursor.setStatus("opening power menu...")
                val ok = IntentExecutor.execute(context, com.dot.capri.intents.Cmd.PowerMenu)
                log.add("opened system power menu" + if (ok) "" else " (failed)")
                delay(800)
            }
            "double_tap" -> {
                CapriCursor.setStatus("double tapping...")
                if (a.x != null && a.y != null) {
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
                val regName = a.region.orEmpty().lowercase(Locale.US)
                val mb = ControlRegions.getButton(context, regName)
                val p = ControlRegions.resolve(context, regName)
                val mbDesc = mb?.effectiveLabel() ?: regName
                CapriCursor.setStatus("using ${regName.uppercase(Locale.US)} ($mbDesc)...")
                if (p != null && mb != null) {
                    if (a.x2 != null && a.y2 != null) {
                        // Two-step MB action: tap the MB to select it, then tap target (x2, y2) on screen
                        val (destX, destY) = PhoneControl.clampToScreen(a.x2, a.y2)
                        val normX2 = destX.toFloat() / sw.coerceAtLeast(1)
                        val normY2 = destY.toFloat() / sh.coerceAtLeast(1)
                        AgentMemory.recordPlacement(pkg, normX2, normY2)
                        recordedPlacementCoord = normX2 to normY2

                        val ok1 = PhoneControl.tapAt(p.first, p.second)
                        delay(240)
                        val ok2 = PhoneControl.tapAt(destX, destY)
                        candidateMove = MoveMemory.Move(
                            kind = MoveMemory.Kind.PRESS_REGION,
                            nx = mb.normX,
                            ny = mb.normY,
                            nx2 = normX2,
                            ny2 = normY2,
                            text = regName,
                            label = regName.uppercase(Locale.US)
                        )
                        log.add("used ${regName.uppercase(Locale.US)} ($mbDesc) -> target ($destX, $destY)" + if (ok1 && ok2) "" else " (partial)")
                    } else if (a.x != null && a.y != null) {
                        // Two-step hybrid action: tap visual coordinate (a.x, a.y) on screen FIRST,
                        // then immediately tap the MB!
                        val (srcX, srcY) = PhoneControl.clampToScreen(a.x, a.y)
                        val normX = srcX.toFloat() / sw.coerceAtLeast(1)
                        val normY = srcY.toFloat() / sh.coerceAtLeast(1)
                        CapriCursor.setStatus("selecting item at ($srcX, $srcY) -> ${regName.uppercase(Locale.US)}...")

                        val ok1 = PhoneControl.tapAt(srcX, srcY)
                        delay(240)
                        val ok2 = PhoneControl.tapAt(p.first, p.second)
                        candidateMove = MoveMemory.Move(
                            kind = MoveMemory.Kind.PRESS_REGION,
                            nx = normX,
                            ny = normY,
                            nx2 = mb.normX,
                            ny2 = mb.normY,
                            text = regName,
                            label = "($srcX,$srcY)->${regName.uppercase(Locale.US)}"
                        )
                        log.add("tapped item ($srcX, $srcY) -> used ${regName.uppercase(Locale.US)} ($mbDesc)" + if (ok1 && ok2) "" else " (partial)")
                    } else {
                        val labelLower = mbDesc.lowercase(Locale.US)
                        val defaultMs = when {
                            labelLower.contains("mine") || labelLower.contains("break") || labelLower.contains("chop") ->
                                inferTargetHoldMs(goal.lowercase(Locale.US), labelLower) ?: 4700L
                            labelLower.contains("walk") || labelLower.contains("hold") -> 1500L
                            else -> 120L
                        }
                        val ms = (a.durationMs ?: defaultMs).coerceIn(60L, 120_000L)
                        val ok = if (ms <= 160L) {
                            PhoneControl.tapAt(p.first, p.second)
                        } else {
                            PhoneControl.holdAt(p.first, p.second, ms)
                        }
                        log.add("used ${regName.uppercase(Locale.US)} ($mbDesc) at (${p.first}, ${p.second}) for ${ms}ms" + if (ok) "" else " (failed)")
                    }
                } else {
                    log.add("macro button ${regName.uppercase(Locale.US)} not found")
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
            "joystick" -> {
                val dir = a.dir?.takeIf { it.isNotBlank() } ?: "forward"
                val dur = a.durationMs ?: 2000L
                CapriCursor.setStatus("joystick $dir (${dur}ms)...")
                val mbs = ControlRegions.allMacroButtons(context)
                val regName = a.region?.lowercase(Locale.US)
                val targetMb = if (!regName.isNullOrBlank()) {
                    ControlRegions.getButton(context, regName)
                } else {
                    mbs.firstOrNull {
                        val l = it.effectiveLabel().lowercase(Locale.US)
                        l.contains("joystick") || l.contains("stick")
                    }
                }
                val cx = if (targetMb != null) {
                    (targetMb.normX * sw).toInt()
                } else {
                    a.x ?: (sw * 0.18f).toInt()
                }
                val cy = if (targetMb != null) {
                    (targetMb.normY * sh).toInt()
                } else {
                    a.y ?: (sh * 0.75f).toInt()
                }
                val ok = PhoneControl.joystick(cx, cy, dir, dur, sh)
                log.add("joystick $dir for ${dur}ms" + if (ok) "" else " (failed)")
            }
            "look" -> {
                val dirLabel = a.dir?.takeIf { it.isNotBlank() } ?: ""
                CapriCursor.setStatus(if (dirLabel.isNotEmpty()) "looking $dirLabel..." else "looking...")
                val dx = a.dx ?: 0
                val dy = a.dy ?: 0
                val origin: Pair<Int, Int> = ControlRegions.resolve(context, "look")
                    ?: Pair(sw * 3 / 4, sh / 2)
                val ok = PhoneControl.look(origin.first, origin.second, dx, dy, a.durationMs ?: 250L)
                log.add("looked ${if (dirLabel.isNotEmpty()) "$dirLabel " else ""}($dx,$dy)" + if (ok) "" else " (failed)")
            }
            "tap_text" -> {
                CapriCursor.setStatus("looking for \"${a.text}\"...")
                val wanted = a.text ?: ""
                val shotBytes = rawBytes ?: service.captureScreenBitmap()
                val hit = if (shotBytes != null && wanted.isNotBlank())
                    TextFinder.find(shotBytes, sw, sh, wanted)
                else null
                if (hit == null) {
                    log.add("tap_text: \"$wanted\" not found")
                } else {
                    val (hx, hy) = PhoneControl.clampToScreen(hit.x, hit.y)
                    val ok = PhoneControl.tapAt(hx, hy)
                    candidateMove = MoveMemory.Move(
                        kind = MoveMemory.Kind.TAP_TEXT,
                        nx = hit.normX,
                        ny = hit.normY,
                        text = wanted,
                        label = hit.text
                    )
                    actionCmd = formatNormalizedActionCmd(candidateMove, a)
                    log.add("tapped text \"${hit.text}\" at ($hx, $hy)" + if (ok) "" else " (failed)")
                }
            }
            "wait" -> {
                delay(500)
                log.add("waited")
            }
            else -> log.add("unknown action")
        }

        return ActionResult.Executed(candidateMove, actionCmd, recordedPlacementCoord)
    }

    suspend fun run(
        context: Context,
        goal: String,
        resume: Pending?,
        chatVisible: suspend (Boolean) -> Unit
    ): String {
        val service = CapriAccessibilityService.instance
            ?: return "hey, my accessibility link is off. turn it on in my health screen first—i can't read your mind if i'm blind, dummy."

        // If the goal directly names any Macro Button (e.g. "tap peashooter and tap lane 1"), execute it immediately!
        if (resume == null) {
            ControlRegions.tryExecuteUserMbCommand(context, goal, chatVisible)?.let { instantResult ->
                return instantResult
            }
        }

        if (!useApi(context) && !OnDeviceAiClient.isAvailable(context)) {
            return "i need an api key or the on-device model downloaded before i can take over your screen, idiot."
        }

        AgentMemory.init(context)

        val log = ArrayList<String>(resume?.log ?: emptyList())
        var steps = resume?.stepsUsed ?: 0
        var confirmedAction: Pending? = resume
        val sentTexts = HashSet<String>()
        var previousCellHashes: LongArray? = null
        val deadClickCounts = mutableMapOf<String, Int>()
        var lastExecutedTurn: ExecutedTurn? = null
        val visitedNavTabs = mutableSetOf<String>()
        var carriedBytes: ByteArray? = null
        var carriedRoot: AccessibilityNodeInfo? = null
        var consecutiveNoOpTurns = 0

        chatVisible(false)
        CapriCursor.show(context)
        ControlRegions.setPassThrough(context, true)
        delay(250)
        var plan: String? = null

        try {
            DevOverlay.setEnabled(context, false)
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager

            val isMusicGoal = Regex("\\b(music|song|songs|track|tracks|audio|listen|spotify|playlist|liked)\\b", RegexOption.IGNORE_CASE).containsMatchIn(goal)

            val targetAppPkg: String? = if (steps == 0 && resume == null) {
                ensureRequestedAppLaunched(context, goal, log)
            } else {
                null
            }

            val stateMachine = GoalStateMachine(goal, ControlRegions.allMacroButtons(context))

            val (swInit, shInit) = PhoneControl.screenSize() ?: PhoneControl.realScreenBounds(context)
            val fastPathChunk = if (resume == null) {
                tryBuildFastPathCompoundChunk(context, goal, ControlRegions.allMacroButtons(context), swInit, shInit)
            } else null

            if (fastPathChunk != null) {
                val fpRes = executeFastPathChunk(context, service, goal, fastPathChunk, swInit, shInit, stateMachine, log)
                steps += fpRes.stepsTaken
                carriedBytes = fpRes.carriedBytes
                carriedRoot = fpRes.carriedRoot
                if (fpRes.completedEarly != null) {
                    return fpRes.completedEarly
                }
            }

            while (!CapriCursor.cancelled) {
                if (isMusicGoal && audioManager?.isMusicActive == true && steps > 0) {
                    log.add("music is actively playing on device")
                    return "playing your music. don't get used to me being your personal dj."
                }

                var root = carriedRoot ?: PhoneControl.foregroundRoot()
                var rawBytes = carriedBytes
                carriedRoot = null
                carriedBytes = null

                if (root == null && rawBytes == null) {
                    delay(350)
                    root = PhoneControl.foregroundRoot()
                }

                val items = if (root != null) PhoneControl.snapshot(root) else emptyList()
                val isGameMode = items.size < 5

                // Only capture screen bitmap when in game mode or when tags are absent.
                // In app mode with UI tags, we use the much faster tag-only method!
                if (isGameMode && rawBytes == null) {
                    rawBytes = service.captureScreenBitmap()
                }

                if (root == null && rawBytes == null) {
                    return "i can't see an app or screen surface to work in, dummy. ${summary(log)}"
                }

                val pkg = root?.packageName?.toString() ?: "game_surface"
                if (pkg in BLOCKED_PACKAGES) return "yeah, no. i'm not touching your wallet or payment apps. handle your own money."

                // If this is a pure app launch request or video playback request (e.g. "open spotify", "play a vid", "reels"),
                // verify that the app is actively visible in the foreground on the live screen!
                val goalTrimmed = goal.trim()
                val isPureOpenGoal = Regex("^\\s*(?:open|launch|start|run|switch\\s+to)\\s+[a-z0-9_\\s]+$", RegexOption.IGNORE_CASE).matches(goalTrimmed)
                val isVideoPlayGoal = Regex("^\\s*(?:play|watch|open|start|view)\\s+(?:a\\s+)?(?:reels?|videos?|vids?|shorts?)$", RegexOption.IGNORE_CASE).matches(goalTrimmed)

                if ((isPureOpenGoal || isVideoPlayGoal) && targetAppPkg != null && pkg.lowercase(Locale.US) == targetAppPkg) {
                    CapriCursor.show(context)
                    val appLabel = AppChooser.findBestMatch(context, goal)?.label ?:
                            AppChooser.launchers(context).firstOrNull { it.pkg.equals(pkg, ignoreCase = true) }?.label ?: pkg
                    val statusMsg = if (isVideoPlayGoal) "playing video in $appLabel" else "opened $appLabel"
                    CapriCursor.setStatus(statusMsg)
                    CapriCursor.pulse()
                    delay(350)
                    return "$statusMsg. next time tap it yourself, dummy."
                }

                if (steps == 0 && resume == null) {
                    AgentMemory.beginNewTaskSession(pkg, goal)
                }

                val beforeFp = ScreenFingerprint.of(rawBytes)
                if (beforeFp != null && beforeFp.shotW > 0 && beforeFp.shotH > 0) {
                    lastShotWidth = beforeFp.shotW
                    lastShotHeight = beforeFp.shotH
                }

                val frameAnalysis = if (rawBytes != null) {
                    AgentVisionHelper.processFrame(context, rawBytes, previousCellHashes)
                } else null
                previousCellHashes = frameAnalysis?.cellHashes
                val frameHash = frameAnalysis?.screenHash ?: 0L
                val (preW, preH) = PhoneControl.orientedScreenSize(lastShotWidth, lastShotHeight)
                val ocrHits = if (rawBytes != null && items.size < 10) {
                    try { TextFinder.scanAll(rawBytes, preW, preH, maxItems = 28) } catch (_: Exception) { emptyList() }
                } else emptyList()
                val ocrLines = ocrHits.map { it.text }
                val ocrSummary = ocrLines.take(6).joinToString(", ")

                if (isGameMode) {
                    // Phase 1 when MBs exist: On the first screenshot (or whenever unassigned MBs exist),
                    // automatically inspect and assign what each MB button is before choosing an action.
                    assignUnassignedMbsIfNeeded(context, rawBytes, ocrHits, log)
                }

                val stateKey = AgentMemory.buildStateKey(
                    packageName = pkg,
                    ocrCellsSummary = ocrSummary,
                    screenHash = frameHash,
                    uiLabels = if (!isGameMode) items.map { it.label } else ocrLines,
                    fingerprint = beforeFp
                )

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

                val traceHolder = IntArray(1) { -1 }

                if (chosen == null) {
                    CapriCursor.setStatus("observing...")
                    val (rawReplyWithTrace, _) = askModel(
                        context = context,
                        goal = goal,
                        pkg = pkg,
                        items = items,
                        log = log,
                        rawShot = rawBytes,
                        stateKey = stateKey,
                        visitedTabs = visitedNavTabs,
                        preScannedOcrHits = ocrHits
                    )

                    if (rawReplyWithTrace == null) {
                        consecutiveNoOpTurns++
                        if (consecutiveNoOpTurns >= 3) {
                            return "brain didn't respond after 3 tries, choom. ${summary(log)}"
                        }
                        log.add("brain unresponsive, retrying")
                        carriedBytes = rawBytes
                        carriedRoot = root
                        delay(600)
                        continue
                    }

                    val traceIdStr = rawReplyWithTrace.substringAfterLast("|_TRACE_ID_:", "").trim()
                    val lastTraceId = traceIdStr.toIntOrNull() ?: -1
                    traceHolder[0] = lastTraceId
                    val actualReply = rawReplyWithTrace.substringBeforeLast("|_TRACE_ID_:").trim()

                    val parsedActs = parseActList(actualReply, lastShotWidth, lastShotHeight)
                    Log.d(TAG, "raw reply: ${actualReply.take(300)}")
                    if (parsedActs.isEmpty()) {
                        Log.w(TAG, "unreadable reply: ${actualReply.take(120)}")
                        log.add("unreadable reply")
                        steps++
                        consecutiveNoOpTurns++
                        if (lastTraceId != -1) BrainTraceStore.updateTurnOutcome(lastTraceId, "Unreadable/Failed Parse", "FAILED")
                        if (consecutiveNoOpTurns >= 2) {
                            if (isGameMode) {
                                log.add("anti-abandonment guard: model unreadable 2 turns; performing camera look recovery")
                                val (swO, shO) = PhoneControl.orientedScreenSize(lastShotWidth, lastShotHeight)
                                val (origX, origY) = ControlRegions.resolve(context, "look") ?: Pair(swO * 3 / 4, shO / 2)
                                PhoneControl.look(origX, origY, 280, 0, 300L)
                            }
                            consecutiveNoOpTurns = 0
                            delay(400)
                            continue
                        }
                        carriedBytes = rawBytes
                        carriedRoot = root
                        delay(300)
                        continue
                    }

                    chosen = parsedActs.first()
                    chosen.plan?.let {
                        if (it.isNotBlank()) AgentMemory.updatePlan(pkg, goal, it)
                    }
                }

                var a: Act = chosen!!
                val (sw, sh) = PhoneControl.orientedScreenSize(lastShotWidth, lastShotHeight)
                val aspect = if (sh > 0) sw.toFloat() / sh.toFloat() else 0.5f

                // Anti-Surrender Guard: Prevent giving up or claiming done before action phase executed
                if (stateMachine.shouldRejectDoneOrFail(a)) {
                    log.add("anti-surrender guard: rejected premature '${a.doWhat}'; forcing action phase for \"$goal\"")
                    a = stateMachine.getActionAct(sw, sh)
                }
                // Anti-Loop Guard: Prevent repeating approach when approach is already completed
                else if (stateMachine.hasAction && stateMachine.completedApproachSteps >= 1 && stateMachine.isMovementAct(a)) {
                    log.add("anti-loop guard: approach phase already finished (${stateMachine.completedApproachSteps} step(s)); forcing action phase instead of repeating walk")
                    a = stateMachine.getActionAct(sw, sh)
                }

                if (a.doWhat == "back" && lastExecutedTurn != null) {
                    val prev = lastExecutedTurn!!
                    val reason = a.undoReason ?: "led to wrong screen (agent backed out)"
                    AgentMemory.recordOutcome(
                        context = context,
                        stateKey = prev.stateKey,
                        normalizedCommand = prev.actionCmd,
                        screenChanged = true,
                        move = prev.move,
                        isGame = prev.isGameMode,
                        goal = goal,
                        unintendedReason = reason,
                        goalScopedWrong = true
                    )
                    log.add("blacklisted previous move ${prev.actionCmd} as wrong outcome ($reason)")
                    lastExecutedTurn = null
                }

                // If MBs exist and the model output a raw coordinate tap/hold/seq right on top of an MB,
                // or referenced an MB by label, snap it to USE that MB directly (only on game surfaces).
                if (isGameMode) {
                    a = snapToMacroButtonIfApplicable(context, a, goal, ocrLines, sw, sh, log)
                }

                var recordedPlacementCoord: Pair<Float, Float>? = null
                if ((a.doWhat == "seq" || a.doWhat == "drag") && a.x2 != null && a.y2 != null) {
                    val normX2 = a.x2.toFloat() / sw.coerceAtLeast(1)
                    val normY2 = a.y2.toFloat() / sh.coerceAtLeast(1)
                    AgentMemory.recordPlacement(pkg, normX2, normY2)
                    recordedPlacementCoord = normX2 to normY2
                }

                var preMatchedItem = when {
                    a.id != null -> items.firstOrNull { it.id == a.id }
                    !a.text.isNullOrBlank() -> items.firstOrNull { it.label.contains(a.text, ignoreCase = true) }
                    a.label != null && !a.label.matches(Regex("^[A-Z][0-9]{1,2}$", RegexOption.IGNORE_CASE)) ->
                        items.firstOrNull { it.label.contains(a.label, ignoreCase = true) }
                    else -> null
                }

                if (!isGameMode) {
                    val navPair = applyAppNavigationGuard(a, preMatchedItem, items, goal, visitedNavTabs, log)
                    a = navPair.first
                    preMatchedItem = navPair.second
                }

                var candidateMove = buildMoveForAct(a, sw, sh, preMatchedItem)
                var actionCmd = formatNormalizedActionCmd(candidateMove, a)
                val currentFails = deadClickCounts[actionCmd] ?: 0

                val blockReason = AgentMemory.getBlacklistReason(stateKey, actionCmd, candidateMove, goal, aspect)
                if (blockReason != null) {
                    log.add("blacklisted move intercepted ($blockReason): $actionCmd")
                    val redirectedAct = redirectBlacklistedAct(
                        context = context,
                        a = a,
                        stateKey = stateKey,
                        goal = goal,
                        isGameMode = isGameMode,
                        items = items,
                        rawBytes = rawBytes,
                        ocrLines = ocrLines,
                        frameAnalysis = frameAnalysis,
                        sw = sw,
                        sh = sh,
                        aspect = aspect,
                        log = log
                    )
                    if (redirectedAct == null) {
                        deadClickCounts[actionCmd] = currentFails + 1
                        steps++
                        consecutiveNoOpTurns++
                        if (consecutiveNoOpTurns >= 2) {
                            if (isGameMode) {
                                log.add("anti-abandonment guard: repeated blocked move; performing camera look recovery")
                                val (swO, shO) = PhoneControl.orientedScreenSize(lastShotWidth, lastShotHeight)
                                val (origX, origY) = ControlRegions.resolve(context, "look") ?: Pair(swO * 3 / 4, shO / 2)
                                PhoneControl.look(origX, origY, 280, 0, 300L)
                            }
                            consecutiveNoOpTurns = 0
                            delay(400)
                            continue
                        }
                        carriedBytes = rawBytes
                        carriedRoot = root
                        delay(300)
                        continue
                    }
                    a = redirectedAct
                    preMatchedItem = a.id?.let { id -> items.firstOrNull { it.id == id } }
                    candidateMove = buildMoveForAct(a, sw, sh, preMatchedItem)
                    actionCmd = formatNormalizedActionCmd(candidateMove, a)
                }

                consecutiveNoOpTurns = 0

                // Gentle OCR Word Snapping when a raw tap is within 10% of an OCR button
                val snapX = a.x
                val snapY = a.y
                if (a.doWhat == "tap" && a.id == null && a.text.isNullOrBlank() && snapX != null && snapY != null && rawBytes != null) {
                    val commonButtonWords = listOf("continue", "resume", "play", "start", "next", "ok", "create", "confirm")
                    for (word in commonButtonWords) {
                        if (ocrLines.any { it.contains(word, ignoreCase = true) }) {
                            val hit = try { TextFinder.find(rawBytes, sw, sh, word) } catch (_: Exception) { null }
                            if (hit != null) {
                                val distNorm = hypot(
                                    ((snapX - hit.x).toDouble() / sw.coerceAtLeast(1)),
                                    ((snapY - hit.y).toDouble() / sh.coerceAtLeast(1))
                                )
                                if (distNorm <= 0.11) {
                                    val (hx, hy) = PhoneControl.clampToScreen(hit.x, hit.y)
                                    a = Act("tap", a.id, hit.text, a.dir, a.app, a.say, hx, hy, a.x2, a.y2, label = "OCR_${hit.text}", plan = a.plan)
                                    candidateMove = buildMoveForAct(a, sw, sh)
                                    actionCmd = formatNormalizedActionCmd(candidateMove, a)
                                    log.add("snapped nearby tap to exact OCR button \"${hit.text}\" at ($hx, $hy)")
                                    break
                                }
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

                val actionResult = executeAction(
                    context = context,
                    service = service,
                    a = a,
                    items = items,
                    preMatchedItem = preMatchedItem,
                    initCandidateMove = candidateMove,
                    initActionCmd = actionCmd,
                    initPlacementCoord = recordedPlacementCoord,
                    sw = sw,
                    sh = sh,
                    goal = goal,
                    pkg = pkg,
                    targetAppPkg = targetAppPkg,
                    rp = rp,
                    steps = steps,
                    rawBytes = rawBytes,
                    sentTexts = sentTexts,
                    log = log
                )

                when (actionResult) {
                    is ActionResult.Terminal -> return actionResult.message
                    is ActionResult.RequireConfirmation -> {
                        pending = actionResult.pending
                        return actionResult.prompt
                    }
                    is ActionResult.Executed -> {
                        candidateMove = actionResult.candidateMove
                        actionCmd = actionResult.actionCmd
                        recordedPlacementCoord = actionResult.recordedPlacementCoord
                    }
                }

                stateMachine.recordStep(a)
                steps++

                val outcome = captureOutcomeState(
                    service = service,
                    isGameMode = isGameMode,
                    settleMs = if (isGameMode) 480L else 380L
                )
                val afterRoot = outcome.root
                val afterItems = outcome.items
                val afterFp = outcome.fp
                carriedRoot = afterRoot
                carriedBytes = outcome.bytes

                val afterPkg = afterRoot?.packageName?.toString() ?: pkg
                val uiChanged = !isGameMode && afterItems.size >= 3 && screenSignature(afterRoot, afterItems, 0L) != beforeSig
                val fpChanged = if (beforeFp != null && afterFp != null) {
                    val globalFrac = beforeFp.changedFraction(afterFp)
                    val localFrac = if (candidateMove.points.isNotEmpty()) {
                        beforeFp.changedFractionAround(afterFp, candidateMove.points, radius = 0.10f, near = true, threshold = 10)
                    } else 0f
                    if (isGameMode || afterItems.size < 5) {
                        globalFrac >= 0.015f || localFrac >= 0.04f || beforeFp.meanAbsDiff(afterFp) >= 2.2f
                    } else {
                        globalFrac >= 0.14f
                    }
                } else false

                val pressedMb = if (a.doWhat == "press_region" && !a.region.isNullOrBlank()) {
                    ControlRegions.getButton(context, a.region)
                } else null
                val isContinuousHoldMb = pressedMb != null && ((a.durationMs ?: 0L) >= 500L)
                val isAlwaysProgress = a.doWhat in setOf("wait", "look", "hold", "swipe") || isContinuousHoldMb
                val changed = uiChanged || fpChanged || afterPkg != pkg || isAlwaysProgress

                val unintended = if (changed) {
                    detectUnintendedTransition(
                        goal = goal,
                        beforePkg = pkg,
                        afterPkg = afterPkg,
                        afterItems = afterItems,
                        beforeFp = beforeFp,
                        afterFp = afterFp,
                        act = a,
                        move = candidateMove,
                        targetAppPkg = targetAppPkg
                    )
                } else null

                if ((!changed || unintended != null) && recordedPlacementCoord != null) {
                    AgentMemory.removeLastPlacementNear(pkg, recordedPlacementCoord.first, recordedPlacementCoord.second)
                }

                val statusTag = when {
                    !changed -> "(failed/no screen change - blacklisted)"
                    unintended != null -> "(wrong outcome: ${unintended.first} - blacklisted)"
                    else -> "(worked/screen updated)"
                }

                if (!changed || unintended != null) {
                    deadClickCounts[actionCmd] = (deadClickCounts[actionCmd] ?: 0) + 1
                } else {
                    deadClickCounts.clear()
                }

                if (log.isNotEmpty()) {
                    val lastIdx = log.lastIndex
                    if (!log[lastIdx].contains("(worked") && !log[lastIdx].contains("(failed") && !log[lastIdx].contains("(wrong")) {
                        log[lastIdx] = "${log[lastIdx]} $statusTag"
                    }
                }

                AgentMemory.recordOutcome(
                    context = context,
                    stateKey = stateKey,
                    normalizedCommand = actionCmd,
                    screenChanged = changed,
                    move = candidateMove,
                    isGame = isGameMode,
                    goal = goal,
                    unintendedReason = unintended?.first,
                    goalScopedWrong = false
                )

                if (unintended != null && unintended.second) {
                    log.add("auto-recovering from unintended transition (${unintended.first}) with BACK")
                    PhoneControl.back()
                    delay(450)
                    carriedRoot = null
                    carriedBytes = null
                    lastExecutedTurn = null
                } else if (changed && a.doWhat != "back") {
                    lastExecutedTurn = ExecutedTurn(
                        stateKey = stateKey,
                        pkg = pkg,
                        actionCmd = actionCmd,
                        move = candidateMove,
                        isGameMode = isGameMode,
                        placedNormX = recordedPlacementCoord?.first,
                        placedNormY = recordedPlacementCoord?.second
                    )
                }

                val lastLogLine = log.lastOrNull() ?: a.doWhat
                if (traceHolder[0] != -1) {
                    BrainTraceStore.updateTurnOutcome(traceHolder[0], lastLogLine, statusTag)
                }

                if (a.doWhat == "press_region" && changed && unintended == null && !a.region.isNullOrBlank()) {
                    val regName = a.region.lowercase(Locale.US)
                    val mb = ControlRegions.getButton(context, regName)
                    if (mb != null && !mb.isAssigned()) {
                        val learnedDesc = when {
                            afterPkg != pkg -> "opened $afterPkg"
                            !isGameMode && afterItems.isNotEmpty() -> "updated screen (${afterItems.first().label.take(18)})"
                            else -> "control at (${String.format(Locale.US, "%.2f,%.2f", mb.normX, mb.normY)})"
                        }
                        ControlRegions.recordLearnedEffect(context, regName, learnedDesc)
                        log.add("learned ${regName.uppercase(Locale.US)} = $learnedDesc")
                    }
                }

                checkAutoTaskCompletion(context, goal, a, changed, unintended, log)?.let { doneMsg ->
                    return doneMsg
                }
            }
            return "h-hey, fine, i stopped! you didn't have to yank the leash like that. ${summary(log)}"
        } finally {
            ControlRegions.setPassThrough(context, false)
            CapriCursor.hide()
            chatVisible(true)
        }
    }
}
