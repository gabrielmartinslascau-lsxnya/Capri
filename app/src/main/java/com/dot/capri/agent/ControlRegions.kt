package com.dot.capri.agent

import android.content.Context

/**
 * Named on-screen buttons, stored as fractions of the screen (0..1), so they survive a
 * change of resolution. Calibrate in the orientation you play in.
 * Stored in SharedPreferences as "name:x:y;name:x:y".
 */
object ControlRegions {

    private const val PREFS = "capri_prefs"
    private const val KEY = "control_regions"

    fun all(context: Context): Map<String, Pair<Float, Float>> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        return raw.split(";").mapNotNull { entry ->
            val p = entry.split(":")
            if (p.size != 3) return@mapNotNull null
            val x = p[1].toFloatOrNull() ?: return@mapNotNull null
            val y = p[2].toFloatOrNull() ?: return@mapNotNull null
            p[0] to Pair(x, y)
        }.toMap()
    }

    fun names(context: Context): String = all(context).keys.joinToString(", ")

    fun set(context: Context, name: String, x: Float, y: Float) {
        val clean = name.trim().lowercase().replace(Regex("[^a-z0-9_]"), "")
        if (clean.isEmpty()) return
        val updated = all(context).toMutableMap()
        updated[clean] = Pair(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        val raw = updated.entries.joinToString(";") { "${it.key}:${it.value.first}:${it.value.second}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, raw).apply()
    }

    /** Pixel position of a named region on the current screen, or null if unknown. */
    fun resolve(context: Context, name: String): Pair<Int, Int>? {
        val (fx, fy) = all(context)[name.trim().lowercase()] ?: return null
        val (w, h) = PhoneControl.screenSize() ?: return null
        return Pair((fx * w).toInt(), (fy * h).toInt())
    }
}
