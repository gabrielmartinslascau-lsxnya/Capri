package com.dot.capri.util

import android.content.Context
import android.graphics.Color

object CustomizationPrefs {
    private const val PREF_NAME = "capri_customization_prefs"

    const val KEY_ACCENT_COLOR = "accent_color"
    const val KEY_TEXT_SIZE_SP = "text_size_sp"
    const val KEY_BANNER_SCALE = "banner_scale"
    const val KEY_PILL_SCALE = "pill_scale"
    const val KEY_ANIM_SPEED = "anim_speed"
    const val KEY_BANNER_DURATION_SEC = "banner_duration_sec"

    // Preset Cyberpunk / Capri Accents
    val PRESET_COLORS = listOf(
        Pair("Neon Cyan", Color.parseColor("#00F0FF")),
        Pair("Cyber Pink", Color.parseColor("#FF007F")),
        Pair("Night City Purple", Color.parseColor("#8B5CF6")),
        Pair("Electric Yellow", Color.parseColor("#FFE600")),
        Pair("Acid Green", Color.parseColor("#39FF14")),
        Pair("Pure White", Color.parseColor("#F8FAFC"))
    )

    fun getAccentColor(context: Context): Int {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getInt(KEY_ACCENT_COLOR, Color.parseColor("#00F0FF"))
    }

    fun setAccentColor(context: Context, color: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ACCENT_COLOR, color)
            .apply()
    }

    fun getTextSizeSp(context: Context): Float {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getFloat(KEY_TEXT_SIZE_SP, 14.0f)
    }

    fun setTextSizeSp(context: Context, sizeSp: Float) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_TEXT_SIZE_SP, sizeSp)
            .apply()
    }

    fun getBannerScale(context: Context): Float {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getFloat(KEY_BANNER_SCALE, 1.0f)
    }

    fun setBannerScale(context: Context, scale: Float) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_BANNER_SCALE, scale)
            .apply()
    }

    fun getPillScale(context: Context): Float {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getFloat(KEY_PILL_SCALE, 1.0f)
    }

    fun setPillScale(context: Context, scale: Float) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_PILL_SCALE, scale)
            .apply()
    }

    fun getAnimSpeed(context: Context): String {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_ANIM_SPEED, "SMOOTH") ?: "SMOOTH"
    }

    fun setAnimSpeed(context: Context, speed: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ANIM_SPEED, speed)
            .apply()
    }

    fun getAnimDurationMs(context: Context): Long {
        return when (getAnimSpeed(context)) {
            "FAST" -> 250L
            "RELAXED" -> 700L
            else -> 450L // SMOOTH
        }
    }

    fun getBannerDurationSec(context: Context): Int {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getInt(KEY_BANNER_DURATION_SEC, 5)
    }

    fun setBannerDurationSec(context: Context, sec: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BANNER_DURATION_SEC, sec)
            .apply()
    }
}
