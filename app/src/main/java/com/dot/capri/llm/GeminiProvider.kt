package com.dot.capri.llm

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class GeminiProvider(
    private val context: Context,
    val apiKey: String,
    modelOverride: String? = null
) : LlmProvider {

    companion object {
        private const val TAG = "CAPRI_GEMINI"
        // Use a valid stable model name
        const val DEFAULT_MODEL = "gemini-1.5-flash"
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
    }

    override val name: String = "Gemini"
    override val currentModel: String = modelOverride?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    private var lastError: String? = null
    private var isReachableState: Boolean = true

    override fun health(): ProviderHealth = ProviderHealth(
        isConfigured = apiKey.isNotBlank(),
        isReachable = isReachableState,
        lastError = lastError
    )

    override suspend fun complete(req: LlmRequest): Result<LlmResponse> {
        if (apiKey.isBlank()) {
            lastError = "Gemini API key is blank"
            return Result.failure(IllegalStateException(lastError))
        }

        val started = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        return try {
            val urlString = BASE + currentModel + ":generateContent?key=" + apiKey
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                // Provide key in both header and query parameter to prevent HTTP 401 unauthorized errors
                setRequestProperty("x-goog-api-key", apiKey)
                connectTimeout = 10_000
                readTimeout = 45_000
                doOutput = true
            }
            connection = conn

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { w ->
                w.write(buildPayload(req).toString())
                w.flush()
            }

            val code = conn.responseCode
            val latency = System.currentTimeMillis() - started

            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                lastError = "HTTP $code: ${err.take(300)}"
                isReachableState = code >= 500
                Log.e(TAG, "Gemini error $lastError")
                return Result.failure(RuntimeException("Gemini HTTP $code"))
            }

            val json = JSONObject(conn.inputStream.bufferedReader().use(BufferedReader::readText))
            val text = extractText(json)
            if (text.isBlank()) {
                lastError = "Empty candidate (finishReason=${finishReason(json)})"
                Log.e(TAG, lastError!!)
                return Result.failure(RuntimeException("Gemini returned no text"))
            }

            val usage = json.optJSONObject("usageMetadata")
            val tokensIn = usage?.optInt("promptTokenCount", req.prompt.length / 4) ?: (req.prompt.length / 4)
            val tokensOut = usage?.optInt("candidatesTokenCount", text.length / 4) ?: (text.length / 4)

            isReachableState = true
            lastError = null
            Result.success(LlmResponse(text, tokensIn, tokensOut, latency, name, currentModel))
        } catch (e: Exception) {
            isReachableState = false
            lastError = e.message ?: "connection error"
            Log.e(TAG, "Gemini exception", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun buildPayload(req: LlmRequest): JSONObject {
        val contents = JSONArray()

        // Real multi-turn history, last 20 exchanges.
        req.history.takeLast(20).forEach { (user, capri) ->
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", user)))
            })
            contents.put(JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().put(JSONObject().put("text", capri)))
            })
        }

        val parts = JSONArray().put(JSONObject().put("text", req.prompt))
        if (req.imageBytes != null) {
            parts.put(JSONObject().apply {
                put("inlineData", JSONObject().apply {
                    put("mimeType", "image/jpeg")
                    put("data", Base64.encodeToString(req.imageBytes, Base64.NO_WRAP))
                })
            })
        }
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", parts)
        })

        return JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", req.systemPrompt)))
            })
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.9)
                put("topP", 0.95)
                put("maxOutputTokens", 1024)
            })
        }
    }

    private fun extractText(json: JSONObject): String {
        val candidates = json.optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            sb.append(parts.getJSONObject(i).optString("text", ""))
        }
        return sb.toString().trim()
    }

    private fun finishReason(json: JSONObject): String =
        json.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason", "unknown") ?: "unknown"
}
