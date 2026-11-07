package com.dot.capri.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object UserMemory {
    private const val FILE_NAME = "capri_user_memory.json"
    private val facts = mutableSetOf<String>()
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) {
                val json = JSONObject(file.readText())
                val arr = json.optJSONArray("facts") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val f = arr.optString(i).trim()
                    if (f.isNotBlank()) facts.add(f)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun saveFact(context: Context, fact: String) {
        init(context)
        val clean = fact.trim().take(120)
        if (clean.isBlank()) return
        facts.add(clean)
        persist(context)
    }

    @Synchronized
    fun getFactsPrompt(context: Context): String {
        init(context)
        if (facts.isEmpty()) return ""
        return facts.take(15).joinToString("\n") { "- $it" }
    }

    @Synchronized
    fun clear(context: Context) {
        facts.clear()
        persist(context)
    }

    private fun persist(context: Context) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            val json = JSONObject()
            val arr = JSONArray()
            facts.forEach { arr.put(it) }
            json.put("facts", arr)
            file.writeText(json.toString(2))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun inspectAndLearn(context: Context, text: String) {
        val trimmed = text.trim()
        val nameMatch = Regex("^(?:my\\s+name\\s+is|call\\s+me)\\s+([a-zA-Z]{2,20})$", RegexOption.IGNORE_CASE).find(trimmed)
        if (nameMatch != null) {
            saveFact(context, "User's name is ${nameMatch.groupValues[1]}")
            return
        }
        val favMatch = Regex("^(?:my\\s+fav(?:orite)?\\s+(.+?)\\s+is|i\\s+(?:really\\s+)?(?:love|like)\\s+([a-zA-Z0-9_ ]+))$", RegexOption.IGNORE_CASE).find(trimmed)
        if (favMatch != null) {
            val item = favMatch.groupValues[1].ifBlank { favMatch.groupValues[2] }.trim()
            if (item.length in 2..40) {
                saveFact(context, "User likes $item")
            }
        }
    }
}
