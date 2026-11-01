package com.dot.capri.agent

import android.graphics.BitmapFactory
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Finds on-screen text with on-device OCR and returns where it is, in real screen pixels.
 * Works for games and canvas apps, where the accessibility tree has nothing to match.
 */
object TextFinder {

    /** A found word or line: its centre on the real screen, and the text it matched. */
    data class Hit(val x: Int, val y: Int, val text: String)

    /**
     * @param screenshot the screenshot bytes (PNG or JPEG)
     * @param screenW real screen width in pixels
     * @param screenH real screen height in pixels
     * @param wanted the words to look for, case-insensitive
     * @return the best matching line, or null if nothing matches
     */
    suspend fun find(screenshot: ByteArray, screenW: Int, screenH: Int, wanted: String): Hit? {
        val bitmap = BitmapFactory.decodeByteArray(screenshot, 0, screenshot.size) ?: return null
        val scaleX = screenW.toFloat() / bitmap.width
        val scaleY = screenH.toFloat() / bitmap.height
        val text = recognise(InputImage.fromBitmap(bitmap, 0)) ?: return null

        val needle = wanted.trim().lowercase()
        if (needle.isEmpty()) return null

        // Prefer an exact line match, then a line that contains the words.
        val lines = text.textBlocks.flatMap { it.lines }
        val match = lines.firstOrNull { it.text.trim().lowercase() == needle }
            ?: lines.firstOrNull { it.text.lowercase().contains(needle) }
            ?: return null

        val box: Rect = match.boundingBox ?: return null
        return Hit(
            x = ((box.exactCenterX()) * scaleX).toInt(),
            y = ((box.exactCenterY()) * scaleY).toInt(),
            text = match.text
        )
    }

    /** Every line of text on screen, for the model to choose from when it's stuck. */
    suspend fun listLines(screenshot: ByteArray): List<String> {
        val bitmap = BitmapFactory.decodeByteArray(screenshot, 0, screenshot.size) ?: return emptyList()
        val text = recognise(InputImage.fromBitmap(bitmap, 0)) ?: return emptyList()
        return text.textBlocks.flatMap { it.lines }.map { it.text.trim() }.filter { it.isNotEmpty() }
    }

    private suspend fun recognise(image: InputImage): Text? {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }
        } finally {
            recognizer.close()
        }
    }
}
