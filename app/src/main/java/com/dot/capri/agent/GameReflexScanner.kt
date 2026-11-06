package com.dot.capri.agent

import android.graphics.BitmapFactory
import android.graphics.Color
import kotlin.math.hypot

/**
 * Ultra-fast (<8ms) pixel reflex scanner that automatically collects falling sun / bonus coins
 * before the LLM executes its strategic move, preventing the LLM from getting distracted
 * or deselecting held plant cards by tapping stale sun coordinates.
 */
object GameReflexScanner {

    data class SunPoint(val screenX: Int, val screenY: Int, val normX: Float, val normY: Float)

    /**
     * Scans a screenshot for bright sun/gold clusters in the playfield (excluding the top seed bar y < 0.20).
     */
    fun detectSunClusters(
        shotBytes: ByteArray?,
        screenW: Int,
        screenH: Int,
        maxTargets: Int = 2
    ): List<SunPoint> {
        if (shotBytes == null || screenW <= 0 || screenH <= 0) return emptyList()
        val bmp = BitmapFactory.decodeByteArray(shotBytes, 0, shotBytes.size) ?: return emptyList()
        val w = bmp.width
        val h = bmp.height
        if (w < 32 || h < 32) return emptyList()

        // Only run sun reflex in landscape game orientation
        if (w <= h) return emptyList()

        val found = mutableListOf<SunPoint>()
        val stepX = (w / 28).coerceAtLeast(4)
        val stepY = (h / 20).coerceAtLeast(4)

        // Scan only the playfield (y from 22% to 88%, x from 15% to 92%) to avoid seed bank & UI borders
        val minY = (h * 0.22f).toInt()
        val maxY = (h * 0.88f).toInt()
        val minX = (w * 0.15f).toInt()
        val maxX = (w * 0.92f).toInt()

        for (y in minY..maxY step stepY) {
            for (x in minX..maxX step stepX) {
                if (isSunClusterAt(bmp, x, y, w, h)) {
                    val nx = x.toFloat() / w.toFloat()
                    val ny = y.toFloat() / h.toFloat()
                    // Deduplicate nearby detections within 10% screen distance
                    if (found.none { hypot((it.normX - nx).toDouble(), (it.normY - ny).toDouble()) < 0.10 }) {
                        val sx = (nx * screenW).toInt().coerceIn(0, screenW - 1)
                        val sy = (ny * screenH).toInt().coerceIn(0, screenH - 1)
                        found.add(SunPoint(sx, sy, nx, ny))
                        if (found.size >= maxTargets) return found
                    }
                }
            }
        }
        return found
    }

    private fun isSunClusterAt(bmp: android.graphics.Bitmap, cx: Int, cy: Int, w: Int, h: Int): Boolean {
        val center = bmp.getPixel(cx, cy)
        if (!isBrightSunYellow(center)) return false

        // Verify a small radius around (cx, cy) also has bright sun/gold pixels (rejects tiny yellow text/icons)
        val r = (w / 45).coerceAtLeast(3)
        var neighbors = 0
        val offsets = arrayOf(Pair(-r, 0), Pair(r, 0), Pair(0, -r), Pair(0, r))
        for ((dx, dy) in offsets) {
            val px = (cx + dx).coerceIn(0, w - 1)
            val py = (cy + dy).coerceIn(0, h - 1)
            if (isBrightSunYellow(bmp.getPixel(px, py))) {
                neighbors++
            }
        }
        return neighbors >= 3
    }

    private fun isBrightSunYellow(pixel: Int): Boolean {
        val red = Color.red(pixel)
        val green = Color.green(pixel)
        val blue = Color.blue(pixel)
        return red >= 238 && green >= 215 && blue <= 95
    }

    data class SeedSlotStatus(
        val name: String,
        val normX: Float,
        val normY: Float,
        val isReady: Boolean,
        val avgLuma: Int
    )

    /**
     * Checks the brightness of top-bar seed packet slots (either user-placed seed MBs or OCR-detected seed cards)
     * to determine whether each card is bright (READY / AFFORDABLE) vs darkened (ON COOLDOWN / NOT ENOUGH SUN).
     */
    fun inspectSeedSlots(
        shotBytes: ByteArray?,
        slots: List<Pair<String, Pair<Float, Float>>>
    ): List<SeedSlotStatus> {
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
            // In PvZ, darkened cards (recharge cooldown or insufficient sun) have luma < 105; ready cards have luma >= 108
            SeedSlotStatus(name = name, normX = coord.first, normY = coord.second, isReady = avg >= 106, avgLuma = avg)
        }
    }

    data class LaneThreat(
        val rowNumber: Int, // 1..5 (Row 3 is middle lane)
        val laneY: Float,
        val zombieX: Float, // normalized X (0.45..0.92), smaller = closer to house!
        val threatScore: Int
    )

    /**
     * Scans the 5 lawn rows (x = 0.42..0.92) between two consecutive screenshots (`beforeBytes` and `afterBytes`)
     * or via non-grass zombie sprite colors to detect which lane has an approaching zombie!
     */
    fun scanLawnThreats(
        shotBytes: ByteArray?,
        beforeFp: ScreenFingerprint?,
        afterFp: ScreenFingerprint?
    ): List<LaneThreat> {
        val laneYs = floatArrayOf(0.25f, 0.39f, 0.53f, 0.68f, 0.82f)
        val threats = mutableListOf<LaneThreat>()

        // 1. Fast motion/sprite diff across right-side lawn columns (x = 0.44..0.90)
        if (beforeFp != null && afterFp != null) {
            for (r in laneYs.indices) {
                val ly = laneYs[r]
                var closestX = 1.0f
                var motionHits = 0
                val sampleXs = floatArrayOf(0.48f, 0.58f, 0.68f, 0.78f, 0.88f)
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
                        if (sx < closestX) closestX = sx
                    }
                }
                if (motionHits > 0) {
                    threats.add(LaneThreat(rowNumber = r + 1, laneY = ly, zombieX = closestX, threatScore = motionHits * 10 + ((1f - closestX) * 20).toInt()))
                }
            }
        }

        if (threats.isNotEmpty()) {
            return threats.sortedByDescending { it.threatScore }
        }

        // 2. Static single-frame fallback: look for grey-blue/brown zombie coat/head pixels on the right half of each row (x = 0.52..0.90)
        if (shotBytes != null) {
            val bmp = BitmapFactory.decodeByteArray(shotBytes, 0, shotBytes.size) ?: return emptyList()
            val w = bmp.width
            val h = bmp.height
            if (w > h && w >= 64 && h >= 64) {
                for (r in laneYs.indices) {
                    val ly = laneYs[r]
                    val py = (ly * h).toInt().coerceIn(4, h - 5)
                    var zombiePixels = 0
                    var minZx = 1.0f
                    val xStart = (w * 0.52f).toInt()
                    val xEnd = (w * 0.90f).toInt()
                    val step = (w / 40).coerceAtLeast(3)
                    for (px in xStart..xEnd step step) {
                        val c = bmp.getPixel(px, py)
                        val red = Color.red(c)
                        val green = Color.green(c)
                        val blue = Color.blue(c)
                        // Zombie coat/skin/cone: muted slate/brown/orange where green is NOT dominant like lawn grass
                        val isLawnGrass = green > red + 18 && green > blue + 25
                        val isDirt = red in 110..175 && green in 75..130 && blue in 40..90 && (red - green) in 20..55
                        if (!isLawnGrass && !isDirt && red in 55..210 && green in 50..180 && blue in 50..175 && kotlin.math.abs(red - blue) < 45) {
                            zombiePixels++
                            val nx = px.toFloat() / w.toFloat()
                            if (nx < minZx) minZx = nx
                        }
                    }
                    if (zombiePixels >= 3) {
                        threats.add(LaneThreat(rowNumber = r + 1, laneY = ly, zombieX = minZx, threatScore = zombiePixels * 3))
                    }
                }
            }
        }

        return threats.sortedByDescending { it.threatScore }
    }
}
