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
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Capri's single precision full-screen canvas cursor and draggable STOP pill.
 * Draws directly on full-screen canvas space for 1:1 mathematical alignment with touch dispatch coordinates.
 */
object CapriCursor {

    private const val TAG = "CAPRI_CURSOR"

    @Volatile
    var cancelled = false

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var appCtx: Context? = null

    private var stageView: StageView? = null
    private var pill: LinearLayout? = null
    private var statusTv: TextView? = null

    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorScale = 1f
    private var sizePx = 0f

    private var animator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    private class StageView(context: Context) : View(context) {
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = 0xFFFF2D6B.toInt()
        }
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xEEFF2D6B.toInt()
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            val r = (sizePx / 2f) * cursorScale
            if (r <= 0f) return
            canvas.drawCircle(cursorX, cursorY, r - 2f, ringPaint)
            canvas.drawCircle(cursorX, cursorY, r / 3f, corePaint)
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
        val hostCtx = com.dot.capri.CapriAccessibilityService.instance ?: context.applicationContext
        return (hostCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { wm = it }
    }

    private fun overlayType(): Int =
        if (com.dot.capri.CapriAccessibilityService.instance != null)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun ensureStage(context: Context) {
        if (stageView != null) return
        val app = context.applicationContext
        appCtx = app
        val manager = windowManager(context)
        val density = app.resources.displayMetrics.density
        sizePx = 36f * density

        val v = StageView(app)
        fun paramsFor(type: Int) = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val preferred = overlayType()
        var added = try {
            manager.addView(v, paramsFor(preferred))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add stage view with type $preferred", e)
            false
        }
        if (!added && preferred != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
            try {
                val appWm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                appWm.addView(v, paramsFor(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY))
                wm = appWm
                added = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add stage view with fallback TYPE_APPLICATION_OVERLAY", e)
                return
            }
        }
        if (added) {
            stageView = v
        }
    }

    private fun ensurePill(context: Context) {
        if (pill != null) return
        val app = context.applicationContext
        val manager = windowManager(context)
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
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            val h = (18 * density).toInt()
            val v = (10 * density).toInt()
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
                            try { wm?.updateViewLayout(v, params) } catch (_: Exception) {}
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
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (16 * density).toInt()
            y = (80 * density).toInt()
        }
        try {
            manager.addView(row, paramsFor(preferred))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add pill with type $preferred", e)
            try {
                val appWm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                appWm.addView(row, paramsFor(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY))
                wm = appWm
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to add pill with fallback TYPE_APPLICATION_OVERLAY", ex)
                return
            }
        }
        val pillScale = com.dot.capri.util.CustomizationPrefs.getPillScale(context)
        row.scaleX = pillScale
        row.scaleY = pillScale
        pill = row
        statusTv = status
    }

    private fun place(x: Float, y: Float) {
        cursorX = x
        cursorY = y
        stageView?.invalidate()
    }

    private fun spawnNow(context: Context, x: Int, y: Int) {
        ensureStage(context)
        if (stageView != null) {
            animator?.cancel()
            place(x.toFloat(), y.toFloat())
        }
    }

    fun setStatus(msg: String) = onMain {
        statusTv?.text = msg
    }

    fun show(context: Context) = onMain {
        val newRun = stageView == null && pill == null
        ensurePill(context)
        if (newRun) {
            cancelled = false
            val m = context.applicationContext.resources.displayMetrics
            spawnNow(context, m.widthPixels / 2, m.heightPixels / 2)
            pulse()
        } else {
            ensureStage(context)
        }
    }

    fun hide() = onMain {
        cancelled = false
        animator?.cancel()
        pulseAnimator?.cancel()
        try { stageView?.let { wm?.removeView(it) } } catch (_: Exception) {}
        try { pill?.let { wm?.removeView(it) } } catch (_: Exception) {}
        stageView = null
        pill = null
        statusTv = null
    }

    /**
     * Temporarily hides both the cursor ring AND the STOP pill while a screenshot is captured
     * so Capri never sees her own UI in screenshots or mistakes pill animations for screen changes.
     */
    fun setCaptureHidden(hidden: Boolean) = onMain {
        val a = if (hidden) 0f else 1f
        stageView?.alpha = a
        pill?.alpha = a
    }

    suspend fun moveTo(x: Int, y: Int) {
        withContext(Dispatchers.Main) {
            val v = stageView
            if (v == null) {
                val ctx = appCtx
                    ?: com.dot.capri.CapriAccessibilityService.instance?.applicationContext
                    ?: com.dot.capri.BubbleService.instance?.applicationContext
                    ?: return@withContext
                spawnNow(ctx, x, y)
                return@withContext
            }
            val dx = kotlin.math.abs(cursorX - x)
            val dy = kotlin.math.abs(cursorY - y)
            if (dx < 2f && dy < 2f) {
                place(x.toFloat(), y.toFloat())
                return@withContext
            }
            suspendCancellableCoroutine<Unit> { cont ->
                animator?.cancel()
                val fx = cursorX
                val fy = cursorY
                val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 60
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
            val ctx = appCtx
                ?: com.dot.capri.CapriAccessibilityService.instance?.applicationContext
                ?: com.dot.capri.BubbleService.instance?.applicationContext
                ?: return@withContext
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
        val v = stageView ?: return@onMain
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.5f, 1f).apply {
            duration = 130
            addUpdateListener {
                cursorScale = it.animatedValue as Float
                v.invalidate()
            }
            start()
        }
    }
}
