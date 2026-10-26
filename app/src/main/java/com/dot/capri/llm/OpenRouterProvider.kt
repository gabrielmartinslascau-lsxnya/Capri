package com.dot.capri.llm

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class OpenRouterProvider(
    val apiKey: String,
    modelOverride: String? = null
) : LlmProvider {

    override val name: String = "OpenRouter"
    override val currentModel: String = modelOverride?.ifBlank { null } ?: "google/gemini-2.0-flash-exp:free"

    private var lastError: String? = null
    private var isReachableState: Boolean = true

    override fun health(): ProviderHealth {
        val configured = apiKey.isNotBlank()
        return ProviderHealth(isConfigured = configured, isReachable = isReachableState, lastError = lastError)
    }

    override suspend fun complete(req: LlmRequest): Result<LlmResponse> {
        if (apiKey.isBlank()) {
            val errorMsg = "OpenRouter API key is blank"
            lastError = errorMsg
            return Result.failure(IllegalStateException(errorMsg))
        }

        val startTime = System.currentTimeMillis()
        val urlString = "https://openrouter.ai/api/v1/chat/completions"

        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("HTTP-Referer", "https://dot.com")
            connection.setRequestProperty("X-Title", "Capri")
            connection.connectTimeout = 5000
            connection.readTimeout = 15000
            connection.doOutput = true

            val messages = JSONArray()
            val sysMsg = JSONObject().apply {
                put("role", "system")
                put("content", req.systemPrompt)
            }
            messages.put(sysMsg)

            if (req.history.isNotEmpty()) {
                req.history.forEach { (user, model) ->
                    messages.put(JSONObject().apply {
                        put("role", "user")
                        put("content", user)
                    })
                    messages.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", model)
                    })
                }
            }

            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", req.prompt)
            })

            val payload = JSONObject().apply {
                put("model", currentModel)
                put("messages", messages)
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

                val usage = jsonResponse.optJSONObject("usage")
                if (usage != null) {
                    tokensIn = usage.optInt("prompt_tokens", tokensIn)
                    tokensOut = usage.optInt("completion_tokens", 0)
                }

                val choices = jsonResponse.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val firstChoice = choices.getJSONObject(0)
                    val message = firstChoice.optJSONObject("message")
                    val text = message?.optString("content", "")?.trim() ?: ""

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
                isReachableState = false
                val err = "Empty response choices from OpenRouter API"
                lastError = err
                Result.failure(RuntimeException(err))
            } else {
                val errorText = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                isReachableState = false
                val err = "OpenRouter API Error $statusCode: $errorText"
                lastError = err
                Result.failure(RuntimeException(err))
            }
        } catch (e: Exception) {
            isReachableState = false
            lastError = e.message ?: "Connection exception"
            Log.e("CAPRI_LLM", "OpenRouter API Exception", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }
}
