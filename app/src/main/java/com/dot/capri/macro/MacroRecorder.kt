package com.dot.capri.macro

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.dot.capri.agent.PhoneControl

/**
 * Records what the user does while recording is on.
 * Captures initial location (app vs launcher), exact (cx, cy) coordinates, labels, IDs, and delays.
 * Ignores events from Capri itself or keyboards.
 */
object MacroRecorder {
    @Volatile
    var isRecording = false
        private set

    private val lock = Any()
    private var keyword = ""
    private val steps = mutableListOf<MacroStep>()
    private var lastPkg = ""
    private var lastScrollY = -1
    private var lastTimestamp = 0L

    fun start(name: String, context: Context? = null) = synchronized(lock) {
        keyword = MacroStore.normalize(name)
        steps.clear()
        lastPkg = ""
        lastScrollY = -1
        lastTimestamp = System.currentTimeMillis()
        isRecording = true

        // Capture starting location
        val root = PhoneControl.foregroundRoot()
        val startPkg = root?.packageName?.toString().orEmpty()
        if (startPkg.isNotEmpty() && context != null && isLauncherPackage(context, startPkg)) {
            steps.add(MacroStep("home", delayMs = 300))
        } else if (startPkg.isNotEmpty() && context != null && startPkg != context.packageName) {
            lastPkg = startPkg
            steps.add(MacroStep("open", pkg = startPkg, delayMs = 300))
        }
    }

    fun stop(context: Context): String = synchronized(lock) {
        if (!isRecording) return@synchronized "i wasn't recording anything, choom."
        isRecording = false
        val clean = steps.fold(mutableListOf<MacroStep>()) { acc, s ->
            if (acc.lastOrNull() != s) acc.add(s)
            acc
        }
        steps.clear()
        if (clean.isEmpty()) return@synchronized "recorded nothing for '$keyword'. try again."
        MacroStore.save(context, Macro(keyword, clean))
        val warn = if (clean.any { it.type == "type" }) {
            " typed text is saved as-is, so don't record passwords."
        } else ""
        "saved '$keyword' with ${clean.size} steps. say '$keyword' to run it.$warn"
    }

    private fun isLauncherPackage(context: Context, pkg: String): Boolean {
        if (pkg.contains("launcher") || pkg.contains("home") || pkg == "com.google.android.apps.nexuslauncher") return true
        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homeInfo = pm.resolveActivity(homeIntent, 0)
        return homeInfo?.activityInfo?.packageName == pkg
    }

    private fun isIgnoredPackage(context: Context, pkg: String): Boolean {
        if (pkg == context.packageName) return true
        val lower = pkg.lowercase()
        return lower.contains("inputmethod") || lower.contains("gboard") ||
                lower.contains("keyboard") || lower.contains("latin")
    }

    fun onEvent(context: Context, event: AccessibilityEvent?) {
        if (event == null || !isRecording) return
        val pkg = event.packageName?.toString() ?: return
        if (isIgnoredPackage(context, pkg)) return

        val now = System.currentTimeMillis()
        val elapsed = (now - lastTimestamp).coerceIn(200L, 3000L)
        lastTimestamp = now

        synchronized(lock) {
            if (!isRecording) return
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    if (pkg != lastPkg) {
                        lastPkg = pkg
                        if (isLauncherPackage(context, pkg)) {
                            steps.add(MacroStep("home", delayMs = elapsed))
                        } else {
                            steps.add(MacroStep("open", pkg = pkg, delayMs = elapsed))
                        }
                    }
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val (label, resId, cx, cy) = nodeDetailsOf(event)
                    steps.add(MacroStep("tap", pkg = pkg, label = label, resId = resId, cx = cx, cy = cy, delayMs = elapsed))
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    if (event.isPassword) return
                    val text = event.text.joinToString("")
                    if (text.isEmpty()) return
                    val (label, resId, cx, cy) = nodeDetailsOf(event)
                    val last = steps.lastOrNull()
                    if (last != null && last.type == "type" && (last.label == label || last.resId == resId)) {
                        steps[steps.size - 1] = last.copy(text = text)
                    } else {
                        steps.add(MacroStep("type", pkg = pkg, label = label, resId = resId, cx = cx, cy = cy, text = text, delayMs = elapsed))
                    }
                }
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    val y = event.scrollY
                    if (lastScrollY >= 0 && y != lastScrollY) {
                        steps.add(MacroStep("scroll", pkg = pkg, dir = if (y > lastScrollY) "down" else "up", delayMs = elapsed))
                    }
                    lastScrollY = y
                }
            }
        }
    }

    private class Details(val label: String, val resId: String, val cx: Int, val cy: Int) {
        operator fun component1() = label
        operator fun component2() = resId
        operator fun component3() = cx
        operator fun component4() = cy
    }

    private fun nodeDetailsOf(event: AccessibilityEvent): Details {
        val node = event.source
        val resId = node?.viewIdResourceName?.substringAfter(":id/", "").orEmpty()
        val rect = Rect()
        node?.getBoundsInScreen(rect)
        val cx = rect.centerX()
        val cy = rect.centerY()

        if (node != null) {
            val ownText = node.text?.toString()?.trim().orEmpty()
            if (ownText.isNotEmpty()) return Details(ownText.take(60), resId, cx, cy)

            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (desc.isNotEmpty()) return Details(desc.take(60), resId, cx, cy)

            fun firstText(n: AccessibilityNodeInfo?, depth: Int = 0): String {
                if (n == null || depth > 3) return ""
                val t = n.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) return t
                val d = n.contentDescription?.toString()?.trim().orEmpty()
                if (d.isNotEmpty()) return d
                for (i in 0 until n.childCount) {
                    val childText = firstText(n.getChild(i), depth + 1)
                    if (childText.isNotEmpty()) return childText
                }
                return ""
            }
            val childT = firstText(node)
            if (childT.isNotEmpty()) return Details(childT.take(60), resId, cx, cy)
        }

        val fallbackText = event.text.firstOrNull()?.toString()?.trim().orEmpty()
        return Details(fallbackText.take(60), resId, cx, cy)
    }
}
