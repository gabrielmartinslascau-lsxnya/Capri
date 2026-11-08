package com.dot.capri.agent

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.hypot

/**
 * General-purpose on-screen Macro Buttons ("MB1", "MB2", ...) stored as normalized
 * screen fractions (0..1) so they work across any app or game and survive resolution changes.
 *
 * - Spawn an MB from the Capri bubble ("[ + MB ]") and drag its hollow ring directly over any button/icon.
 * - Tap an MB ring to open a floating textbox dialog where you can type any custom label
 *   (e.g., "resume game", "menu and pause", "mine", "jump", "walk forward") or leave it as "auto".
 * - If left on "auto", Capri inspects the first screenshot (with hollow rings drawn around each MB)
 *   to automatically assign what each MB overlaps before starting the action loop.
 */
object ControlRegions {

    private const val PREFS = "capri_prefs"
    private const val KEY = "control_regions"
    private const val KEY_MBS_JSON = "macro_buttons_v1"

    private val QUICK_CHIPS = listOf("auto", "mine", "jump", "walk", "joystick", "attack", "use", "resume game", "menu")

    data class MacroButton(
        val name: String,          // e.g. "mb1", "mb2"
        val normX: Float,          // 0f..1f
        val normY: Float,          // 0f..1f
        val role: String = "auto", // "auto" or user-entered custom label
        val learnedNote: String = ""
    ) {
        fun isAssigned(): Boolean = (role.isNotBlank() && !role.equals("auto", ignoreCase = true)) || learnedNote.isNotBlank()

        fun effectiveLabel(): String = when {
            role.isNotBlank() && !role.equals("auto", ignoreCase = true) -> role
            learnedNote.isNotBlank() -> learnedNote
            else -> "unassigned"
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeOverlayViews = mutableMapOf<String, Pair<TextView, WindowManager.LayoutParams>>()
    private var activeEditorView: View? = null

    @Volatile
    private var overlaysVisible = false
    @Volatile
    private var passThroughMode = false

    fun allMacroButtons(context: Context): List<MacroButton> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rawJson = prefs.getString(KEY_MBS_JSON, null)
        val out = mutableListOf<MacroButton>()
        if (!rawJson.isNullOrBlank()) {
            try {
                val arr = JSONArray(rawJson)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.optString("name").trim().lowercase(Locale.US)
                    if (name.isNotEmpty()) {
                        out.add(
                            MacroButton(
                                name = name,
                                normX = o.optDouble("normX", 0.5).toFloat().coerceIn(0f, 1f),
                                normY = o.optDouble("normY", 0.5).toFloat().coerceIn(0f, 1f),
                                role = o.optString("role", "auto").ifBlank { "auto" },
                                learnedNote = o.optString("learnedNote", "")
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }

        val legacyRaw = prefs.getString(KEY, "") ?: ""
        legacyRaw.split(";").forEach { entry ->
            val p = entry.split(":")
            if (p.size == 3) {
                val k = p[0].trim().lowercase(Locale.US)
                val x = p[1].toFloatOrNull()
                val y = p[2].toFloatOrNull()
                if (k.isNotEmpty() && x != null && y != null && out.none { it.name == k }) {
                    out.add(MacroButton(name = k, normX = x.coerceIn(0f, 1f), normY = y.coerceIn(0f, 1f), role = "auto"))
                }
            }
        }
        return out
    }

    private fun saveMacroButtons(context: Context, list: List<MacroButton>) {
        val arr = JSONArray()
        for (mb in list) {
            arr.put(
                JSONObject()
                    .put("name", mb.name)
                    .put("normX", mb.normX.toDouble())
                    .put("normY", mb.normY.toDouble())
                    .put("role", mb.role)
                    .put("learnedNote", mb.learnedNote)
            )
        }
        val legacyStr = list.joinToString(";") { "${it.name}:${it.normX}:${it.normY}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MBS_JSON, arr.toString())
            .putString(KEY, legacyStr)
            .apply()
    }

    fun all(context: Context): Map<String, Pair<Float, Float>> {
        return allMacroButtons(context).associate { it.name to Pair(it.normX, it.normY) }
    }

    fun getButton(context: Context, name: String): MacroButton? {
        val key = name.trim().lowercase(Locale.US)
        return allMacroButtons(context).firstOrNull { it.name == key }
    }

    fun getUnassignedButtons(context: Context): List<MacroButton> {
        return allMacroButtons(context).filter { !it.isAssigned() }
    }

    private val STOP_WORDS = setOf(
        "tap", "click", "press", "touch", "use", "hit", "select", "pick", "hold", "long",
        "on", "the", "a", "an", "to", "in", "at", "and", "then", "button", "icon", "card", "slot",
        "for", "seconds", "second", "sec", "secs", "ms", "milliseconds", "please", "now", "fast", "quickly"
    )

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev else minOf(prev, dp[j - 1], dp[j]) + 1
                prev = temp
            }
        }
        return dp[b.length]
    }

    /**
     * Finds the best-matching MacroButton whose ID ("mb1") or user/learned label ("peashooter", "resume game", "mine")
     * resembles the given user phrase.
     */
    fun findMatchingMb(context: Context, rawPhrase: String): MacroButton? {
        val mbs = allMacroButtons(context)
        if (mbs.isEmpty()) return null

        val phraseLower = rawPhrase.lowercase(Locale.US).trim()
        if (phraseLower.isEmpty()) return null

        // 1. Direct MB ID match ("mb1", "mb2", ...)
        val idMatch = Regex("\\b(mb\\d+)\\b").find(phraseLower)?.groupValues?.get(1)
        if (idMatch != null) {
            mbs.firstOrNull { it.name == idMatch }?.let { return it }
        }

        // Clean action verbs & stop words from user phrase
        val userTokens = phraseLower
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in STOP_WORDS }
        val cleanedPhrase = userTokens.joinToString(" ")
        if (cleanedPhrase.isEmpty()) return null

        var bestMb: MacroButton? = null
        var bestScore = 0

        for (mb in mbs) {
            val rawLabel = mb.effectiveLabel().lowercase(Locale.US).trim()
            if (rawLabel.isEmpty() || rawLabel == "unassigned" || rawLabel.startsWith("button@(")) continue

            val labelTokens = rawLabel
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() && it !in STOP_WORDS }
            val cleanedLabel = labelTokens.joinToString(" ").ifEmpty { rawLabel }

            var score = 0
            if (cleanedPhrase == cleanedLabel || phraseLower == rawLabel) {
                score = 100
            } else if (cleanedLabel.length >= 3 && cleanedPhrase.contains(cleanedLabel)) {
                score = 85 + cleanedLabel.length.coerceAtMost(10)
            } else if (cleanedPhrase.length >= 3 && cleanedLabel.contains(cleanedPhrase)) {
                score = 78 + cleanedPhrase.length.coerceAtMost(10)
            } else {
                for (uTok in userTokens) {
                    if (uTok.length < 3) continue
                    for (lTok in labelTokens) {
                        if (lTok.length < 3) continue
                        when {
                            uTok == lTok -> score = maxOf(score, 75)
                            uTok.length >= 4 && lTok.length >= 4 && (uTok.startsWith(lTok) || lTok.startsWith(uTok)) ->
                                score = maxOf(score, 65)
                            uTok.length >= 4 && lTok.length >= 4 && levenshtein(uTok, lTok) <= 1 ->
                                score = maxOf(score, 60)
                            uTok.length >= 7 && lTok.length >= 7 && levenshtein(uTok, lTok) <= 2 ->
                                score = maxOf(score, 55)
                        }
                    }
                }
            }

            if (score > bestScore) {
                bestScore = score
                bestMb = mb
            }
        }

        return if (bestScore >= 55) bestMb else null
    }

    private sealed class DirectStep {
        data class UseMb(val mb: MacroButton, val holdMs: Long?) : DirectStep()
        data class TapTarget(val normX: Float, val normY: Float, val desc: String) : DirectStep()
    }

    private fun parseHoldDurationMs(clause: String): Long? {
        val msMatch = Regex("(\\d+)\\s*(?:ms|milliseconds?)", RegexOption.IGNORE_CASE).find(clause)
        if (msMatch != null) return msMatch.groupValues[1].toLongOrNull()

        val secMatch = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:s|sec|secs|seconds?)\\b", RegexOption.IGNORE_CASE).find(clause)
        if (secMatch != null) {
            val secs = secMatch.groupValues[1].toDoubleOrNull()
            if (secs != null) return (secs * 1000.0).toLong()
        }
        if (Regex("\\b(hold|long\\s+press)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clause)) {
            return 2500L
        }
        return null
    }

    private fun parseScreenTargetInClause(clause: String, screenW: Int, screenH: Int): DirectStep.TapTarget? {
        val lower = clause.lowercase(Locale.US).trim()

        // Universal normalized grid coordinates (completely game & app agnostic)
        val defaultRows = 5
        val defaultCols = 8
        fun calcRowY(idx: Int, totalRows: Int = defaultRows): Float {
            val topMargin = 0.22f
            val usableH = 0.64f
            return topMargin + ((idx + 0.5f) / totalRows) * usableH
        }
        fun calcColX(idx: Int, totalCols: Int = defaultCols): Float {
            val leftMargin = 0.22f
            val usableW = 0.68f
            return leftMargin + ((idx + 0.5f) / totalCols) * usableW
        }

        // 1. Check "lane 1..N", "row 1..N", "column 1..N", or "top/middle/bottom row"
        val explicitLaneMatch = Regex("\\b(?:lane|row)\\s*(\\d+)\\b").find(lower)
        val explicitColMatch = Regex("\\b(?:col|collum|colum|column|cloumn|slot|spot|tile|square|box)\\s*(\\d+)\\b").find(lower)
        val secondaryRowMatch = if (explicitLaneMatch != null) Regex("\\brow\\s*(\\d+)\\b").find(lower) else null
        val colMatch = explicitColMatch ?: secondaryRowMatch
        val explicitRowMatch = if (explicitLaneMatch == null) Regex("\\brow\\s*(\\d+)\\b").find(lower) else null

        val laneIdx = when {
            explicitLaneMatch != null -> explicitLaneMatch.groupValues[1].toInt() - 1
            explicitRowMatch != null -> explicitRowMatch.groupValues[1].toInt() - 1
            Regex("\\b(?:top|first|upper)\\s+(?:lane|row)\\b").containsMatchIn(lower) -> 0
            Regex("\\b(?:middle|center|centre|mid)\\s+(?:lane|row)\\b").containsMatchIn(lower) -> 2
            Regex("\\b(?:bottom|last|lower)\\s+(?:lane|row)\\b").containsMatchIn(lower) -> 4
            else -> -1
        }

        val colIdx = when {
            colMatch != null -> colMatch.groupValues[1].toInt() - 1
            lower.contains("back") || lower.contains("left") -> 0
            lower.contains("front") || lower.contains("right") -> defaultCols - 1
            lower.contains("mid") || lower.contains("center") -> defaultCols / 2
            else -> null
        }

        if (laneIdx >= 0 || colIdx != null) {
            val totalRows = (laneIdx + 1).coerceAtLeast(defaultRows)
            val totalCols = ((colIdx ?: 0) + 1).coerceAtLeast(defaultCols)
            val targetY = if (laneIdx >= 0) calcRowY(laneIdx, totalRows) else 0.50f
            val targetX = if (colIdx != null) calcColX(colIdx, totalCols) else 0.35f
            val desc = buildString {
                if (laneIdx >= 0) append("row ${laneIdx + 1}")
                if (colIdx != null) {
                    if (isNotEmpty()) append(" ")
                    append("col ${colIdx + 1}")
                }
            }
            return DirectStep.TapTarget(targetX, targetY, desc)
        }

        // 2. Grid cell (e.g. "C4", "E5")
        val cellMatch = Regex("\\b([a-j][1-9][0]?)\\b", RegexOption.IGNORE_CASE).find(lower)
        if (cellMatch != null) {
            val cell = cellMatch.groupValues[1].uppercase(Locale.US)
            val pt = GridSpec.centre(cell, screenW, screenH)
            if (pt != null) {
                return DirectStep.TapTarget(
                    pt.first.toFloat() / screenW.coerceAtLeast(1),
                    pt.second.toFloat() / screenH.coerceAtLeast(1),
                    cell
                )
            }
        }

        // 3. Normalized or pixel coordinate pair (e.g. "0.35 0.53")
        val coordMatch = Regex("\\b(0\\.\\d+|1\\.0)\\s*[,\\s]+\\s*(0\\.\\d+|1\\.0)\\b").find(lower)
        if (coordMatch != null) {
            val nx = coordMatch.groupValues[1].toFloatOrNull()
            val ny = coordMatch.groupValues[2].toFloatOrNull()
            if (nx != null && ny != null) {
                return DirectStep.TapTarget(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f), String.format(Locale.US, "(%.2f, %.2f)", nx, ny))
            }
        }

        // 4. Named screen area ("center", "middle of screen")
        if (Regex("\\b(?:center|middle)(?:\\s+of\\s+(?:the\\s+)?screen)?\\b").matches(lower.replace(Regex("^(?:tap|click|press|touch|on|at|in|the)\\s+"), ""))) {
            return DirectStep.TapTarget(0.50f, 0.50f, "center")
        }

        return null
    }

    private val LEADING_VERBS = Regex("^(?:tap|click|press|touch|use|hit|select|pick|hold|long\\s+press)\\s+(?:on\\s+|the\\s+)*", RegexOption.IGNORE_CASE)

    private fun normalizeLabel(s: String): String =
        s.lowercase(Locale.US).replace(LEADING_VERBS, "").replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun isWholeInputAnMbLabel(mbs: List<MacroButton>, input: String): Boolean {
        val norm = normalizeLabel(input)
        if (norm.isEmpty()) return false
        return mbs.any { mb ->
            val l = normalizeLabel(mb.effectiveLabel())
            l.isNotEmpty() && l != "unassigned" && l == norm
        }
    }

    private fun stripDurations(s: String): String =
        s.replace(Regex("\\bfor\\s+", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\d+(?:\\.\\d+)?\\s*(?:ms|milliseconds?|s|secs?|seconds?)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** True when every meaningful word of [phrase] is part of [mb]'s label (exact, prefix or 1-typo match). */
    private fun coversAllTokens(mb: MacroButton, phrase: String): Boolean {
        val lower = phrase.lowercase(Locale.US)
        if (Regex("\\b${mb.name}\\b").containsMatchIn(lower)) return true
        val labelTokens = mb.effectiveLabel().lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        val phraseTokens = lower
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 && it !in STOP_WORDS && it.toDoubleOrNull() == null }
        if (phraseTokens.isEmpty()) return false
        return phraseTokens.all { p ->
            labelTokens.any { l ->
                p == l ||
                        (p.length >= 4 && l.length >= 4 && (p.startsWith(l) || l.startsWith(p))) ||
                        (p.length >= 4 && l.length >= 4 && levenshtein(p, l) <= 1) ||
                        (p.length >= 7 && l.length >= 7 && levenshtein(p, l) <= 2)
            }
        }
    }

    /**
     * Checks if the user's natural language command mentions any Macro Button (by name or custom label,
     * e.g., "tap peashooter and tap lane 1", "resume game", "hold mine for 5s").
     * If so, executes those MB steps INSTANTLY without waiting for an LLM call!
     */
    suspend fun tryExecuteUserMbCommand(
        context: Context,
        rawInput: String,
        chatVisible: suspend (Boolean) -> Unit
    ): String? {
        val mbs = allMacroButtons(context)
        if (mbs.isEmpty()) return null

        val cleaned = rawInput.trim()
            .replace(Regex("^(?:(?:hey|yo|ok)\\s+)?capri[,!.:]?\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:(?:can|could|will|would)\\s+you\\s+|please\\s+|just\\s+|go\\s+ahead\\s+and\\s+)+", RegexOption.IGNORE_CASE), "")
            .trim()
        if (cleaned.isEmpty()) return null

        // Do not intercept pure questions ("what is mb1?", "where is peashooter?")
        if (Regex("^(?:what|where|why|how|who|when|which)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)) {
            return null
        }

        // Do not intercept multi-block / open-ended spatial goals (e.g. "mine the whole tree", "walk to the tree",
        // "take over the screen and walk towards that tree and mine one block only")
        // unless the user gave an explicit duration ("mine for 10000ms") — those need PhoneAgent's vision + camera-look loop!
        val hasExplicitDuration = Regex("\\d+(?:\\.\\d+)?\\s*(?:ms|milliseconds?|s|secs?|seconds?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)
        if (!hasExplicitDuration && Regex("\\b(take\\s+over|take\\s+control|whole|entire|all|every|towards|toward|around|until|find|search|tree|wood|log|logs|block|blocks|only|once|forest|house|stone|dirt)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)) {
            return null
        }

        val (screenW, screenH) = PhoneControl.screenSize() ?: PhoneControl.realScreenBounds(context)
        val rawClauses = if (isWholeInputAnMbLabel(mbs, cleaned)) {
            // e.g. MB labelled "menu and pause" -> never split it into "menu" + "pause"
            listOf(cleaned)
        } else {
            cleaned
                .split(Regex("\\s*(?:,|->|\\band\\s+then\\b|\\bthen\\b|\\band\\b)\\s*", RegexOption.IGNORE_CASE))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        val steps = mutableListOf<DirectStep>()
        val unresolvedClauses = mutableListOf<String>()
        var matchedAtLeastOneMb = false

        for (clause in rawClauses) {
            // Check if the clause has both an MB and a target separated by "on/to/at/in" or a trailing lane/row/col
            // e.g. "tap peashooter on lane 1" or "peashooter lane 1 collum 3"
            val inlineTarget = parseScreenTargetInClause(clause, screenW, screenH)
            val clauseWithoutTarget = if (inlineTarget != null) {
                clause.replace(Regex("\\b(?:on|to|at|in)?\\s*(?:(?:top|middle|center|bottom|first|last)\\s+)?(?:lane|row|col|collum|colum|column|cloumn|slot|spot|tile|square|box)\\s*[1-9]?\\b.*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\b(0\\.\\d+|1\\.0)\\s*[,\\s]+\\s*(0\\.\\d+|1\\.0)\\b"), "")
                    .trim()
            } else {
                clause
            }

            val mbPhrase = stripDurations(clauseWithoutTarget.ifEmpty { clause })
            // Only accept the MB if EVERY meaningful word in the clause belongs to that MB's label,
            // so normal chat/requests like "open the settings menu" never fire an MB by accident.
            val mbMatch = findMatchingMb(context, mbPhrase)?.takeIf { coversAllTokens(it, mbPhrase) }
            if (mbMatch != null) {
                matchedAtLeastOneMb = true
                val explicitHoldMs = parseHoldDurationMs(clause)
                val explicitlyAskedTap = Regex("\\b(tap|click)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clause)
                val lblLower = mbMatch.effectiveLabel().lowercase(Locale.US)
                val inferredHoldMs = when {
                    explicitHoldMs != null -> explicitHoldMs
                    explicitlyAskedTap -> null
                    lblLower.contains("hold") || lblLower.contains("charge") -> 2500L
                    lblLower.contains("walk") || lblLower.contains("move") || lblLower.contains("forward") || lblLower.contains("run") -> 1500L
                    else -> null
                }
                steps.add(DirectStep.UseMb(mbMatch, inferredHoldMs))
                if (inlineTarget != null) {
                    steps.add(inlineTarget)
                }
            } else if (inlineTarget != null) {
                val lastStep = steps.lastOrNull()
                // If previous step was a TapTarget that lacked a column, merge this column into it!
                if (lastStep is DirectStep.TapTarget && inlineTarget.desc.contains("col") && !lastStep.desc.contains("col")) {
                    steps[steps.lastIndex] = DirectStep.TapTarget(inlineTarget.normX, lastStep.normY, "${lastStep.desc} ${inlineTarget.desc}")
                } else {
                    steps.add(inlineTarget)
                }
            } else {
                unresolvedClauses.add(clause)
            }
        }

        // Filter out pure placement/grammar filler words from unresolvedClauses (e.g. "on row 2", "collum 3", "grid", "spot")
        val filteredUnresolved = unresolvedClauses.filterNot { unres ->
            val u = unres.lowercase(Locale.US).trim()
            u.isEmpty() || Regex("^(?:on|in|to|at|the|board|grid|screen|field|spot|tile|lane|row|col|collum|colum|column|cloumn|slot|box|square|\\d+|and)+$").matches(u.replace(Regex("[^a-z0-9]"), ""))
        }

        // If any clause was an unresolved natural-language instruction (e.g. "walk towards that tree and mine"),
        // do NOT fire a partial blind MB tap — let PhoneAgent execute the complete multi-step goal in order!
        if (!matchedAtLeastOneMb || steps.isEmpty() || filteredUnresolved.isNotEmpty()) return null

        // Execute all resolved MB & target steps INSTANTLY!
        chatVisible(false)
        CapriCursor.show(context)
        setPassThrough(context, true)
        val executedLog = mutableListOf<String>()

        try {
            kotlinx.coroutines.delay(160)
            val (w, h) = PhoneControl.screenSize() ?: PhoneControl.realScreenBounds(context)
            for ((idx, step) in steps.withIndex()) {
                if (CapriCursor.cancelled) break
                when (step) {
                    is DirectStep.UseMb -> {
                        val px = (step.mb.normX * w).toInt().coerceIn(0, (w - 1).coerceAtLeast(1))
                        val py = (step.mb.normY * h).toInt().coerceIn(0, (h - 1).coerceAtLeast(1))
                        val label = step.mb.effectiveLabel()
                        val hold = step.holdMs
                        CapriCursor.setStatus("${step.mb.name.uppercase(Locale.US)} ($label)...")
                        if (hold != null && hold > 180L) {
                            PhoneControl.holdAt(px, py, hold)
                            executedLog.add("held ${step.mb.name.uppercase(Locale.US)} ($label) for ${hold}ms")
                        } else {
                            PhoneControl.tapAt(px, py)
                            executedLog.add("tapped ${step.mb.name.uppercase(Locale.US)} ($label)")
                        }
                    }
                    is DirectStep.TapTarget -> {
                        val px = (step.normX * w).toInt().coerceIn(0, (w - 1).coerceAtLeast(1))
                        val py = (step.normY * h).toInt().coerceIn(0, (h - 1).coerceAtLeast(1))
                        CapriCursor.setStatus("tapping ${step.desc}...")
                        PhoneControl.tapAt(px, py)
                        executedLog.add("tapped ${step.desc}")
                    }
                }
                if (idx < steps.lastIndex) {
                    kotlinx.coroutines.delay(220)
                }
            }
            kotlinx.coroutines.delay(180)
        } finally {
            setPassThrough(context, false)
            CapriCursor.hide()
            chatVisible(true)
        }

        return "done: ${executedLog.joinToString(" -> ")}."
    }

    fun names(context: Context): String {
        val list = allMacroButtons(context)
        if (list.isEmpty()) return ""
        return list.joinToString(", ") { mb ->
            "${mb.name.uppercase(Locale.US)}(${mb.effectiveLabel()})"
        }
    }

    /**
     * General-purpose prompt description of all user-placed Macro Buttons.
     * When screen dimensions are provided, annotates each MB with its visual Grid Cell and
     * explicit gameplay safety notes (e.g. marking pause/resume buttons to prevent menu loops).
     */
    fun describeForPrompt(context: Context, screenW: Int = 0, screenH: Int = 0): String {
        val list = allMacroButtons(context)
        if (list.isEmpty()) return "none"
        val isLandscape = screenW > screenH
        val cols = if (isLandscape) 10 else 6
        val rows = if (isLandscape) 6 else 10
        return list.joinToString(" | ") { mb ->
            val labelUp = mb.name.uppercase(Locale.US)
            val coords = String.format(Locale.US, "(x=%.2f, y=%.2f)", mb.normX, mb.normY)
            val label = mb.effectiveLabel()
            val colLetter = 'A' + (mb.normX * cols).toInt().coerceIn(0, cols - 1)
            val rowNum = (mb.normY * rows).toInt().coerceIn(0, rows - 1) + 1
            val cellStr = if (screenW > 0 && screenH > 0) "[Cell $colLetter$rowNum] " else ""
            val isPauseOrResume = label.contains("resume", ignoreCase = true) ||
                    (label.contains("menu", ignoreCase = true) && !label.contains("select", ignoreCase = true)) ||
                    label.contains("pause", ignoreCase = true)
            if (isPauseOrResume) {
                "$labelUp = \"$label\" ${cellStr}at $coords (PAUSE/RESUME - ONLY tap if game is paused!)"
            } else {
                "$labelUp = \"$label\" ${cellStr}at $coords (use '$labelUp' to tap, or '$labelUp <cell>' to select card and target cell e.g. '$labelUp C3')"
            }
        }
    }

    /**
     * Checks if any unassigned MB overlaps visible OCR text on screen and assigns that text as its learnedNote.
     */
    fun autoInspectOverlapsFromScreen(
        context: Context,
        ocrHits: List<TextFinder.Hit>
    ) {
        val current = allMacroButtons(context).toMutableList()
        if (current.isEmpty() || ocrHits.isEmpty()) return

        var changed = false
        for (i in current.indices) {
            val mb = current[i]
            // Never overwrite a user label or an already-learned/model-assigned note with stray OCR text
            if (mb.isAssigned()) continue

            val nearbyHit = ocrHits.minByOrNull { hit ->
                hypot((hit.normX - mb.normX).toDouble(), (hit.normY - mb.normY).toDouble())
            }?.takeIf { hit ->
                hypot((hit.normX - mb.normX).toDouble(), (hit.normY - mb.normY).toDouble()) <= 0.09
            }

            if (nearbyHit != null) {
                val word = nearbyHit.text.trim()
                if (word.isNotBlank() && mb.learnedNote != word) {
                    current[i] = mb.copy(learnedNote = word)
                    changed = true
                }
            }
        }

        if (changed) {
            saveMacroButtons(context, current)
            current.forEach { refreshBadgeTextOnMain(it) }
        }
    }

    /**
     * Draws hollow neon rings and "MB1", "MB2" tags at each MB's position on a copy of the screenshot
     * so the vision model can see both the MB tag AND the icon inside the ring on Frame 1.
     */
    fun annotateScreenshotWithMbRings(shotBytes: ByteArray, buttons: List<MacroButton>): ByteArray {
        val src = try {
            BitmapFactory.decodeByteArray(shotBytes, 0, shotBytes.size)
        } catch (_: Exception) {
            null
        } ?: return shotBytes

        val bmp = src.copy(Bitmap.Config.ARGB_8888, true) ?: return shotBytes
        val canvas = Canvas(bmp)
        val minDim = bmp.width.coerceAtMost(bmp.height).toFloat()
        val ringRadius = (minDim * 0.042f).coerceIn(18f, 48f)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (minDim * 0.006f).coerceIn(3f, 7f)
            color = Color.parseColor("#FF2A85")
        }
        val tagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#DD101018")
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00F0FF")
            textSize = (minDim * 0.028f).coerceIn(14f, 30f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        for (mb in buttons) {
            val cx = (mb.normX * bmp.width).coerceIn(ringRadius, bmp.width - ringRadius)
            val cy = (mb.normY * bmp.height).coerceIn(ringRadius, bmp.height - ringRadius)
            canvas.drawCircle(cx, cy, ringRadius, ringPaint)

            val label = mb.name.uppercase(Locale.US)
            val textW = textPaint.measureText(label)
            val tagY = if (cy - ringRadius - 24f > 10f) cy - ringRadius - 8f else cy + ringRadius + textPaint.textSize + 6f
            canvas.drawRoundRect(
                cx - textW / 2f - 8f,
                tagY - textPaint.textSize,
                cx + textW / 2f + 8f,
                tagY + 6f,
                8f,
                8f,
                tagBgPaint
            )
            canvas.drawText(label, cx, tagY, textPaint)
        }

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    /**
     * Parses the Frame-1 vision response (e.g. "MB1: jump | MB2: mine | MB3: pause menu")
     * and stores the discovered labels on any unassigned MBs.
     */
    fun applyModelAssignedLabels(context: Context, rawReply: String): Int {
        val current = allMacroButtons(context).toMutableList()
        if (current.isEmpty()) return 0
        var assignedCount = 0
        val regex = Regex("\\b(MB\\d+)\\s*[:=-]\\s*([^|\\n,;]+)", RegexOption.IGNORE_CASE)
        for (match in regex.findAll(rawReply)) {
            val name = match.groupValues[1].trim().lowercase(Locale.US)
            val desc = match.groupValues[2].trim().trim('"', '\'', '.', ' ')
                .take(36)
            if (desc.isBlank() || desc.equals("unknown", ignoreCase = true) || desc.equals("none", ignoreCase = true)) continue
            val idx = current.indexOfFirst { it.name == name }
            if (idx >= 0 && !current[idx].isAssigned()) {
                current[idx] = current[idx].copy(learnedNote = desc)
                assignedCount++
            }
        }
        // Fallback: if any unassigned MB still has no note, give it a positional note so we don't re-query every frame
        for (i in current.indices) {
            if (!current[i].isAssigned()) {
                val posDesc = String.format(Locale.US, "button@(%.2f,%.2f)", current[i].normX, current[i].normY)
                current[i] = current[i].copy(learnedNote = posDesc)
            }
        }
        saveMacroButtons(context, current)
        current.forEach { refreshBadgeTextOnMain(it) }
        return assignedCount
    }

    fun set(context: Context, name: String, x: Float, y: Float, role: String? = null, learnedNote: String? = null) {
        val clean = name.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9_]"), "")
        if (clean.isEmpty()) return
        val current = allMacroButtons(context).toMutableList()
        val idx = current.indexOfFirst { it.name == clean }
        val existing = if (idx >= 0) current[idx] else null
        val newRole = (role ?: existing?.role ?: "auto").trim().ifBlank { "auto" }
        val newNote = learnedNote ?: existing?.learnedNote ?: ""
        val updatedMb = MacroButton(
            name = clean,
            normX = x.coerceIn(0f, 1f),
            normY = y.coerceIn(0f, 1f),
            role = newRole,
            learnedNote = newNote
        )
        if (idx >= 0) current[idx] = updatedMb else current.add(updatedMb)
        saveMacroButtons(context, current)
        refreshBadgeTextOnMain(updatedMb)
    }

    fun recordLearnedEffect(context: Context, name: String, effectSummary: String) {
        val clean = name.trim().lowercase(Locale.US)
        val current = allMacroButtons(context).toMutableList()
        val idx = current.indexOfFirst { it.name == clean }
        if (idx < 0) return
        val old = current[idx]
        if (old.role != "auto" && old.role.isNotBlank()) return
        current[idx] = old.copy(learnedNote = effectSummary.take(40))
        saveMacroButtons(context, current)
        refreshBadgeTextOnMain(current[idx])
    }

    fun remove(context: Context, name: String) {
        val clean = name.trim().lowercase(Locale.US)
        val updated = allMacroButtons(context).filterNot { it.name == clean }
        saveMacroButtons(context, updated)
    }

    fun clearAll(context: Context) {
        saveMacroButtons(context, emptyList())
        hideOverlays(context)
    }

    fun resolve(context: Context, name: String): Pair<Int, Int>? {
        val mb = getButton(context, name) ?: return null
        val (w, h) = PhoneControl.screenSize() ?: PhoneControl.realScreenBounds(context)
        return Pair(
            (mb.normX * w).toInt().coerceIn(0, (w - 1).coerceAtLeast(1)),
            (mb.normY * h).toInt().coerceIn(0, (h - 1).coerceAtLeast(1))
        )
    }

    fun addNextMacroButton(context: Context): MacroButton {
        val appCtx = context.applicationContext
        val current = allMacroButtons(appCtx).toMutableList()
        var index = 1
        while (current.any { it.name == "mb$index" }) {
            index++
        }
        val name = "mb$index"
        val offsetCol = ((index - 1) % 4) - 1
        val offsetRow = ((index - 1) / 4) % 3
        val startX = (0.45f + offsetCol * 0.10f).coerceIn(0.15f, 0.85f)
        val startY = (0.45f + offsetRow * 0.10f).coerceIn(0.20f, 0.80f)
        val created = MacroButton(name = name, normX = startX, normY = startY, role = "auto")
        current.add(created)
        saveMacroButtons(appCtx, current)
        showOverlays(appCtx)
        return created
    }

    fun areOverlaysVisible(): Boolean = overlaysVisible && activeOverlayViews.isNotEmpty()

    fun toggleOverlays(context: Context): Boolean {
        val appCtx = context.applicationContext
        return if (areOverlaysVisible()) {
            hideOverlays(appCtx)
            false
        } else {
            if (allMacroButtons(appCtx).isEmpty()) {
                addNextMacroButton(appCtx)
            } else {
                showOverlays(appCtx)
            }
            true
        }
    }

    fun showOverlays(context: Context) {
        val appCtx = context.applicationContext
        if (!Settings.canDrawOverlays(appCtx)) return
        mainHandler.post {
            overlaysVisible = true
            val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
            val (screenW, screenH) = PhoneControl.realScreenBounds(appCtx)
            val buttons = allMacroButtons(appCtx)

            val validNames = buttons.map { it.name }.toSet()
            val toRemove = activeOverlayViews.keys.filter { it !in validNames }
            for (k in toRemove) {
                activeOverlayViews.remove(k)?.first?.let { v ->
                    try { wm.removeView(v) } catch (_: Exception) {}
                }
            }

            for (mb in buttons) {
                val existing = activeOverlayViews[mb.name]
                if (existing != null) {
                    updateBadgeLabel(existing.first, mb)
                } else {
                    createAndAddBadgeView(appCtx, wm, mb, screenW, screenH)
                }
            }
        }
    }

    fun hideOverlays(context: Context) {
        val appCtx = context.applicationContext
        mainHandler.post {
            overlaysVisible = false
            val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            dismissActiveEditor(wm)
            for ((_, pair) in activeOverlayViews) {
                try { wm?.removeView(pair.first) } catch (_: Exception) {}
            }
            activeOverlayViews.clear()
        }
    }

    @Suppress("DEPRECATION")
    private fun overlayFlags(enabled: Boolean): Int =
        if (enabled) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
        }

    fun setPassThrough(context: Context, enabled: Boolean) {
        val appCtx = context.applicationContext
        passThroughMode = enabled
        val applyUpdate = Runnable {
            val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@Runnable
            if (enabled) dismissActiveEditor(wm)
            for ((_, pair) in activeOverlayViews) {
                val (view, params) = pair
                params.flags = overlayFlags(enabled)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    params.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                // Keep overlay badges visible (translucent at 0.65f, well below Android 12's 0.8f obscuring limit)
                // so user can clearly see where all MBs are during active play!
                view.alpha = if (enabled) 0.65f else 0.95f
                view.visibility = View.VISIBLE
                try {
                    if (view.isAttachedToWindow) wm.updateViewLayout(view, params)
                } catch (_: Exception) {}
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyUpdate.run()
        } else {
            mainHandler.post(applyUpdate)
        }
    }

    fun setCaptureHidden(hidden: Boolean) {
        mainHandler.post {
            for ((_, pair) in activeOverlayViews) {
                pair.first.alpha = when {
                    hidden -> 0f
                    passThroughMode -> 0.65f
                    else -> 0.95f
                }
                pair.first.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            }
        }
    }

    private fun refreshBadgeTextOnMain(mb: MacroButton) {
        mainHandler.post {
            activeOverlayViews[mb.name]?.first?.let { updateBadgeLabel(it, mb) }
        }
    }

    private fun updateBadgeLabel(tv: TextView, mb: MacroButton) {
        val up = mb.name.uppercase(Locale.US)
        val tag = when {
            mb.role.isNotBlank() && !mb.role.equals("auto", ignoreCase = true) -> "\n${mb.role.take(10)}"
            mb.learnedNote.isNotBlank() -> "\n${mb.learnedNote.take(10)}"
            else -> "\n(auto)"
        }
        tv.text = "$up$tag"
    }

    private fun dismissActiveEditor(wm: WindowManager?) {
        val v = activeEditorView ?: return
        activeEditorView = null
        try {
            wm?.removeView(v)
        } catch (_: Exception) {
        }
    }

    /**
     * Opens a compact floating overlay dialog with a custom textbox so the user can name
     * an MB anything they want (e.g. "resume game", "menu and pause", "mine", "jump") or leave it as "auto".
     */
    private fun showMbLabelDialog(appCtx: Context, wm: WindowManager, mbName: String) {
        dismissActiveEditor(wm)
        val currentMb = getButton(appCtx, mbName) ?: return
        val density = appCtx.resources.displayMetrics.density
        val pad = (12 * density).toInt()

        val root = LinearLayout(appCtx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                setColor(Color.parseColor("#F214141F"))
                setStroke((2 * density).toInt(), Color.parseColor("#FF2A85"))
            }
        }

        val title = TextView(appCtx).apply {
            text = "Assign ${mbName.uppercase(Locale.US)} (or leave blank for auto-detect)"
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        root.addView(title)

        val editText = EditText(appCtx).apply {
            val initVal = when {
                currentMb.role != "auto" -> currentMb.role
                currentMb.learnedNote.isNotBlank() -> currentMb.learnedNote
                else -> ""
            }
            setText(initVal)
            setSelection(text.length)
            hint = "e.g. resume game, mine, jump, peashooter..."
            setHintTextColor(Color.parseColor("#88AAAA"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * density
                setColor(Color.parseColor("#FF222233"))
                setStroke((1 * density).toInt(), Color.parseColor("#44FFFFFF"))
            }
            val ep = (8 * density).toInt()
            setPadding(ep, ep, ep, ep)
        }
        val editLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (8 * density).toInt()
        }
        root.addView(editText, editLp)

        // Quick-pick row
        val chipRow = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            val topM = (6 * density).toInt()
            setPadding(0, topM, 0, topM)
        }
        for (chip in QUICK_CHIPS.take(5)) {
            val chipTv = TextView(appCtx).apply {
                text = chip
                setTextColor(Color.parseColor("#00F0FF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                val cp = (6 * density).toInt()
                setPadding(cp, cp / 2, cp, cp / 2)
                background = GradientDrawable().apply {
                    cornerRadius = 6f * density
                    setColor(Color.parseColor("#26263A"))
                }
                setOnClickListener {
                    editText.setText(if (chip == "auto") "" else chip)
                    editText.setSelection(editText.text.length)
                }
            }
            val clp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (5 * density).toInt()
            }
            chipRow.addView(chipTv, clp)
        }
        root.addView(chipRow)

        val btnRow = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val deleteBtn = TextView(appCtx).apply {
            text = "Delete"
            setTextColor(Color.parseColor("#FF5566"))
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val bp = (8 * density).toInt()
            setPadding(bp, bp, bp, bp)
            setOnClickListener {
                remove(appCtx, mbName)
                activeOverlayViews.remove(mbName)?.first?.let { v ->
                    try { wm.removeView(v) } catch (_: Exception) {}
                }
                dismissActiveEditor(wm)
                Toast.makeText(appCtx, "Deleted ${mbName.uppercase(Locale.US)}", Toast.LENGTH_SHORT).show()
            }
        }

        val cancelBtn = TextView(appCtx).apply {
            text = "Cancel"
            setTextColor(Color.LTGRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val bp = (8 * density).toInt()
            setPadding(bp, bp, bp, bp)
            setOnClickListener { dismissActiveEditor(wm) }
        }

        val saveBtn = TextView(appCtx).apply {
            text = "Save"
            setTextColor(Color.parseColor("#00F0FF"))
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val bp = (8 * density).toInt()
            setPadding(bp, bp, bp, bp)
            setOnClickListener {
                val entered = editText.text?.toString()?.trim().orEmpty()
                val latest = getButton(appCtx, mbName) ?: currentMb
                val newRole = if (entered.isBlank() || entered.equals("auto", ignoreCase = true)) "auto" else entered
                val newNote = if (newRole == "auto") "" else entered
                set(appCtx, mbName, latest.normX, latest.normY, role = newRole, learnedNote = newNote)
                dismissActiveEditor(wm)
                val msg = if (newRole == "auto") "${mbName.uppercase(Locale.US)} set to Auto-Detect on 1st screenshot" else "${mbName.uppercase(Locale.US)} assigned to \"$newRole\""
                Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show()
            }
        }

        btnRow.addView(deleteBtn)
        btnRow.addView(cancelBtn)
        btnRow.addView(saveBtn)
        root.addView(btnRow)

        val widthPx = (290 * density).toInt()
        val params = WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }

        try {
            wm.addView(root, params)
            activeEditorView = root
            editText.requestFocus()
        } catch (_: Exception) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createAndAddBadgeView(
        appCtx: Context,
        wm: WindowManager,
        mb: MacroButton,
        screenW: Int,
        screenH: Int
    ) {
        val density = appCtx.resources.displayMetrics.density
        val sizePx = (48 * density).toInt()
        val halfPx = sizePx / 2

        val tv = TextView(appCtx).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            setLineSpacing(0f, 0.9f)
            alpha = if (passThroughMode) 0.50f else 0.95f
            // Hollow ring background so the icon underneath the MB remains clearly visible!
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#38101018"))
                setStroke((2.5f * density).toInt(), Color.parseColor("#FF2A85"))
            }
            updateBadgeLabel(this, mb)
        }

        // Allow the center of the MB ring to reach the extreme corners (0,0 to screenW,screenH) in both portrait & landscape
        val initX = ((mb.normX * screenW).toInt() - halfPx).coerceIn(-halfPx, (screenW - halfPx).coerceAtLeast(1))
        val initY = ((mb.normY * screenH).toInt() - halfPx).coerceIn(-halfPx, (screenH - halfPx).coerceAtLeast(1))

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayFlags(passThroughMode),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initX
            y = initY
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        var downRawX = 0f
        var downRawY = 0f
        var startParamX = 0
        var startParamY = 0
        var dragged = false
        var longPressTriggered = false

        val longPressRunnable = Runnable {
            if (!dragged) {
                longPressTriggered = true
                remove(appCtx, mb.name)
                activeOverlayViews.remove(mb.name)?.first?.let { v ->
                    try { wm.removeView(v) } catch (_: Exception) {}
                }
                Toast.makeText(appCtx, "Removed ${mb.name.uppercase(Locale.US)}", Toast.LENGTH_SHORT).show()
            }
        }

        tv.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startParamX = params.x
                    startParamY = params.y
                    dragged = false
                    longPressTriggered = false
                    mainHandler.postDelayed(longPressRunnable, 700L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (hypot(dx.toDouble(), dy.toDouble()) > 10 * density) {
                        dragged = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (dragged && !longPressTriggered) {
                        val (curW, curH) = PhoneControl.realScreenBounds(appCtx)
                        // Allow dragging the MB ring center all the way into any corner or display cutout edge
                        params.x = (startParamX + dx.toInt()).coerceIn(-halfPx, (curW - halfPx).coerceAtLeast(1))
                        params.y = (startParamY + dy.toInt()).coerceIn(-halfPx, (curH - halfPx).coerceAtLeast(1))
                        try {
                            if (v.isAttachedToWindow) wm.updateViewLayout(v, params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!longPressTriggered) {
                        val (curW, curH) = PhoneControl.realScreenBounds(appCtx)
                        val centerX = ((params.x + halfPx).toFloat() / curW.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val centerY = ((params.y + halfPx).toFloat() / curH.coerceAtLeast(1)).coerceIn(0f, 1f)
                        if (dragged) {
                            val latest = getButton(appCtx, mb.name)
                            // When dragged to a new spot, if role is "auto", clear stale learnedNote so Capri re-learns the new spot
                            val keptNote = if (latest?.role == "auto") "" else (latest?.learnedNote ?: "")
                            set(appCtx, mb.name, centerX, centerY, role = latest?.role ?: mb.role, learnedNote = keptNote)
                        } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                            // Short tap opens the custom textbox dialog to type any label!
                            showMbLabelDialog(appCtx, wm, mb.name)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(tv, params)
            activeOverlayViews[mb.name] = Pair(tv, params)
        } catch (_: Exception) {
        }
    }
}
