package com.dot.capri.macro

import android.content.Context
import android.content.Intent
import com.dot.capri.CapriAccessibilityService
import com.dot.capri.agent.CapriCursor
import com.dot.capri.agent.PhoneControl
import com.dot.capri.intents.ScrollDirection
import kotlinx.coroutines.delay

/** Replays a saved macro by matching labels/IDs on the current screen. Stops and asks if a step can't be found. */
object MacroRunner {

    suspend fun run(context: Context, macro: Macro, chatVisible: suspend (Boolean) -> Unit): String {
        if (CapriAccessibilityService.instance == null) {
            return "accessibility is off, choom. i can't run '${macro.keyword}' without it."
        }
        chatVisible(false)
        CapriCursor.show(context)
        delay(250)
        try {
            macro.steps.forEachIndexed { i, step ->
                if (CapriCursor.cancelled) return "stopped at step ${i + 1} of ${macro.steps.size}."
                if (!runStep(context, step)) {
                    return "stuck at step ${i + 1} (${describe(step)}). want me to take over, or re-record it?"
                }
                delay(400)
            }
            return "done with '${macro.keyword}', choom."
        } finally {
            CapriCursor.hide()
            chatVisible(true)
        }
    }

    private suspend fun runStep(context: Context, step: MacroStep): Boolean {
        return when (step.type) {
            "open" -> {
                val intent = context.packageManager.getLaunchIntentForPackage(step.pkg) ?: return false
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                delay(1500)
                true
            }
            "scroll" -> PhoneControl.scroll(if (step.dir == "up") ScrollDirection.UP else ScrollDirection.DOWN)
            "tap" -> {
                val root = PhoneControl.foregroundRoot() ?: return false
                val items = PhoneControl.snapshot(root)
                val item = items.firstOrNull { 
                    (step.resId.isNotEmpty() && it.node.viewIdResourceName?.endsWith(":id/${step.resId}") == true) ||
                    (step.label.isNotEmpty() && it.label.contains(step.label, ignoreCase = true))
                } ?: items.firstOrNull { step.label.isNotEmpty() && step.label.contains(it.label, ignoreCase = true) } ?: return false
                PhoneControl.tap(item)
            }
            "type" -> {
                val root = PhoneControl.foregroundRoot() ?: return false
                val items = PhoneControl.snapshot(root)
                val item = items.firstOrNull { 
                    (step.resId.isNotEmpty() && it.node.viewIdResourceName?.endsWith(":id/${step.resId}") == true) ||
                    (step.label.isNotEmpty() && it.label.contains(step.label, ignoreCase = true)) ||
                    it.kind == "input"
                } ?: return false
                PhoneControl.typeInto(context, item, step.text)
            }
            else -> false
        }
    }

    private fun describe(step: MacroStep): String = when (step.type) {
        "open" -> "open ${step.pkg}"
        "tap" -> "tap ${step.label.ifEmpty { step.resId }}"
        "type" -> "type into ${step.label.ifEmpty { step.resId }}"
        "scroll" -> "scroll ${step.dir}"
        else -> step.type
    }
}
