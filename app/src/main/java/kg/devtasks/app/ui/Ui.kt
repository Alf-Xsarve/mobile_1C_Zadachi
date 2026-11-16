package kg.devtasks.app.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.text.*
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*

class Ui(
    val a: Activity,
    val style: String,
    val dark: Boolean,
    val scale: Float,
    val motion: Boolean,
) {
    val bg = Color.parseColor(if (dark) "#141824" else "#F6F7FA")
    val paper = Color.parseColor(if (dark) "#202636" else "#FFFFFF")
    val ink = Color.parseColor(if (dark) "#EFF1F8" else "#202739")
    val muted = Color.parseColor(if (dark) "#A4ADC1" else "#7D8597")
    val line = Color.parseColor(if (dark) "#343B4F" else "#E9EBF1")
    val accent =
        Color.parseColor(
            when (style) {
                "blue" -> "#3968F5"
                "purple" -> "#9B7BFF"
                else -> "#FFD342"
            }
        )
    val onAccent = if (style == "yellow") Color.parseColor("#272A34") else Color.WHITE

    fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    fun shape(color: Int, radius: Int = 18, stroke: Boolean = false) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke) setStroke(dp(1), line)
        }

    fun column(pad: Int = 0) =
        LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        }

    fun row() =
        LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    fun text(value: String, size: Float = 16f, bold: Boolean = false, color: Int = ink) =
        TextView(a).apply {
            text = value
            textSize = size * scale
            setTextColor(color)
            typeface =
                Typeface.create(
                    if (bold) "sans-serif-medium" else "sans-serif",
                    if (bold) Typeface.BOLD else Typeface.NORMAL,
                )
            setPadding(0, dp(4), 0, dp(4))
        }

    fun gap(height: Int = 12) =
        View(a).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }

    fun fill(parent: LinearLayout, view: View) {
        parent.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    fun card(pad: Int = 16) =
        column(pad).apply {
            background = shape(paper, 20, true)
            elevation = dp(1).toFloat()
            layoutParams =
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(6), dp(16), dp(6)) }
        }

    fun button(
        label: String,
        enabled: Boolean = true,
        secondary: Boolean = false,
        action: () -> Unit,
    ) =
        TextView(a).apply {
            text = label
            contentDescription = label
            gravity = Gravity.CENTER
            textSize = 15f * scale
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minHeight = dp(52)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextColor(if (secondary) ink else onAccent)
            background = shape(if (secondary) paper else accent, 16, secondary)
            alpha = if (enabled) 1f else .45f
            isEnabled = enabled
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            layoutParams =
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(5), 0, dp(5)) }
            if (motion)
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN ->
                            v.animate().scaleX(.98f).scaleY(.98f).setDuration(90).start()
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL ->
                            v.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                    }
                    false
                }
        }

    fun icon(label: String, description: String = label, action: () -> Unit) =
        text(label, 22f, true).apply {
            contentDescription = description
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            setOnClickListener { action() }
            isFocusable = true
        }

    fun input(
        label: String,
        value: String = "",
        password: Boolean = false,
        changed: (String) -> Unit,
    ): EditText =
        EditText(a).apply {
            hint = label
            tag = label
            contentDescription = label
            textSize = 15f * scale
            setTextColor(ink)
            setHintTextColor(muted)
            background = shape(paper, 14, true)
            setPadding(dp(15), dp(12), dp(15), dp(12))
            minimumHeight = dp(54)
            isSingleLine = true
            inputType =
                if (password)
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                else android.text.InputType.TYPE_CLASS_TEXT
            setText(value)
            layoutParams =
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(5), 0, dp(5)) }
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int,
                    ) {}

                    override fun afterTextChanged(s: Editable?) {}

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int,
                    ) {
                        changed(s.toString())
                    }
                }
            )
        }

    fun chip(label: String, color: Int = muted) =
        text(label, 11f, true, color).apply {
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = shape((color and 0x00FFFFFF) or 0x18000000, 9)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    fun scroll(view: View) =
        ScrollView(a).apply {
            isFillViewport = true
            clipToPadding = false
            addView(view)
        }

    fun note(parent: LinearLayout, value: String?) {
        if (value.isNullOrBlank()) return
        parent.addView(
            card(12).apply {
                background = shape(if (dark) 0xFF442B30.toInt() else 0xFFFFEEEB.toInt(), 14)
                addView(
                    text(value, 13f, false, if (dark) 0xFFFFB7A9.toInt() else 0xFFAE4734.toInt())
                )
            }
        )
    }

    fun header(
        title: String,
        sub: String = "",
        back: (() -> Unit)? = null,
        actions: List<View> = emptyList(),
        compact: Boolean = false,
    ): View =
        row().apply {
            setPadding(
                dp(if (compact) 12 else 16),
                dp(if (compact) 2 else 12),
                dp(12),
                dp(if (compact) 2 else 8),
            )
            if (back != null) addView(icon("‹", "Назад", back))
            val labels = column()
            labels.addView(
                text(title, if (compact) 20f else 23f, true).apply {
                    if (compact) {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                }
            )
            if (sub.isNotBlank()) labels.addView(text(sub, 12f, false, muted))
            addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            actions.forEach { addView(it) }
        }

    fun skeleton(count: Int = 2) =
        column().apply {
            repeat(count) {
                addView(
                    Shimmer(a, paper, line, motion).apply {
                        layoutParams =
                            LinearLayout.LayoutParams(-1, dp(120)).apply {
                                setMargins(dp(16), dp(7), dp(16), dp(7))
                            }
                    }
                )
            }
        }

    fun reveal(v: View) {
        if (!motion || !ValueAnimator.areAnimatorsEnabled()) return
        v.alpha = 0f
        v.translationY = dp(9).toFloat()
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}

class Shimmer(
    context: android.content.Context,
    private val paper: Int,
    private val shade: Int,
    private val motion: Boolean,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shift = 0f
    private var animator: ValueAnimator? = null
    private val d = resources.displayMetrics.density

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (motion && ValueAnimator.areAnimatorsEnabled()) {
            animator =
                ValueAnimator.ofFloat(-1f, 2f).apply {
                    duration = 1250
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener {
                        shift = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.shader = null
        paint.color = paper
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 20 * d, 20 * d, paint)
        paint.color = shade
        canvas.drawRoundRect(16 * d, 18 * d, width * .38f, 32 * d, 7 * d, 7 * d, paint)
        canvas.drawRoundRect(16 * d, 47 * d, width * .85f, 63 * d, 6 * d, 6 * d, paint)
        canvas.drawRoundRect(16 * d, 77 * d, width * .60f, 89 * d, 5 * d, 5 * d, paint)
        if (animator != null) {
            paint.shader =
                LinearGradient(
                    width * (shift - .4f),
                    0f,
                    width * (shift + .4f),
                    height.toFloat(),
                    intArrayOf(0x00FFFFFF, 0x33FFFFFF, 0x00FFFFFF),
                    null,
                    Shader.TileMode.CLAMP,
                )
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 20 * d, 20 * d, paint)
        }
    }
}
