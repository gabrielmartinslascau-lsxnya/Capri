package com.dot.capri.accessibility

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.dot.capri.CapriAccessibilityService

sealed interface WriteResult {
    object Success : WriteResult
    object NoField : WriteResult
    object OwnChatFocused : WriteResult
    data class Failed(val attemptedSteps: List<String>) : WriteResult
}

object ScreenWriter {

    private const val TAG = "CAPRI_WRITE"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun writeToFocusedField(text: String): WriteResult {
        return performLadder(text)
    }

    fun clearFocusedField(): WriteResult {
        return performLadder("")
    }

    private fun performLadder(text: String): WriteResult {
        val service = CapriAccessibilityService.instance
        if (service == null) {
            Log.i(TAG, "timestamp=${System.currentTimeMillis()}, step=NONE, length=${text.length}, result=NO_SERVICE")
            return WriteResult.NoField
        }

        val root = service.rootInActiveWindow
        if (root == null) {
            Log.i(TAG, "timestamp=${System.currentTimeMillis()}, step=NONE, length=${text.length}, result=NO_ROOT")
            return WriteResult.NoField
        }

        val focusedNode = findFocusedEditableNode(root) ?: findDeepestEditableNode(root)
        if (focusedNode == null) {
            Log.i(TAG, "timestamp=${System.currentTimeMillis()}, step=NONE, length=${text.length}, result=NO_FIELD")
            return WriteResult.NoField
        }

        val pkgName = focusedNode.packageName?.toString() ?: ""
        if (pkgName == "com.dot.capri") {
            Log.i(TAG, "timestamp=${System.currentTimeMillis()}, fieldClass=${focusedNode.className}, length=${text.length}, result=OWN_CHAT_FOCUSED")
            return WriteResult.OwnChatFocused
        }

        val attemptedSteps = mutableListOf<String>()

        // Step 1: ACTION_SET_TEXT on focused node
        attemptedSteps.add("Step1_DirectSetText")
        val setSuccess = performSetTextAction(focusedNode, text)
        if (setSuccess) {
            Log.i(TAG, "timestamp=${System.currentTimeMillis()}, step=Step1_DirectSetText, length=${text.length}, result=SUCCESS")
            return WriteResult.Success
        }
        Log.i(TAG, "timestamp=${System.currentTimeMillis()}, step=Step1_DirectSetText, length=${text.length}, result=FAILED")

        // Step 2: find deepest node with ACTION_SET_TEXT in actionList -> ACTION_FOCUS, wait 150ms, ACTION_SET_TEXT
        attemptedSteps.add("Step2_DeepestNodeFocusAndSet")
        val deepestNode = findDeepestNodeWithSetText(root)
        if (deepestNode != null) {
            deepestNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            try { Thread.sleep(150) } catch (_: InterruptedException) {}
            val step2Success = performSetTextAction(deepestNode, text)
            if (step2Success) {
                Log.i(TAG, "timestamp=${System.currentTimeMillis()}, step=Step2_DeepestNodeFocusAndSet, length=${text.length}, result=SUCCESS")
                return WriteResult.Success
            }
        }
        Log.i(TAG, "timestamp=${System.currentTimeMillis()}, step=Step2_DeepestNodeFocusAndSet, length=${text.length}, result=FAILED")

        // Step 3: ClipboardManager fallback -> ACTION_PASTE; clear clipboard after 60s
        attemptedSteps.add("Step3_ClipboardPaste")
        val pasteNode: AccessibilityNodeInfo? = focusedNode ?: deepestNode
        if (pasteNode != null) {
            setClipboardText(service, text)
            val pasteSuccess = pasteNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            scheduleClipboardClear(service)
            if (pasteSuccess) {
                Log.i(TAG, "timestamp=${System.currentTimeMillis()}, step=Step3_ClipboardPaste, length=${text.length}, result=SUCCESS")
                return WriteResult.Success
            }
        }
        Log.i(TAG, "timestamp=${System.currentTimeMillis()}, step=Step3_ClipboardPaste, length=${text.length}, result=FAILED")

        // Step 4: dispatchGesture tap node bounds center, wait 300ms, clipboard + ACTION_PASTE again
        attemptedSteps.add("Step4_GestureTapAndPaste")
        val tapTargetNode: AccessibilityNodeInfo? = focusedNode ?: deepestNode
        if (tapTargetNode != null) {
            var gestureDone = false
            service.tapNode(tapTargetNode) { _ ->
                gestureDone = true
            }
            val startTime = System.currentTimeMillis()
            while (!gestureDone && (System.currentTimeMillis() - startTime < 1000)) {
                try { Thread.sleep(50) } catch (_: InterruptedException) {}
            }
            try { Thread.sleep(300) } catch (_: InterruptedException) {}

            setClipboardText(service, text)
            val step4Success = tapTargetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            scheduleClipboardClear(service)
            if (step4Success) {
                Log.i(TAG, "timestamp=${System.currentTimeMillis()}, step=Step4_GestureTapAndPaste, length=${text.length}, result=SUCCESS")
                return WriteResult.Success
            }
        }
        Log.i(TAG, "timestamp=${System.currentTimeMillis()}, step=Step4_GestureTapAndPaste, length=${text.length}, result=FAILED")

        // Step 5: Failed(list of attempted steps)
        Log.i(TAG, "timestamp=${System.currentTimeMillis()}, step=ALL_FAILED, length=${text.length}, result=FAILED_LADDER")
        return WriteResult.Failed(attemptedSteps)
    }

    private fun performSetTextAction(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        var success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (!success) {
            success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return success
    }

    private var savedClip: ClipData? = null
    private var hasSavedClip = false

    private fun setClipboardText(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        if (!hasSavedClip) {
            savedClip = try { clipboard.primaryClip } catch (e: Exception) { null }
            hasSavedClip = true
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("Capri", text))
    }

    private fun scheduleClipboardClear(context: Context, delayMs: Long = 60_000L) {
        mainHandler.postDelayed({
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val previous = savedClip
                if (previous != null) {
                    clipboard?.setPrimaryClip(previous)
                } else {
                    clipboard?.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore clipboard", e)
            }
            savedClip = null
            hasSavedClip = false
        }, delayMs)
    }

    private fun findFocusedEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val inputFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (inputFocused != null && isEditableNode(inputFocused)) {
            return inputFocused
        }
        return searchTreeForFocusedEditable(root)
    }

    private fun isEditableNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return false
        if (!node.isFocused) return false
        val className = node.className?.toString() ?: ""
        return node.isEditable || className.contains("EditText", ignoreCase = true) || node.inputType != 0
    }

    private fun searchTreeForFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isEditableNode(node)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchTreeForFocusedEditable(child)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun findDeepestEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var deepest: AccessibilityNodeInfo? = null
        if (node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
            deepest = node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childEditable = findDeepestEditableNode(child)
            if (childEditable != null) {
                deepest = childEditable
            }
        }
        return deepest
    }

    private fun findDeepestNodeWithSetText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var deepest: AccessibilityNodeInfo? = null
        val supportsSetText = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        if (supportsSetText) {
            deepest = node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childMatch = findDeepestNodeWithSetText(child)
            if (childMatch != null) {
                deepest = childMatch
            }
        }
        return deepest
    }
}
