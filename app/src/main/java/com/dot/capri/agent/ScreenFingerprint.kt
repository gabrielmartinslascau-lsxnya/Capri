package com.dot.capri.agent

import android.graphics.BitmapFactory
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A coarse luminance grid (24x24 cells) of a screenshot.
 *
 * Used to answer two questions reliably:
 *  1. "Did my move change anything on screen?" (before vs after the action)
 *  2. "Is this the same screen I have seen before?" (fuzzy state identity for memory)
 *
 * Averaging each cell makes the grid tolerant to JPEG noise, blinking cursors and tiny
 * animations, while still catching real UI changes such as dialogs, menus or placed pieces.
 */
class ScreenFingerprint private constructor(
    val luma: IntArray,
    val shotW: Int,
    val shotH: Int
) {

    companion object {
        const val COLS = 24
        const val ROWS = 24

        /** Per-cell luminance difference (0..255) that counts as "this cell changed". */
        const val CELL_THRESHOLD = 12

        fun of(bytes: ByteArray?): ScreenFingerprint? {
            if (bytes == null || bytes.isEmpty()) return null
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

                var sample = 1
                while (bounds.outWidth / (sample * 2) >= COLS * 3 && bounds.outHeight / (sample * 2) >= ROWS * 3) {
                    sample *= 2
                }
                val bmp = BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                ) ?: return null

                val w = bmp.width
                val h = bmp.height
                val pixels = IntArray(w * h)
                bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                bmp.recycle()

                val sums = LongArray(COLS * ROWS)
                val counts = IntArray(COLS * ROWS)
                for (y in 0 until h) {
                    val r = (y * ROWS / h).coerceAtMost(ROWS - 1)
                    val rowOff = y * w
                    for (x in 0 until w) {
                        val c = (x * COLS / w).coerceAtMost(COLS - 1)
                        val p = pixels[rowOff + x]
                        val red = (p shr 16) and 0xFF
                        val green = (p shr 8) and 0xFF
                        val blue = p and 0xFF
                        val idx = r * COLS + c
                        sums[idx] += ((red * 3 + green * 4 + blue) shr 3).toLong()
                        counts[idx]++
                    }
                }
                val luma = IntArray(COLS * ROWS) { i -> if (counts[i] == 0) 0 else (sums[i] / counts[i]).toInt() }
                ScreenFingerprint(luma, bounds.outWidth, bounds.outHeight)
            } catch (_: Throwable) {
                null
            }
        }

        fun fromLuma(luma: IntArray): ScreenFingerprint? =
            if (luma.size == COLS * ROWS) ScreenFingerprint(luma, 0, 0) else null

        /** Compact hex encoding for disk persistence (2 chars per cell). */
        fun encode(luma: IntArray): String {
            val sb = StringBuilder(luma.size * 2)
            for (v in luma) {
                val b = v.coerceIn(0, 255)
                sb.append(Character.forDigit(b shr 4, 16)).append(Character.forDigit(b and 0xF, 16))
            }
            return sb.toString()
        }

        fun decode(hex: String): IntArray? {
            if (hex.length != COLS * ROWS * 2) return null
            return IntArray(COLS * ROWS) { i ->
                (Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)
            }
        }
    }

    val isLandscape: Boolean get() = shotW > shotH

    fun meanAbsDiff(other: ScreenFingerprint): Float {
        var total = 0L
        for (i in luma.indices) total += abs(luma[i] - other.luma[i])
        return total.toFloat() / luma.size
    }

    /** Fraction (0..1) of all cells that changed. */
    fun changedFraction(other: ScreenFingerprint, threshold: Int = CELL_THRESHOLD): Float {
        var changed = 0
        for (i in luma.indices) if (abs(luma[i] - other.luma[i]) >= threshold) changed++
        return changed.toFloat() / luma.size
    }

    /**
     * Fraction of cells that changed inside (near = true) or outside (near = false) a radius
     * around any of the given normalized points. Radius is in normalized screen units.
     */
    fun changedFractionAround(
        other: ScreenFingerprint,
        points: List<Pair<Float, Float>>,
        radius: Float,
        near: Boolean,
        threshold: Int = CELL_THRESHOLD
    ): Float {
        if (points.isEmpty()) return if (near) 0f else changedFraction(other, threshold)
        var considered = 0
        var changed = 0
        for (r in 0 until ROWS) {
            val cy = (r + 0.5f) / ROWS
            for (c in 0 until COLS) {
                val cx = (c + 0.5f) / COLS
                val isNear = points.any { (px, py) -> hypot((cx - px).toDouble(), (cy - py).toDouble()) <= radius }
                if (isNear != near) continue
                considered++
                val i = r * COLS + c
                if (abs(luma[i] - other.luma[i]) >= threshold) changed++
            }
        }
        return if (considered == 0) 0f else changed.toFloat() / considered
    }

    /** Same screen (ignoring small animations / counters)? Used for fuzzy state identity. */
    fun looksLike(other: ScreenFingerprint): Boolean =
        meanAbsDiff(other) <= 9f && changedFraction(other, 24) <= 0.18f
}
