package com.dot.capri.macro

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Records what the user does in other apps while recording is on.
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
                    val label = labelOf(event)
                    if (label.isNotEmpty()) steps.add(MacroStep("tap", pkg = pkg, label = label))
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    if (event.isPassword) return
                    val text = event.text.joinToString("")
                    if (text.isEmpty()) return
                    val label = labelOf(event)
                    val last = steps.lastOrNull()
                    if (last != null && last.type == "type" && last.label == label) {
                        steps[steps.size - 1] = last.copy(text = text)
                    } else {
                        steps.add(MacroStep("type", pkg = pkg, label = label, text = text))
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

    private fun labelOf(event: AccessibilityEvent): String {
        val fromText = event.text.joinToString(" ") { it.toString() }.trim()
        if (fromText.isNotEmpty()) return fromText.take(60)
        return event.contentDescription?.toString()?.trim()?.take(60).orEmpty()
    }
}
