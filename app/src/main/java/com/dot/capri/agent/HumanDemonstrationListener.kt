package com.dot.capri.agent

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CompletableDeferred

/**
 * Captures a single user touch or scroll demonstration in real-time when Capri asks for guidance.
 * Once captured, the action is immediately engraved into AgentMemory / MoveMemory as a verified
 * GREENLIGHT move, and Capri takes back control without bouncing the chat window.
 */
object HumanDemonstrationListener {

    private const val TAG = "CAPRI_DEMO_LISTENER"

    data class DemonstratedAction(
        val type: String, // "tap" or "scroll"
        val pkg: String,
        val label: String?,
        val resId: String?,
        val cx: Int,
        val cy: Int,
        val normX: Float,
        val normY: Float,
        val scrollDir: String? = null
    )

    @Volatile
    var isListening: Boolean = false
        private set

    private var targetPkg: String? = null
    private var targetGoal: String? = null
    private var targetStateKey: String? = null
    private var deferredResult: CompletableDeferred<DemonstratedAction?>? = null

    @Synchronized
    fun startListening(
        pkg: String,
        goal: String,
        stateKey: String
    ): CompletableDeferred<DemonstratedAction?> {
        targetPkg = pkg
        targetGoal = goal
        targetStateKey = stateKey
        val deferred = CompletableDeferred<DemonstratedAction?>()
        deferredResult = deferred
        isListening = true
        Log.d(TAG, "Started listening for user demonstration on $pkg for goal \"$goal\"")
        return deferred
    }

    @Synchronized
    fun stopListening() {
        if (!isListening) return
        isListening = false
        targetPkg = null
        targetGoal = null
        targetStateKey = null
        deferredResult?.cancel()
        deferredResult = null
        Log.d(TAG, "Stopped listening for user demonstration")
    }

    /**
     * Called by CapriAccessibilityService.onAccessibilityEvent(event)
     */
    fun onAccessibilityEvent(context: Context, event: AccessibilityEvent?) {
        if (!isListening || event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == context.packageName) return // Ignore touches on Capri's own overlays

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source
                val rect = Rect()
                node?.getBoundsInScreen(rect)
                val cx = rect.centerX()
                val cy = rect.centerY()
                val (sw, sh) = PhoneControl.screenSize() ?: Pair(1080, 2400)
                val normX = (cx.toFloat() / sw.coerceAtLeast(1)).coerceIn(0f, 1f)
                val normY = (cy.toFloat() / sh.coerceAtLeast(1)).coerceIn(0f, 1f)

                val label = node?.text?.toString()?.trim()
                    ?.ifBlank { node.contentDescription?.toString()?.trim() }
                    ?.ifBlank { null }
                val resId = node?.viewIdResourceName?.substringAfter(":id/", "")?.ifBlank { null }

                val action = DemonstratedAction(
                    type = "tap",
                    pkg = pkg,
                    label = label,
                    resId = resId,
                    cx = cx,
                    cy = cy,
                    normX = normX,
                    normY = normY
                )
                completeWith(context, action)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val dir = if (event.scrollY > 0) "down" else "up"
                val (sw, sh) = PhoneControl.screenSize() ?: Pair(1080, 2400)
                val action = DemonstratedAction(
                    type = "scroll",
                    pkg = pkg,
                    label = null,
                    resId = null,
                    cx = sw / 2,
                    cy = sh / 2,
                    normX = 0.5f,
                    normY = 0.5f,
                    scrollDir = dir
                )
                completeWith(context, action)
            }
        }
    }

    @Synchronized
    private fun completeWith(context: Context, action: DemonstratedAction) {
        if (!isListening) return
        isListening = false

        val stateKey = targetStateKey
        val goal = targetGoal
        val pkg = targetPkg ?: action.pkg

        Log.d(TAG, "Captured user demonstration: ${action.type} in $pkg (label=${action.label}, resId=${action.resId}, coords=(${action.cx},${action.cy}))")

        // Engrave directly into AgentMemory and MoveMemory as a verified GREENLIGHT move!
        if (stateKey != null && goal != null) {
            val move = when (action.type) {
                "scroll" -> MoveMemory.Move(
                    kind = MoveMemory.Kind.SCROLL,
                    text = action.scrollDir ?: "down"
                )
                else -> MoveMemory.Move(
                    kind = if (action.label != null) MoveMemory.Kind.TAP_TEXT else MoveMemory.Kind.TAP,
                    nx = action.normX,
                    ny = action.normY,
                    text = action.label,
                    label = action.label
                )
            }

            MoveMemory.recordGood(context, stateKey, move)
            AgentMemory.recordOutcome(
                context = context,
                stateKey = stateKey,
                normalizedCommand = move.describe(),
                screenChanged = true,
                move = move,
                isGame = false,
                goal = goal
            )
            if (!action.label.isNullOrBlank()) {
                AgentMemory.recordAppTransition(
                    context = context,
                    pkg = pkg,
                    tappedLabel = action.label,
                    beforeLabels = emptyList(),
                    afterLabels = listOf(action.label)
                )
            }
        }

        deferredResult?.complete(action)
        deferredResult = null
        targetPkg = null
        targetGoal = null
        targetStateKey = null
    }
}
