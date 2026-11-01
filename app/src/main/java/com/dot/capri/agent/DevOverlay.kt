package com.dot.capri.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Debug overlay: a blue grid and red tap markers. Off unless "dev grid on" is set. */
object DevOverlay {

    private const val PREFS = "capri_prefs"
    private const val KEY = "dev_overlay"
    private const val MARK_LIFE_MS = 3000L

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var view: GridView? = null
    private val marks = ArrayList<Mark>()

    private class Mark(val x: Int, val y: Int, val label: String, val at: Long)

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        if (on) start(context) else stop()
    }

    /** Shows the grid if the dev setting is on. Safe to call on every run. */
    fun start(context: Context) {
        if (!isEnabled(context)) return
        val app = context.applicationContext
        main.post { attach(app) }
    }

    /** Previews the dev overlay grid immediately regardless of enabled preference. */
    fun preview(context: Context) {
        val app = context.applicationContext
        main.post { attach(app) }
    }

    fun stop() {
        main.post { detach() }
    }

    private fun attach(app: Context) {
        if (view != null) return
        val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager
        val type = if (com.dot.capri.CapriAccessibilityService.instance != null)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val v = GridView(app)
        try {
            manager.addView(v, params)
            view = v
        } catch (_: Exception) {
        }
    }

    private fun detach() {
        view?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        view = null
        marks.clear()
    }

    /** Puts a red marker on screen. The label says what and where. */
    fun mark(x: Int, y: Int, label: String) {
        main.post {
            marks.add(Mark(x, y, label, System.currentTimeMillis()))
            while (marks.size > 8) marks.removeAt(0)
            view?.invalidate()
            main.postDelayed({ view?.invalidate() }, MARK_LIFE_MS + 100)
        }
    }

    /** The cell name for a pixel, using the same grid as GridSpec. */
    fun cellName(x: Int, y: Int, w: Int, h: Int): String =
        GridSpec.nameOf(x, y, w, h)

    /** Centre pixel of a cell such as "C4", or null if the cell doesn't exist. */
    fun cellCentre(cell: String, w: Int, h: Int): Pair<Int, Int>? =
        GridSpec.centre(cell, w, h)

    private class GridView(context: Context) : View(context) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAA1E88FF.toInt()
            strokeWidth = 2f
        }
        private val cellText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1E88FF.toInt()
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
        }
        private val pctText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
        }
        private val markRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF2D6B.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        private val markLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(5f, 0f, 0f, 0xFF000000.toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val g = GridSpec.of(w.toInt(), h.toInt())

            // Grid lines: dynamic cols x rows based on GridSpec.
            for (c in 0..g.cols) {
                val x = w * c / g.cols.toFloat()
                canvas.drawLine(x, 0f, x, h, line)
            }
            for (r in 0..g.rows) {
                val y = h * r / g.rows.toFloat()
                canvas.drawLine(0f, y, w, y, line)
            }
            for (c in 0 until g.cols) {
                for (r in 0 until g.rows) {
                    canvas.drawText("${'A' + c}${r + 1}", w * c / g.cols.toFloat() + 8f, h * r / g.rows.toFloat() + 30f, cellText)
                }
            }

            // Percent axis along the bottom: 0.1 to 0.9.
            for (i in 1..9) {
                val x = w * i / 10f
                canvas.drawText(String.format(java.util.Locale.US, "%.1f", i / 10f), x + 4f, h - 12f, pctText)
            }

            // Tap markers, newest last, each shown for 3 seconds.
            val now = System.currentTimeMillis()
            for (m in marks) {
                if (now - m.at > MARK_LIFE_MS) continue
                val mx = m.x.toFloat()
                val my = m.y.toFloat()
                canvas.drawCircle(mx, my, 40f, markRing)
                canvas.drawLine(mx - 60f, my, mx + 60f, my, markRing)
                canvas.drawLine(mx, my - 60f, mx, my + 60f, markRing)
                canvas.drawText(m.label, mx + 50f, my - 50f, markLabel)
            }
        }
    }
}
