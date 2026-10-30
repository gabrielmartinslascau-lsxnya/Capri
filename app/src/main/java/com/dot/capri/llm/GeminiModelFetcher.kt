package com.dot.capri.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

object GeminiModelFetcher {

    suspend fun fetchModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            if (conn.responseCode in 200..299) {
                val jsonStr = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(jsonStr)
                val modelsArr = json.optJSONArray("models") ?: return@withContext emptyList()
                val list = mutableListOf<String>()
                for (i in 0 until modelsArr.length()) {
                    val m = modelsArr.getJSONObject(i)
                    val name = m.optString("name").substringAfter("models/")
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    var supportsGenerate = false
                    if (methods != null) {
                        for (j in 0 until methods.length()) {
                            if (methods.getString(j).contains("generateContent", ignoreCase = true)) {
                                supportsGenerate = true
                                break
                            }
                        }
                    }
                    if (supportsGenerate && name.isNotEmpty()) {
                        list.add(name)
                    }
                }
                return@withContext list.sorted()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            conn.disconnect()
        }
        return@withContext emptyList()
    }
}
