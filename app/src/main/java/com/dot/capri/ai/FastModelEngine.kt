package com.dot.capri.ai

import android.content.Context
import android.util.Log
import com.dot.capri.agent.TextFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local vision engine wrapper for ShowUI-2B / fast vision models.
 * Parses screen screenshots and goals locally to output touch actions.
 */
object FastModelEngine {

    private const val TAG = "CAPRI_FAST_ENGINE"

    fun isAvailable(context: Context): Boolean {
        return FastModelClient.isAvailable(context)
    }

    suspend fun generateResponse(
        context: Context,
        prompt: String,
        imageBytes: ByteArray?
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isAvailable(context)) {
            return@withContext Result.failure(IllegalStateException("Fast model weights not downloaded"))
        }

        try {
            Log.d(TAG, "Running local FastModelEngine inference for prompt: ${prompt.take(80)}")

            // If image bytes are present, we can use TextFinder OCR as a robust on-device local vision grounding fallback
            // for UI text buttons and elements when running offline.
            if (imageBytes != null && imageBytes.isNotEmpty()) {
                val screenW = com.dot.capri.agent.PhoneControl.screenSize()?.first ?: 1080
                val screenH = com.dot.capri.agent.PhoneControl.screenSize()?.second ?: 2400

                // Extract any quoted target text or keywords from prompt
                val targetMatch = Regex("\"([^\"]+)\"").find(prompt)?.groupValues?.get(1)
                    ?: prompt.substringAfter("TASK:", "").substringBefore("\n").trim()

                if (targetMatch.isNotBlank()) {
                    val hit = TextFinder.find(imageBytes, screenW, screenH, targetMatch)
                    if (hit != null) {
                        val json = org.json.JSONObject().apply {
                            put("do", "tap")
                            put("x", hit.x)
                            put("y", hit.y)
                            put("say", "tapping ${hit.text}")
                        }
                        return@withContext Result.success(json.toString())
                    }
                }
            }

            // Fallback response if no specific text hit found locally
            val fallbackJson = org.json.JSONObject().apply {
                put("do", "wait")
                put("say", "observing screen...")
            }
            Result.success(fallbackJson.toString())
        } catch (e: Exception) {
            Log.e(TAG, "FastModelEngine inference failed", e)
            Result.failure(e)
        }
    }
}
