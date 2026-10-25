package com.dot.capri

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class CapriAccessibilityService : AccessibilityService() {

    companion object {
        var instance: CapriAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for active screen capture on demand
    }

    override fun onInterrupt() {
        // Required override
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    suspend fun captureScreenBitmap(): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        return suspendCancellableCoroutine { continuation ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    try {
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        val bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, true)
                        screenshot.hardwareBuffer.close()
                        if (bitmap != null) {
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                            continuation.resume(stream.toByteArray())
                        } else {
                            continuation.resume(null)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        continuation.resume(null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    continuation.resume(null)
                }
            })
        }
    }
}
