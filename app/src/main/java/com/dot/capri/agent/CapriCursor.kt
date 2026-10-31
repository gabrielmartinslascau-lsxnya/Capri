package com.dot.capri.agent

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Capri's on-screen cursors and STOP pill.
 *
 * One full-screen, click-through stage holds every cursor ring. Moving a ring is a view
 * translation, so there is no window-manager call per frame.
 * The STOP pill (top of screen) is the only touchable window. It stays up for a whole run.
 * All view work runs on the main thread.
 */
object CapriCursor {

    /** Set when STOP is tapped. Reset only when a new run starts (see show()). */
    @Volatile
    var cancelled = false

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var appCtx: Context? = null
    private var sizePx = 0

    private var stage: FrameLayout? = null
    private var pill: LinearLayout? = null
    private var statusTv: TextView? = null

    // Where the stage really sits on screen. Usually (0,0), but insets or cutouts can shift it.
    private var originX = 0
    private var originY = 0

    private val cursors = HashMap<Int, Cursor>()

    private class DotView(context: Context, colorHex: Int) : View(context) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = colorHex
        }
        private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (colorHex and 0x00FFFFFF) or 0xCC000000.toInt()
        }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            canvas.drawCircle(r, r, r - 4f, ring)
            canvas.drawCircle(r, r, r / 3f, core)
        }
    }

    /** The "thinking" bars. They animate while the pill is on screen. */
    private class AnimatedBarsView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF2D6B.toInt()
            style = Paint.Style.FILL
        }
        private var phase = 0f
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (!animator.isStarted) animator.start()
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val barWidth = 4f
            val gap = 3f
            val maxH = height.toFloat()
            for (i in 0..3) {
                val wave = kotlin.math.abs(kotlin.math.sin((phase * Math.PI * 2) + (i * 0.7)).toFloat())
                val h = maxH * (0.25f + 0.75f * wave)
                val left = i * (barWidth + gap)
                canvas.drawRect(left, maxH - h, left + barWidth, maxH, paint)
            }
        }
    }

    private class Cursor(val id: Int, val view: DotView) {
        var x = 0f // centre, in screen pixels
        var y = 0f
        var animator: ValueAnimator? = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun windowManager(context: Context): WindowManager {
        return wm ?: (context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .also { wm = it }
    }

    /**
     * Accessibility overlays stay visible above launchers and most system screens.
     * Falls back to the normal overlay type when the accessibility service isn't connected.
     */
    private fun overlayType(): Int =
        if (com.dot.capri.CapriAccessibilityService.instance != null)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun ensureStage(context: Context) {
        if (stage != null) return
        val app = context.applicationContext
        appCtx = app
        val manager = windowManager(app)
        val density = app.resources.displayMetrics.density
        sizePx = (36 * density).toInt()
        val frame = FrameLayout(app)
        fun paramsFor(type: Int) = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val preferred = overlayType()
        val added = try {
            manager.addView(frame, paramsFor(preferred))
            true
        } catch (_: Exception) {
            false
        }
        if (!added && preferred != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
            // Fallback: normal overlay type.
            try { manager.addView(frame, paramsFor(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)) } catch (_: Exception) { return }
        } else if (!added) {
            return
        }
        stage = frame
        android.util.Log.d("CAPRI_CURSOR", "stage attached, type=${if (preferred == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) "accessibility" else "application"}")
        frame.post {
            val loc = IntArray(2)
            frame.getLocationOnScreen(loc)
            originX = loc[0]
            originY = loc[1]
        }
    }

    private fun ensurePill(context: Context) {
        if (pill != null) return
        val app = context.applicationContext
        val manager = windowManager(app)
        val density = app.resources.displayMetrics.density

        val bars = AnimatedBarsView(app).apply {
            layoutParams = LinearLayout.LayoutParams((22 * density).toInt(), (14 * density).toInt()).apply {
                leftMargin = (4 * density).toInt()
            }
        }
        val status = TextView(app).apply {
            text = "working..."
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding((8 * density).toInt(), 0, (10 * density).toInt(), 0)
        }
        val stop = TextView(app).apply {
            text = "STOP"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            val h = (14 * density).toInt()
            val v = (6 * density).toInt()
            setPadding(h, v, h, v)
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(0xEEFF2D6B.toInt())
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                cancelled = true
                text = "stopping..."
            }
            setOnTouchListener { view, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    cancelled = true
                    text = "stopping..."
                    view.performClick()
                }
                false
            }
        }
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(0xEE1A1A1A.toInt())
                setStroke(2, 0xFFFF2D6B.toInt())
            }
            val ph = (12 * density).toInt()
            val pv = (6 * density).toInt()
            setPadding(ph, pv, ph, pv)
            addView(bars)
            addView(status)
            addView(stop)
        }
        val preferred = overlayType()
        fun paramsFor(type: Int) = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (36 * density).toInt()
        }
        val added = try {
            manager.addView(row, paramsFor(preferred))
            true
        } catch (_: Exception) {
            false
        }
        if (!added && preferred != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
            try { manager.addView(row, paramsFor(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)) } catch (_: Exception) { return }
        } else if (!added) {
            return
        }
        pill = row
        statusTv = status
    }

    private fun place(c: Cursor, x: Float, y: Float) {
        c.x = x
        c.y = y
        val half = sizePx / 2f
        c.view.translationX = x - half - originX
        c.view.translationY = y - half - originY
    }

    private fun spawnNow(context: Context, id: Int, x: Int, y: Int, colorHex: Int) {
        ensureStage(context)
        val frame = stage ?: return
        val existing = cursors[id]
        if (existing != null) {
            existing.animator?.cancel()
            place(existing, x.toFloat(), y.toFloat())
            return
        }
        val view = DotView(frame.context, colorHex)
        frame.addView(view, FrameLayout.LayoutParams(sizePx, sizePx))
        val c = Cursor(id, view)
        cursors[id] = c
        place(c, x.toFloat(), y.toFloat())
    }

    private fun removeCursorNow(id: Int) {
        val c = cursors.remove(id) ?: return
        c.animator?.cancel()
        stage?.removeView(c.view)
    }

    /** Animates one cursor. Must run on the main thread. */
    private suspend fun animate(c: Cursor, fx: Float, fy: Float, tx: Float, ty: Float, ms: Long) {
        suspendCancellableCoroutine<Unit> { cont ->
            c.animator?.cancel()
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ms
                addUpdateListener {
                    val f = it.animatedValue as Float
                    place(c, fx + (tx - fx) * f, fy + (ty - fy) * f)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) {
                        if (c.animator === a) c.animator = null
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onAnimationCancel(a: Animator) {
                        if (c.animator === a) c.animator = null
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
            }
            c.animator = anim
            anim.start()
            cont.invokeOnCancellation { anim.cancel() }
        }
    }

    val PALETTE = intArrayOf(
        0xFFFF2A55.toInt(), // 1: Default Pink-Red
        0xFF00E676.toInt(), // 2: Bright Green
        0xFF29B6F6.toInt(), // 3: Bright Blue
        0xFFFFD600.toInt(), // 4: Bright Yellow
        0xFFFF1744.toInt(), // 5: Crimson Red
        0xFF009688.toInt(), // 6: Teal Green
        0xFF3F51B5.toInt(), // 7: Royal Blue
        0xFFFF9100.toInt(), // 8: Amber Yellow
        0xFFE040FB.toInt(), // 9: Pink Magenta
        0xFF00E5FF.toInt()  // 10: Electric Cyan
    )

    fun cursorColorFor(id: Int): Int {
        val index = ((id - 1) % PALETTE.size + PALETTE.size) % PALETTE.size
        return PALETTE[index]
    }

    // ---------------- public API (same names as before) ----------------

    fun setStatus(msg: String) = onMain {
        statusTv?.text = msg
    }

    fun spawnCursor(context: Context, id: Int = 1, x: Int, y: Int, colorHex: Int = cursorColorFor(id)) {
        onMain {
            ensurePill(context)
            spawnNow(context, id, x, y, colorHex)
        }
    }

    fun removeCursor(id: Int) = onMain {
        removeCursorNow(id)
    }

    /** Starts a run's overlay. The cancel flag is cleared only when no overlay is up yet. */
    fun show(context: Context) = onMain {
        val newRun = stage == null
        ensurePill(context)
        if (newRun) cancelled = false
        val m = context.applicationContext.resources.displayMetrics
        val cx = m.widthPixels / 2
        val cy = m.heightPixels / 2
        if (!cursors.containsKey(1)) {
            spawnNow(context, 1, cx - 120, cy, cursorColorFor(1))
        }
        if (!cursors.containsKey(2)) {
            spawnNow(context, 2, cx + 120, cy, cursorColorFor(2))
        }
        pulseAll()
    }

    fun showDual(context: Context, x1: Int, y1: Int, x2: Int, y2: Int) = onMain {
        ensurePill(context)
        spawnNow(context, 1, x1, y1, cursorColorFor(1))
        spawnNow(context, 2, x2, y2, cursorColorFor(2))
    }

    fun showMulti(context: Context, points: List<Pair<Int, Int>>) = onMain {
        ensurePill(context)
        points.forEachIndexed { index, (x, y) ->
            val id = index + 1
            spawnNow(context, id, x, y, cursorColorFor(id))
        }
    }

    /** Removes only the second cursor. The stage and STOP pill stay up for the rest of the run. */
    fun hideDual() = onMain {
        removeCursorNow(2)
    }

    /** Removes everything. Call this once, when a run ends. Never between steps. */
    fun hide() = onMain {
        cancelled = false
        cursors.values.forEach { it.animator?.cancel() }
        cursors.clear()
        try { stage?.let { wm?.removeView(it) } } catch (_: Exception) {}
        try { pill?.let { wm?.removeView(it) } } catch (_: Exception) {}
        stage = null
        pill = null
        statusTv = null
    }

    /** Glides cursor `id` to (x, y). Creates the cursor at that point if it doesn't exist. */
    suspend fun moveTo(x: Int, y: Int, id: Int = 1) {
        withContext(Dispatchers.Main) {
            val c = cursors[id]
            if (c == null) {
                val ctx = appCtx ?: return@withContext
                spawnNow(ctx, id, x, y, 0xFFFF2D6B.toInt())
                return@withContext
            }
            animate(c, c.x, c.y, x.toFloat(), y.toFloat(), 150)
        }
    }

    /** Places cursor `id` at the start point instantly, then glides it to the end point. */
    suspend fun glideAlongPath(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long, id: Int = 1) {
        withContext(Dispatchers.Main) {
            val c = cursors[id] ?: run {
                val ctx = appCtx ?: return@withContext
                spawnNow(ctx, id, fromX.toInt(), fromY.toInt(), 0xFFFF2D6B.toInt())
                cursors[id] ?: return@withContext
            }
            place(c, fromX, fromY)
            animate(c, fromX, fromY, toX, toY, durationMs)
        }
    }

    /** Two drags at the same time. Runs in parallel. */
    suspend fun glideDualPaths(
        x1From: Float, y1From: Float, x1To: Float, y1To: Float,
        x2From: Float, y2From: Float, x2To: Float, y2To: Float,
        durationMs: Long
    ) {
        coroutineScope {
            launch { glideAlongPath(x1From, y1From, x1To, y1To, durationMs, id = 1) }
            launch { glideAlongPath(x2From, y2From, x2To, y2To, durationMs, id = 2) }
        }
    }

    suspend fun glideCursorsParallel(moves: List<Triple<Int, Pair<Float, Float>, Pair<Float, Float>>>, durationMs: Long) {
        if (moves.isEmpty()) return
        coroutineScope {
            for ((id, from, to) in moves) {
                launch { glideAlongPath(from.first, from.second, to.first, to.second, durationMs, id) }
            }
        }
    }

    fun pulse(id: Int = 1) = onMain {
        val v = cursors[id]?.view ?: return@onMain
        v.animate().scaleX(1.7f).scaleY(1.7f).setDuration(110).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }.start()
    }

    fun pulseAll() = onMain {
        cursors.values.forEach { c ->
            c.view.animate().scaleX(1.7f).scaleY(1.7f).setDuration(110).withEndAction {
                c.view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }.start()
        }
    }
}
