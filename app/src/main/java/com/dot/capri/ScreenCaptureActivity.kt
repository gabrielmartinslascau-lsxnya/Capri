package com.dot.capri

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class ScreenCaptureActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_CAPTURE = 1001
        private var sResultCode: Int = 0
        private var sResultData: Intent? = null
        private var pendingCallback: ((ByteArray?) -> Unit)? = null

        fun requestScreenshot(context: Context, callback: (ByteArray?) -> Unit) {
            if (sResultCode != 0 && sResultData != null) {
                captureSilently(context, sResultCode, sResultData!!, callback)
            } else {
                pendingCallback = callback
                val intent = Intent(context, ScreenCaptureActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(intent)
            }
        }

        private fun captureSilently(context: Context, resultCode: Int, data: Intent, callback: (ByteArray?) -> Unit) {
            try {
                val projectionManager = context.getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val mediaProjection = projectionManager.getMediaProjection(resultCode, data)
                if (mediaProjection != null) {
                    performCapture(context, mediaProjection, callback)
                } else {
                    sResultCode = 0
                    sResultData = null
                    requestScreenshot(context, callback)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                sResultCode = 0
                sResultData = null
                callback(null)
            }
        }

        private fun performCapture(context: Context, mediaProjection: MediaProjection, callback: (ByteArray?) -> Unit) {
            val windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val virtualDisplay = mediaProjection.createVirtualDisplay(
                "CapriScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, null
            )

            CoroutineScope(Dispatchers.IO).launch {
                delay(500)
                var bitmap: Bitmap? = null
                try {
                    val image = imageReader.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width

                        bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(buffer)
                        image.close()

                        if (bitmap.width != width || bitmap.height != height) {
                            bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                try {
                    virtualDisplay?.release()
                    mediaProjection.stop()
                    imageReader.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                var jpegBytes: ByteArray? = null
                if (bitmap != null) {
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                    jpegBytes = stream.toByteArray()
                }

                withContext(Dispatchers.Main) {
                    callback(jpegBytes)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            Toast.makeText(this, "Grant screen capture permission for Capri...", Toast.LENGTH_SHORT).show()
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_CAPTURE)
        } catch (e: Exception) {
            e.printStackTrace()
            pendingCallback?.invoke(null)
            pendingCallback = null
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_CAPTURE && resultCode == RESULT_OK && data != null) {
            sResultCode = resultCode
            sResultData = data
            val cb = pendingCallback
            pendingCallback = null
            finish()
            if (cb != null) {
                captureSilently(this, sResultCode, sResultData!!, cb)
            }
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            pendingCallback?.invoke(null)
            pendingCallback = null
            finish()
        }
    }
}
