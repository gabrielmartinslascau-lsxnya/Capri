package com.dot.capri

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
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
        com.dot.capri.macro.MacroRecorder.onEvent(this, event)
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

    fun tapNode(node: AccessibilityNodeInfo, onComplete: ((Boolean) -> Unit)? = null) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()
        if (x <= 0 || y <= 0) {
            onComplete?.invoke(false)
            return
        }
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onComplete?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                onComplete?.invoke(false)
            }
        }, null)
    }

    fun dumpScreenTextTree(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        val pkg = root.packageName?.toString() ?: ""
        if (pkg.isNotEmpty()) {
            sb.append("Foreground Package: $pkg\n")
        }
        traverseNodeTree(root, sb)
        return sb.toString().trim()
    }

    private fun traverseNodeTree(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        val text = node.text?.toString()?.trim()
        val contentDesc = node.contentDescription?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            sb.append("- $text\n")
        } else if (!contentDesc.isNullOrEmpty()) {
            sb.append("- [desc]: $contentDesc\n")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNodeTree(child, sb)
        }
    }
}
