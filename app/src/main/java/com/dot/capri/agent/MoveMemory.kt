package com.dot.capri.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * Outcome memory for Capri's moves.
 *
 * Why this exists: the old blacklist keyed moves by exact pixel strings ("TAP (523,1201)") on a
 * screen key that changed every frame, so a blacklisted move almost never matched again.
 *
 * Here:
 *  - Screens are identified fuzzily: by their accessibility label set (apps) or by a coarse
 *    luminance fingerprint (games / canvas apps), so the same screen is recognised again.
 *  - Moves are stored in normalized coordinates and matched spatially (a tap 20px away from a
 *    dead tap is the same dead tap).
 *  - Two kinds of bad moves are remembered:
 *      NO_EFFECT -> nothing changed on screen.
 *      WRONG     -> the screen changed, but not the way it should have (left the app / opened an ad /
 *                   opened something that had to be undone / the model judged the result wrong).
 */
object MoveMemory {

    private const val FILE_NAME = "capri_move_memory.json"
    private const val SCHEMA_VERSION = 2
    private const val MAX_STATES = 160
    private const val MAX_BAD_PER_STATE = 30

    /** Matching radius for taps, in units of the screen's short side (~70px on a 1080px-wide phone). */
    private const val POINT_RADIUS = 0.065f
    private const val PATH_RADIUS = 0.08f

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val APP_TTL_MS = 7 * DAY_MS
    private const val GAME_NO_EFFECT_TTL_MS = 90_000L
    private const val GAME_WRONG_TTL_MS = 20 * 60_000L

    enum class Kind { TAP, DOUBLE_TAP, LONG_PRESS, SEQ, DRAG, SWIPE, HOLD, LOOK, SCROLL, BACK, HOME, OPEN, TYPE, TAP_TEXT, PRESS_REGION, WAIT, OTHER }
    enum class Verdict { NO_EFFECT, WRONG }

    data class Move(
        val kind: Kind,
        val nx: Float = -1f,
        val ny: Float = -1f,
        val nx2: Float = -1f,
        val ny2: Float = -1f,
        val text: String? = null,
        val label: String? = null
    ) {
        val isPath: Boolean get() = nx >= 0f && ny >= 0f && nx2 >= 0f && ny2 >= 0f
        val isPoint: Boolean get() = nx >= 0f && ny >= 0f && !isPath
        val points: List<Pair<Float, Float>>
            get() = when {
                isPath -> listOf(nx to ny, nx2 to ny2)
                isPoint -> listOf(nx to ny)
                else -> emptyList()
            }

        /** Same notation the model uses to write commands, so it can recognise its own moves. */
        fun describe(): String = when {
            isPath -> "${kind.name} ${f(nx)} ${f(ny)} ${f(nx2)} ${f(ny2)}"
            isPoint -> "${kind.name} ${f(nx)} ${f(ny)}" + (label?.takeIf { it.isNotBlank() }?.let { " (\"${it.take(24)}\")" } ?: "")
            !text.isNullOrBlank() -> "${kind.name} $text"
            else -> kind.name
        }
    }

    class Bad(
        val move: Move,
        val verdict: Verdict,
        var note: String,
        var count: Int,
        var lastTs: Long,
        val baseTtlMs: Long,
        /** null = applies to every goal (e.g. "did nothing", "opened the Play Store"). */
        val goalKey: String?
    ) {
        // Repeated failures are remembered longer.
        fun expired(now: Long): Boolean = now - lastTs > baseTtlMs * count.coerceIn(1, 6)
        val reason: String get() = if (verdict == Verdict.NO_EFFECT) "did nothing" else "wrong: $note"
    }

    private class StateRec(
        val id: String,
        val pkg: String,
        val labels: Set<String>?,
        val fp: ScreenFingerprint?,
        var lastSeen: Long,
        val bad: MutableList<Bad> = mutableListOf(),
        val good: MutableList<Pair<Move, Int>> = mutableListOf()
    )

    private val states = ArrayList<StateRec>()
    private var initialized = false
    private val io = Executors.newSingleThreadExecutor()
    private var seq = 0

    // ---- lifecycle ------------------------------------------------------

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        load(context.applicationContext)
    }

    @Synchronized
    fun clear(context: Context) {
        states.clear()
        val f = file(context)
        io.execute { runCatching { f.delete() } }
    }

    // ---- state identity -------------------------------------------------

    /**
     * Returns a stable id for the current screen. Uses the accessibility label set when the app
     * exposes one (>= 3 labels), else the screenshot fingerprint.
     */
    @Synchronized
    fun stateFor(pkg: String, rawLabels: List<String>?, fp: ScreenFingerprint?): String {
        val labels = rawLabels?.let { normalizeLabels(it) }?.takeIf { it.size >= 3 }
        val now = System.currentTimeMillis()

        val match: StateRec? = if (labels != null) {
            states.filter { it.pkg == pkg && it.labels != null }
                .map { it to jaccard(labels, it.labels!!) }
                .filter { it.second >= 0.88f }
                .maxByOrNull { it.second }?.first
        } else if (fp != null) {
            states.filter { it.pkg == pkg && it.labels == null && it.fp != null && it.fp.looksLike(fp) }
                .minByOrNull { it.fp!!.meanAbsDiff(fp) }
        } else null

        if (match != null) {
            match.lastSeen = now
            return match.id
        }

        val rec = StateRec(
            id = "$pkg#${java.lang.Long.toString(now, 36)}-${seq++}",
            pkg = pkg,
            labels = labels,
            fp = if (labels == null) fp else null,
            lastSeen = now
        )
        states.add(rec)
        trimStates()
        return rec.id
    }

    // ---- recording --------------------------------------------------------

    @Synchronized
    fun recordBad(
        context: Context,
        stateId: String,
        move: Move,
        verdict: Verdict,
        note: String,
        isGame: Boolean,
        goal: String?,
        goalScoped: Boolean
    ) {
        if (move.kind == Kind.WAIT) return
        val s = find(stateId) ?: return
        val now = System.currentTimeMillis()
        val ttl = when {
            !isGame -> APP_TTL_MS
            verdict == Verdict.NO_EFFECT -> GAME_NO_EFFECT_TTL_MS
            else -> GAME_WRONG_TTL_MS
        }
        val gk = if (goalScoped) goalKey(goal) else null
        val existing = s.bad.firstOrNull { it.verdict == verdict && it.goalKey == gk && matches(it.move, move, null) }
        if (existing != null) {
            existing.count++
            existing.lastTs = now
            existing.note = note
        } else {
            s.bad.add(Bad(move, verdict, note, 1, now, ttl, gk))
            while (s.bad.size > MAX_BAD_PER_STATE) s.bad.removeAt(0)
        }
        s.good.removeAll { matches(it.first, move, null) }
        save(context)
    }

    @Synchronized
    fun recordGood(context: Context, stateId: String, move: Move) {
        if (move.kind == Kind.WAIT) return
        val s = find(stateId) ?: return
        // It works now, so a stale "did nothing" entry must not keep blocking it.
        s.bad.removeAll { it.verdict == Verdict.NO_EFFECT && matches(it.move, move, null) }
        val idx = s.good.indexOfFirst { matches(it.first, move, null) }
        if (idx >= 0) {
            s.good[idx] = s.good[idx].first to (s.good[idx].second + 1)
        } else {
            s.good.add(move to 1)
            while (s.good.size > 12) s.good.removeAt(0)
        }
        save(context)
    }

    // ---- queries ------------------------------------------------------------

    /** The bad record that blocks this move on this screen, or null if the move is allowed. */
    @Synchronized
    fun blockedBy(stateId: String, move: Move, goal: String?, aspect: Float?): Bad? {
        if (move.kind == Kind.WAIT) return null
        val s = find(stateId) ?: return null
        val now = System.currentTimeMillis()
        val gk = goalKey(goal)
        return s.bad.firstOrNull { !it.expired(now) && (it.goalKey == null || it.goalKey == gk) && matches(it.move, move, aspect) }
    }

    @Synchronized
    fun activeBad(stateId: String, goal: String?, max: Int = 8): List<Bad> {
        val s = find(stateId) ?: return emptyList()
        val now = System.currentTimeMillis()
        val gk = goalKey(goal)
        return s.bad.filter { !it.expired(now) && (it.goalKey == null || it.goalKey == gk) }
            .sortedByDescending { it.lastTs }
            .take(max)
    }

    @Synchronized
    fun topGood(stateId: String, max: Int = 4): List<Move> {
        val s = find(stateId) ?: return emptyList()
        return s.good.sortedByDescending { it.second }.take(max).map { it.first }
    }

    // ---- matching -------------------------------------------------------------

    /**
     * Spatial match. [aspect] = screen width / height; used to measure distance in units of the
     * screen's short side so the radius is a real finger-sized circle in portrait and landscape.
     */
    fun matches(a: Move, b: Move, aspect: Float?): Boolean {
        if (a.kind != b.kind) return false
        if (a.isPath && b.isPath) {
            return dist(a.nx, a.ny, b.nx, b.ny, aspect) <= PATH_RADIUS &&
                    dist(a.nx2, a.ny2, b.nx2, b.ny2, aspect) <= PATH_RADIUS
        }
        if (a.isPoint && b.isPoint) {
            return dist(a.nx, a.ny, b.nx, b.ny, aspect) <= POINT_RADIUS
        }
        if (!a.isPoint && !a.isPath && !b.isPoint && !b.isPath) {
            return (a.text ?: "").trim().equals((b.text ?: "").trim(), ignoreCase = true)
        }
        return false
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float, aspect: Float?): Float {
        val asp = aspect ?: 0.5f
        val sx = if (asp >= 1f) asp else 1f
        val sy = if (asp >= 1f) 1f else 1f / asp
        return hypot(((ax - bx) * sx).toDouble(), ((ay - by) * sy).toDouble()).toFloat()
    }

    // ---- helpers ----------------------------------------------------------------

    fun goalKey(goal: String?): String {
        if (goal.isNullOrBlank()) return "-"
        return goal.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
            .sorted()
            .joinToString(" ")
            .hashCode()
            .toString(36)
    }

    private fun normalizeLabels(raw: List<String>): Set<String> =
        raw.asSequence()
            .map { it.lowercase(Locale.US).replace(Regex("\\d+"), "#").trim() }
            .filter { it.length in 1..40 && it != "(no label)" }
            .take(40)
            .toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        val inter = a.count { it in b }
        val union = a.size + b.size - inter
        return if (union == 0) 0f else inter.toFloat() / union
    }

    private fun find(id: String): StateRec? = states.firstOrNull { it.id == id }

    private fun trimStates() {
        if (states.size <= MAX_STATES) return
        // Drop least-recently-seen states that taught us nothing first.
        val removable = states.sortedWith(compareBy<StateRec>({ it.bad.isNotEmpty() || it.good.isNotEmpty() }, { it.lastSeen }))
        val toRemove = removable.take(states.size - MAX_STATES).toSet()
        states.removeAll(toRemove)
    }

    private fun f(v: Float) = String.format(Locale.US, "%.2f", v)

    // ---- persistence ---------------------------------------------------------------

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    private fun moveToJson(m: Move): JSONObject = JSONObject()
        .put("k", m.kind.name)
        .put("x", m.nx.toDouble()).put("y", m.ny.toDouble())
        .put("x2", m.nx2.toDouble()).put("y2", m.ny2.toDouble())
        .apply {
            m.text?.let { put("t", it) }
            m.label?.let { put("l", it) }
        }

    private fun moveFromJson(o: JSONObject): Move? {
        val kind = runCatching { Kind.valueOf(o.optString("k")) }.getOrNull() ?: return null
        return Move(
            kind = kind,
            nx = o.optDouble("x", -1.0).toFloat(),
            ny = o.optDouble("y", -1.0).toFloat(),
            nx2 = o.optDouble("x2", -1.0).toFloat(),
            ny2 = o.optDouble("y2", -1.0).toFloat(),
            text = o.optString("t").ifEmpty { null },
            label = o.optString("l").ifEmpty { null }
        )
    }

    private fun save(context: Context) {
        val now = System.currentTimeMillis()
        val root = JSONObject()
        val arr = JSONArray()
        for (s in states) {
            val bad = s.bad.filter { !it.expired(now) }
            if (bad.isEmpty() && s.good.isEmpty()) continue
            val so = JSONObject()
                .put("id", s.id)
                .put("pkg", s.pkg)
                .put("seen", s.lastSeen)
            s.labels?.let { so.put("labels", JSONArray(it.toList())) }
            s.fp?.let { so.put("luma", ScreenFingerprint.encode(it.luma)) }
            val ba = JSONArray()
            for (b in bad) {
                ba.put(
                    JSONObject()
                        .put("m", moveToJson(b.move))
                        .put("v", b.verdict.name)
                        .put("n", b.note)
                        .put("c", b.count)
                        .put("ts", b.lastTs)
                        .put("ttl", b.baseTtlMs)
                        .apply { b.goalKey?.let { put("g", it) } }
                )
            }
            so.put("bad", ba)
            val ga = JSONArray()
            for ((m, c) in s.good) ga.put(JSONObject().put("m", moveToJson(m)).put("c", c))
            so.put("good", ga)
            arr.put(so)
        }
        root.put("v", SCHEMA_VERSION)
        root.put("states", arr)
        val text = root.toString()
        val f = file(context)
        io.execute { runCatching { f.writeText(text) } }
    }

    private fun load(context: Context) {
        val f = file(context)
        if (!f.exists()) return
        val root = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return
        if (root.optInt("v", 1) < SCHEMA_VERSION) {
            runCatching { f.delete() }
            return
        }
        val arr = root.optJSONArray("states") ?: return
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val so = arr.optJSONObject(i) ?: continue
            val labels = so.optJSONArray("labels")?.let { la -> (0 until la.length()).map { la.optString(it) }.toSet() }
            val fp = so.optString("luma").ifEmpty { null }?.let { ScreenFingerprint.decode(it) }?.let { ScreenFingerprint.fromLuma(it) }
            val rec = StateRec(
                id = so.optString("id"),
                pkg = so.optString("pkg"),
                labels = labels,
                fp = fp,
                lastSeen = so.optLong("seen", now)
            )
            so.optJSONArray("bad")?.let { ba ->
                for (j in 0 until ba.length()) {
                    val bo = ba.optJSONObject(j) ?: continue
                    val m = bo.optJSONObject("m")?.let { moveFromJson(it) } ?: continue
                    val v = runCatching { Verdict.valueOf(bo.optString("v")) }.getOrNull() ?: continue
                    val b = Bad(m, v, bo.optString("n"), bo.optInt("c", 1), bo.optLong("ts", now), bo.optLong("ttl", APP_TTL_MS), bo.optString("g").ifEmpty { null })
                    if (!b.expired(now)) rec.bad.add(b)
                }
            }
            so.optJSONArray("good")?.let { ga ->
                for (j in 0 until ga.length()) {
                    val go = ga.optJSONObject(j) ?: continue
                    val m = go.optJSONObject("m")?.let { moveFromJson(it) } ?: continue
                    rec.good.add(m to go.optInt("c", 1))
                }
            }
            if (rec.id.isNotBlank() && (rec.bad.isNotEmpty() || rec.good.isNotEmpty())) states.add(rec)
        }
        trimStates()
    }

    // ---- inspector ---------------------------------------------------------------------

    @Synchronized
    fun dump(): String {
        val now = System.currentTimeMillis()
        return buildString {
            val withBad = states.filter { s -> s.bad.any { !it.expired(now) } }
            if (withBad.isEmpty()) appendLine("(none)")
            for (s in withBad.sortedByDescending { it.lastSeen }.take(20)) {
                val kind = if (s.labels != null) "ui:${s.labels.size} labels" else "pixels"
                appendLine("[${s.pkg}] screen ${s.id.substringAfter('#')} ($kind)")
                for (b in s.bad.filter { !it.expired(now) }.takeLast(10)) {
                    val scope = if (b.goalKey == null) "all goals" else "this goal"
                    appendLine("  ✗ ${b.move.describe()} -> ${b.reason} (${b.count}x, $scope)")
                }
                for ((m, c) in s.good.sortedByDescending { it.second }.take(4)) {
                    appendLine("  ✓ ${m.describe()} (${c}x)")
                }
            }
        }.trimEnd()
    }
}
