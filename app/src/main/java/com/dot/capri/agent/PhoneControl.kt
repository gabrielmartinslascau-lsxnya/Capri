package com.dot.capri.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.dot.capri.CapriAccessibilityService
import com.dot.capri.MainActivity
import com.dot.capri.intents.ScrollDirection
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Everything Capri can do to the screen: look at it, tap, swipe, scroll, type, press send. */
object PhoneControl {

    class UiItem(
        val id: Int,
        val label: String,
        val kind: String,   // input, button, toggle:on, toggle:off, scroll, text
        val cx: Int,
        val cy: Int,
        val node: AccessibilityNodeInfo
    )

    private fun service(): CapriAccessibilityService? = CapriAccessibilityService.instance

    /** The app the user is looking at. Never returns one of Capri's own windows. */
    fun foregroundRoot(): AccessibilityNodeInfo? {
        val s = service() ?: return null
        val own = s.packageName
        val fromWindows = s.windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
            .firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName?.toString() != own } }
        return fromWindows ?: s.rootInActiveWindow?.takeIf { it.packageName?.toString() != own }
    }

    /** A numbered list of things on screen she can act on. Password fields are never included. */
    fun snapshot(root: AccessibilityNodeInfo, max: Int = 60): List<UiItem> {
        val out = ArrayList<UiItem>()
        val seen = HashSet<String>()
        val r = Rect()

        fun firstText(n: AccessibilityNodeInfo?, depth: Int = 0): String {
            if (n == null || depth > 4) return ""
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            for (i in 0 until n.childCount) {
                val t = firstText(n.getChild(i), depth + 1)
                if (t.isNotEmpty()) return t
            }
            return ""
        }

        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || out.size >= max || depth > 40) return
            if (n.isVisibleToUser && !n.isPassword) {
                val actionable = n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
                val own = n.text?.toString()?.trim().orEmpty()
                if (actionable || own.isNotEmpty()) {
                    n.getBoundsInScreen(r)
                    if (r.width() > 0 && r.height() > 0) {
                        val idName = n.viewIdResourceName?.substringAfter(":id/", "").orEmpty()
                        val label = own.ifEmpty { firstText(n) }.ifEmpty { idName }.ifEmpty { "(no label)" }.take(60)
                        val kind = when {
                            n.isEditable -> "input"
                            n.isCheckable -> if (n.isChecked) "toggle:on" else "toggle:off"
                            n.isClickable -> "button"
                            n.isScrollable -> "scroll"
                            else -> "text"
                        }
                        val key = "$label|${r.centerX()}|${r.centerY()}"
                        if (seen.add(key)) {
                            out.add(UiItem(out.size + 1, label, kind, r.centerX(), r.centerY(), n))
                        }
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }

        walk(root, 0)
        return out
    }

    private suspend fun dispatch(path: Path, ms: Long): Boolean {
        val s = service() ?: return false
        return suspendCancellableCoroutine { cont ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
                .build()
            val accepted = s.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!accepted && cont.isActive) cont.resume(false)
        }
    }

    suspend fun tap(item: UiItem): Boolean {
        CapriCursor.moveTo(item.cx, item.cy)
        CapriCursor.pulse()
        item.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (item.node.isClickable && item.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return dispatch(Path().apply { moveTo(item.cx.toFloat(), item.cy.toFloat()) }, 60)
    }

    suspend fun scroll(direction: ScrollDirection): Boolean {
        val s = service() ?: return false
        val w = s.resources.displayMetrics.widthPixels
        val h = s.resources.displayMetrics.heightPixels
        val cx = w / 2f
        val cy = h / 2f

        val (fromX, fromY, toX, toY) = when (direction) {
            ScrollDirection.DOWN -> listOf(cx, h * 0.70f, cx, h * 0.30f)
            ScrollDirection.UP -> listOf(cx, h * 0.30f, cx, h * 0.70f)
            ScrollDirection.LEFT -> listOf(w * 0.80f, cy, w * 0.20f, cy)
            ScrollDirection.RIGHT -> listOf(w * 0.20f, cy, w * 0.80f, cy)
        }

        CapriCursor.show(s)
        CapriCursor.moveTo(fromX.toInt(), fromY.toInt())
        CapriCursor.pulse()
        delay(150)

        val path = Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }
        var ok = false
        coroutineScope {
            val animJob = launch { CapriCursor.glideAlongPath(fromX, fromY, toX, toY, 350) }
            val gestureJob = async { dispatch(path, 350) }
            ok = gestureJob.await()
            animJob.join()
        }

        delay(250)
        CapriCursor.hide()
        return ok
    }

    suspend fun scroll(down: Boolean): Boolean =
        scroll(if (down) ScrollDirection.DOWN else ScrollDirection.UP)

    /** Puts `text` into a field (replaces what is there) and automatically hits enter/submit if possible. */
    suspend fun typeInto(context: Context, item: UiItem, text: String): Boolean {
        if (!MainActivity.isAllowTyping(context)) return false
        CapriCursor.moveTo(item.cx, item.cy)
        CapriCursor.pulse()
        item.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        item.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(300)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = item.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(300)
        submitTypedText(context, item)
        return ok
    }

    fun back(): Boolean =
        service()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) == true

    fun home(): Boolean =
        service()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) == true

    // ---- submit / send / enter handling ----

    private val SUBMIT_WORDS = setOf(
        "send", "search", "go", "done", "enter", "submit", "enviar", "envoyer", "senden", "invia", "verzenden", "gönder", "отправить", "buscar", "suchen"
    )

    private fun findSubmitButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        fun walk(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (n == null || depth > 40) return null
            if (n.isVisibleToUser && n.isClickable) {
                val id = n.viewIdResourceName.orEmpty().lowercase()
                val desc = n.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
                val text = n.text?.toString()?.trim()?.lowercase().orEmpty()
                if (id.endsWith(":id/send") || id.endsWith(":id/search") || id.endsWith(":id/button_yes") ||
                    desc in SUBMIT_WORDS || text in SUBMIT_WORDS || desc.startsWith("send ") || desc.startsWith("search ")) {
                    return n
                }
            }
            for (i in 0 until n.childCount) {
                walk(n.getChild(i), depth + 1)?.let { return it }
            }
            return null
        }
        return walk(root, 0)
    }

    suspend fun submitTypedText(context: Context, item: UiItem): Boolean {
        item.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val root = foregroundRoot() ?: return false
        val btn = findSubmitButton(root) ?: return false
        val r = Rect()
        btn.getBoundsInScreen(r)
        CapriCursor.moveTo(r.centerX(), r.centerY())
        CapriCursor.pulse()
        return btn.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                dispatch(Path().apply { moveTo(r.centerX().toFloat(), r.centerY().toFloat()) }, 60)
    }

    /** Glides the cursor to the send button and presses it. Returns false if it can't find one. */
    suspend fun pressSend(context: Context): Boolean {
        val root = foregroundRoot() ?: return false
        val button = findSubmitButton(root) ?: return false
        val r = Rect()
        button.getBoundsInScreen(r)
        CapriCursor.show(context)
        delay(200)
        try {
            CapriCursor.moveTo(r.centerX(), r.centerY())
            CapriCursor.pulse()
            val ok = button.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                    dispatch(Path().apply { moveTo(r.centerX().toFloat(), r.centerY().toFloat()) }, 60)
            delay(600)
            return ok
        } finally {
            CapriCursor.hide()
        }
    }
}
