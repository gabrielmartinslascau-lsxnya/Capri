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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CapriAccessibilityService : AccessibilityService() {

    companion object {
        var instance: CapriAccessibilityService? = null
            private set

        private const val MIN_SCREENSHOT_INTERVAL_MS = 650L
        private const val REUSE_CACHE_WINDOW_MS = 320L
        private const val FALLBACK_CACHE_MAX_AGE_MS = 3500L
    }

    private val captureMutex = Mutex()
    @Volatile private var lastCaptureTimeMs: Long = 0L
    @Volatile private var lastCapturedBytes: ByteArray? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        com.dot.capri.macro.MacroRecorder.onEvent(this, event)
        com.dot.capri.agent.HumanDemonstrationListener.onAccessibilityEvent(this, event)
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
        return captureMutex.withLock {
            val now = System.currentTimeMillis()
            val sinceLast = now - lastCaptureTimeMs
            val cached = lastCapturedBytes
            if (cached != null && sinceLast in 0 until REUSE_CACHE_WINDOW_MS) {
                return@withLock cached
            }
            if (lastCaptureTimeMs > 0L && sinceLast in 0 until MIN_SCREENSHOT_INTERVAL_MS) {
                kotlinx.coroutines.delay((MIN_SCREENSHOT_INTERVAL_MS - sinceLast).coerceAtMost(MIN_SCREENSHOT_INTERVAL_MS))
            }

            // Hide ALL Capri overlays (STOP pill, cursor, floating bubble, chat, dev grid, and MB rings)
            // so Capri never sees her own UI in screenshots or mistakes her own animations for screen changes.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                com.dot.capri.agent.CapriCursor.setCaptureHidden(true)
                com.dot.capri.BubbleService.setCaptureHidden(true)
                com.dot.capri.agent.DevOverlay.setCaptureHidden(true)
                com.dot.capri.agent.ControlRegions.setCaptureHidden(true)
            }
            kotlinx.coroutines.delay(55)

            val freshBytes: ByteArray? = try {
                suspendCancellableCoroutine { continuation ->
                    try {
                        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                com.dot.capri.agent.CapriCursor.setCaptureHidden(false)
                                com.dot.capri.BubbleService.setCaptureHidden(false)
                                com.dot.capri.agent.DevOverlay.setCaptureHidden(false)
                                com.dot.capri.agent.ControlRegions.setCaptureHidden(false)
                                try {
                                    val hwBuffer = screenshot.hardwareBuffer
                                    val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                                    var mutableBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                    hwBuffer.close()
                                    if (mutableBitmap != null) {
                                        val display = (getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay
                                        val rotation = display?.rotation ?: android.view.Surface.ROTATION_0
                                        if ((rotation == android.view.Surface.ROTATION_90 || rotation == android.view.Surface.ROTATION_270) && mutableBitmap.width < mutableBitmap.height) {
                                            val matrix = android.graphics.Matrix()
                                            matrix.postRotate(if (rotation == android.view.Surface.ROTATION_90) 270f else 90f)
                                            mutableBitmap = Bitmap.createBitmap(mutableBitmap, 0, 0, mutableBitmap.width, mutableBitmap.height, matrix, true)
                                        }

                                        // Use user's exact VisionConfig slider resolution & JPEG quality
                                        val maxDim = com.dot.capri.agent.VisionConfig.getMaxSide(this@CapriAccessibilityService)
                                        val jpegQuality = com.dot.capri.agent.VisionConfig.getJpegQuality(this@CapriAccessibilityService)
                                        val srcW = mutableBitmap.width
                                        val srcH = mutableBitmap.height
                                        val scaledBitmap = if (srcW > maxDim || srcH > maxDim) {
                                            val scale = maxDim.toFloat() / Math.max(srcW, srcH)
                                            val newW = (srcW * scale).toInt().coerceAtLeast(1)
                                            val newH = (srcH * scale).toInt().coerceAtLeast(1)
                                            Bitmap.createScaledBitmap(mutableBitmap, newW, newH, true)
                                        } else {
                                            mutableBitmap
                                        }
                                        val stream = ByteArrayOutputStream()
                                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream)
                                        if (continuation.isActive) continuation.resume(stream.toByteArray())
                                    } else {
                                        if (continuation.isActive) continuation.resume(null)
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    if (continuation.isActive) continuation.resume(null)
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                com.dot.capri.agent.CapriCursor.setCaptureHidden(false)
                                com.dot.capri.BubbleService.setCaptureHidden(false)
                                com.dot.capri.agent.DevOverlay.setCaptureHidden(false)
                                com.dot.capri.agent.ControlRegions.setCaptureHidden(false)
                                if (continuation.isActive) continuation.resume(null)
                            }
                        })
                    } catch (e: Exception) {
                        com.dot.capri.agent.CapriCursor.setCaptureHidden(false)
                        com.dot.capri.BubbleService.setCaptureHidden(false)
                        com.dot.capri.agent.DevOverlay.setCaptureHidden(false)
                        com.dot.capri.agent.ControlRegions.setCaptureHidden(false)
                        e.printStackTrace()
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            } finally {
                com.dot.capri.agent.CapriCursor.setCaptureHidden(false)
                com.dot.capri.BubbleService.setCaptureHidden(false)
                com.dot.capri.agent.DevOverlay.setCaptureHidden(false)
                com.dot.capri.agent.ControlRegions.setCaptureHidden(false)
            }

            val completedAt = System.currentTimeMillis()
            if (freshBytes != null) {
                lastCaptureTimeMs = completedAt
                lastCapturedBytes = freshBytes
                freshBytes
            } else {
                // If rate-limited by Android system, return recent cached frame rather than null
                val fallback = lastCapturedBytes
                if (fallback != null && (completedAt - lastCaptureTimeMs) <= FALLBACK_CACHE_MAX_AGE_MS) {
                    fallback
                } else {
                    null
                }
            }
        }
    }

    private suspend fun takeSingleRawBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { continuation ->
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hwBuffer = screenshot.hardwareBuffer
                            val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                            var mutableBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBuffer.close()
                            if (mutableBitmap != null) {
                                val display = (getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay
                                val rotation = display?.rotation ?: android.view.Surface.ROTATION_0
                                if ((rotation == android.view.Surface.ROTATION_90 || rotation == android.view.Surface.ROTATION_270) && mutableBitmap.width < mutableBitmap.height) {
                                    val matrix = android.graphics.Matrix()
                                    matrix.postRotate(if (rotation == android.view.Surface.ROTATION_90) 270f else 90f)
                                    mutableBitmap = Bitmap.createBitmap(mutableBitmap, 0, 0, mutableBitmap.width, mutableBitmap.height, matrix, true)
                                }
                            }
                            if (continuation.isActive) continuation.resume(mutableBitmap)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    /**
     * Captures a multi-frame burst (e.g. 3 frames over 700ms) and stitches them side-by-side
     * into a single chronological filmstrip JPEG. Allows the multimodal AI to see video movement,
     * Reels progression, and comedic timing without increasing token count or inference latency.
     */
    suspend fun captureBurstFilmstrip(frameCount: Int = 3, intervalMs: Long = 350L): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return captureMutex.withLock {
            val count = frameCount.coerceIn(1, 5)

            // Hide overlays once for the whole burst
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                com.dot.capri.agent.CapriCursor.setCaptureHidden(true)
                com.dot.capri.BubbleService.setCaptureHidden(true)
                com.dot.capri.agent.DevOverlay.setCaptureHidden(true)
                com.dot.capri.agent.ControlRegions.setCaptureHidden(true)
            }
            kotlinx.coroutines.delay(45)

            val capturedFrames = mutableListOf<Bitmap>()
            try {
                for (i in 0 until count) {
                    val frame = takeSingleRawBitmap()
                    if (frame != null) {
                        capturedFrames.add(frame)
                    }
                    if (i < count - 1) {
                        kotlinx.coroutines.delay(intervalMs)
                    }
                }
            } finally {
                // Re-enable overlays when burst completes
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    com.dot.capri.agent.CapriCursor.setCaptureHidden(false)
                    com.dot.capri.BubbleService.setCaptureHidden(false)
                    com.dot.capri.agent.DevOverlay.setCaptureHidden(false)
                    com.dot.capri.agent.ControlRegions.setCaptureHidden(false)
                }
            }

            if (capturedFrames.isEmpty()) return@withLock null

            val maxDim = com.dot.capri.agent.VisionConfig.getMaxSide(this@CapriAccessibilityService)
            val jpegQuality = com.dot.capri.agent.VisionConfig.getJpegQuality(this@CapriAccessibilityService)

            if (capturedFrames.size == 1) {
                val single = capturedFrames.first()
                val scale = minOf(1f, maxDim.toFloat() / maxOf(single.width, single.height))
                val targetW = (single.width * scale).toInt().coerceAtLeast(1)
                val targetH = (single.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(single, targetW, targetH, true)
                val stream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream)
                return@withLock stream.toByteArray()
            }

            // Stitch frames horizontally into a balanced filmstrip
            val n = capturedFrames.size
            val first = capturedFrames.first()
            val srcW = first.width
            val srcH = first.height

            // Calculate height such that total stitched width fits comfortably within maxDim
            val idealH = ((maxDim.toFloat() * srcH) / (n * srcW)).toInt().coerceIn(160, 480)
            val frameW = ((idealH.toFloat() * srcW) / srcH).toInt().coerceAtLeast(1)
            val frameH = idealH
            val dividerW = 3
            val totalW = (frameW * n) + (dividerW * (n - 1))
            val totalH = frameH

            val filmstrip = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(filmstrip)
            canvas.drawColor(android.graphics.Color.BLACK)

            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = (frameH * 0.08f).coerceIn(10f, 18f)
                setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
            }

            capturedFrames.forEachIndexed { index, bmp ->
                val scaled = Bitmap.createScaledBitmap(bmp, frameW, frameH, true)
                val left = index * (frameW + dividerW)
                canvas.drawBitmap(scaled, left.toFloat(), 0f, paint)

                // Sequence badge (e.g. "1/3", "2/3", "3/3")
                val label = "${index + 1}/$n"
                canvas.drawText(label, left + 8f, frameH - 10f, textPaint)
            }

            val stream = ByteArrayOutputStream()
            filmstrip.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream)
            val resultBytes = stream.toByteArray()

            lastCaptureTimeMs = System.currentTimeMillis()
            lastCapturedBytes = resultBytes
            resultBytes
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
