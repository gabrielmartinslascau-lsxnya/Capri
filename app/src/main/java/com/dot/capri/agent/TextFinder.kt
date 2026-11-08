package com.dot.capri.agent

import android.graphics.BitmapFactory
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Fast on-device OCR using a persistent ML Kit TextRecognizer singleton.
 * Maps every visible word/button on screen to exact real-screen pixels and normalized (0..1) coordinates.
 */
object TextFinder {

    /** Persistent recognizer singleton — never close per frame. */
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    data class Hit(
        val x: Int,
        val y: Int,
        val normX: Float,
        val normY: Float,
        val text: String
    )

    suspend fun find(screenshot: ByteArray, screenW: Int, screenH: Int, wanted: String): Hit? {
        val needle = wanted.trim().lowercase()
        if (needle.isEmpty()) return null
        val all = scanAll(screenshot, screenW, screenH)
        return all.firstOrNull { it.text.lowercase() == needle }
            ?: all.firstOrNull { it.text.lowercase().contains(needle) }
            ?: all.firstOrNull { needle.contains(it.text.lowercase()) && it.text.length >= 3 }
    }

    /**
     * Scans the screenshot once and returns all distinct text lines with their exact
     * real-screen pixel coordinates (x, y) and normalized (0.00..1.00) coordinates.
     */
    suspend fun scanAll(screenshot: ByteArray, screenW: Int, screenH: Int, maxItems: Int = 35): List<Hit> {
        val bitmap = BitmapFactory.decodeByteArray(screenshot, 0, screenshot.size) ?: return emptyList()
        if (bitmap.width <= 0 || bitmap.height <= 0) return emptyList()

        val (orientedW, orientedH) = if (screenW > 0 && screenH > 0) {
            PhoneControl.orientedScreenSize(bitmap.width, bitmap.height)
        } else {
            Pair(bitmap.width, bitmap.height)
        }

        val scaleX = orientedW.toFloat() / bitmap.width.toFloat()
        val scaleY = orientedH.toFloat() / bitmap.height.toFloat()

        val visionText = recognise(InputImage.fromBitmap(bitmap, 0)) ?: return emptyList()
        val results = ArrayList<Hit>()
        val seen = HashSet<String>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val clean = line.text.trim().replace("\n", " ")
                if (clean.isEmpty() || clean.length > 45) continue
                // Never register the STOP pill as an OCR target
                if (clean.equals("stop", ignoreCase = true)) continue

                val box: Rect = line.boundingBox ?: continue
                val cx = (box.exactCenterX() * scaleX).toInt().coerceIn(0, orientedW - 1)
                val cy = (box.exactCenterY() * scaleY).toInt().coerceIn(0, orientedH - 1)
                val nx = (box.exactCenterX() / bitmap.width.toFloat()).coerceIn(0f, 1f)
                val ny = (box.exactCenterY() / bitmap.height.toFloat()).coerceIn(0f, 1f)

                val key = "${clean.lowercase()}|${cx / 24}|${cy / 24}"
                if (seen.add(key)) {
                    results.add(Hit(x = cx, y = cy, normX = nx, normY = ny, text = clean))
                    if (results.size >= maxItems) return results
                }
            }
        }
        return results
    }

    suspend fun formatForPrompt(screenshot: ByteArray?, screenW: Int, screenH: Int): String {
        if (screenshot == null) return "none"
        val hits = scanAll(screenshot, screenW, screenH, maxItems = 28)
        return formatHitsForPrompt(hits, screenW, screenH)
    }

    /**
     * Formats pre-scanned OCR hits into a general-purpose list of visible text and normalized coordinates.
     * When screen dimensions are given, annotates each hit with its visual grid cell (e.g. [Cell B1]).
     */
    fun formatHitsForPrompt(hits: List<Hit>, screenW: Int = 0, screenH: Int = 0): String {
        if (hits.isEmpty()) return "(no text detected by OCR)"
        val isLandscape = screenW > screenH
        val cols = if (isLandscape) 10 else 6
        val rows = if (isLandscape) 6 else 10
        return hits.joinToString("\n") { h ->
            val colLetter = 'A' + (h.normX * cols).toInt().coerceIn(0, cols - 1)
            val rowNum = (h.normY * rows).toInt().coerceIn(0, rows - 1) + 1
            val cellStr = if (screenW > 0 && screenH > 0) " [Cell $colLetter$rowNum]" else ""
            String.format(Locale.US, "- \"%s\" at (x=%.2f, y=%.2f)%s", h.text, h.normX, h.normY, cellStr)
        }
    }

    suspend fun listLines(screenshot: ByteArray): List<String> {
        val bitmap = BitmapFactory.decodeByteArray(screenshot, 0, screenshot.size) ?: return emptyList()
        val text = recognise(InputImage.fromBitmap(bitmap, 0)) ?: return emptyList()
        return text.textBlocks.flatMap { it.lines }.map { it.text.trim() }.filter { it.isNotEmpty() }
    }

    private suspend fun recognise(image: InputImage): Text? {
        return suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
    }
}
