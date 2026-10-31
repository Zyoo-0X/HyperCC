package com.example.hypercc

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import android.widget.TextView

val Int.dp: Int get() = (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()
val Int.dpf: Float get() = this * Resources.getSystem().displayMetrics.density
fun dpToPx(v: Float): Float = v * Resources.getSystem().displayMetrics.density

fun withAlpha(color: Int, alpha: Float): Int =
    Color.argb((alpha.coerceIn(0f, 1f) * 255f).toInt(), Color.red(color), Color.green(color), Color.blue(color))

fun Context.tinted(res: Int, color: Int): Drawable {
    val d = getDrawable(res)!!.mutate()
    d.setTint(color)
    return d
}

fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

fun cornerShape(color: Int, tl: Float, tr: Float, br: Float, bl: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
}

/** Latar dengan sudut berbeza + kesan ripple bila disentuh. */
fun rippleBg(color: Int, tl: Float, tr: Float, br: Float, bl: Float, ripple: Int): Drawable =
    RippleDrawable(ColorStateList.valueOf(ripple), cornerShape(color, tl, tr, br, bl), cornerShape(Color.WHITE, tl, tr, br, bl))

fun rippleBg(color: Int, radius: Float, ripple: Int): Drawable = rippleBg(color, radius, radius, radius, radius, ripple)

fun Context.text(
    s: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false, medium: Boolean = false, light: Boolean = false
): TextView = TextView(this).apply {
    text = s
    textSize = sizeSp
    setTextColor(color)
    includeFontPadding = false
    typeface = when {
        bold -> Typeface.create("sans-serif-medium", Typeface.BOLD)
        medium -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        light -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        else -> Typeface.create("sans-serif", Typeface.NORMAL)
    }
}

/** Bentuk tile ikut tetapan: dinamik (aktif = pil, tak aktif = segi empat bulat), pil, atau segi empat bulat. */
fun tileBackground(pal: Palette, shapeMode: Int, on: Boolean, heightPx: Int, circleOnly: Boolean = false): Drawable {
    val pill = heightPx / 2f
    val soft = 20.dpf.coerceAtMost(pill)
    val r = when {
        circleOnly -> if (shapeMode == 2 || (shapeMode == 0 && !on)) soft else pill
        shapeMode == 1 -> pill
        shapeMode == 2 -> soft
        else -> if (on) pill else soft
    }
    val fill = if (on) pal.primary else pal.container
    return rippleBg(fill, r, withAlpha(if (on) pal.onPrimary else pal.onSurface, .14f))
}

/** Suis gaya Material 3. */
class M3Switch(ctx: Context, private val pal: Palette) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eval = ArgbEvaluator()
    private var t = 0f
    private var anim: ValueAnimator? = null
    var onToggle: ((Boolean) -> Unit)? = null
    var checked = false
        private set

    init {
        isClickable = true
        setOnClickListener { set(!checked, fromUser = true) }
    }

    fun set(value: Boolean, fromUser: Boolean = false, animate: Boolean = true) {
        checked = value
        anim?.cancel()
        val target = if (value) 1f else 0f
        if (animate && isAttachedToWindow) {
            anim = ValueAnimator.ofFloat(t, target).apply {
                duration = 180
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
                start()
            }
        } else { t = target; invalidate() }
        if (fromUser) onToggle?.invoke(value)
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(52.dp, 32.dp)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val inset = 1.dpf
        val rect = RectF(inset, inset, w - inset, h - inset)
        paint.style = Paint.Style.FILL
        paint.color = eval.evaluate(t, pal.high, pal.primary) as Int
        c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
        if (t < 1f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.dpf
            paint.color = withAlpha(pal.outline, 1f - t)
            c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = eval.evaluate(t, pal.outline, pal.onPrimary) as Int
        val radius = dpToPx(8f + 4f * t)
        val cx = (h / 2f) + (w - h) * t
        c.drawCircle(cx, h / 2f, radius, paint)
    }
}

/** Slider tebal berbentuk pil, gaya slider kecerahan Android 16. */
class M3Slider(
    ctx: Context, private val pal: Palette, iconRes: Int?, private val heightDp: Int = 52,
    private val onChange: (Float) -> Unit
) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconOn: Drawable? = iconRes?.let { ctx.tinted(it, pal.onPrimary) }
    private val iconOff: Drawable? = iconRes?.let { ctx.tinted(it, pal.onSecondary) }
    private var v = 0.5f
    var dragging = false
        private set
    var onRelease: (() -> Unit)? = null

    /** Dikemas kini daripada sistem; diabaikan semasa pengguna sedang seret. */
    fun setSystemValue(x: Float) { if (!dragging) { v = x.coerceIn(0f, 1f); invalidate() } }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), heightDp.dp)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
        val rect = RectF(0f, 0f, w, h)
        paint.color = pal.secondary
        c.drawRoundRect(rect, r, r, paint)

        val fx = (w * v).coerceIn(0f, w)
        c.save(); c.clipRect(0f, 0f, fx, h)
        paint.color = pal.primary
        c.drawRoundRect(rect, r, r, paint)
        drawIcon(c, iconOn, h)
        c.restore()

        c.save(); c.clipRect(fx, 0f, w, h)
        drawIcon(c, iconOff, h)
        c.restore()

        if (fx > h * 0.9f && fx < w - 4.dpf) {
            paint.color = pal.onPrimary
            val bw = 4.dpf; val bh = h * 0.42f
            c.drawRoundRect(RectF(fx - 12.dpf - bw / 2, (h - bh) / 2, fx - 12.dpf + bw / 2, (h + bh) / 2), bw / 2, bw / 2, paint)
        }
    }

    private fun drawIcon(c: Canvas, d: Drawable?, h: Float) {
        d ?: return
        val s = 24.dp
        val left = dpToPx(heightDp * 0.34f).toInt()
        val top = ((h - s) / 2f).toInt()
        d.setBounds(left, top, left + s, top + s)
        d.draw(c)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                update(e.x)
            }
            MotionEvent.ACTION_MOVE -> update(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onRelease?.invoke()
            }
        }
        return true
    }

    private fun update(x: Float) {
        v = (x / width).coerceIn(0f, 1f)
        invalidate()
        onChange(v)
    }
}

/** Slider menegak berbentuk pil, digunakan pada gaya HyperOS untuk brightness dan volume. */
class M3VerticalSlider(
    ctx: Context,
    private val pal: Palette,
    iconRes: Int,
    private val heightDp: Int = 148,
    private val widthDp: Int = 54,
    private val fillColor: Int = pal.primary,
    private val trackColor: Int = pal.high,
    private val activeIconColor: Int = pal.onPrimary,
    private val inactiveIconColor: Int = pal.onSurface,
    private val onChange: (Float) -> Unit
) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = ctx.tinted(iconRes, activeIconColor)
    private var value = 0.5f
    private val touchSlop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var verticalGesture = false
    var dragging = false
        private set
    var onRelease: (() -> Unit)? = null

    fun setSystemValue(v: Float) {
        if (!dragging) {
            value = v.coerceIn(0f, 1f)
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(widthDp.dp, heightDp.dp)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = w / 2f
        val track = RectF(0f, 0f, w, h)
        paint.style = Paint.Style.FILL
        paint.color = trackColor
        canvas.drawRoundRect(track, radius, radius, paint)

        val filledTop = h * (1f - value)
        canvas.save()
        canvas.clipRect(0f, filledTop, w, h)
        paint.color = fillColor
        canvas.drawRoundRect(track, radius, radius, paint)
        canvas.restore()

        val iconSize = 23.dp
        val iconLeft = ((w - iconSize) / 2f).toInt()
        val iconTop = (h - iconSize - 16.dp).toInt().coerceAtLeast(4.dp)
        val iconIsOnFill = iconTop + iconSize > filledTop
        icon.setTint(if (iconIsOnFill) activeIconColor else inactiveIconColor)
        icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
        icon.draw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                downX = event.x
                downY = event.y
                verticalGesture = false
                updateValue(event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = kotlin.math.abs(event.x - downX)
                val dy = kotlin.math.abs(event.y - downY)
                if (!verticalGesture && dy > touchSlop && dy > dx * 1.15f) {
                    verticalGesture = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (verticalGesture || dy >= dx) updateValue(event.y)
            }
            MotionEvent.ACTION_UP -> {
                if (verticalGesture || kotlin.math.abs(event.y - downY) >= kotlin.math.abs(event.x - downX)) {
                    updateValue(event.y)
                }
                dragging = false
                verticalGesture = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onRelease?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> {
                // A horizontal page swipe is allowed to take over the slider gesture.
                dragging = false
                verticalGesture = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun updateValue(y: Float) {
        value = (1f - y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        invalidate()
        onChange(value)
    }
}

/** ScrollView yang ketinggiannya dihadkan (wrap_content sehingga maxH). */
class MaxHeightScrollView(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
    init {
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
    }
}
