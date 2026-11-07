package com.dot.capri.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 3-Tier Memory Engine for Capri:
 * 1. Working Strategic Plan (persisted across frames so she never forgets her goal/plan).
 * 2. Spatial Board & Lane Ledger (tracks exact (x,y) placements & lane balance so she never stacks 3 plants on 1 lane while leaving others empty).
 * 3. Cross-Session Disk Memory (concepts, verified actions, blacklisted dead actions, and wrong-outcome actions via MoveMemory).
 */
object AgentMemory {

    private const val FILE_NAME = "capri_agent_memory.json"
    private const val SCHEMA_VERSION = 2

    data class PlacementPoint(
        val normX: Float,
        val normY: Float,
        val laneIndex: Int, // 0..4 (5 horizontal rows in gameplay area)
        val colIndex: Int,  // 0..8 (9 vertical columns in gameplay area)
        val label: String,
        val timestamp: Long
    )

    // Tier 1: Active strategic plan per package/goal
    private val activePlans = mutableMapOf<String, String>()

    // Tier 2: Spatial placement ledger per package
    private val spatialLedger = mutableMapOf<String, MutableList<PlacementPoint>>()
    private var lastSelectedCardCoord: Pair<Float, Float>? = null
    private var lastSelectedCardTime: Long = 0L

    // Tier 3: Outcome & concept memory
    private val deadActionsByState = mutableMapOf<String, MutableSet<String>>()
    private val wrongActionsByState = mutableMapOf<String, MutableMap<String, String>>()
    private val verifiedActionsByState = mutableMapOf<String, MutableMap<String, Int>>()
    private val recentSessionLog = ArrayDeque<String>(14)

    // Cross-screen navigation map: pkg -> (parentTabOrButtonLabel -> Set of child labels revealed)
    private val appBreadcrumbs = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()

    private var memoryCache: JSONObject? = null
    private var initialized = false

    // Standard 5-lane Y centers for landscape grid/lawn games (e.g., PvZ lawn rows 1..5; Row 3 center = 0.53f)
    private val LANE_Y_CENTERS = floatArrayOf(0.25f, 0.39f, 0.53f, 0.68f, 0.82f)
    // Center-out lane priority: Row 3 (middle) first, then Rows 2 & 4, then Rows 1 & 5
    private val CENTER_OUT_LANES = intArrayOf(2, 1, 3, 0, 4)
    // Standard X columns for planting (left-to-right defense columns)
    private val COL_X_CENTERS = floatArrayOf(0.26f, 0.34f, 0.42f, 0.50f, 0.58f, 0.66f, 0.74f, 0.82f, 0.89f)
    // Tracks outer lanes that failed to accept a plant (e.g., unsodded dirt in PvZ 1-1 / 1-2)
    private val unsoddedLanesByPkg = mutableMapOf<String, MutableSet<Int>>()

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        MoveMemory.init(context)
        load(context)
    }

    private fun getFile(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    private fun load(context: Context): JSONObject {
        if (memoryCache != null) return memoryCache!!
        val f = getFile(context)
        val loaded = if (f.exists()) {
            try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() }
        } else {
            JSONObject()
        }
        val root = if (loaded.optInt("_schema_version", 1) < SCHEMA_VERSION) {
            runCatching { f.delete() }
            JSONObject().put("_schema_version", SCHEMA_VERSION)
        } else {
            loaded
        }
        memoryCache = root

        val deadObj = root.optJSONObject("_dead_actions")
        if (deadObj != null) {
            for (key in deadObj.keys()) {
                val arr = deadObj.optJSONArray(key) ?: continue
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) set.add(arr.optString(i))
                deadActionsByState[key] = set
            }
        }

        val wrongObj = root.optJSONObject("_wrong_actions")
        if (wrongObj != null) {
            for (key in wrongObj.keys()) {
                val inner = wrongObj.optJSONObject(key) ?: continue
                val map = mutableMapOf<String, String>()
                for (cmd in inner.keys()) map[cmd] = inner.optString(cmd, "unintended screen change")
                wrongActionsByState[key] = map
            }
        }

        val verifiedObj = root.optJSONObject("_verified_actions")
        if (verifiedObj != null) {
            for (key in verifiedObj.keys()) {
                val inner = verifiedObj.optJSONObject(key) ?: continue
                val map = mutableMapOf<String, Int>()
                for (cmd in inner.keys()) map[cmd] = inner.optInt(cmd, 1)
                verifiedActionsByState[key] = map
            }
        }

        val breadObj = root.optJSONObject("_app_breadcrumbs")
        if (breadObj != null) {
            for (pkgKey in breadObj.keys()) {
                val pkgMap = breadObj.optJSONObject(pkgKey) ?: continue
                val targetMap = appBreadcrumbs.getOrPut(pkgKey) { mutableMapOf() }
                for (parentLabel in pkgMap.keys()) {
                    val arr = pkgMap.optJSONArray(parentLabel) ?: continue
                    val children = targetMap.getOrPut(parentLabel) { mutableSetOf() }
                    for (i in 0 until arr.length()) {
                        children.add(arr.optString(i))
                    }
                }
            }
        }
        return root
    }

    @Synchronized
    private fun save(context: Context) {
        try {
            val root = memoryCache ?: JSONObject().also { memoryCache = it }

            val deadObj = JSONObject()
            for ((k, set) in deadActionsByState.entries.toList().takeLast(60)) {
                val arr = JSONArray()
                set.toList().takeLast(15).forEach { arr.put(it) }
                deadObj.put(k, arr)
            }
            val wrongObj = JSONObject()
            for ((k, map) in wrongActionsByState.entries.toList().takeLast(60)) {
                val inner = JSONObject()
                map.entries.toList().takeLast(15).forEach { inner.put(it.key, it.value) }
                wrongObj.put(k, inner)
            }
            val verifiedObj = JSONObject()
            for ((k, map) in verifiedActionsByState.entries.toList().takeLast(60)) {
                val inner = JSONObject()
                map.entries.toList().take(15).forEach { inner.put(it.key, it.value) }
                verifiedObj.put(k, inner)
            }

            val breadObj = JSONObject()
            for ((pkgKey, parentMap) in appBreadcrumbs.entries.toList().takeLast(40)) {
                val pkgJson = JSONObject()
                for ((parentLabel, children) in parentMap.entries.toList().takeLast(25)) {
                    val arr = JSONArray()
                    children.toList().take(30).forEach { arr.put(it) }
                    pkgJson.put(parentLabel, arr)
                }
                breadObj.put(pkgKey, pkgJson)
            }

            root.put("_schema_version", SCHEMA_VERSION)
            root.put("_dead_actions", deadObj)
            root.put("_wrong_actions", wrongObj)
            root.put("_verified_actions", verifiedObj)
            root.put("_app_breadcrumbs", breadObj)
            getFile(context).writeText(root.toString())
        } catch (_: Exception) {
        }
    }

    // ---- Concept Learning (Backwards-Compatible) ---------------------

    @Synchronized
    fun learn(context: Context, pkg: String, concept: String, normalizedX: Float, normalizedY: Float) {
        init(context)
        val mem = load(context)
        val appMem = mem.optJSONObject(pkg) ?: JSONObject().also { mem.put(pkg, it) }
        val coords = JSONObject()
            .put("x", normalizedX.toDouble())
            .put("y", normalizedY.toDouble())
        appMem.put(concept.lowercase(), coords)
        save(context)
    }

    @Synchronized
    fun getLearned(context: Context, pkg: String): Map<String, Pair<Float, Float>> {
        init(context)
        val appMem = load(context).optJSONObject(pkg) ?: return emptyMap()
        val map = mutableMapOf<String, Pair<Float, Float>>()
        appMem.keys().forEach { key ->
            val coords = appMem.optJSONObject(key)
            if (coords != null && coords.has("x") && coords.has("y")) {
                map[key] = Pair(coords.getDouble("x").toFloat(), coords.getDouble("y").toFloat())
            }
        }
        return map
    }

    // ---- Strategic Plan Memory (Tier 1) ------------------------------

    private fun planKey(pkg: String, goal: String): String = "$pkg|${MoveMemory.goalKey(goal)}"

    /**
     * Resets per-task working memory (strategic plan and recent turn log) when a brand-new task starts,
     * so Capri never stays stuck on a previous task's goal (e.g. still thinking she needs to "walk towards tree"
     * when the user gave a new task "mine wood").
     */
    @Synchronized
    fun beginNewTaskSession(pkg: String, goal: String) {
        val key = planKey(pkg, goal)
        activePlans.remove(key)
        activePlans.keys.removeAll { it.startsWith("$pkg|") }
        activePlans.remove(pkg)
        recentSessionLog.clear()
        activePlans[key] = buildInitialPlan(pkg, goal)
    }

    fun buildInitialPlan(pkg: String, goal: String): String {
        val gLower = goal.lowercase(Locale.US)
        val isMusic = Regex("\\b(songs?|music|track|tracks|audio|tune|playlist|liked)\\b").containsMatchIn(gLower)
        val isReels = Regex("\\b(reels?|shorts?|tiktok)\\b|\\b(?:play|watch)\\s+(?:a\\s+)?(?:vid|video)\\b").containsMatchIn(gLower) && !isMusic
        val isSearch = Regex("\\b(search|find|google)\\b").containsMatchIn(gLower)
        val isCall = Regex("\\b(call|dial|phone)\\b").containsMatchIn(gLower)
        val isText = Regex("\\b(text|message|chat|sms|whatsapp)\\b").containsMatchIn(gLower)

        return when {
            isMusic -> "Target: Play music in current media app. Roadmap: 1. Stay in current app (do NOT switch apps or open TikTok/YouTube). 2. Find/search the song or open playlist. 3. Tap Play/Shuffle button or song row so audio plays. 4. Output DONE once playback starts."
            isReels -> "Target: Watch video/reels in video app. Roadmap: 1. Stay in current video app. 2. Verify video is playing. 3. Output DONE."
            isSearch -> "Target: Search '$goal'. Roadmap: 1. In browser/search app, inspect results. 2. Tap most relevant result or summarize. 3. Output DONE."
            isCall -> "Target: Place call. Roadmap: 1. Verify dialer is open. 2. Tap Call button to place call. 3. Output DONE."
            isText -> "Target: Send message. Roadmap: 1. Verify chat is open with draft message. 2. Tap Send button. 3. Output DONE."
            else -> "Target: \"$goal\". Roadmap: 1. Identify target action on screen. 2. Execute necessary tap/action. 3. Output DONE."
        }
    }

    @Synchronized
    fun updatePlan(pkg: String, goal: String, newPlan: String?) {
        val key = planKey(pkg, goal)
        val currentPlan = activePlans[key] ?: buildInitialPlan(pkg, goal)
        val clean = newPlan?.trim().orEmpty()
        if (clean.isBlank()) return

        val gLower = goal.lowercase(Locale.US)
        val isMusic = Regex("\\b(songs?|music|track|tracks|audio|tune|playlist|liked)\\b").containsMatchIn(gLower)
        val cleanLower = clean.lowercase(Locale.US)

        // Prevent plan corruption: If user wants music, never accept a plan that switches to tiktok or unrelated video apps!
        if (isMusic && (cleanLower.contains("tiktok") || (cleanLower.contains("video") && !cleanLower.contains("music")))) {
            return
        }

        // If newPlan is a full multi-step strategic plan, update it:
        if (clean.contains("->") || clean.contains("1.") || clean.contains("roadmap", ignoreCase = true) || clean.contains("step", ignoreCase = true) || clean.contains("then", ignoreCase = true)) {
            activePlans[key] = clean
        } else {
            // It's a micro-action for the current turn: update the sub-step without destroying the overarching strategic plan!
            val basePlan = currentPlan.substringBefore("\n[Current Step:").trim()
            activePlans[key] = "$basePlan\n[Current Step: $clean]"
        }
    }

    @Synchronized
    fun getActivePlan(pkg: String, goal: String): String {
        val key = planKey(pkg, goal)
        return activePlans[key] ?: buildInitialPlan(pkg, goal)
    }

    // ---- Spatial Board & Lane Memory (Tier 2) ------------------------

    @Synchronized
    fun clearSessionSpatialMemory(pkg: String) {
        spatialLedger.remove(pkg)
        unsoddedLanesByPkg.remove(pkg)
        lastSelectedCardCoord = null
    }

    @Synchronized
    fun applyLawnAnchor(anchorX: Float, anchorY: Float) {
        val dy = anchorY.coerceIn(0.35f, 0.70f) - 0.53f
        val base = floatArrayOf(0.25f, 0.39f, 0.53f, 0.68f, 0.82f)
        for (i in LANE_Y_CENTERS.indices) {
            LANE_Y_CENTERS[i] = (base[i] + dy).coerceIn(0.18f, 0.92f)
        }
    }

    private fun yToLaneIndex(normY: Float): Int {
        var bestIdx = 0
        var bestDist = Float.MAX_VALUE
        for (i in LANE_Y_CENTERS.indices) {
            val d = abs(normY - LANE_Y_CENTERS[i])
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun xToColIndex(normX: Float): Int {
        var bestIdx = 0
        var bestDist = Float.MAX_VALUE
        for (i in COL_X_CENTERS.indices) {
            val d = abs(normX - COL_X_CENTERS[i])
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return bestIdx
    }

    @Synchronized
    fun recordPlacement(pkg: String, normX: Float, normY: Float, label: String = "placed") {
        if (normY < 0.19f || normX < 0.16f) return
        val list = spatialLedger.getOrPut(pkg) { mutableListOf() }
        val lane = yToLaneIndex(normY)
        val col = xToColIndex(normX)
        // If a placement succeeded on this lane, it is definitely sodded
        unsoddedLanesByPkg[pkg]?.remove(lane)
        list.add(
            PlacementPoint(
                normX = normX,
                normY = normY,
                laneIndex = lane,
                colIndex = col,
                label = label,
                timestamp = System.currentTimeMillis()
            )
        )
        while (list.size > 35) list.removeAt(0)
    }

    @Synchronized
    fun removeLastPlacementNear(pkg: String, normX: Float, normY: Float) {
        val list = spatialLedger[pkg] ?: return
        val idx = list.indexOfLast { hypot((it.normX - normX).toDouble(), (it.normY - normY).toDouble()) < 0.08 }
        if (idx >= 0) {
            val removed = list.removeAt(idx)
            // If an outer lane (not Row 3 center) had 0 prior successful plants and just rejected a placement,
            // mark it as unsodded/unavailable so we don't keep missing Row 3 on 1-lane or 3-lane levels!
            if (removed.laneIndex != 2 && list.none { it.laneIndex == removed.laneIndex }) {
                unsoddedLanesByPkg.getOrPut(pkg) { mutableSetOf() }.add(removed.laneIndex)
            }
        }
    }

    @Synchronized
    fun recordCardSelection(normX: Float, normY: Float) {
        lastSelectedCardCoord = Pair(normX, normY)
        lastSelectedCardTime = System.currentTimeMillis()
    }

    @Synchronized
    fun consumeSelectedCard(): Pair<Float, Float>? {
        val c = lastSelectedCardCoord ?: return null
        lastSelectedCardCoord = null
        return if (System.currentTimeMillis() - lastSelectedCardTime < 12_000L) c else null
    }

    @Synchronized
    fun deconflictBoardPlacement(pkg: String, rawNormX: Float, rawNormY: Float): Pair<Float, Float> {
        val list = spatialLedger[pkg].orEmpty()
        if (rawNormY < 0.19f) return Pair(rawNormX, rawNormY)

        val unsodded = unsoddedLanesByPkg[pkg].orEmpty()
        val validLanes = CENTER_OUT_LANES.filter { it !in unsodded }.ifEmpty { listOf(2) }

        val targetLane = yToLaneIndex(rawNormY)
        val laneCounts = IntArray(5)
        for (p in list) {
            if (p.laneIndex in 0..4) laneCounts[p.laneIndex]++
        }

        // Always allow at least 2 plants on Row 3 (center lane) before forcing overflow to outer lanes,
        // so early 1-lane PvZ levels never deflect Row 3 shots onto unsodded Row 1/Row 2 dirt!
        val minCount = validLanes.minOfOrNull { laneCounts[it] } ?: 0
        val hitExistingSpot = list.any { hypot((it.normX - rawNormX).toDouble(), (it.normY - rawNormY).toDouble()) < 0.065 }

        val chosenLane = when {
            targetLane !in validLanes -> validLanes.minByOrNull { laneCounts[it] } ?: 2
            targetLane == 2 && laneCounts[2] < 2 && !hitExistingSpot -> 2
            (laneCounts[targetLane] > minCount && minCount == 0 && laneCounts[2] >= 2) ||
                    (laneCounts[targetLane] >= minCount + 2) || hitExistingSpot -> {
                validLanes.minByOrNull { laneCounts[it] } ?: targetLane
            }
            else -> targetLane
        }

        val finalY = LANE_Y_CENTERS[chosenLane]

        val usedColsInLane = list.filter { it.laneIndex == chosenLane }.map { it.colIndex }.toSet()
        val requestedCol = xToColIndex(rawNormX).coerceIn(0, 6)
        val chosenCol = if (requestedCol !in usedColsInLane) {
            requestedCol
        } else {
            (0..6).firstOrNull { it !in usedColsInLane } ?: ((requestedCol + 1) % 6)
        }

        val finalX = COL_X_CENTERS[chosenCol]
        return Pair(finalX, finalY)
    }

    @Synchronized
    fun suggestNextBoardTarget(pkg: String): Pair<Float, Float> {
        return deconflictBoardPlacement(pkg, 0.34f, LANE_Y_CENTERS[2])
    }

    // ---- Outcome & Blacklist Memory (Tier 3) -------------------------

    @Synchronized
    fun buildStateKey(
        packageName: String,
        ocrCellsSummary: String,
        screenHash: Long,
        uiLabels: List<String>? = null,
        fingerprint: ScreenFingerprint? = null
    ): String {
        if (uiLabels != null || fingerprint != null) {
            return MoveMemory.stateFor(packageName, uiLabels, fingerprint)
        }
        val sig = if (ocrCellsSummary.length > 4) {
            ocrCellsSummary.lowercase(Locale.US).replace(Regex("\\d+"), "#").take(64).hashCode().toString()
        } else {
            (screenHash ushr 16).toString()
        }
        return "$packageName|$sig"
    }

    @Synchronized
    fun recordOutcome(
        context: Context,
        stateKey: String,
        normalizedCommand: String,
        screenChanged: Boolean,
        move: MoveMemory.Move? = null,
        isGame: Boolean = false,
        goal: String? = null,
        unintendedReason: String? = null,
        goalScopedWrong: Boolean = false
    ) {
        init(context)
        val cmd = normalizedCommand.trim().uppercase(Locale.US)

        when {
            !screenChanged -> {
                val deadSet = deadActionsByState.getOrPut(stateKey) { mutableSetOf() }
                deadSet.add(cmd)
                if (move != null) {
                    MoveMemory.recordBad(
                        context = context,
                        stateId = stateKey,
                        move = move,
                        verdict = MoveMemory.Verdict.NO_EFFECT,
                        note = "no screen change",
                        isGame = isGame,
                        goal = goal,
                        goalScoped = false
                    )
                }
                pushRecentLog("$cmd -> FAILED(NO CHANGE - BLACKLISTED)")
            }
            unintendedReason != null -> {
                deadActionsByState[stateKey]?.remove(cmd)
                verifiedActionsByState[stateKey]?.remove(cmd)
                val wrongMap = wrongActionsByState.getOrPut(stateKey) { mutableMapOf() }
                wrongMap[cmd] = unintendedReason
                if (move != null) {
                    MoveMemory.recordBad(
                        context = context,
                        stateId = stateKey,
                        move = move,
                        verdict = MoveMemory.Verdict.WRONG,
                        note = unintendedReason,
                        isGame = isGame,
                        goal = goal,
                        goalScoped = goalScopedWrong
                    )
                }
                pushRecentLog("$cmd -> WRONG($unintendedReason - BLACKLISTED)")
            }
            else -> {
                deadActionsByState[stateKey]?.remove(cmd)
                wrongActionsByState[stateKey]?.remove(cmd)
                val verifiedMap = verifiedActionsByState.getOrPut(stateKey) { mutableMapOf() }
                verifiedMap[cmd] = (verifiedMap[cmd] ?: 0) + 1
                if (move != null) {
                    MoveMemory.recordGood(context, stateKey, move)
                }
                pushRecentLog("$cmd -> WORKED")
            }
        }
        save(context)
    }

    @Synchronized
    fun isBlacklisted(
        stateKey: String,
        normalizedCommand: String,
        move: MoveMemory.Move? = null,
        goal: String? = null,
        aspect: Float? = null
    ): Boolean {
        val cmd = normalizedCommand.trim().uppercase(Locale.US)
        if (deadActionsByState[stateKey]?.contains(cmd) == true) return true
        if (wrongActionsByState[stateKey]?.containsKey(cmd) == true) return true
        if (move != null && MoveMemory.blockedBy(stateKey, move, goal, aspect) != null) return true
        return false
    }

    @Synchronized
    fun getBlacklistReason(
        stateKey: String,
        normalizedCommand: String,
        move: MoveMemory.Move? = null,
        goal: String? = null,
        aspect: Float? = null
    ): String? {
        val cmd = normalizedCommand.trim().uppercase(Locale.US)
        wrongActionsByState[stateKey]?.get(cmd)?.let { return "wrong outcome ($it)" }
        if (deadActionsByState[stateKey]?.contains(cmd) == true) return "did nothing on this screen"
        if (move != null) {
            MoveMemory.blockedBy(stateKey, move, goal, aspect)?.let { return it.reason }
        }
        return null
    }

    @Synchronized
    fun getMemoryPromptBlock(stateKey: String): String {
        val pkg = stateKey.substringBefore("|").substringBefore("#")
        return buildFullStrategicContext(pkg, stateKey, "")
    }

    @Synchronized
    fun buildFullStrategicContext(pkg: String, stateKey: String, goal: String): String {
        val plan = getActivePlan(pkg, goal)
        val list = spatialLedger[pkg].orEmpty()

        val spatialBad = MoveMemory.activeBad(stateKey, goal, max = 8)
        val deadMoves = buildList {
            spatialBad.filter { it.verdict == MoveMemory.Verdict.NO_EFFECT }.forEach { add(it.move.describe()) }
            deadActionsByState[stateKey]?.toList()?.takeLast(5)?.forEach { if (it !in this) add(it) }
        }.take(7)

        val wrongMoves = buildList {
            spatialBad.filter { it.verdict == MoveMemory.Verdict.WRONG }.forEach {
                add("${it.move.describe()} [CAUSED: ${it.note}]")
            }
            wrongActionsByState[stateKey]?.entries?.toList()?.takeLast(5)?.forEach { (k, v) ->
                val entry = "$k [CAUSED: $v]"
                if (none { it.startsWith(k) }) add(entry)
            }
        }.take(7)

        val verified = buildList {
            MoveMemory.topGood(stateKey, max = 4).forEach { add(it.describe()) }
            verifiedActionsByState[stateKey]
                ?.entries
                ?.sortedByDescending { it.value }
                ?.take(4)
                ?.forEach { if (it.key !in this) add(it.key) }
        }.take(5)

        val recent = recentSessionLog.takeLast(5)

        return buildString {
            appendLine("ACTIVE STRATEGIC PLAN: $plan")
            if (list.isNotEmpty()) {
                val occupied = list.takeLast(6).joinToString(", ") {
                    String.format(Locale.US, "(%.2f,%.2f)", it.normX, it.normY)
                }
                appendLine("RECENTLY TARGETED SPOTS (AVOID STACKING REPEATEDLY): $occupied")
            }
            if (verified.isNotEmpty()) {
                appendLine("VERIFIED WORKING MOVES: ${verified.joinToString(", ")}")
            }
            if (deadMoves.isNotEmpty()) {
                appendLine("BLACKLISTED NO-EFFECT MOVES (DID NOTHING - NEVER REPEAT): ${deadMoves.joinToString(", ")}")
            }
            if (wrongMoves.isNotEmpty()) {
                appendLine("BLACKLISTED WRONG-OUTCOME MOVES (CHANGED SCREEN UNINTENDEDLY - AVOID): ${wrongMoves.joinToString(", ")}")
            }
            if (recent.isNotEmpty()) {
                appendLine("RECENT TURN OUTCOMES: ${recent.joinToString(" > ")}")
            }
        }.trim()
    }

    @Synchronized
    private fun pushRecentLog(entry: String) {
        if (recentSessionLog.size >= 12) {
            recentSessionLog.removeFirst()
        }
        recentSessionLog.addLast(entry)
    }

    @Synchronized
    fun recordAppTransition(
        context: Context,
        pkg: String,
        tappedLabel: String?,
        beforeLabels: List<String>,
        afterLabels: List<String>
    ) {
        val parent = tappedLabel?.trim()?.takeIf { it.length in 2..35 && it != "(no label)" } ?: return
        if (pkg.isBlank() || afterLabels.isEmpty()) return
        init(context)

        val beforeSet = beforeLabels.map { it.trim().lowercase() }.toSet()
        val newlyRevealed = afterLabels
            .map { it.trim() }
            .filter { it.length in 2..40 && it != "(no label)" && it.lowercase() !in beforeSet }
            .take(20)

        if (newlyRevealed.isEmpty()) return

        val pkgMap = appBreadcrumbs.getOrPut(pkg) { mutableMapOf() }
        val childSet = pkgMap.getOrPut(parent) { mutableSetOf() }
        childSet.addAll(newlyRevealed)
        save(context)
    }

    @Synchronized
    fun getAppBreadcrumbHints(
        pkg: String,
        goal: String,
        currentLabels: List<String>,
        visitedTabs: Set<String> = emptySet()
    ): String {
        val goalLower = goal.lowercase()
        val hints = mutableListOf<String>()
        val alreadyVisitedLibrary = visitedTabs.any { it.contains("library") || it == "you" || it.contains("playlist") } ||
                recentSessionLog.any { it.contains("Library", ignoreCase = true) && it.contains("[OK]") }

        val pkgMap = appBreadcrumbs[pkg].orEmpty()
        for ((parentTab, children) in pkgMap) {
            if (parentTab.lowercase() in visitedTabs) continue
            val matchedChild = children.firstOrNull { child ->
                val cLow = child.lowercase()
                cLow.length >= 3 && (goalLower.contains(cLow) || cLow.split(" ").any { w -> w.length >= 4 && goalLower.contains(w) })
            }
            if (matchedChild != null) {
                hints.add("Learned in $pkg: Tap \"$parentTab\" to reach \"$matchedChild\".")
            }
        }

        val currentLower = currentLabels.map { it.lowercase() }
        val targetOnScreen = currentLower.any { lbl ->
            (goalLower.contains("liked") && lbl.contains("liked")) ||
            (goalLower.contains("playlist") && lbl.contains("playlist")) ||
            (goalLower.contains("history") && lbl.contains("history")) ||
            (goalLower.contains("download") && lbl.contains("download"))
        }

        if (alreadyVisitedLibrary) {
            hints.add("NAVIGATION HINT: You are ALREADY inside Library! Do NOT tap 'Library' again. Use SCROLL down to reveal songs, or tap a song row / Play button directly!")
        } else if (!targetOnScreen) {
            if (goalLower.contains("liked") || goalLower.contains("playlist") || goalLower.contains("library") ||
                goalLower.contains("saved") || goalLower.contains("favorite") || goalLower.contains("downloaded") ||
                goalLower.contains("album") || goalLower.contains("artist")
            ) {
                val libTab = currentLabels.firstOrNull {
                    it.contains("Library", ignoreCase = true) ||
                    it.equals("You", ignoreCase = true) ||
                    it.contains("Playlists", ignoreCase = true) ||
                    it.contains("Collection", ignoreCase = true)
                }
                if (libTab != null) {
                    hints.add("NAVIGATION HINT: Target is inside your library. Tap \"$libTab\" first!")
                } else {
                    hints.add("NAVIGATION HINT: Look for 'Your Library', 'Library', or 'Playlists' tab (usually at the bottom bar) to find Liked Songs / Playlists.")
                }
            }
        } else if (goalLower.contains("pick one") || goalLower.contains("play one") || goalLower.contains("play a ") || goalLower.contains("liked song")) {
            val isInsideLikedSongs = currentLower.any { it.contains("liked songs") } && currentLabels.size >= 6
            if (isInsideLikedSongs) {
                hints.add("NAVIGATION HINT: If 'Liked Songs' header is already open, tap the Play/Shuffle button or tap any song row below the header to start playing!")
            }
        }

        return hints.distinct().joinToString("\n")
    }

    @Synchronized
    fun buildDualModeContext(
        pkg: String,
        stateKey: String,
        goal: String,
        isGameMode: Boolean,
        currentLabels: List<String>,
        visitedTabs: Set<String> = emptySet()
    ): String {
        if (isGameMode) {
            return "MODE: GAME_MODE (Canvas/OpenGL)\n" + buildFullStrategicContext(pkg, stateKey, goal)
        }

        val plan = getActivePlan(pkg, goal)
        val breadcrumbHints = getAppBreadcrumbHints(pkg, goal, currentLabels, visitedTabs)

        val spatialBad = MoveMemory.activeBad(stateKey, goal, max = 8)
        val deadMoves = buildList {
            spatialBad.filter { it.verdict == MoveMemory.Verdict.NO_EFFECT }.forEach { add(it.move.describe()) }
            deadActionsByState[stateKey]?.toList()?.takeLast(5)?.forEach { if (it !in this) add(it) }
        }.take(7)

        val wrongMoves = buildList {
            spatialBad.filter { it.verdict == MoveMemory.Verdict.WRONG }.forEach {
                add("${it.move.describe()} [CAUSED: ${it.note}]")
            }
            wrongActionsByState[stateKey]?.entries?.toList()?.takeLast(5)?.forEach { (k, v) ->
                val entry = "$k [CAUSED: $v]"
                if (none { it.startsWith(k) }) add(entry)
            }
        }.take(7)

        val recent = recentSessionLog.takeLast(5)

        return buildString {
            appendLine("MODE: APP_NAVIGATION_MODE (Standard UI)")
            appendLine("ACTIVE STRATEGIC PLAN: $plan")
            if (breadcrumbHints.isNotBlank()) {
                appendLine(breadcrumbHints)
            }
            if (deadMoves.isNotEmpty()) {
                appendLine("BLACKLISTED NO-EFFECT MOVES (DO NOT REPEAT): ${deadMoves.joinToString(", ")}")
            }
            if (wrongMoves.isNotEmpty()) {
                appendLine("BLACKLISTED WRONG-OUTCOME MOVES (OPENED WRONG SCREEN - DO NOT REPEAT): ${wrongMoves.joinToString(", ")}")
            }
            if (recent.isNotEmpty()) {
                appendLine("RECENT STEPS (DO NOT REPEAT TAPS ALREADY DONE): ${recent.joinToString(" > ")}")
            }
        }.trim()
    }

    @Synchronized
    fun dumpMemorySummary(context: Context): String {
        init(context)
        return buildString {
            appendLine("=== ACTIVE STRATEGIC PLANS ===")
            if (activePlans.isEmpty()) appendLine("(none yet)")
            for ((pkg, p) in activePlans) appendLine("• $pkg: $p")

            appendLine("\n=== LEARNED APP BREADCRUMB MAPS ===")
            if (appBreadcrumbs.isEmpty()) appendLine("(no cross-screen transitions recorded yet)")
            for ((pkg, map) in appBreadcrumbs) {
                appendLine("[$pkg]")
                for ((parent, children) in map) {
                    appendLine("  \"$parent\" -> reveals: ${children.take(10).joinToString(", ")}")
                }
            }

            appendLine("\n=== GAME SPATIAL BOARD LEDGER ===")
            if (spatialLedger.isEmpty()) appendLine("(no board placements in current session)")
            for ((pkg, pts) in spatialLedger) {
                appendLine("[$pkg] (${pts.size} placements):")
                pts.takeLast(10).forEach {
                    appendLine(String.format(Locale.US, "  • Row %d Col %d (x=%.2f, y=%.2f) [%s]", it.laneIndex + 1, it.colIndex + 1, it.normX, it.normY, it.label))
                }
            }

            appendLine("\n=== SPATIAL & FUZZY STATE MOVE MEMORY ===")
            appendLine(MoveMemory.dump())

            appendLine("\n=== BLACKLISTED DEAD MOVES (NO SCREEN CHANGE) ===")
            if (deadActionsByState.isEmpty()) appendLine("(none)")
            for ((state, set) in deadActionsByState.entries.toList().takeLast(15)) {
                if (set.isNotEmpty()) appendLine("• $state -> ${set.joinToString(", ")}")
            }

            appendLine("\n=== BLACKLISTED WRONG-OUTCOME MOVES (UNINTENDED CHANGE) ===")
            if (wrongActionsByState.isEmpty()) appendLine("(none)")
            for ((state, map) in wrongActionsByState.entries.toList().takeLast(15)) {
                if (map.isNotEmpty()) {
                    appendLine("• $state -> ${map.entries.joinToString(", ") { "${it.key} [${it.value}]" }}")
                }
            }

            appendLine("\n=== VERIFIED WORKING MOVES ===")
            if (verifiedActionsByState.isEmpty()) appendLine("(none)")
            for ((state, map) in verifiedActionsByState.entries.toList().takeLast(15)) {
                if (map.isNotEmpty()) appendLine("• $state -> ${map.entries.joinToString(", ") { "${it.key}(${it.value}x)" }}")
            }
        }
    }

    @Synchronized
    fun clearAllMemory(context: Context) {
        activePlans.clear()
        spatialLedger.clear()
        appBreadcrumbs.clear()
        deadActionsByState.clear()
        wrongActionsByState.clear()
        verifiedActionsByState.clear()
        recentSessionLog.clear()
        memoryCache = JSONObject()
        MoveMemory.clear(context)
        runCatching { getFile(context).delete() }
    }
}
