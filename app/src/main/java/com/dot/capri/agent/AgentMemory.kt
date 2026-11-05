package com.dot.capri.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent memory for Capri: stores learned concepts ("concepts") and
 * per-screen-hash dead/verified actions ("dead", "verified").
 */
object AgentMemory {

    private const val FILE_NAME = "capri_agent_memory.json"
    private fun getFile(context: Context) = File(context.filesDir, FILE_NAME)

    private var memoryCache: JSONObject? = null
    private val recentSessionLog = ArrayDeque<String>(12)

    @Synchronized
    private fun load(context: Context): JSONObject {
        if (memoryCache != null) return memoryCache!!
        val f = getFile(context)
        memoryCache = if (f.exists()) {
            try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() }
        } else {
            JSONObject()
        }
        return memoryCache!!
    }

    @Synchronized
    private fun save(context: Context) {
        memoryCache?.let {
            try { getFile(context).writeText(it.toString()) } catch (_: Exception) {}
        }
    }

    // ---- Concept Memory (Preserves original API for backward compatibility) ----

    @Synchronized
    fun learn(context: Context, pkg: String, concept: String, normalizedX: Float, normalizedY: Float) {
        val root = load(context)
        val concepts = root.optJSONObject("concepts") ?: JSONObject().also { root.put("concepts", it) }
        val appMem = concepts.optJSONObject(pkg) ?: JSONObject().also { concepts.put(pkg, it) }
        val coords = JSONObject().put("x", normalizedX.toDouble()).put("y", normalizedY.toDouble())
        appMem.put(concept.lowercase(), coords)
        save(context)
    }

    @Synchronized
    fun getLearned(context: Context, pkg: String): Map<String, Pair<Float, Float>> {
        val root = load(context)
        val concepts = root.optJSONObject("concepts") ?: root.optJSONObject(pkg) ?: return emptyMap()
        val appMem = if (concepts.has(pkg)) concepts.optJSONObject(pkg) ?: concepts else concepts
        val map = mutableMapOf<String, Pair<Float, Float>>()
        appMem.keys().forEach { key ->
            val coords = appMem.optJSONObject(key)
            if (coords != null && coords.has("x") && coords.has("y")) {
                map[key] = Pair(coords.getDouble("x").toFloat(), coords.getDouble("y").toFloat())
            }
        }
        return map
    }

    // ---- Outcome Learning (Blacklists dead actions & tracks verified working actions) ----

    @Synchronized
    fun buildStateKey(pkg: String, ocrCellsSummary: String, screenHash: Long): String {
        val sig = if (ocrCellsSummary.length > 4) {
            ocrCellsSummary.take(64).hashCode().toString()
        } else {
            (screenHash ushr 12).toString()
        }
        return "$pkg|$sig"
    }

    @Synchronized
    fun recordOutcome(
        context: Context,
        stateKey: String,
        normalizedCommand: String,
        screenChanged: Boolean
    ) {
        val root = load(context)
        val deadRoot = root.optJSONObject("dead") ?: JSONObject().also { root.put("dead", it) }
        val verifiedRoot = root.optJSONObject("verified") ?: JSONObject().also { root.put("verified", it) }

        val cmd = normalizedCommand.trim().uppercase()

        if (screenChanged) {
            val deadArr = deadRoot.optJSONArray(stateKey)
            if (deadArr != null) {
                val newArr = JSONArray()
                for (i in 0 until deadArr.length()) {
                    if (deadArr.optString(i) != cmd) newArr.put(deadArr.optString(i))
                }
                deadRoot.put(stateKey, newArr)
            }
            val verObj = verifiedRoot.optJSONObject(stateKey) ?: JSONObject().also { verifiedRoot.put(stateKey, it) }
            verObj.put(cmd, verObj.optInt(cmd, 0) + 1)
            pushRecentLog("$cmd -> OK")
        } else {
            val deadArr = deadRoot.optJSONArray(stateKey) ?: JSONArray().also { deadRoot.put(stateKey, it) }
            var exists = false
            for (i in 0 until deadArr.length()) {
                if (deadArr.optString(i) == cmd) { exists = true; break }
            }
            if (!exists) deadArr.put(cmd)
            pushRecentLog("$cmd -> NO_EFFECT")
        }
        save(context)
    }

    @Synchronized
    fun isBlacklisted(context: Context, stateKey: String, normalizedCommand: String): Boolean {
        val root = load(context)
        val deadRoot = root.optJSONObject("dead") ?: return false
        val deadArr = deadRoot.optJSONArray(stateKey) ?: return false
        val cmd = normalizedCommand.trim().uppercase()
        for (i in 0 until deadArr.length()) {
            if (deadArr.optString(i) == cmd) return true
        }
        return false
    }

    @Synchronized
    fun getMemoryPromptBlock(context: Context, stateKey: String): String {
        val root = load(context)
        val deadRoot = root.optJSONObject("dead")
        val deadArr = deadRoot?.optJSONArray(stateKey)
        val deadList = mutableListOf<String>()
        if (deadArr != null) {
            for (i in 0 until deadArr.length()) deadList.add(deadArr.optString(i))
        }

        val verRoot = root.optJSONObject("verified")
        val verObj = verRoot?.optJSONObject(stateKey)
        val verList = mutableListOf<String>()
        if (verObj != null) {
            verObj.keys().forEach { verList.add(it) }
        }

        val recent = recentSessionLog.takeLast(4)

        return buildString {
            if (verList.isNotEmpty()) {
                appendLine("LEARNED_WORKING_MOVES: ${verList.take(5).joinToString(", ")}")
            }
            if (deadList.isNotEmpty()) {
                appendLine("DO_NOT_REPEAT_DEAD_MOVES: ${deadList.takeLast(8).joinToString(", ")}")
            }
            if (recent.isNotEmpty()) {
                appendLine("RECENT_HISTORY: ${recent.joinToString(" | ")}")
            }
        }.trim()
    }

    private fun pushRecentLog(entry: String) {
        if (recentSessionLog.size >= 10) recentSessionLog.removeFirst()
        recentSessionLog.addLast(entry)
    }
}
