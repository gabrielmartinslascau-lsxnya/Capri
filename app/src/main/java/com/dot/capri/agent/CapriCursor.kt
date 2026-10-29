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
import android.widget.TextView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Capri's visible "hand": a glowing ring that glides to where she is about to tap,
 * plus a small STOP pill at the top. The ring never takes touches, so her own taps go through it.
 * Tapping STOP sets `cancelled`, which the agent loop checks every step.
 */
object CapriCursor {

    @Volatile
    var cancelled = false
        private set

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var wm: WindowManager? = null
    @Volatile private var dot: View? = null
    @Volatile private var dotParams: WindowManager.LayoutParams? = null
    @Volatile private var pill: TextView? = null
    @Volatile private var sizePx = 0

    private class DotView(context: Context) : View(context) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = 0xFFFF2D6B.toInt()
        }
        private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFF2D6B.toInt() }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            canvas.drawCircle(r, r, r - 4f, ring)
            canvas.drawCircle(r, r, r / 3f, core)
        }
    }

    fun show(context: Context) {
        cancelled = false
        main.post {
            if (dot != null) return@post
            val app = context.applicationContext
            val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = app.resources.displayMetrics
            val density = metrics.density
            sizePx = (36 * density).toInt()
            wm = manager

            val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            val dotView = DotView(app)
            val dp = WindowManager.LayoutParams(
                sizePx, sizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = metrics.widthPixels / 2 - sizePx / 2
                y = metrics.heightPixels / 2 - sizePx / 2
            }
            manager.addView(dotView, dp)
            dot = dotView
            dotParams = dp

            val stop = TextView(app).apply {
                text = "STOP"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                val h = (16 * density).toInt()
                val v = (8 * density).toInt()
                setPadding(h, v, h, v)
                background = GradientDrawable().apply {
                    cornerRadius = 100f
                    setColor(0xEEFF2D6B.toInt())
                }
                setOnClickListener {
                    cancelled = true
                    text = "stopping..."
                }
            }
            val pp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                baseFlags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (36 * density).toInt()
            }
            manager.addView(stop, pp)
            pill = stop
        }
    }

    fun hide() {
        main.post {
            dot?.let { runCatching { wm?.removeView(it) } }
            pill?.let { runCatching { wm?.removeView(it) } }
            dot = null
            pill = null
            dotParams = null
        }
    }

    /** Glides the ring so its CENTER sits on (x, y) in screen pixels. Suspends until it arrives. */
    suspend fun moveTo(x: Int, y: Int) {
        val view = dot ?: return
        val params = dotParams ?: return
        val half = sizePx / 2
        val fromX = params.x
        val fromY = params.y
        val toX = x - half
        val toY = y - half
        suspendCancellableCoroutine<Unit> { cont ->
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 450
                addUpdateListener {
                    val f = it.animatedValue as Float
                    params.x = (fromX + (toX - fromX) * f).toInt()
                    params.y = (fromY + (toY - fromY) * f).toInt()
                    try {
                        wm?.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        // view already removed
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
            }
            main.post { anim.start() }
            cont.invokeOnCancellation { main.post { anim.cancel() } }
        }
    }

    fun pulse() {
        main.post {
            dot?.animate()?.scaleX(1.7f)?.scaleY(1.7f)?.setDuration(110)?.withEndAction {
                dot?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(140)?.start()
            }?.start()
        }
    }
}
