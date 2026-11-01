package com.dot.capri.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object FastModelClient {

    private const val TAG = "CAPRI_FAST_MODEL"
    private val REPO_BASES = listOf(
        "https://huggingface.co/ShowUI/ShowUI-2B/resolve/main/",
        "https://huggingface.co/showlab/ShowUI-2B/resolve/main/",
        "https://huggingface.co/ShowUI/showui-2b/resolve/main/",
        "https://huggingface.co/OpenGVLab/ShowUI-2B/resolve/main/",
        "https://huggingface.co/showui-2b/resolve/main/"
    )
    private val TARGET_FILES = listOf("model.safetensors", "config.json", "preprocessor_config.json")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun modelDirectory(context: Context): File {
        return File(context.filesDir, "ShowUI-2B")
    }

    fun isAvailable(context: Context): Boolean {
        val dir = modelDirectory(context)
        return TARGET_FILES.all { File(dir, it).exists() && File(dir, it).length() > 0 }
    }

    fun statusLine(context: Context): String {
        val dir = modelDirectory(context)
        return if (isAvailable(context)) {
            "Fast Model (ShowUI-2B) ready in app storage."
        } else {
            "Fast Model not downloaded yet. Tap Download Fast Model."
        }
    }

    fun downloadFastModel(
        context: Context,
        onProgress: ((Int, String) -> Unit)? = null,
        onComplete: (Boolean, String) -> Unit
    ) {
        val dir = modelDirectory(context)
        dir.mkdirs()

        scope.launch(Dispatchers.IO) {
            suspend fun finish(ok: Boolean, msg: String) =
                withContext(Dispatchers.Main) { onComplete(ok, msg) }

            try {
                val totalFiles = TARGET_FILES.size
                for ((index, fileName) in TARGET_FILES.withIndex()) {
                    val destFile = File(dir, fileName)
                    val tempFile = File(dir, "$fileName.tmp")

                    withContext(Dispatchers.Main) {
                        onProgress?.invoke(((index * 100) / totalFiles), "Downloading $fileName (${index + 1}/$totalFiles)...")
                    }

                    var success = false
                    var lastHttpCode = -1

                    for (base in REPO_BASES) {
                        var url = URL(base + fileName)
                        var response: HttpURLConnection? = null
                        var hops = 0
                        var failed = false

                        while (response == null && !failed) {
                            try {
                            val hfToken = context.getSharedPreferences("capri_prefs", Context.MODE_PRIVATE).getString("user_hf_token", "")?.trim() ?: ""
                            val c = (url.openConnection() as HttpURLConnection).apply {
                                instanceFollowRedirects = false
                                connectTimeout = 15_000
                                readTimeout = 60_000
                                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                if (hfToken.isNotBlank()) {
                                    setRequestProperty("Authorization", "Bearer $hfToken")
                                }
                            }
                            val code = c.responseCode
                                lastHttpCode = code
                                when (code) {
                                    in 300..399 -> {
                                        val loc = c.getHeaderField("Location")
                                        if (loc == null || ++hops > 6) {
                                            c.disconnect()
                                            failed = true
                                        } else {
                                            url = URL(url, loc)
                                            c.disconnect()
                                        }
                                    }
                                    in 200..299 -> response = c
                                    else -> {
                                        val errBody = try { c.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (_: Exception) { "" }
                                        Log.e(TAG, "HTTP $code for $url: $errBody")
                                        c.disconnect()
                                        failed = true
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Connection exception for $url", e)
                                failed = true
                            }
                        }

                        if (response != null) {
                            val resp = response
                            val fileTotal = resp.contentLengthLong
                            var done = 0L

                            try {
                                resp.inputStream.use { input ->
                                    FileOutputStream(tempFile).use { out ->
                                        val buf = ByteArray(64 * 1024)
                                        while (true) {
                                            val n = input.read(buf)
                                            if (n == -1) break
                                            out.write(buf, 0, n)
                                            done += n
                                            if (fileTotal > 0) {
                                                val filePct = ((done * 100) / fileTotal).toInt()
                                                val overallPct = (((index * 100) + filePct) / totalFiles).coerceIn(0, 99)
                                                withContext(Dispatchers.Main) {
                                                    onProgress?.invoke(overallPct, "Downloading $fileName ($filePct%)...")
                                                }
                                            }
                                        }
                                    }
                                }

                                if (tempFile.length() <= 0) {
                                    tempFile.delete()
                                    continue
                                }
                                if (destFile.exists()) destFile.delete()
                                if (!tempFile.renameTo(destFile)) {
                                    finish(false, "Failed to save $fileName.")
                                    return@launch
                                }
                                success = true
                                break
                            } catch (e: Exception) {
                                Log.e(TAG, "Read/write error for $fileName", e)
                                tempFile.delete()
                            } finally {
                                resp.disconnect()
                            }
                        }
                    }

                    if (!success) {
                        tempFile.delete()
                        finish(false, "Download failed for $fileName (HTTP $lastHttpCode). Check Logcat tag CAPRI_FAST_MODEL.")
                        return@launch
                    }
                }

                finish(true, "Fast Model (ShowUI-2B) downloaded successfully!")
            } catch (e: Exception) {
                Log.e(TAG, "Fast model download failed", e)
                finish(false, "Download failed: ${e.message ?: "network error"}")
            }
        }
    }
}
