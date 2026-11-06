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
import java.util.Locale
import kotlin.coroutines.resume

/** Everything Capri can do to the screen: look at it, tap, swipe, scroll, type, press send. */
object PhoneControl {

    /** One stroke, dispatched on its own. Used for strokes that must continue into the next one. */
    private suspend fun dispatchStroke(stroke: GestureDescription.StrokeDescription): Boolean {
        val s = service() ?: return false
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCancellableCoroutine { cont ->
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

    /** Press at A, hold holdMs, move to B over moveMs, release. The finger stays down between strokes. */
    suspend fun dragWithHold(ax: Int, ay: Int, bx: Int, by: Int, holdMs: Long, moveMs: Long): Boolean {
        val (sax, say) = clampToScreen(ax, ay)
        val (sbx, sby) = clampToScreen(bx, by)
        val max = GestureDescription.getMaxGestureDuration()
        CapriCursor.moveTo(sax, say)
        CapriCursor.pulse()
        val holdPath = Path().apply { moveTo(sax.toFloat(), say.toFloat()); lineTo(sax.toFloat(), say.toFloat()) }
        val movePath = Path().apply { moveTo(sax.toFloat(), say.toFloat()); lineTo(sbx.toFloat(), sby.toFloat()) }
        val first = GestureDescription.StrokeDescription(holdPath, 0L, holdMs.coerceIn(1L, max - 1), true)
        if (!dispatchStroke(first)) return false
        val safeMoveMs = moveMs.coerceIn(50L, max - 1)
        val second = first.continueStroke(movePath, 0L, safeMoveMs, false)
        return coroutineScope {
            val anim = launch { CapriCursor.glideAlongPath(sax.toFloat(), say.toFloat(), sbx.toFloat(), sby.toFloat(), safeMoveMs) }
            val ok = async { dispatchStroke(second) }.await()
            anim.join()
            ok
        }
    }

    /** Press at center (fx, fy), deflect to (tx, ty) over dragMs, and KEEP HOLDING at (tx, ty) for holdMs before releasing. */
    suspend fun dragAndHold(fx: Int, fy: Int, tx: Int, ty: Int, dragMs: Long = 100L, holdMs: Long = 1500L): Boolean {
        val (sfx, sfy) = clampToScreen(fx, fy)
        val (stx, sty) = clampToScreen(tx, ty)
        val max = GestureDescription.getMaxGestureDuration()
        CapriCursor.moveTo(sfx, sfy)
        CapriCursor.pulse()
        val movePath = Path().apply { moveTo(sfx.toFloat(), sfy.toFloat()); lineTo(stx.toFloat(), sty.toFloat()) }
        val holdPath = Path().apply { moveTo(stx.toFloat(), sty.toFloat()); lineTo(stx.toFloat(), sty.toFloat()) }
        val safeMoveMs = dragMs.coerceIn(50L, 200L)
        val safeHoldMs = holdMs.coerceIn(50L, max - safeMoveMs - 10L)
        val first = GestureDescription.StrokeDescription(movePath, 0L, safeMoveMs, true)
        if (!dispatchStroke(first)) return false
        val second = first.continueStroke(holdPath, 0L, safeHoldMs, false)
        return coroutineScope {
            val anim = launch { CapriCursor.glideAlongPath(sfx.toFloat(), sfy.toFloat(), stx.toFloat(), sty.toFloat(), safeMoveMs) }
            val ok = async { dispatchStroke(second) }.await()
            anim.join()
            ok
        }
    }

    /**
     * Controls an on-screen virtual joystick: touches down at center (cx, cy),
     * deflects in the given direction by radius pixels, and holds for durationMs before releasing.
     */
    suspend fun joystick(cx: Int, cy: Int, dir: String, durationMs: Long, sh: Int): Boolean {
        val radius = (sh * 0.12f).coerceIn(80f, 180f)
        val d = dir.lowercase(Locale.US).trim()
        val (dx, dy) = when (d) {
            "forward", "up", "north" -> 0f to -radius
            "backward", "back", "down", "south" -> 0f to radius
            "left", "west" -> -radius to 0f
            "right", "east" -> radius to 0f
            "forward_left", "up_left" -> -radius * 0.707f to -radius * 0.707f
            "forward_right", "up_right" -> radius * 0.707f to -radius * 0.707f
            "back_left", "down_left" -> -radius * 0.707f to radius * 0.707f
            "back_right", "down_right" -> radius * 0.707f to radius * 0.707f
            else -> 0f to -radius
        }
        val targetX = (cx + dx).toInt()
        val targetY = (cy + dy).toInt()
        return dragAndHold(cx, cy, targetX, targetY, dragMs = 90L, holdMs = durationMs)
    }

    /** Turns the camera: a drag from (zoneX, zoneY) by (dx, dy) pixels. */
    suspend fun look(zoneX: Int, zoneY: Int, dx: Int, dy: Int, ms: Long = 250L): Boolean {
        val (sx, sy) = clampToScreen(zoneX, zoneY)
        val (ex, ey) = clampToScreen(zoneX + dx, zoneY + dy)
        CapriCursor.moveTo(sx, sy)
        CapriCursor.pulse()
        val path = Path().apply { moveTo(sx.toFloat(), sy.toFloat()); lineTo(ex.toFloat(), ey.toFloat()) }
        val safeMs = ms.coerceIn(50L, GestureDescription.getMaxGestureDuration())
        return coroutineScope {
            val anim = launch { CapriCursor.glideAlongPath(sx.toFloat(), sy.toFloat(), ex.toFloat(), ey.toFloat(), safeMs) }
            val ok = async { dispatch(path, safeMs) }.await()
            anim.join()
            ok
        }
    }

    class UiItem(
        val id: Int,
        val label: String,
        val kind: String,   // input, button, toggle:on, toggle:off, scroll, text
        val cx: Int,
        val cy: Int,
        val node: AccessibilityNodeInfo
    )

    private fun service(): CapriAccessibilityService? = CapriAccessibilityService.instance

    fun realScreenBounds(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }

    fun screenSize(): Pair<Int, Int>? {
        val s = service() ?: return null
        return realScreenBounds(s)
    }

    /**
     * Returns the real screen pixel dimensions guaranteed to match the orientation
     * (landscape vs portrait) of a captured screenshot so X and Y axes never invert in landscape games.
     */
    fun orientedScreenSize(shotW: Int, shotH: Int): Pair<Int, Int> {
        val raw = screenSize() ?: return Pair(shotW.coerceAtLeast(1), shotH.coerceAtLeast(1))
        val shotLandscape = shotW > shotH
        val screenLandscape = raw.first > raw.second
        return if (shotLandscape == screenLandscape) {
            raw
        } else {
            Pair(raw.second, raw.first)
        }
    }

    /**
     * Keeps a point inside the screen. Uses a 2px margin so Macro Buttons placed in the very
     * corners (e.g. pause/menu/inventory buttons in landscape games) are tapped accurately.
     */
    fun clampToScreen(x: Int, y: Int): Pair<Int, Int> {
        val s = screenSize() ?: return Pair(x, y)
        val edge = 2
        return Pair(x.coerceIn(edge, (s.first - edge).coerceAtLeast(edge)), y.coerceIn(edge, (s.second - edge).coerceAtLeast(edge)))
    }

    // Windows that are never the app the user is working in.
    private val IGNORED_PACKAGES = setOf("com.android.systemui")

    fun foregroundRoot(): AccessibilityNodeInfo? {
        val s = service() ?: return null
        val own = s.packageName

        fun usable(n: AccessibilityNodeInfo?): Boolean {
            val pkg = n?.packageName?.toString() ?: return false
            return pkg != own && pkg !in IGNORED_PACKAGES
        }

        // The top app window is the one the user is looking at.
        val top = s.windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
            .map { it.root }
            .firstOrNull { usable(it) }
        if (top != null) return top

        val active = s.rootInActiveWindow
        return if (usable(active)) active else null
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

        val ownPkg = service()?.packageName ?: "com.dot.capri"

        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || out.size >= max || depth > 40) return
            if (n.packageName?.toString() == ownPkg) return // Ignore Capri's own chat bubble, cursor, and overlay views
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
        val safeMs = ms.coerceIn(50L, GestureDescription.getMaxGestureDuration())
        return suspendCancellableCoroutine { cont ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, safeMs))
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
        val gestureOk = dispatchTap(item.cx, item.cy)
        if (gestureOk) return true
        return if (item.node.isClickable) item.node.performAction(AccessibilityNodeInfo.ACTION_CLICK) else false
    }

    suspend fun longPress(item: UiItem, durationMs: Long = 800): Boolean {
        CapriCursor.moveTo(item.cx, item.cy)
        CapriCursor.pulse()
        item.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val gestureOk = dispatch(Path().apply { moveTo(item.cx.toFloat(), item.cy.toFloat()); lineTo(item.cx.toFloat(), item.cy.toFloat()) }, durationMs)
        if (gestureOk) return true
        return item.node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    suspend fun holdAt(x: Int, y: Int, durationMs: Long = 3000L): Boolean {
        val (safeX, safeY) = clampToScreen(x, y)
        CapriCursor.moveTo(safeX, safeY)
        CapriCursor.pulse()
        val path = Path().apply { moveTo(safeX.toFloat(), safeY.toFloat()); lineTo(safeX.toFloat(), safeY.toFloat()) }
        return dispatch(path, durationMs)
    }

    suspend fun hold(item: UiItem, durationMs: Long = 3000L): Boolean {
        return holdAt(item.cx, item.cy, durationMs)
    }

    suspend fun tapAt(x: Int, y: Int): Boolean {
        val (safeX, safeY) = clampToScreen(x, y)
        CapriCursor.moveTo(safeX, safeY)
        CapriCursor.pulse()
        val gestureOk = dispatchTap(safeX, safeY)
        if (gestureOk) return true
        val root = foregroundRoot()
        val target = root?.let { smallestClickableAt(it, safeX, safeY) }
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    suspend fun doubleTapAt(x: Int, y: Int): Boolean {
        val (safeX, safeY) = clampToScreen(x, y)
        CapriCursor.moveTo(safeX, safeY)
        CapriCursor.pulse()
        val ok1 = dispatchTap(safeX, safeY)
        delay(60)
        val ok2 = dispatchTap(safeX, safeY)
        return ok1 && ok2
    }

    suspend fun doubleTap(item: UiItem): Boolean {
        return doubleTapAt(item.cx, item.cy)
    }

    private suspend fun dispatchTap(x: Int, y: Int): Boolean {
        val (safeX, safeY) = clampToScreen(x, y)
        return dispatch(Path().apply { moveTo(safeX.toFloat(), safeY.toFloat()); lineTo(safeX.toFloat(), safeY.toFloat()) }, 60)
    }

    suspend fun dispatchMultiTouch(points: List<Pair<Int, Int>>, durationMs: Long = 100L): Boolean {
        val s = service() ?: return false
        if (points.isEmpty()) return false
        val safeMs = durationMs.coerceIn(50L, GestureDescription.getMaxGestureDuration())
        val builder = GestureDescription.Builder()

        for ((x, y) in points) {
            val (safeX, safeY) = clampToScreen(x, y)
            val path = Path().apply {
                moveTo(safeX.toFloat(), safeY.toFloat())
                lineTo(safeX.toFloat(), safeY.toFloat())
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0L, safeMs))
        }

        val gesture = builder.build()
        return suspendCancellableCoroutine { cont ->
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

    suspend fun tapMulti(points: List<Pair<Int, Int>>, durationMs: Long = 100L): Boolean {
        val s = service() ?: return false
        if (points.isEmpty()) return false
        points.firstOrNull()?.let { (x, y) ->
            CapriCursor.moveTo(x, y)
        }
        CapriCursor.pulse()
        return dispatchMultiTouch(points, durationMs)
    }

    private fun smallestClickableAt(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        val r = Rect()
        val tolerance = 20
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || !n.isVisibleToUser) return
            n.getBoundsInScreen(r)
            val expanded = Rect(r.left - tolerance, r.top - tolerance, r.right + tolerance, r.bottom + tolerance)
            if (n.isClickable && expanded.contains(x, y)) {
                val area = r.width().toLong() * r.height()
                if (area in 1 until bestArea) {
                    best = n
                    bestArea = area
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return best
    }

    suspend fun scroll(direction: ScrollDirection): Boolean {
        val s = service() ?: return false
        val root = foregroundRoot()
        val vertical = direction == ScrollDirection.UP || direction == ScrollDirection.DOWN
        val scrollable = root?.let { largestScrollable(it, vertical) }

        val r = Rect()
        if (scrollable != null) {
            scrollable.getBoundsInScreen(r)
        } else {
            val (realW, realH) = realScreenBounds(s)
            r.set(0, 0, realW, realH)
        }

        val cx = r.centerX().toFloat()
        val cy = r.centerY().toFloat()
        val w = r.width().toFloat()
        val h = r.height().toFloat()

        val (fx, fy, tx, ty) = when (direction) {
            ScrollDirection.DOWN -> listOf(cx, cy + h * 0.40f, cx, cy - h * 0.40f)
            ScrollDirection.UP -> listOf(cx, cy - h * 0.40f, cx, cy + h * 0.40f)
            ScrollDirection.RIGHT -> listOf(cx + w * 0.40f, cy, cx - w * 0.40f, cy)
            ScrollDirection.LEFT -> listOf(cx - w * 0.40f, cy, cx + w * 0.40f, cy)
        }

        CapriCursor.moveTo(cx.toInt(), cy.toInt())
        CapriCursor.pulse()
        val swipeOk = swipe(fx, fy, tx, ty, 400)
        if (swipeOk) return true

        val forward = direction == ScrollDirection.DOWN || direction == ScrollDirection.RIGHT
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return scrollable?.performAction(action) ?: false
    }

    suspend fun scrollAt(x: Int, y: Int, direction: ScrollDirection): Boolean {
        val s = service() ?: return false
        val root = foregroundRoot()
        val vertical = direction == ScrollDirection.UP || direction == ScrollDirection.DOWN
        val container = root?.let { scrollableAt(it, x, y, vertical) }

        val r = Rect()
        if (container != null) {
            container.getBoundsInScreen(r)
        } else {
            val (realW, realH) = realScreenBounds(s)
            val grid = GridSpec.of(realW, realH)
            val cellW = realW / grid.cols
            val cellH = realH / grid.rows
            val left = (x - cellW / 2).coerceIn(0, realW - cellW)
            val top = (y - cellH / 2).coerceIn(0, realH - cellH)
            r.set(left, top, left + cellW, top + cellH)
        }

        val cx = r.centerX().toFloat()
        val cy = r.centerY().toFloat()
        val w = r.width().toFloat()
        val h = r.height().toFloat()

        val (fx, fy, tx, ty) = when (direction) {
            ScrollDirection.DOWN -> listOf(cx, cy + h * 0.35f, cx, cy - h * 0.35f)
            ScrollDirection.UP -> listOf(cx, cy - h * 0.35f, cx, cy + h * 0.35f)
            ScrollDirection.RIGHT -> listOf(cx + w * 0.35f, cy, cx - w * 0.35f, cy)
            ScrollDirection.LEFT -> listOf(cx - w * 0.35f, cy, cx + w * 0.35f, cy)
        }

        CapriCursor.moveTo(cx.toInt(), cy.toInt())
        CapriCursor.pulse()
        val swipeOk = swipe(fx, fy, tx, ty, 350)
        if (swipeOk) return true

        val forward = direction == ScrollDirection.DOWN || direction == ScrollDirection.RIGHT
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return container?.performAction(action) ?: false
    }

    private fun scrollableAt(root: AccessibilityNodeInfo, x: Int, y: Int, vertical: Boolean): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var smallestArea = Long.MAX_VALUE
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || !n.isVisibleToUser) return
            if (n.isScrollable) {
                n.getBoundsInScreen(r)
                if (r.contains(x, y)) {
                    val area = r.width().toLong() * r.height()
                    if (area in 1 until smallestArea) {
                        best = n
                        smallestArea = area
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return best
    }

    /**
     * The largest visible scrollable container whose shape matches the scroll direction.
     * vertical = true  -> taller than wide (lists, feeds, the app drawer)
     * vertical = false -> wider than tall (launcher pages, carousels)
     */
    private fun largestScrollable(root: AccessibilityNodeInfo, vertical: Boolean): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || !n.isVisibleToUser) return
            if (n.isScrollable) {
                n.getBoundsInScreen(r)
                val tall = r.height() >= r.width()
                if (tall == vertical) {
                    val area = r.width().toLong() * r.height()
                    if (area > bestArea) {
                        best = n
                        bestArea = area
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return best
    }

    /**
     * Visible, labelled, clickable items in reading order: rows top to bottom, then left to right.
     * Items whose centres are within `rowTolerancePx` of each other count as one row.
     */
    fun orderedTargets(root: AccessibilityNodeInfo, rowTolerancePx: Int): List<UiItem> {
        val items = snapshot(root, max = 200)
            .filter { it.kind == "button" && it.label != "(no label)" }
            .sortedBy { it.cy }
        val rows = mutableListOf<MutableList<UiItem>>()
        for (item in items) {
            val row = rows.lastOrNull()
            if (row != null && kotlin.math.abs(row.first().cy - item.cy) <= rowTolerancePx) {
                row.add(item)
            } else {
                rows.add(mutableListOf(item))
            }
        }
        return rows.flatMap { row -> row.sortedBy { it.cx } }
    }

    /** Drags from (x1,y1) to (x2,y2). The cursor is placed at the start first, so there's no diagonal jump. */
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 250): Boolean {
        service() ?: return false
        val (safeX1, safeY1) = clampToScreen(x1.toInt(), y1.toInt())
        val (safeX2, safeY2) = clampToScreen(x2.toInt(), y2.toInt())
        CapriCursor.pulse()
        val path = Path().apply { moveTo(safeX1.toFloat(), safeY1.toFloat()); lineTo(safeX2.toFloat(), safeY2.toFloat()) }
        return coroutineScope {
            val anim = launch { CapriCursor.glideAlongPath(safeX1.toFloat(), safeY1.toFloat(), safeX2.toFloat(), safeY2.toFloat(), ms) }
            val ok = async { dispatch(path, ms) }.await()
            anim.join()
            ok
        }
    }

    /** Press and hold at a pixel for `ms` milliseconds. */
    suspend fun longPressAt(x: Int, y: Int, ms: Long = 800): Boolean {
        CapriCursor.moveTo(x, y)
        CapriCursor.pulse()
        return dispatch(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, ms)
    }

    suspend fun scroll(down: Boolean): Boolean =
        scroll(if (down) ScrollDirection.DOWN else ScrollDirection.UP)

    suspend fun multiStrokeGesture(path1: Path, path2: Path, durationMs: Long): Boolean {
        val s = service() ?: return false
        return suspendCancellableCoroutine { cont ->
            val stroke1 = GestureDescription.StrokeDescription(path1, 0, durationMs)
            val stroke2 = GestureDescription.StrokeDescription(path2, 0, durationMs)
            val gesture = GestureDescription.Builder()
                .addStroke(stroke1)
                .addStroke(stroke2)
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

    suspend fun pinch(zoomIn: Boolean): Boolean {
        val s = service() ?: return false
        val root = foregroundRoot()
        val windowRect = Rect()
        root?.getBoundsInScreen(windowRect)

        val w = if (windowRect.width() > 0) windowRect.width().toFloat() else s.resources.displayMetrics.widthPixels.toFloat()
        val h = if (windowRect.height() > 0) windowRect.height().toFloat() else s.resources.displayMetrics.heightPixels.toFloat()
        val cy = h / 2f

        val (x1From, x1To, x2From, x2To) = if (zoomIn) {
            listOf(w * 0.40f, w * 0.15f, w * 0.60f, w * 0.85f)
        } else {
            listOf(w * 0.15f, w * 0.40f, w * 0.85f, w * 0.60f)
        }

        CapriCursor.moveTo(x1From.toInt(), cy.toInt())
        delay(150)

        val path1 = Path().apply { moveTo(x1From, cy); lineTo(x1To, cy) }
        val path2 = Path().apply { moveTo(x2From, cy); lineTo(x2To, cy) }

        var ok = false
        coroutineScope {
            val animJob = launch {
                CapriCursor.glideAlongPath(x1From, cy, x1To, cy, 400)
            }
            val gestureJob = async { multiStrokeGesture(path1, path2, 400) }
            ok = gestureJob.await()
            animJob.join()
        }

        delay(250)
        return ok
    }

    suspend fun dualTap(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        service() ?: return false
        CapriCursor.moveTo(x1, y1)
        CapriCursor.pulse()

        val path1 = Path().apply { moveTo(x1.toFloat(), y1.toFloat()) }
        val path2 = Path().apply { moveTo(x2.toFloat(), y2.toFloat()) }

        val ok = multiStrokeGesture(path1, path2, 60)
        delay(300)
        return ok
    }

    /** Puts `text` into a field (replaces what is there) and automatically hits enter/submit if possible. */
    suspend fun typeInto(context: Context, item: UiItem, text: String, submit: Boolean = true): Boolean {
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
        if (submit) {
            submitTypedText(context, item)
        }
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
            // keep pill up
        }
    }
}
