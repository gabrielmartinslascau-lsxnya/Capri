package com.dot.capri.llm

import android.content.Context
import android.util.Base64
import android.util.Log
import com.dot.capri.MainActivity
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

    override val name: String = "Gemini"
    override val currentModel: String = modelOverride?.ifBlank { null } ?: "gemini-3.5-flash-lite"

    private var lastError: String? = null
    private var isReachableState: Boolean = true

    override fun health(): ProviderHealth {
        val configured = apiKey.isNotBlank()
        return ProviderHealth(isConfigured = configured, isReachable = isReachableState, lastError = lastError)
    }

    override suspend fun complete(req: LlmRequest): Result<LlmResponse> {
        if (apiKey.isBlank()) {
            val errorMsg = "Gemini API key is blank"
            lastError = errorMsg
            return Result.failure(IllegalStateException(errorMsg))
        }

        val startTime = System.currentTimeMillis()
        val urlString = "https://generativelanguage.googleapis.com/v1beta/models/$currentModel:generateContent?key=$apiKey"

        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.connectTimeout = 5000
            connection.readTimeout = 15000
            connection.doOutput = true

            val payload = JSONObject().apply {
                val contentsArray = JSONArray()

                val systemPromptText = StringBuilder(req.systemPrompt)

                if (req.history.isNotEmpty()) {
                    val historyBuilder = StringBuilder()
                    req.history.forEach { (user, model) ->
                        val line = "user: $user\ncapri: $model\n"
                        if (historyBuilder.length + line.length > 800) {
                            val current = historyBuilder.toString()
                            val needed = 800 - line.length
                            if (needed > 0 && current.length >= needed) {
                                historyBuilder.setLength(0)
                                historyBuilder.append(current.takeLast(needed))
                            } else {
                                historyBuilder.setLength(0)
                            }
                        }
                        historyBuilder.append(line)
                    }
                    val historyBlock = historyBuilder.toString().trim()
                    if (historyBlock.isNotEmpty()) {
                        systemPromptText.append("\n\n[Recent Conversation History]:\n$historyBlock")
                    }
                }

                val partsArray = JSONArray()
                val promptObj = JSONObject().apply {
                    put("text", "$systemPromptText\n\nUser: ${req.prompt}")
                }
                partsArray.put(promptObj)

                if (req.imageBytes != null) {
                    val base64Image = Base64.encodeToString(req.imageBytes, Base64.NO_WRAP)
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

            val latency = System.currentTimeMillis() - startTime
            val statusCode = connection.responseCode
            if (statusCode in 200..299) {
                val responseText = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                val jsonResponse = JSONObject(responseText)

                var tokensIn = req.prompt.length / 4
                var tokensOut = 0

                val usageMetadata = jsonResponse.optJSONObject("usageMetadata")
                if (usageMetadata != null) {
                    tokensIn = usageMetadata.optInt("promptTokenCount", tokensIn)
                    tokensOut = usageMetadata.optInt("candidatesTokenCount", 0)
                }

                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val text = parts.getJSONObject(0).optString("text", "").trim()
                        if (tokensOut == 0) tokensOut = text.length / 4

                        isReachableState = true
                        lastError = null

                        return Result.success(
                            LlmResponse(
                                text = text,
                                tokensIn = tokensIn,
                                tokensOut = tokensOut,
                                latencyMs = latency,
                                provider = name,
                                model = currentModel
                            )
                        )
                    }
                }
                isReachableState = false
                val err = "Empty response candidate from Gemini API"
                lastError = err
                Result.failure(RuntimeException(err))
            } else {
                val errorText = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                isReachableState = false
                val err = "Gemini API Error $statusCode: $errorText"
                lastError = err
                Result.failure(RuntimeException(err))
            }
        } catch (e: Exception) {
            isReachableState = false
            lastError = e.message ?: "Connection exception"
            Log.e("CAPRI_LLM", "Gemini API Exception", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }
}
