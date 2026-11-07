package com.dot.capri.agent

import android.graphics.BitmapFactory
import android.graphics.Color
import kotlin.math.hypot

/**
 * Ultra-fast (<8ms) generic pixel flux & motion scanner that measures active screen regions,
 * dynamic highlight clusters, and slot brightness across any Android game or app without title-specific hardcoding.
 */
object GameReflexScanner {

    data class ActiveHighlight(val screenX: Int, val screenY: Int, val normX: Float, val normY: Float)

    /**
     * Scans a screenshot for high-energy bright or moving highlight clusters in the active play area.
     */
    fun detectDynamicHighlights(
        shotBytes: ByteArray?,
        screenW: Int,
        screenH: Int,
        maxTargets: Int = 2
    ): List<ActiveHighlight> {
        if (shotBytes == null || screenW <= 0 || screenH <= 0) return emptyList()
        val bmp = BitmapFactory.decodeByteArray(shotBytes, 0, shotBytes.size) ?: return emptyList()
        val w = bmp.width
        val h = bmp.height
        if (w < 32 || h < 32) return emptyList()

        val found = mutableListOf<ActiveHighlight>()
        val stepX = (w / 28).coerceAtLeast(4)
        val stepY = (h / 20).coerceAtLeast(4)

        val minY = (h * 0.20f).toInt()
        val maxY = (h * 0.88f).toInt()
        val minX = (w * 0.15f).toInt()
        val maxX = (w * 0.90f).toInt()

        for (y in minY..maxY step stepY) {
            for (x in minX..maxX step stepX) {
                if (isHighEnergyClusterAt(bmp, x, y, w, h)) {
                    val nx = x.toFloat() / w.toFloat()
                    val ny = y.toFloat() / h.toFloat()
                    if (found.none { hypot((it.normX - nx).toDouble(), (it.normY - ny).toDouble()) < 0.10 }) {
                        val sx = (nx * screenW).toInt().coerceIn(0, screenW - 1)
                        val sy = (ny * screenH).toInt().coerceIn(0, screenH - 1)
                        found.add(ActiveHighlight(sx, sy, nx, ny))
                        if (found.size >= maxTargets) return found
                    }
                }
            }
        }
        return found
    }

    private fun isHighEnergyClusterAt(bmp: android.graphics.Bitmap, cx: Int, cy: Int, w: Int, h: Int): Boolean {
        val center = bmp.getPixel(cx, cy)
        val luma = (Color.red(center) * 299 + Color.green(center) * 587 + Color.blue(center) * 114) / 1000
        if (luma < 215) return false

        val r = (w / 45).coerceAtLeast(3)
        var neighbors = 0
        val offsets = arrayOf(Pair(-r, 0), Pair(r, 0), Pair(0, -r), Pair(0, r))
        for ((dx, dy) in offsets) {
            val px = (cx + dx).coerceIn(0, w - 1)
            val py = (cy + dy).coerceIn(0, h - 1)
            val pLuma = (Color.red(bmp.getPixel(px, py)) * 299 + Color.green(bmp.getPixel(px, py)) * 587 + Color.blue(bmp.getPixel(px, py)) * 114) / 1000
            if (pLuma >= 200) {
                neighbors++
            }
        }
        return neighbors >= 3
    }

    data class ButtonSlotStatus(
        val name: String,
        val normX: Float,
        val normY: Float,
        val isReady: Boolean,
        val avgLuma: Int
    )

    /**
     * Checks the brightness and contrast of candidate buttons or action slots
     * to determine whether each is bright/active vs dimmed/on cooldown.
     */
    fun inspectSlotReadiness(
        shotBytes: ByteArray?,
        slots: List<Pair<String, Pair<Float, Float>>>
    ): List<ButtonSlotStatus> {
        if (shotBytes == null || slots.isEmpty()) return emptyList()
        val bmp = BitmapFactory.decodeByteArray(shotBytes, 0, shotBytes.size) ?: return emptyList()
        val w = bmp.width
        val h = bmp.height
        if (w < 32 || h < 32) return emptyList()

        return slots.map { (name, coord) ->
            val cx = (coord.first * w).toInt().coerceIn(4, w - 5)
            val cy = (coord.second * h).toInt().coerceIn(4, h - 5)
            var sumLuma = 0
            var count = 0
            val r = (w / 60).coerceAtLeast(2)
            for (dy in -r..r step r.coerceAtLeast(1)) {
                for (dx in -r..r step r.coerceAtLeast(1)) {
                    val p = bmp.getPixel((cx + dx).coerceIn(0, w - 1), (cy + dy).coerceIn(0, h - 1))
                    val luma = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                    sumLuma += luma
                    count++
                }
            }
            val avg = if (count > 0) sumLuma / count else 128
            ButtonSlotStatus(name = name, normX = coord.first, normY = coord.second, isReady = avg >= 106, avgLuma = avg)
        }
    }

    data class ActiveMotionRow(
        val rowNumber: Int,
        val normY: Float,
        val leadingX: Float,
        val activityScore: Int
    )

    /**
     * Measures row-by-row motion activity between two consecutive screen fingerprints.
     */
    fun scanActiveMotionRows(
        beforeFp: ScreenFingerprint?,
        afterFp: ScreenFingerprint?,
        rowYs: FloatArray = floatArrayOf(0.25f, 0.40f, 0.55f, 0.70f, 0.85f)
    ): List<ActiveMotionRow> {
        if (beforeFp == null || afterFp == null) return emptyList()
        val results = mutableListOf<ActiveMotionRow>()

        for (r in rowYs.indices) {
            val ly = rowYs[r]
            var minX = 1.0f
            var motionHits = 0
            val sampleXs = floatArrayOf(0.40f, 0.52f, 0.64f, 0.76f, 0.88f)
            for (sx in sampleXs) {
                val diff = beforeFp.changedFractionAround(
                    other = afterFp,
                    points = listOf(sx to ly),
                    radius = 0.055f,
                    near = true,
                    threshold = 12
                )
                if (diff >= 0.08f) {
                    motionHits++
                    if (sx < minX) minX = sx
                }
            }
            if (motionHits > 0) {
                results.add(ActiveMotionRow(rowNumber = r + 1, normY = ly, leadingX = minX, activityScore = motionHits * 10))
            }
        }
        return results.sortedByDescending { it.activityScore }
    }
}
