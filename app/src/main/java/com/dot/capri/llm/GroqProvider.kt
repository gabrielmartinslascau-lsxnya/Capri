package com.dot.capri.llm

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class GroqProvider(
    val apiKey: String,
    modelOverride: String? = null
) : LlmProvider {

    override val name: String = "Groq"
    override val currentModel: String = modelOverride?.ifBlank { null } ?: "llama-3.1-70b-versatile"

    private var lastError: String? = null
    private var isReachableState: Boolean = true

    override fun health(): ProviderHealth {
        val configured = apiKey.isNotBlank()
        return ProviderHealth(isConfigured = configured, isReachable = isReachableState, lastError = lastError)
    }

    override suspend fun complete(req: LlmRequest): Result<LlmResponse> {
        if (apiKey.isBlank()) {
            val errorMsg = "Groq API key is blank"
            lastError = errorMsg
            return Result.failure(IllegalStateException(errorMsg))
        }

        val startTime = System.currentTimeMillis()
        val urlString = "https://api.groq.com/openai/v1/chat/completions"

        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.connectTimeout = 5000
            connection.readTimeout = 20000
            connection.doOutput = true

            val messages = JSONArray()
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", req.systemPrompt)
            })

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

            val userMsg = JSONObject().apply {
                put("role", "user")
                if (req.imageBytes != null && req.imageBytes.isNotEmpty()) {
                    val userContent = JSONArray()
                    userContent.put(JSONObject().apply {
                        put("type", "text")
                        put("text", req.prompt)
                    })
                    
                    // Downscale for Groq Vision limits (max 1120x1120)
                    var finalBytes = req.imageBytes
                    try {
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(req.imageBytes, 0, req.imageBytes.size)
                        if (bmp != null && (bmp.width > 1024 || bmp.height > 1024)) {
                            val scale = 1024f / maxOf(bmp.width, bmp.height)
                            val scaled = android.graphics.Bitmap.createScaledBitmap(
                                bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true
                            )
                            val out = java.io.ByteArrayOutputStream()
                            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                            finalBytes = out.toByteArray()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    val base64Image = Base64.encodeToString(finalBytes, Base64.NO_WRAP)
                    userContent.put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$base64Image")
                        })
                    })
                    put("content", userContent)
                } else {
                    put("content", req.prompt)
                }
            }
            messages.put(userMsg)

            val effectiveModel = if (req.imageBytes != null && req.imageBytes.isNotEmpty() && !currentModel.lowercase().contains("vision")) {
                "llama-3.2-90b-vision-preview"
            } else {
                currentModel
            }

            val payload = JSONObject().apply {
                put("model", effectiveModel)
                put("messages", messages)
                put("max_tokens", req.maxTokens ?: 1024)
                if (req.systemPrompt.contains("JSON object")) {
                    put("response_format", JSONObject().put("type", "json_object"))
                }
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
                val err = "Empty response choices from Groq API"
                lastError = err
                Result.failure(RuntimeException(err))
            } else {
                val errorText = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                isReachableState = false
                val err = "Groq API Error $statusCode: $errorText"
                lastError = err
                Result.failure(RuntimeException(err))
            }
        } catch (e: Exception) {
            isReachableState = false
            lastError = e.message ?: "Connection exception"
            Log.e("CAPRI_LLM", "Groq API Exception", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }
}
