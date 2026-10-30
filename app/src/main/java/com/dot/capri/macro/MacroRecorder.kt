package com.dot.capri.macro

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Records what the user does in other apps while recording is on.
 * Uses event.source node inspection for exact child labels and resource IDs.
 * Ignores events from Capri itself, Gboard/keyboards, and home launchers.
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

    fun start(name: String) = synchronized(lock) {
        keyword = MacroStore.normalize(name)
        steps.clear()
        lastPkg = ""
        lastScrollY = -1
        isRecording = true
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

    private fun isIgnoredPackage(context: Context, pkg: String): Boolean {
        if (pkg == context.packageName) return true
        val lower = pkg.lowercase()
        if (lower.contains("inputmethod") || lower.contains("gboard") ||
            lower.contains("keyboard") || lower.contains("latin")) return true
        if (lower.contains("launcher") || lower.contains("home") || lower == "com.google.android.apps.nexuslauncher") return true
        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homeInfo = pm.resolveActivity(homeIntent, 0)
        return homeInfo?.activityInfo?.packageName == pkg
    }

    fun onEvent(context: Context, event: AccessibilityEvent?) {
        if (event == null || !isRecording) return
        val pkg = event.packageName?.toString() ?: return
        if (isIgnoredPackage(context, pkg)) return

        synchronized(lock) {
            if (!isRecording) return
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    if (pkg != lastPkg) {
                        lastPkg = pkg
                        steps.add(MacroStep("open", pkg = pkg))
                    }
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val (label, resId) = labelAndResIdOf(event)
                    if (label.isNotEmpty() || resId.isNotEmpty()) {
                        steps.add(MacroStep("tap", pkg = pkg, label = label, resId = resId))
                    }
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    if (event.isPassword) return
                    val text = event.text.joinToString("")
                    if (text.isEmpty()) return
                    val (label, resId) = labelAndResIdOf(event)
                    val last = steps.lastOrNull()
                    if (last != null && last.type == "type" && (last.label == label || last.resId == resId)) {
                        steps[steps.size - 1] = last.copy(text = text)
                    } else {
                        steps.add(MacroStep("type", pkg = pkg, label = label, resId = resId, text = text))
                    }
                }
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    val y = event.scrollY
                    if (lastScrollY >= 0 && y != lastScrollY) {
                        steps.add(MacroStep("scroll", pkg = pkg, dir = if (y > lastScrollY) "down" else "up"))
                    }
                    lastScrollY = y
                }
            }
        }
    }

    private fun labelAndResIdOf(event: AccessibilityEvent): Pair<String, String> {
        val node = event.source
        val resId = node?.viewIdResourceName?.substringAfter(":id/", "").orEmpty()

        if (node != null) {
            val ownText = node.text?.toString()?.trim().orEmpty()
            if (ownText.isNotEmpty()) return ownText.take(60) to resId

            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (desc.isNotEmpty()) return desc.take(60) to resId

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
            if (childT.isNotEmpty()) return childT.take(60) to resId
        }

        val fallbackText = event.text.firstOrNull()?.toString()?.trim().orEmpty()
        return fallbackText.take(60) to resId
    }
}
