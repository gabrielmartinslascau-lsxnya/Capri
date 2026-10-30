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
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Capri's dynamic multi-cursor engine:
 * Supports spawning, gliding, pulsing, and removing as many independent cursors as needed (id=1, 2, 3...).
 */
object CapriCursor {

    @Volatile
    var cancelled = false
        private set

    private val main = Handler(Looper.getMainLooper())

    private class DotView(context: Context, val ringColor: Int = 0xFFFF2D6B.toInt()) : View(context) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = ringColor
        }
        private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (ringColor and 0x00FFFFFF) or 0xCC000000.toInt() }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            canvas.drawCircle(r, r, r - 4f, ring)
            canvas.drawCircle(r, r, r / 3f, core)
        }
    }

    private class CursorInstance(
        val id: Int,
        val view: View,
        val params: WindowManager.LayoutParams,
        var currentX: Int,
        var currentY: Int
    ) {
        var animator: ValueAnimator? = null
    }

    @Volatile private var wm: WindowManager? = null
    @Volatile private var pill: TextView? = null
    @Volatile private var sizePx = 0
    private val activeCursors = ConcurrentHashMap<Int, CursorInstance>()

    private fun getWindowManager(context: Context): WindowManager {
        val manager = wm
        if (manager != null) return manager
        val app = context.applicationContext
        val newManager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = newManager
        return newManager
    }

    private fun ensurePill(context: Context) {
        if (pill != null) return
        val app = context.applicationContext
        val manager = getWindowManager(app)
        val metrics = app.resources.displayMetrics
        val density = metrics.density
        if (sizePx <= 0) sizePx = (36 * density).toInt()

        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

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
            gravity = Gravity.TOP or Gravity.START
            y = (36 * density).toInt()
        }
        try {
            manager.addView(stop, pp)
            pill = stop
        } catch (_: Exception) {}
    }

    fun spawnCursor(context: Context, id: Int = 1, x: Int, y: Int, colorHex: Int = 0xFFFF2D6B.toInt()) {
        cancelled = false
        val task = Runnable {
            val app = context.applicationContext
            val manager = getWindowManager(app)
            val metrics = app.resources.displayMetrics
            val density = metrics.density
            if (sizePx <= 0) sizePx = (36 * density).toInt()

            ensurePill(app)

            val existing = activeCursors[id]
            val half = sizePx / 2
            if (existing != null) {
                existing.currentX = x
                existing.currentY = y
                existing.params.x = x - half
                existing.params.y = y - half
                try { manager.updateViewLayout(existing.view, existing.params) } catch (_: Exception) {}
                return@Runnable
            }

            val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

            val dotView = DotView(app, colorHex)
            val dp = WindowManager.LayoutParams(
                sizePx, sizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                baseFlags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x - half
                this.y = y - half
            }
            try {
                manager.addView(dotView, dp)
                activeCursors[id] = CursorInstance(id, dotView, dp, x, y)
            } catch (_: Exception) {}
        }

        if (Looper.myLooper() == Looper.getMainLooper()) task.run() else main.post(task)
    }

    fun removeCursor(id: Int) {
        val task = Runnable {
            val instance = activeCursors.remove(id) ?: return@Runnable
            instance.animator?.cancel()
            try { wm?.removeView(instance.view) } catch (_: Exception) {}
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run() else main.post(task)
    }

    fun show(context: Context) {
        val app = context.applicationContext
        val metrics = app.resources.displayMetrics
        spawnCursor(app, 1, metrics.widthPixels / 2, metrics.heightPixels / 2)
    }

    fun showDual(context: Context, x1: Int, y1: Int, x2: Int, y2: Int) {
        spawnCursor(context, 1, x1, y1)
        spawnCursor(context, 2, x2, y2)
    }

    fun hideDual() {
        removeCursor(2)
        hide()
    }

    fun hide() {
        val task = Runnable {
            activeCursors.forEach { (id, instance) ->
                instance.animator?.cancel()
                try { wm?.removeView(instance.view) } catch (_: Exception) {}
            }
            activeCursors.clear()
            pill?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
            pill = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run() else main.post(task)
    }

    suspend fun moveTo(x: Int, y: Int, id: Int = 1) {
        if (!activeCursors.containsKey(id)) {
            withContext(Dispatchers.Main) { }
        }
        val instance = activeCursors[id] ?: return
        val half = sizePx / 2
        val fromX = instance.params.x
        val fromY = instance.params.y
        val toX = x - half
        val toY = y - half

        suspendCancellableCoroutine<Unit> { cont ->
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 150
                addUpdateListener {
                    val f = it.animatedValue as Float
                    instance.params.x = (fromX + (toX - fromX) * f).toInt()
                    instance.params.y = (fromY + (toY - fromY) * f).toInt()
                    instance.currentX = instance.params.x + half
                    instance.currentY = instance.params.y + half
                    try { wm?.updateViewLayout(instance.view, instance.params) } catch (_: Exception) {}
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (instance.animator === animation) instance.animator = null
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        if (instance.animator === animation) instance.animator = null
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
            }
            main.post {
                instance.animator?.cancel()
                instance.animator = anim
                anim.start()
            }
            cont.invokeOnCancellation { main.post { anim.cancel() } }
        }
    }

    suspend fun glideAlongPath(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long, id: Int = 1) {
        if (!activeCursors.containsKey(id)) {
            withContext(Dispatchers.Main) { }
        }
        val instance = activeCursors[id] ?: return
        val half = sizePx / 2
        val startX = fromX - half
        val startY = fromY - half
        val endX = toX - half
        val endY = toY - half

        suspendCancellableCoroutine<Unit> { cont ->
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                addUpdateListener {
                    val f = it.animatedValue as Float
                    instance.params.x = (startX + (endX - startX) * f).toInt()
                    instance.params.y = (startY + (endY - startY) * f).toInt()
                    instance.currentX = instance.params.x + half
                    instance.currentY = instance.params.y + half
                    try { wm?.updateViewLayout(instance.view, instance.params) } catch (_: Exception) {}
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (instance.animator === animation) instance.animator = null
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        if (instance.animator === animation) instance.animator = null
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
            }
            main.post {
                instance.animator?.cancel()
                instance.animator = anim
                anim.start()
            }
            cont.invokeOnCancellation { main.post { anim.cancel() } }
        }
    }

    suspend fun glideDualPaths(
        x1From: Float, y1From: Float, x1To: Float, y1To: Float,
        x2From: Float, y2From: Float, x2To: Float, y2To: Float,
        durationMs: Long
    ) {
        glideAlongPath(x1From, y1From, x1To, y1To, durationMs, id = 1)
        glideAlongPath(x2From, y2From, x2To, y2To, durationMs, id = 2)
    }

    fun pulse(id: Int = 1) {
        main.post {
            activeCursors[id]?.view?.animate()?.scaleX(1.7f)?.scaleY(1.7f)?.setDuration(110)?.withEndAction {
                activeCursors[id]?.view?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(140)?.start()
            }?.start()
        }
    }

    fun pulseAll() {
        main.post {
            activeCursors.values.forEach { inst ->
                inst.view.animate().scaleX(1.7f).scaleY(1.7f).setDuration(110).withEndAction {
                    inst.view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }.start()
            }
        }
    }
}
