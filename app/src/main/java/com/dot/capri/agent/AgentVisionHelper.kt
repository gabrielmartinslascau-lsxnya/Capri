package com.dot.capri.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream

/**
 * Clean screen frame analyzer and perceptual hash engine.
 * Scales frames directly to the user's VisionConfig pixel budget (maxSide) WITHOUT drawing grid lines
 * or badges over the screenshot, providing a 100% clean image to the LLM.
 */
object AgentVisionHelper {

    data class FrameAnalysis(
        val stampedBitmap: Bitmap,
        val jpegBytes: ByteArray,
        val screenHash: Long,
        val cellHashes: LongArray,
        val yellowTargetCells: List<String>,
        val motionCells: List<String>
    )

    fun processFrame(
        context: Context,
        rawBytes: ByteArray,
        previousCellHashes: LongArray? = null
    ): FrameAnalysis? {
        val softBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null

        val maxSide = VisionConfig.getMaxSide(context)
        val jpegQuality = VisionConfig.getJpegQuality(context)

        val origW = softBitmap.width
        val origH = softBitmap.height

        val scale = minOf(1f, maxSide.toFloat() / maxOf(origW, origH))
        val outW = (origW * scale).toInt().coerceAtLeast(1)
        val outH = (origH * scale).toInt().coerceAtLeast(1)

        val scaledSrc = Bitmap.createScaledBitmap(softBitmap, outW, outH, true)
        val outBitmap = scaledSrc.copy(Bitmap.Config.ARGB_8888, true)

        val grid = GridSpec.of(origW, origH)
        val cols = grid.cols
        val rows = grid.rows

        val cellW = outW.toFloat() / cols
        val cellH = outH.toFloat() / rows

        val cellHashes = LongArray(cols * rows)
        val yellowCells = mutableListOf<String>()
        val motionCells = mutableListOf<String>()

        var globalHash = 1125899906842597L

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cellIndex = r * cols + c
                val cellName = "${'A' + c}${r + 1}"
                val startX = (c * cellW).toInt().coerceIn(0, outW - 1)
                val startY = (r * cellH).toInt().coerceIn(0, outH - 1)
                val endX = ((c + 1) * cellW).toInt().coerceIn(0, outW - 1)
                val endY = ((r + 1) * cellH).toInt().coerceIn(0, outH - 1)

                var yellowHits = 0
                val stepX = ((endX - startX) / 4).coerceAtLeast(1)
                val stepY = ((endY - startY) / 4).coerceAtLeast(1)

                var localHash = 1469598103934665603L
                for (py in startY until endY step stepY) {
                    for (px in startX until endX step stepX) {
                        val pixel = outBitmap.getPixel(px, py)
                        val red = Color.red(pixel)
                        val green = Color.green(pixel)
                        val blue = Color.blue(pixel)
                        val lum = (red * 3 + green * 4 + blue) shr 3

                        localHash = (localHash xor ((lum shr 4).toLong())) * 1099511628211L

                        if (red > 220 && green > 190 && blue < 90) {
                            yellowHits++
                        }
                    }
                }

                cellHashes[cellIndex] = localHash
                globalHash = (globalHash xor localHash) * 1099511628211L

                if (yellowHits >= 3 && r in 1 until (rows - 1)) {
                    yellowCells.add(cellName)
                }

                if (previousCellHashes != null && previousCellHashes.size == cols * rows) {
                    if (previousCellHashes[cellIndex] != localHash) {
                        motionCells.add(cellName)
                    }
                }
            }
        }

        val baos = ByteArrayOutputStream()
        outBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)

        return FrameAnalysis(
            stampedBitmap = outBitmap,
            jpegBytes = baos.toByteArray(),
            screenHash = globalHash,
            cellHashes = cellHashes,
            yellowTargetCells = yellowCells.take(4),
            motionCells = motionCells.take(6)
        )
    }

    fun cellToPixelCenter(cellId: String, screenWidth: Int, screenHeight: Int): Pair<Int, Int>? {
        return GridSpec.centre(cellId, screenWidth, screenHeight)
    }

    fun pixelToCell(x: Float, y: Float, screenWidth: Int, screenHeight: Int): String {
        return GridSpec.nameOf(x.toInt(), y.toInt(), screenWidth, screenHeight)
    }
}
