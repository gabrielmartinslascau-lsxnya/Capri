package com.dot.capri

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

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
        // Not needed for active screen capture or typing on demand
    }

    override fun onInterrupt() {
        // Required override
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun typeTextIntoFocusedField(text: String): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun captureScreenBitmap(): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }
        return suspendCancellableCoroutine { continuation ->
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hwBuffer = screenshot.hardwareBuffer
                            val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                            val mutableBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBuffer.close()
                            if (mutableBitmap != null) {
                                val stream = ByteArrayOutputStream()
                                mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
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
            } catch (e: Exception) {
                e.printStackTrace()
                continuation.resume(null)
            }
        }
    }
}
