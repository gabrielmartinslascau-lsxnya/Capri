package com.dot.capri.llm

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class AnthropicProvider(
    val apiKey: String,
    modelOverride: String? = null
) : LlmProvider {

    override val name: String = "Anthropic"
    override val currentModel: String = modelOverride?.ifBlank { null } ?: "claude-haiku-4-5"

    private var lastError: String? = null
    private var isReachableState: Boolean = true

    override fun health(): ProviderHealth {
        val configured = apiKey.isNotBlank()
        return ProviderHealth(isConfigured = configured, isReachable = isReachableState, lastError = lastError)
    }

    override suspend fun complete(req: LlmRequest): Result<LlmResponse> {
        if (apiKey.isBlank()) {
            val errorMsg = "Anthropic API key is blank"
            lastError = errorMsg
            return Result.failure(IllegalStateException(errorMsg))
        }

        val startTime = System.currentTimeMillis()
        val urlString = "https://api.anthropic.com/v1/messages"

        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("x-api-key", apiKey)
            connection.setRequestProperty("anthropic-version", "2023-06-01")
            connection.connectTimeout = 5000
            connection.readTimeout = 15000
            connection.doOutput = true

            val fullUserPrompt = StringBuilder()
            if (req.history.isNotEmpty()) {
                req.history.forEach { (user, model) ->
                    fullUserPrompt.append("User: $user\nCapri: $model\n")
                }
            }
            fullUserPrompt.append("User: ${req.prompt}")

            val payload = JSONObject().apply {
                put("model", currentModel)
                put("max_tokens", 1024)
                put("system", req.systemPrompt)

                val messages = JSONArray()
                val userMsg = JSONObject().apply {
                    put("role", "user")
                    put("content", fullUserPrompt.toString())
                }
                messages.put(userMsg)
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
                    tokensIn = usage.optInt("input_tokens", tokensIn)
                    tokensOut = usage.optInt("output_tokens", 0)
                }

                val contentArr = jsonResponse.optJSONArray("content")
                if (contentArr != null && contentArr.length() > 0) {
                    val firstContent = contentArr.getJSONObject(0)
                    val text = firstContent.optString("text", "").trim()
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
                val err = "Empty response content from Anthropic API"
                lastError = err
                Result.failure(RuntimeException(err))
            } else {
                val errorText = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                isReachableState = false
                val err = "Anthropic API Error $statusCode: $errorText"
                lastError = err
                Result.failure(RuntimeException(err))
            }
        } catch (e: Exception) {
            isReachableState = false
            lastError = e.message ?: "Connection exception"
            Log.e("CAPRI_LLM", "Anthropic API Exception", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }
}
