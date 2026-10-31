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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Capri's single precision on-screen cursor and draggable STOP pill.
 * Uncluttered and optimized for single-cursor gaming and task agents.
 */
object CapriCursor {

    @Volatile
    var cancelled = false

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var appCtx: Context? = null
    private var sizePx = 0

    private var stage: FrameLayout? = null
    private var pill: LinearLayout? = null
    private var statusTv: TextView? = null

    private var originX = 0
    private var originY = 0

    private var cursorView: DotView? = null
    private var cursorX = 0f
    private var cursorY = 0f
    private var animator: ValueAnimator? = null

    private class DotView(context: Context) : View(context) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = 0xFFFF2D6B.toInt()
        }
        private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFF2D6B.toInt()
        }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            canvas.drawCircle(r, r, r - 4f, ring)
            canvas.drawCircle(r, r, r / 3f, core)
        }
    }

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

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun windowManager(context: Context): WindowManager {
        return wm ?: (context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .also { wm = it }
    }

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
            try { manager.addView(frame, paramsFor(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)) } catch (_: Exception) { return }
        } else if (!added) {
            return
        }
        stage = frame
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
                if (event.action == MotionEvent.ACTION_DOWN) {
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

            setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    val params = layoutParams as? WindowManager.LayoutParams ?: return false
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = initialX - (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            try { manager.updateViewLayout(v, params) } catch (_: Exception) {}
                            return true
                        }
                    }
                    return false
                }
            })
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
            gravity = Gravity.TOP or Gravity.END
            x = (16 * density).toInt()
            y = (12 * density).toInt()
        }
        try { manager.addView(row, paramsFor(preferred)) } catch (_: Exception) {
            try { manager.addView(row, paramsFor(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)) } catch (_: Exception) { return }
        }
        pill = row
        statusTv = status
    }

    private fun place(x: Float, y: Float) {
        cursorX = x
        cursorY = y
        val half = sizePx / 2f
        cursorView?.let {
            it.translationX = x - half - originX
            it.translationY = y - half - originY
        }
    }

    private fun spawnNow(context: Context, x: Int, y: Int) {
        ensureStage(context)
        val frame = stage ?: return
        if (cursorView != null) {
            animator?.cancel()
            place(x.toFloat(), y.toFloat())
            return
        }
        val view = DotView(frame.context)
        frame.addView(view, FrameLayout.LayoutParams(sizePx, sizePx))
        cursorView = view
        place(x.toFloat(), y.toFloat())
    }

    fun setStatus(msg: String) = onMain {
        statusTv?.text = msg
    }

    fun show(context: Context) = onMain {
        val newRun = stage == null
        ensurePill(context)
        if (newRun) cancelled = false
        val m = context.applicationContext.resources.displayMetrics
        spawnNow(context, m.widthPixels / 2, m.heightPixels / 2)
        pulse()
    }

    fun hide() = onMain {
        cancelled = false
        animator?.cancel()
        cursorView = null
        try { stage?.let { wm?.removeView(it) } } catch (_: Exception) {}
        try { pill?.let { wm?.removeView(it) } } catch (_: Exception) {}
        stage = null
        pill = null
        statusTv = null
    }

    suspend fun moveTo(x: Int, y: Int) {
        withContext(Dispatchers.Main) {
            val v = cursorView
            if (v == null) {
                val ctx = appCtx ?: return@withContext
                spawnNow(ctx, x, y)
                return@withContext
            }
            suspendCancellableCoroutine<Unit> { cont ->
                animator?.cancel()
                val fx = cursorX
                val fy = cursorY
                val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 150
                    addUpdateListener {
                        val f = it.animatedValue as Float
                        place(fx + (x - fx) * f, fy + (y - fy) * f)
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(a: Animator) {
                            if (animator === a) animator = null
                            if (cont.isActive) cont.resume(Unit)
                        }
                        override fun onAnimationCancel(a: Animator) {
                            if (animator === a) animator = null
                            if (cont.isActive) cont.resume(Unit)
                        }
                    })
                }
                animator = anim
                anim.start()
                cont.invokeOnCancellation { anim.cancel() }
            }
        }
    }

    suspend fun glideAlongPath(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long) {
        withContext(Dispatchers.Main) {
            val ctx = appCtx ?: return@withContext
            spawnNow(ctx, fromX.toInt(), fromY.toInt())
            place(fromX, fromY)
            suspendCancellableCoroutine<Unit> { cont ->
                animator?.cancel()
                val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = durationMs
                    addUpdateListener {
                        val f = it.animatedValue as Float
                        place(fromX + (toX - fromX) * f, fromY + (toY - fromY) * f)
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(a: Animator) {
                            if (animator === a) animator = null
                            if (cont.isActive) cont.resume(Unit)
                        }
                        override fun onAnimationCancel(a: Animator) {
                            if (animator === a) animator = null
                            if (cont.isActive) cont.resume(Unit)
                        }
                    })
                }
                animator = anim
                anim.start()
                cont.invokeOnCancellation { anim.cancel() }
            }
        }
    }

    fun pulse() = onMain {
        val v = cursorView ?: return@onMain
        v.animate().scaleX(1.7f).scaleY(1.7f).setDuration(110).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }.start()
    }
}
