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
    private const val REPO_BASE = "https://huggingface.co/ShowUI/ShowUI-2B/resolve/main/"
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
                    val fileUrl = URL(REPO_BASE + fileName)

                    withContext(Dispatchers.Main) {
                        onProgress?.invoke(((index * 100) / totalFiles), "Downloading $fileName (${index + 1}/$totalFiles)...")
                    }

                    var conn: HttpURLConnection? = null
                    try {
                        val c = (fileUrl.openConnection() as HttpURLConnection).apply {
                            instanceFollowRedirects = true
                            connectTimeout = 15_000
                            readTimeout = 60_000
                            setRequestProperty("User-Agent", "Capri-Android-App")
                        }
                        conn = c
                        val code = c.responseCode
                        if (code !in 200..299) {
                            tempFile.delete()
                            finish(false, "Download failed for $fileName (HTTP $code).")
                            return@launch
                        }

                        val fileTotal = c.contentLengthLong
                        var done = 0L
                        c.inputStream.use { input ->
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
                            finish(false, "File $fileName was empty.")
                            return@launch
                        }
                        if (destFile.exists()) destFile.delete()
                        if (!tempFile.renameTo(destFile)) {
                            finish(false, "Failed to save $fileName.")
                            return@launch
                        }
                    } finally {
                        conn?.disconnect()
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
