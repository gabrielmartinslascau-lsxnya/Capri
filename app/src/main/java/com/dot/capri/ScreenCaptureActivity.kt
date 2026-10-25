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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class ScreenCaptureActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_CAPTURE = 1001
        var pendingCallback: ((ByteArray?) -> Unit)? = null

        fun requestScreenshot(context: Context, callback: (ByteArray?) -> Unit) {
            pendingCallback = callback
            val intent = Intent(context, ScreenCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
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
        if (requestCode == REQUEST_CODE_CAPTURE && resultCode == Activity.RESULT_OK && data != null) {
            try {
                val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val mediaProjection = projectionManager.getMediaProjection(resultCode, data)
                if (mediaProjection != null) {
                    captureImage(mediaProjection)
                } else {
                    pendingCallback?.invoke(null)
                    pendingCallback = null
                    finish()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                pendingCallback?.invoke(null)
                pendingCallback = null
                finish()
            }
        } else {
            pendingCallback?.invoke(null)
            pendingCallback = null
            finish()
        }
    }

    private fun captureImage(mediaProjection: MediaProjection) {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
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
            delay(400)
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
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                jpegBytes = stream.toByteArray()
            }

            withContext(Dispatchers.Main) {
                pendingCallback?.invoke(jpegBytes)
                pendingCallback = null
                finish()
            }
        }
    }
}
