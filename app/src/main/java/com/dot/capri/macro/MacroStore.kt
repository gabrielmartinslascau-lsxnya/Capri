package com.dot.capri.macro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class MacroStep(
    val type: String,        // home | open | tap | type | scroll
    val pkg: String = "",
    val label: String = "",
    val resId: String = "",
    val cx: Int = 0,
    val cy: Int = 0,
    val dir: String = "",
    val text: String = "",
    val delayMs: Long = 400
)

data class Macro(val keyword: String, val steps: List<MacroStep>)

object MacroStore {
    private const val FILE = "capri_macros.json"
    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun all(context: Context): List<Macro> = synchronized(lock) {
        val f = file(context)
        if (!f.exists()) return@synchronized emptyList()
        try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val sa = o.getJSONArray("steps")
                Macro(
                    o.getString("keyword"),
                    (0 until sa.length()).map { j ->
                        val s = sa.getJSONObject(j)
                        MacroStep(
                            s.getString("type"), s.optString("pkg"), s.optString("label"),
                            s.optString("resId"), s.optInt("cx"), s.optInt("cy"),
                            s.optString("dir"), s.optString("text"), s.optLong("delayMs", 400)
                        )
                    }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, macro: Macro) = synchronized(lock) {
        val list = all(context).filter { it.keyword != macro.keyword } + macro
        val arr = JSONArray()
        list.forEach { m ->
            val sa = JSONArray()
            m.steps.forEach { s ->
                sa.put(
                    JSONObject().put("type", s.type).put("pkg", s.pkg).put("label", s.label)
                        .put("resId", s.resId).put("cx", s.cx).put("cy", s.cy)
                        .put("dir", s.dir).put("text", s.text).put("delayMs", s.delayMs)
                )
            }
            arr.put(JSONObject().put("keyword", m.keyword).put("steps", sa))
        }
        file(context).writeText(arr.toString())
    }

    fun delete(context: Context, keyword: String): Boolean = synchronized(lock) {
        val key = normalize(keyword)
        val before = all(context)
        val after = before.filter { it.keyword != key }
        if (after.size == before.size) return@synchronized false
        val arr = JSONArray()
        after.forEach { m ->
            val sa = JSONArray()
            m.steps.forEach { s ->
                sa.put(
                    JSONObject().put("type", s.type).put("pkg", s.pkg).put("label", s.label)
                        .put("resId", s.resId).put("cx", s.cx).put("cy", s.cy)
                        .put("dir", s.dir).put("text", s.text).put("delayMs", s.delayMs)
                )
            }
            arr.put(JSONObject().put("keyword", m.keyword).put("steps", sa))
        }
        file(context).writeText(arr.toString())
        true
    }

    fun find(context: Context, input: String): Macro? {
        val key = normalize(input)
        return all(context).firstOrNull { it.keyword == key }
    }

    fun normalize(s: String): String = s.lowercase().trim().trimEnd('.', '!', '?')
}
