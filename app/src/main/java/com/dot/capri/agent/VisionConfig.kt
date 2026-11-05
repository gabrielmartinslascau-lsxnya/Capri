package com.dot.capri.agent

import android.content.Context
import kotlin.math.roundToInt

/**
 * Single source of truth for Capri's screen vision pixel resolution.
 * Controls exact maxSide (192px..1088px) and JPEG quality across Accessibility, PhoneAgent, and OnDeviceAiClient.
 */
object VisionConfig {

    private const val PREFS_NAME = "capri_prefs"
    const val KEY_VISION_MAX_SIDE = "vision_max_side_px"

    const val MIN_SIDE_PX = 192
    const val MAX_SIDE_PX = 1088
    const val STEP_PX = 64
    const val DEFAULT_SIDE_PX = 512

    data class Preset(
        val maxSide: Int,
        val shortLabel: String,
        val title: String,
        val description: String,
        val jpegQuality: Int
    )

    val PRESETS = listOf(
        Preset(
            maxSide = 256,
            shortLabel = "256",
            title = "256px · Turbo (Low Detail)",
            description = "Fastest local Gemma E2B prefill. Sees large buttons and high-contrast objects; tiny text relies on OCR.",
            jpegQuality = 72
        ),
        Preset(
            maxSide = 384,
            shortLabel = "384",
            title = "384px · Fast",
            description = "Fast local prefill (~1.1s). Good for standard UI buttons, game menus, and PvZ lawn rows.",
            jpegQuality = 78
        ),
        Preset(
            maxSide = 512,
            shortLabel = "512",
            title = "512px · Balanced (Recommended)",
            description = "Best balance of speed and pixel accuracy. Clearly resolves game hotbars, seed packets, and icons.",
            jpegQuality = 84
        ),
        Preset(
            maxSide = 768,
            shortLabel = "768",
            title = "768px · Sharp",
            description = "High pixel clarity for dense game UIs, small icons, and chess boards. Slower prefill on local CPU.",
            jpegQuality = 88
        ),
        Preset(
            maxSide = 1024,
            shortLabel = "1024",
            title = "1024px · Eagle Eye (Max Detail)",
            description = "Near-native detail for tiny buttons and fine pixel inspection. Heaviest load on local model.",
            jpegQuality = 92
        )
    )

    fun getMaxSide(context: Context): Int {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_VISION_MAX_SIDE, DEFAULT_SIDE_PX)
        return snapToStep(raw)
    }

    fun setMaxSide(context: Context, maxSidePx: Int): Int {
        val clamped = snapToStep(maxSidePx)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VISION_MAX_SIDE, clamped)
            .apply()
        return clamped
    }

    fun getJpegQuality(context: Context): Int {
        val side = getMaxSide(context)
        return when {
            side <= 256 -> 72
            side <= 384 -> 78
            side <= 512 -> 84
            side <= 768 -> 88
            else -> 92
        }
    }

    fun snapToStep(value: Int): Int {
        val clamped = value.coerceIn(MIN_SIDE_PX, MAX_SIDE_PX)
        val steps = ((clamped - MIN_SIDE_PX).toFloat() / STEP_PX).roundToInt()
        return (MIN_SIDE_PX + steps * STEP_PX).coerceIn(MIN_SIDE_PX, MAX_SIDE_PX)
    }

    fun sliderMaxSteps(): Int = (MAX_SIDE_PX - MIN_SIDE_PX) / STEP_PX

    fun sideToSliderProgress(maxSidePx: Int): Int =
        ((snapToStep(maxSidePx) - MIN_SIDE_PX) / STEP_PX).coerceIn(0, sliderMaxSteps())

    fun sliderProgressToSide(progress: Int): Int =
        (MIN_SIDE_PX + progress * STEP_PX).coerceIn(MIN_SIDE_PX, MAX_SIDE_PX)

    fun describeSelection(maxSidePx: Int): Preset {
        PRESETS.firstOrNull { it.maxSide == maxSidePx }?.let { return it }
        val shortSide = (maxSidePx * 0.45f).toInt()
        val totalKilopixels = (maxSidePx * shortSide) / 1000
        return Preset(
            maxSide = maxSidePx,
            shortLabel = "$maxSidePx",
            title = "${maxSidePx}px · Custom (~${totalKilopixels}K px)",
            description = "Active frame size: ~${shortSide}x${maxSidePx}px (~${totalKilopixels},000 pixels per frame).",
            jpegQuality = getJpegQualityFromSide(maxSidePx)
        )
    }

    private fun getJpegQualityFromSide(side: Int): Int = when {
        side <= 256 -> 72
        side <= 384 -> 78
        side <= 512 -> 84
        side <= 768 -> 88
        else -> 92
    }
}
