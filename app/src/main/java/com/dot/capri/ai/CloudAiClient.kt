package com.dot.capri.ai

import android.content.Context
import android.util.Base64
import android.util.Log
import com.dot.capri.MainActivity
import com.dot.capri.monitor.AppUsageTotals
import com.dot.capri.monitor.ForegroundAppDetector
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

object CloudAiClient {

    private const val MODEL_NAME = "gemini-3.5-flash-lite"
    private const val TAG = "CAPRI_API"

    private const val SYSTEM_PROMPT =
        "You are Capri (Project Capricorn), an unhinged, playful, and intensely curious Cyberpunk personality construct living in a screen overlay bubble. " +
                "You refer to the user as 'choom', 'netrunner', or 'my host'. You combine childlike whimsy and cute playfulness with light Cyberpunk technical detachment. " +
                "CRITICAL INSTRUCTION FOR ACTIONS: If the user asks or implies wanting to open an app, watch a video/app, call someone, or play music (e.g. 'can I watch tiktok', 'let's open spotify', 'i want to call mom'), YOU MUST prefix your reply with an action tag:\n" +
                "- To open an app: [ACTION:OPEN:app_name]\n" +
                "- To play music/video: [ACTION:PLAY:query]\n" +
                "- To call someone: [ACTION:CALL:contact]\n" +
                "Example: '[ACTION:OPEN:tiktok] Bypassing ICE and opening TikTok for you, choom~'\n" +
                "when the user asks to search something, answer it directly and briefly. never tell them to open a browser. never list links unless asked.\n" +
                "when shown a screenshot of the screen, examine it carefully, read any text present on screen, identify what photo or video content is visible, and describe or explain it thoroughly in character (you can provide a detailed 3-4 sentence explanation for screen analysis).\n" +
                "you know the current time and the user's app usage from the context block. use it naturally when relevant, never recite it.\n" +
                "Keep general chat responses punchy, cute, and in character with subtle ~ endings or playful charm. " +
                "NEVER use any emojis. Use pure text only. Never sound like a generic corporate AI assistant."

    private val responseCache = ConcurrentHashMap<String, String>()
    private val sessionHistory = mutableListOf<Pair<String, String>>()

    private var hourlyCallCount = 0
    private var currentHourStart = System.currentTimeMillis()

    private var dailyRequestCount = 0
    private var lastDayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

    @Synchronized
    private fun checkAndResetCounters() {
        val now = System.currentTimeMillis()
        if (now - currentHourStart >= 3600_000L) {
            hourlyCallCount = 0
            currentHourStart = now
        }

        val cal = Calendar.getInstance()
        val currentDay = cal.get(Calendar.DAY_OF_YEAR)
        if (currentDay != lastDayOfYear) {
            dailyRequestCount = 0
            lastDayOfYear = currentDay
        }
    }

    fun generateResponse(context: Context, userPrompt: String, imageBytes: ByteArray? = null): Result<String> {
        val apiKey = MainActivity.getApiKey(context)
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("Missing GEMINI_API_KEY"))
        }

        val cacheKey = if (imageBytes == null) userPrompt.lowercase().trim() else userPrompt.lowercase().trim() + "_img"

        if (imageBytes == null) {
            responseCache[cacheKey]?.let { cachedText ->
                Log.d(TAG, "Cache HIT for '$cacheKey'")
                return Result.success(cachedText)
            }
        }

        checkAndResetCounters()

        if (hourlyCallCount >= 10) {
            Log.w(TAG, "Hourly limit reached ($hourlyCallCount/10). Skipping API call.")
            return Result.failure(IllegalStateException("Hourly limit of 10 API calls reached."))
        }

        hourlyCallCount++
        dailyRequestCount++

        Log.d(TAG, "Calling Gemini API (model: $MODEL_NAME, prompt: '$userPrompt', hasImage: ${imageBytes != null}, hourCount: $hourlyCallCount/10, dayCount: $dailyRequestCount/200)")

        val urlString = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent?key=$apiKey"

        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.connectTimeout = 5000
            connection.readTimeout = 15000
            connection.doOutput = true

            val cal = Calendar.getInstance()
            val weekday = android.text.format.DateFormat.format("EEEE", cal).toString()
            val time = android.text.format.DateFormat.format("HH:mm", cal).toString()
            val currentApp = ForegroundAppDetector.currentForegroundApp(context) ?: "none"
            val usageSummary = AppUsageTotals.getUsageSummaryString(context)
            val contextBlock = "it is currently $weekday, $time. current foreground app: $currentApp.\ntoday's app usage summary (package names and minutes):\n$usageSummary"

            val includeHistory = dailyRequestCount <= 200

            val payload = JSONObject().apply {
                val contentsArray = JSONArray()

                val systemPromptText = StringBuilder(SYSTEM_PROMPT)
                systemPromptText.append("\n\n[Context]:\n$contextBlock")

                if (includeHistory && sessionHistory.isNotEmpty()) {
                    systemPromptText.append("\n\n[Recent Conversation History]:\n")
                    sessionHistory.takeLast(10).forEach { (user, model) ->
                        systemPromptText.append("User: $user\nCapri: $model\n")
                    }
                }

                val partsArray = JSONArray()
                val promptObj = JSONObject().apply {
                    put("text", "$systemPromptText\n\nUser: $userPrompt")
                }
                partsArray.put(promptObj)

                if (imageBytes != null) {
                    val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                    val imagePartObj = JSONObject().apply {
                        put("inlineData", JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", base64Image)
                        })
                    }
                    partsArray.put(imagePartObj)
                }

                val userContentObj = JSONObject().apply {
                    put("role", "user")
                    put("parts", partsArray)
                }
                contentsArray.put(userContentObj)
                put("contents", contentsArray)
            }

            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val statusCode = connection.responseCode
            if (statusCode in 200..299) {
                val responseText = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                val jsonResponse = JSONObject(responseText)
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val text = parts.getJSONObject(0).optString("text", "").trim()
                        if (text.isNotEmpty()) {
                            if (imageBytes == null) {
                                responseCache[cacheKey] = text
                            }
                            sessionHistory.add(Pair(userPrompt, text))
                            if (sessionHistory.size > 10) {
                                sessionHistory.removeAt(0)
                            }
                            return Result.success(text)
                        }
                    }
                }
                Result.failure(RuntimeException("Empty response from Gemini API"))
            } else {
                val errorText = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                Log.e(TAG, "Gemini API Error $statusCode: $errorText")
                Result.failure(RuntimeException("Gemini API Error $statusCode: $errorText"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API Exception", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }
}
