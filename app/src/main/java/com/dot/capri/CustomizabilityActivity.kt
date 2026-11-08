package com.dot.capri

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dot.capri.util.CustomizationPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CustomizabilityActivity : AppCompatActivity() {

    private lateinit var previewBanner: LinearLayout
    private lateinit var previewTitle: TextView
    private lateinit var previewText: TextView
    private lateinit var previewAccentDot: View
    private lateinit var btnTestAnimation: Button

    private lateinit var layoutColorSwatches: LinearLayout
    private lateinit var rgTextSize: RadioGroup
    private lateinit var rgBannerScale: RadioGroup
    private lateinit var rgAnimSpeed: RadioGroup
    private lateinit var rgDuration: RadioGroup

    private var animJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customizability)

        initViews()
        setupListeners()
        populateFromPrefs()
        updateLivePreviewVisuals()
    }

    private fun initViews() {
        previewBanner = findViewById(R.id.preview_banner)
        previewTitle = findViewById(R.id.preview_title)
        previewText = findViewById(R.id.preview_text)
        previewAccentDot = findViewById(R.id.preview_accent_dot)
        btnTestAnimation = findViewById(R.id.btn_test_animation)

        layoutColorSwatches = findViewById(R.id.layout_color_swatches)
        rgTextSize = findViewById(R.id.rg_text_size)
        rgBannerScale = findViewById(R.id.rg_banner_scale)
        rgAnimSpeed = findViewById(R.id.rg_anim_speed)
        rgDuration = findViewById(R.id.rg_duration)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener {
            finish()
        }
    }

    private fun populateFromPrefs() {
        buildColorSwatches()

        // Text size
        when (CustomizationPrefs.getTextSizeSp(this)) {
            12.0f -> findViewById<RadioButton>(R.id.rb_text_small).isChecked = true
            16.0f -> findViewById<RadioButton>(R.id.rb_text_large).isChecked = true
            else -> findViewById<RadioButton>(R.id.rb_text_normal).isChecked = true
        }

        // Scale
        when (CustomizationPrefs.getBannerScale(this)) {
            0.85f -> findViewById<RadioButton>(R.id.rb_scale_compact).isChecked = true
            1.15f -> findViewById<RadioButton>(R.id.rb_scale_expanded).isChecked = true
            else -> findViewById<RadioButton>(R.id.rb_scale_normal).isChecked = true
        }

        // Speed
        when (CustomizationPrefs.getAnimSpeed(this)) {
            "FAST" -> findViewById<RadioButton>(R.id.rb_anim_fast).isChecked = true
            "RELAXED" -> findViewById<RadioButton>(R.id.rb_anim_relaxed).isChecked = true
            else -> findViewById<RadioButton>(R.id.rb_anim_smooth).isChecked = true
        }

        // Duration
        when (CustomizationPrefs.getBannerDurationSec(this)) {
            3 -> findViewById<RadioButton>(R.id.rb_dur_3s).isChecked = true
            8 -> findViewById<RadioButton>(R.id.rb_dur_8s).isChecked = true
            else -> findViewById<RadioButton>(R.id.rb_dur_5s).isChecked = true
        }
    }

    private fun buildColorSwatches() {
        layoutColorSwatches.removeAllViews()
        val currentAccent = CustomizationPrefs.getAccentColor(this)

        for ((name, color) in CustomizationPrefs.PRESET_COLORS) {
            val swatch = View(this).apply {
                val size = (36 * resources.displayMetrics.density).toInt()
                val margin = (6 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(margin, 0, margin, 0)
                }
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (color == currentAccent) {
                        setStroke((3 * resources.displayMetrics.density).toInt(), 0xFFFFFFFF.toInt())
                    }
                }
                background = bg
                contentDescription = name
                setOnClickListener {
                    CustomizationPrefs.setAccentColor(this@CustomizabilityActivity, color)
                    buildColorSwatches()
                    updateLivePreviewVisuals()
                    triggerDropletAnimation(playIntroOnly = false)
                }
            }
            layoutColorSwatches.addView(swatch)
        }
    }

    private fun setupListeners() {
        btnTestAnimation.setOnClickListener {
            triggerDropletAnimation(playIntroOnly = false)
        }

        rgTextSize.setOnCheckedChangeListener { _, checkedId ->
            val sizeSp = when (checkedId) {
                R.id.rb_text_small -> 12.0f
                R.id.rb_text_large -> 16.0f
                else -> 14.0f
            }
            CustomizationPrefs.setTextSizeSp(this, sizeSp)
            updateLivePreviewVisuals()
        }

        rgBannerScale.setOnCheckedChangeListener { _, checkedId ->
            val scale = when (checkedId) {
                R.id.rb_scale_compact -> 0.85f
                R.id.rb_scale_expanded -> 1.15f
                else -> 1.0f
            }
            CustomizationPrefs.setBannerScale(this, scale)
            CustomizationPrefs.setPillScale(this, scale)
            updateLivePreviewVisuals()
            triggerDropletAnimation(playIntroOnly = true)
        }

        rgAnimSpeed.setOnCheckedChangeListener { _, checkedId ->
            val speed = when (checkedId) {
                R.id.rb_anim_fast -> "FAST"
                R.id.rb_anim_relaxed -> "RELAXED"
                else -> "SMOOTH"
            }
            CustomizationPrefs.setAnimSpeed(this, speed)
            triggerDropletAnimation(playIntroOnly = false)
        }

        rgDuration.setOnCheckedChangeListener { _, checkedId ->
            val sec = when (checkedId) {
                R.id.rb_dur_3s -> 3
                R.id.rb_dur_8s -> 8
                else -> 5
            }
            CustomizationPrefs.setBannerDurationSec(this, sec)
        }
    }

    private fun updateLivePreviewVisuals() {
        val accent = CustomizationPrefs.getAccentColor(this)
        val textSize = CustomizationPrefs.getTextSizeSp(this)
        val scale = CustomizationPrefs.getBannerScale(this)

        previewTitle.setTextColor(accent)
        btnTestAnimation.setTextColor(accent)
        previewAccentDot.backgroundTintList = ColorStateList.valueOf(accent)

        previewText.textSize = textSize
        previewBanner.scaleX = scale
        previewBanner.scaleY = scale

        // Dynamic stroke color for banner
        val bg = previewBanner.background?.mutate() as? GradientDrawable
        bg?.setStroke((1.5f * resources.displayMetrics.density).toInt(), accent)
    }

    private fun triggerDropletAnimation(playIntroOnly: Boolean) {
        animJob?.cancel()
        val duration = CustomizationPrefs.getAnimDurationMs(this)
        val scale = CustomizationPrefs.getBannerScale(this)

        previewBanner.translationY = -120f
        previewBanner.scaleX = 0.2f * scale
        previewBanner.scaleY = 0.2f * scale
        previewBanner.alpha = 0f

        val enterAnim = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(previewBanner, "translationY", -120f, 0f),
                ObjectAnimator.ofFloat(previewBanner, "scaleX", 0.2f * scale, scale),
                ObjectAnimator.ofFloat(previewBanner, "scaleY", 0.2f * scale, scale),
                ObjectAnimator.ofFloat(previewBanner, "alpha", 0f, 1f)
            )
            this.duration = duration
            interpolator = OvershootInterpolator(1.3f)
        }
        enterAnim.start()

        if (!playIntroOnly) {
            animJob = lifecycleScope.launch {
                delay(duration + 2200L)
                val exitAnim = AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(previewBanner, "translationY", 0f, -120f),
                        ObjectAnimator.ofFloat(previewBanner, "scaleX", scale, 0.2f * scale),
                        ObjectAnimator.ofFloat(previewBanner, "scaleY", scale, 0.2f * scale),
                        ObjectAnimator.ofFloat(previewBanner, "alpha", 1f, 0f)
                    )
                    this.duration = duration
                    interpolator = AccelerateInterpolator(1.4f)
                }
                exitAnim.start()

                delay(duration + 200L)
                // Restore to steady visible preview
                previewBanner.translationY = 0f
                previewBanner.scaleX = scale
                previewBanner.scaleY = scale
                previewBanner.alpha = 1f
            }
        }
    }
}
