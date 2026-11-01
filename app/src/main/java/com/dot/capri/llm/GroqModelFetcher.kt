package com.dot.capri.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

object GroqModelFetcher {

    private const val TAG = "CAPRI_GROQ"

    suspend fun fetchModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()
        var conn: HttpURLConnection? = null
        try {
            val url = URL("https://api.groq.com/openai/v1/models")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $apiKey")
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            if (conn.responseCode in 200..299) {
                val jsonStr = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(jsonStr)
                val dataArr = json.optJSONArray("data") ?: return@withContext emptyList()
                val list = mutableListOf<String>()
                for (i in 0 until dataArr.length()) {
                    val m = dataArr.getJSONObject(i)
                    val id = m.optString("id")
                    if (id.isNotEmpty()) {
                        list.add(id)
                    }
                }
                return@withContext list.sorted()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch Groq models", e)
        } finally {
            conn?.disconnect()
        }
        emptyList()
    }
}
